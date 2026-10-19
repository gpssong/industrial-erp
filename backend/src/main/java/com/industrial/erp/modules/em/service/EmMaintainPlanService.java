package com.industrial.erp.modules.em.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.common.Constants;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.em.entity.BaseEquipment;
import com.industrial.erp.modules.em.entity.EmMaintainPlan;
import com.industrial.erp.modules.em.mapper.BaseEquipmentMapper;
import com.industrial.erp.modules.em.mapper.EmMaintainPlanMapper;
import com.industrial.erp.security.PermissionService;
import com.industrial.erp.utils.BillNoGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * v1.1.96 EM — 维保计划服务.
 *
 * <p>CRUD: page / save / delete. 计划本身只是模板, 不直接产生业务影响;
 * 真正的执行是 {@link EmMaintainOrderService} 通过 plan_id 生成工单.
 */
@Service
public class EmMaintainPlanService {
    private static final Logger log = LoggerFactory.getLogger(EmMaintainPlanService.class);

    private final EmMaintainPlanMapper mapper;
    private final BaseEquipmentMapper equipmentMapper;
    private final BillNoGenerator billNoGenerator;
    private final PermissionService permService;

    public EmMaintainPlanService(EmMaintainPlanMapper mapper,
                                  BaseEquipmentMapper equipmentMapper,
                                  BillNoGenerator billNoGenerator,
                                  PermissionService permService) {
        this.mapper = mapper;
        this.equipmentMapper = equipmentMapper;
        this.billNoGenerator = billNoGenerator;
        this.permService = permService;
    }

    @Transactional(readOnly = true)
    public IPage<EmMaintainPlan> page(String equipmentCode, String status,
                                       Integer pageNum, Integer pageSize) {
        permService.requirePerm("equipment:maintain-plan:list");
        Page<EmMaintainPlan> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<EmMaintainPlan> w = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(equipmentCode)) {
            w.like(EmMaintainPlan::getEquipmentCode, equipmentCode);
        }
        if (StrUtil.isNotBlank(status)) {
            w.eq(EmMaintainPlan::getStatus, status);
        }
        w.orderByDesc(EmMaintainPlan::getNextRunDate);
        return mapper.selectPage(p, w);
    }

    @Transactional(rollbackFor = Exception.class)
    public EmMaintainPlan save(EmMaintainPlan plan) {
        if (plan.getId() == null) {
            permService.requirePerm("equipment:maintain-plan:add");
            if (plan.getEquipmentId() == null) throw BizException.of("请选择设备");
            if (StrUtil.isBlank(plan.getMaintContent())) throw BizException.of("维保内容必填");
            if (plan.getCycleDays() == null || plan.getCycleDays() <= 0) {
                throw BizException.of("周期天数必填且 >0");
            }
            // 设备冗余字段回填
            BaseEquipment eq = equipmentMapper.selectById(plan.getEquipmentId());
            if (eq == null) throw BizException.of("设备不存在");
            plan.setEquipmentCode(eq.getEquipmentCode());
            plan.setEquipmentName(eq.getEquipmentName());
            if (StrUtil.isBlank(plan.getPlanNo())) {
                plan.setPlanNo(billNoGenerator.generate("MP"));
            }
            if (StrUtil.isBlank(plan.getStatus())) plan.setStatus("ACTIVE");
            mapper.insert(plan);
            log.info("[EM] 新建维保计划 planNo={} equipment={}", plan.getPlanNo(), plan.getEquipmentCode());
        } else {
            permService.requirePerm("equipment:maintain-plan:edit");
            EmMaintainPlan origin = mapper.selectById(plan.getId());
            if (origin == null) throw BizException.of("计划不存在");
            // planNo / equipmentId 不允许改
            plan.setPlanNo(origin.getPlanNo());
            plan.setEquipmentId(origin.getEquipmentId());
            plan.setEquipmentCode(origin.getEquipmentCode());
            plan.setEquipmentName(origin.getEquipmentName());
            mapper.updateById(plan);
            log.info("[EM] 更新维保计划 planNo={}", plan.getPlanNo());
        }
        return plan;
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        permService.requirePerm("equipment:maintain-plan:delete");
        EmMaintainPlan origin = mapper.selectById(id);
        if (origin == null) throw BizException.of("计划不存在");
        if (Constants.STATUS_CHECKED.equals(origin.getStatus())) {
            throw BizException.of("已激活的计划不可删除, 请先停用");
        }
        mapper.deleteById(id);
        log.info("[EM] 删除维保计划 planNo={}", origin.getPlanNo());
    }
}
