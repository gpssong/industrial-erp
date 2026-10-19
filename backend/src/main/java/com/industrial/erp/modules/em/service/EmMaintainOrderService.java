package com.industrial.erp.modules.em.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.common.Constants;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.em.entity.BaseEquipment;
import com.industrial.erp.modules.em.entity.EmMaintainOrder;
import com.industrial.erp.modules.em.entity.EmMaintainPlan;
import com.industrial.erp.modules.em.entity.EmMaintainRecord;
import com.industrial.erp.modules.em.mapper.BaseEquipmentMapper;
import com.industrial.erp.modules.em.mapper.EmMaintainOrderMapper;
import com.industrial.erp.modules.em.mapper.EmMaintainPlanMapper;
import com.industrial.erp.modules.em.mapper.EmMaintainRecordMapper;
import com.industrial.erp.security.PermissionService;
import com.industrial.erp.security.SecurityContext;
import com.industrial.erp.utils.BillNoGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * v1.1.96 EM — 维保工单服务.
 *
 * <p>状态机: DRAFT / DISPATCHED / PROCESSING / COMPLETED / CANCELLED.
 * <p>完成 (complete) 时:
 * <ol>
 *   <li>同步写 {@link EmMaintainRecord} (1:1, order_id UNIQUE)</li>
 *   <li>回写 plan.last_run_date / next_run_date (cycle_days 推进)</li>
 *   <li>更新设备 status=MAINT→ACTIVE, 写 last_maint_date</li>
 * </ol>
 */
@Service
public class EmMaintainOrderService {
    private static final Logger log = LoggerFactory.getLogger(EmMaintainOrderService.class);

    private final EmMaintainOrderMapper orderMapper;
    private final EmMaintainPlanMapper planMapper;
    private final EmMaintainRecordMapper recordMapper;
    private final BaseEquipmentMapper equipmentMapper;
    private final BillNoGenerator billNoGenerator;
    private final PermissionService permService;

    public EmMaintainOrderService(EmMaintainOrderMapper orderMapper,
                                   EmMaintainPlanMapper planMapper,
                                   EmMaintainRecordMapper recordMapper,
                                   BaseEquipmentMapper equipmentMapper,
                                   BillNoGenerator billNoGenerator,
                                   PermissionService permService) {
        this.orderMapper = orderMapper;
        this.planMapper = planMapper;
        this.recordMapper = recordMapper;
        this.equipmentMapper = equipmentMapper;
        this.billNoGenerator = billNoGenerator;
        this.permService = permService;
    }

    @Transactional(readOnly = true)
    public IPage<EmMaintainOrder> page(String equipmentCode, String status,
                                        Integer pageNum, Integer pageSize) {
        permService.requirePerm("equipment:maintain-order:list");
        Page<EmMaintainOrder> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<EmMaintainOrder> w = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(equipmentCode)) {
            w.like(EmMaintainOrder::getEquipmentCode, equipmentCode);
        }
        if (StrUtil.isNotBlank(status)) {
            w.eq(EmMaintainOrder::getStatus, status);
        }
        w.orderByDesc(EmMaintainOrder::getPlanDate);
        return orderMapper.selectPage(p, w);
    }

    /**
     * v1.1.99 附带清理 — 给 XK 合规中心设备维保聚合用的「不校验本 service 权限」分页。
     * XK 端点自身已用 {@code work:maintain:list} 门禁, 这里复用 em_maintain 新表作为唯一数据源
     * (消除 XK 读老 WfMaintainRecord 表、维保功能页读 em_maintain 表的分裂).
     * 参数: equipmentCode 模糊 (XK 传 deviceName), status, 分页.
     */
    @Transactional(readOnly = true)
    public IPage<EmMaintainOrder> pageForXk(Integer pageNum, Integer pageSize, String equipmentCode, String status) {
        Page<EmMaintainOrder> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<EmMaintainOrder> w = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(equipmentCode)) {
            w.and(x -> x.like(EmMaintainOrder::getEquipmentCode, equipmentCode)
                    .or().like(EmMaintainOrder::getEquipmentName, equipmentCode));
        }
        if (StrUtil.isNotBlank(status)) {
            w.eq(EmMaintainOrder::getStatus, status);
        }
        w.orderByDesc(EmMaintainOrder::getPlanDate);
        return orderMapper.selectPage(p, w);
    }

    @Transactional(readOnly = true)
    public EmMaintainOrder findById(Long id) {
        permService.requirePerm("equipment:maintain-order:list");
        EmMaintainOrder o = orderMapper.selectById(id);
        if (o == null) throw BizException.of("工单不存在");
        return o;
    }

    /**
     * 新建工单 (临时 MANUAL 或从计划 PLAN 生成).
     */
    @Transactional(rollbackFor = Exception.class)
    public EmMaintainOrder save(EmMaintainOrder order) {
        if (order.getId() == null) {
            permService.requirePerm("equipment:maintain-order:add");
            if (order.getEquipmentId() == null) throw BizException.of("请选择设备");
            if (StrUtil.isBlank(order.getMaintContent())) throw BizException.of("维保内容必填");
            if (order.getPlanDate() == null) order.setPlanDate(LocalDate.now());

            BaseEquipment eq = equipmentMapper.selectById(order.getEquipmentId());
            if (eq == null) throw BizException.of("设备不存在");
            order.setEquipmentCode(eq.getEquipmentCode());
            order.setEquipmentName(eq.getEquipmentName());

            // 计划模式: 从 plan 复制字段
            if (order.getPlanId() != null) {
                EmMaintainPlan plan = planMapper.selectById(order.getPlanId());
                if (plan == null) throw BizException.of("来源计划不存在");
                if (StrUtil.isBlank(order.getMaintContent())) order.setMaintContent(plan.getMaintContent());
                if (StrUtil.isBlank(order.getOperator())) order.setOperator(plan.getOwner());
                order.setSourceType("PLAN");
            } else {
                order.setSourceType("MANUAL");
            }
            if (StrUtil.isBlank(order.getOrderNo())) {
                order.setOrderNo(billNoGenerator.generate("MO"));
            }
            if (StrUtil.isBlank(order.getStatus())) order.setStatus(Constants.STATUS_DRAFT);
            orderMapper.insert(order);
            log.info("[EM] 新建维保工单 orderNo={} equipment={} source={}",
                    order.getOrderNo(), order.getEquipmentCode(), order.getSourceType());
        } else {
            permService.requirePerm("equipment:maintain-order:edit");
            EmMaintainOrder origin = orderMapper.selectById(order.getId());
            if (origin == null) throw BizException.of("工单不存在");
            if (Constants.STATUS_CHECKED.equals(origin.getStatus()) || "COMPLETED".equals(origin.getStatus())) {
                throw BizException.of("已完成工单不可修改");
            }
            // orderNo / equipmentId / planId 不允许改
            order.setOrderNo(origin.getOrderNo());
            order.setEquipmentId(origin.getEquipmentId());
            order.setEquipmentCode(origin.getEquipmentCode());
            order.setEquipmentName(origin.getEquipmentName());
            order.setPlanId(origin.getPlanId());
            order.setSourceType(origin.getSourceType());
            orderMapper.updateById(order);
            log.info("[EM] 更新维保工单 orderNo={}", order.getOrderNo());
        }
        return order;
    }

    /**
     * 完成工单 → 写 record + 回写 plan + 更新设备 status.
     */
    @Transactional(rollbackFor = Exception.class)
    public EmMaintainRecord complete(Long id, String result, String reviewer) {
        permService.requirePerm("equipment:maintain-order:complete");
        EmMaintainOrder order = orderMapper.selectById(id);
        if (order == null) throw BizException.of("工单不存在");
        if ("COMPLETED".equals(order.getStatus())) {
            throw BizException.of("工单已完成, 不可重复完成");
        }
        if (StrUtil.isBlank(result)) {
            throw BizException.of("处理结果必填");
        }
        if (StrUtil.isBlank(reviewer)) {
            reviewer = SecurityContext.getUsername();
        }

        // 1. 写 record
        EmMaintainRecord rec = new EmMaintainRecord();
        rec.setRecordNo(billNoGenerator.generate("MR"));
        rec.setOrderId(order.getId());
        rec.setOrderNo(order.getOrderNo());
        rec.setPlanId(order.getPlanId());
        rec.setEquipmentId(order.getEquipmentId());
        rec.setEquipmentCode(order.getEquipmentCode());
        rec.setEquipmentName(order.getEquipmentName());
        rec.setMaintContent(order.getMaintContent());
        rec.setOperator(order.getOperator());
        rec.setStartTime(order.getStartTime() == null ? LocalDateTime.now() : order.getStartTime());
        rec.setCompleteTime(LocalDateTime.now());
        rec.setResult(result);
        rec.setPhotoUrls(order.getPhotoUrls());
        rec.setReviewer(reviewer);
        rec.setReviewTime(LocalDateTime.now());
        recordMapper.insert(rec);

        // 2. 改工单状态 + 完成时间
        order.setResult(result);
        order.setCompleteTime(rec.getCompleteTime());
        order.setStatus("COMPLETED");
        orderMapper.updateById(order);

        // 3. 推进 plan
        if (order.getPlanId() != null) {
            EmMaintainPlan plan = planMapper.selectById(order.getPlanId());
            if (plan != null && "ACTIVE".equals(plan.getStatus())) {
                plan.setLastRunDate(rec.getCompleteTime().toLocalDate());
                if (plan.getCycleDays() != null && plan.getCycleDays() > 0) {
                    plan.setNextRunDate(plan.getLastRunDate().plusDays(plan.getCycleDays()));
                }
                planMapper.updateById(plan);
            }
        }

        // 4. 设备: MAINT→ACTIVE + last_maint_date
        BaseEquipment eq = equipmentMapper.selectById(order.getEquipmentId());
        if (eq != null) {
            if ("MAINT".equals(eq.getStatus())) eq.setStatus("ACTIVE");
            eq.setLastMaintDate(rec.getCompleteTime().toLocalDate());
            equipmentMapper.updateById(eq);
        }

        log.info("[EM] 完成维保工单 orderNo={} recordNo={}", order.getOrderNo(), rec.getRecordNo());
        return rec;
    }

    /**
     * 删除工单 (仅 DRAFT/DISPATCHED, COMPLETED 走回收站, 不允许物理删).
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        permService.requirePerm("equipment:maintain-order:edit");
        EmMaintainOrder origin = orderMapper.selectById(id);
        if (origin == null) throw BizException.of("工单不存在");
        if ("COMPLETED".equals(origin.getStatus())) {
            throw BizException.of("已完成的工单不可删除, 请走回收站");
        }
        orderMapper.deleteById(id);
        log.info("[EM] 删除维保工单 orderNo={}", origin.getOrderNo());
    }
}
