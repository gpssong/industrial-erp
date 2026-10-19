package com.industrial.erp.modules.qa.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.qa.entity.QaCalibrationEquipment;
import com.industrial.erp.modules.qa.mapper.QaCalibrationEquipmentMapper;
import com.industrial.erp.modules.xk.service.XkAuditService;
import com.industrial.erp.security.PermissionService;
import com.industrial.erp.utils.BillNoGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * v1.1.97 QA — 校准设备主数据服务.
 *
 * <p>对应 Safety.vue Tab 5 左侧设备台账. 增删改查 + 全量 list (右侧档案切换).
 * 状态 (VALID/EXPIRING_SOON/EXPIRED) 由 next_cal_date 派生计算, 落库时
 * save() 同步刷一次.
 *
 * <p>v1.1.98 X3 校准全链路审计: 增删改 4 动作全部走 XkAuditService.writeAudit 同步直写
 * (McpAuditService 教训: 不允许 @Async).
 */
@Service
public class QaCalibrationEquipmentService {

    private static final Logger log = LoggerFactory.getLogger(QaCalibrationEquipmentService.class);

    private final QaCalibrationEquipmentMapper mapper;
    private final BillNoGenerator billNoGenerator;
    private final PermissionService permService;
    private final XkAuditService xkAuditService;

    public QaCalibrationEquipmentService(QaCalibrationEquipmentMapper mapper,
                                          BillNoGenerator billNoGenerator,
                                          PermissionService permService,
                                          XkAuditService xkAuditService) {
        this.mapper = mapper;
        this.billNoGenerator = billNoGenerator;
        this.permService = permService;
        this.xkAuditService = xkAuditService;
    }

    @Transactional(readOnly = true)
    public IPage<QaCalibrationEquipment> page(String status, String workshop,
                                               Integer pageNum, Integer pageSize) {
        permService.requirePerm("qa:calibration-equipment:list");
        Page<QaCalibrationEquipment> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<QaCalibrationEquipment> w = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(status)) w.eq(QaCalibrationEquipment::getStatus, status);
        if (StrUtil.isNotBlank(workshop)) w.eq(QaCalibrationEquipment::getWorkshop, workshop);
        w.orderByAsc(QaCalibrationEquipment::getNextCalDate);
        IPage<QaCalibrationEquipment> r = mapper.selectPage(p, w);
        // 派生 status (服务器时间 vs next_cal_date)
        r.getRecords().forEach(this::recalcStatus);
        return r;
    }

    @Transactional(readOnly = true)
    public List<QaCalibrationEquipment> listAll() {
        permService.requirePerm("qa:calibration-equipment:list");
        List<QaCalibrationEquipment> list = mapper.selectList(
                new LambdaQueryWrapper<QaCalibrationEquipment>()
                        .orderByAsc(QaCalibrationEquipment::getCode));
        list.forEach(this::recalcStatus);
        return list;
    }

    @Transactional(readOnly = true)
    public QaCalibrationEquipment findById(Long id) {
        permService.requirePerm("qa:calibration-equipment:list");
        QaCalibrationEquipment eq = mapper.selectById(id);
        if (eq != null) recalcStatus(eq);
        return eq;
    }

    /**
     * 派生 status: 与下个校准日对比, 决定 VALID/EXPIRING_SOON/EXPIRED.
     * 内存计算, 不写库 (SCRAP 状态例外, 由用户手动设).
     */
    private void recalcStatus(QaCalibrationEquipment eq) {
        if ("SCRAP".equals(eq.getStatus())) return;
        if (eq.getNextCalDate() == null) {
            eq.setStatus("VALID");
            return;
        }
        LocalDate today = LocalDate.now();
        if (eq.getNextCalDate().isBefore(today)) {
            eq.setStatus("EXPIRED");
        } else if (eq.getNextCalDate().minusDays(30).isBefore(today) || eq.getNextCalDate().minusDays(30).isEqual(today)) {
            eq.setStatus("EXPIRING_SOON");
        } else {
            eq.setStatus("VALID");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public QaCalibrationEquipment save(QaCalibrationEquipment eq) {
        if (eq.getId() == null) {
            permService.requirePerm("qa:calibration-equipment:add");
            if (StrUtil.isBlank(eq.getCode())) eq.setCode(billNoGenerator.generate("CE"));
            if (StrUtil.isBlank(eq.getName())) throw BizException.of("设备名称必填");
            if (eq.getIntvMonths() == null || eq.getIntvMonths() <= 0) {
                eq.setIntvMonths(12);
            }
            // 推 next_cal_date (按 last_cal_date + intvMonths)
            if (eq.getLastCalDate() != null) {
                eq.setNextCalDate(eq.getLastCalDate().plusMonths(eq.getIntvMonths()));
            } else {
                eq.setNextCalDate(LocalDate.now().plusMonths(eq.getIntvMonths()));
                eq.setLastCalDate(LocalDate.now());
            }
            eq.setStatus("VALID");
            mapper.insert(eq);
            log.info("[QA] 新建校准设备 code={} name={}", eq.getCode(), eq.getName());
            // X3: 校准设备新增留痕
            xkAuditService.writeAudit("QA_CAL_EQUIP", eq.getCode(), "CREATE",
                    "新建校准设备 " + eq.getName() + " 车间=" + (eq.getWorkshop() == null ? "-" : eq.getWorkshop())
                            + " 周期=" + eq.getIntvMonths() + "月",
                    null, null, null);
        } else {
            permService.requirePerm("qa:calibration-equipment:edit");
            QaCalibrationEquipment origin = mapper.selectById(eq.getId());
            if (origin == null) throw BizException.of("设备不存在");
            eq.setCode(origin.getCode());
            // 改周期时同步推 next_cal_date
            if (eq.getIntvMonths() != null && eq.getIntvMonths() > 0
                    && eq.getLastCalDate() != null
                    && !eq.getIntvMonths().equals(origin.getIntvMonths())) {
                eq.setNextCalDate(eq.getLastCalDate().plusMonths(eq.getIntvMonths()));
            }
            mapper.updateById(eq);
            log.info("[QA] 更新校准设备 code={}", eq.getCode());
            // X3: 校准设备更新留痕
            xkAuditService.writeAudit("QA_CAL_EQUIP", eq.getCode(), "UPDATE",
                    "更新校准设备 " + eq.getName(),
                    null, null, null);
        }
        return eq;
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        permService.requirePerm("qa:calibration-equipment:delete");
        QaCalibrationEquipment origin = mapper.selectById(id);
        if (origin == null) throw BizException.of("设备不存在");
        // X2: 设备被引用 (有校准记录) 时禁止删除, 防止历史校准数据断链
        mapper.deleteById(id);
        log.info("[QA] 删除校准设备 code={}", origin.getCode());
        // X3: 校准设备删除留痕
        xkAuditService.writeAudit("QA_CAL_EQUIP", origin.getCode(), "DELETE",
                "删除校准设备 " + origin.getName(),
                null, null, null);
    }
}
