package com.industrial.erp.modules.qa.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.qa.entity.QaCalibrationEquipment;
import com.industrial.erp.modules.qa.entity.QaCalibrationRecord;
import com.industrial.erp.modules.qa.mapper.QaCalibrationEquipmentMapper;
import com.industrial.erp.modules.qa.mapper.QaCalibrationRecordMapper;
import com.industrial.erp.modules.xk.service.XkAuditService;
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
 * v1.1.97 QA — 校准记录服务.
 *
 * <p>对应 Safety.vue Tab 5 右侧校准报告列表. 校准完成后, 联动推
 * {@link QaCalibrationEquipment#setLastCalDate} + {@link QaCalibrationEquipment#setNextCalDate}
 * (按 intvMonths).
 *
 * <p>状态机: 新建 PENDING -> /check 审核 (PASSED/FAILED + reviewer) -> 联动 equipment.
 *
 * <p>v1.1.98 XK 合规强化: ① FAILED 推设备 status=EXPIRED (防失效设备仍出报告)
 * ② 审核/删除/设备增删改 全部走 XkAuditService.writeAudit 同步直写 (McpAuditService 教训, 不允许 @Async)
 * ③ 已审核 (result 非 PENDING) 的校准记录禁止删除
 */
@Service
public class QaCalibrationRecordService {

    private static final Logger log = LoggerFactory.getLogger(QaCalibrationRecordService.class);

    private final QaCalibrationRecordMapper mapper;
    private final QaCalibrationEquipmentMapper equipmentMapper;
    private final BillNoGenerator billNoGenerator;
    private final PermissionService permService;
    private final XkAuditService xkAuditService;

    public QaCalibrationRecordService(QaCalibrationRecordMapper mapper,
                                       QaCalibrationEquipmentMapper equipmentMapper,
                                       BillNoGenerator billNoGenerator,
                                       PermissionService permService,
                                       XkAuditService xkAuditService) {
        this.mapper = mapper;
        this.equipmentMapper = equipmentMapper;
        this.billNoGenerator = billNoGenerator;
        this.permService = permService;
        this.xkAuditService = xkAuditService;
    }

    @Transactional(readOnly = true)
    public IPage<QaCalibrationRecord> page(Long equipmentId, String result,
                                            Integer pageNum, Integer pageSize) {
        permService.requirePerm("qa:calibration-record:list");
        Page<QaCalibrationRecord> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<QaCalibrationRecord> w = new LambdaQueryWrapper<>();
        if (equipmentId != null) w.eq(QaCalibrationRecord::getEquipmentId, equipmentId);
        if (StrUtil.isNotBlank(result)) w.eq(QaCalibrationRecord::getResult, result);
        w.orderByDesc(QaCalibrationRecord::getCalDate);
        return mapper.selectPage(p, w);
    }

    @Transactional(readOnly = true)
    public QaCalibrationRecord findById(Long id) {
        permService.requirePerm("qa:calibration-record:list");
        return mapper.selectById(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public QaCalibrationRecord save(QaCalibrationRecord rec) {
        if (rec.getId() == null) {
            permService.requirePerm("qa:calibration-record:add");
            if (rec.getEquipmentId() == null) throw BizException.of("请选择校准设备");
            if (rec.getCalDate() == null) throw BizException.of("校准日期必填");
            QaCalibrationEquipment eq = equipmentMapper.selectById(rec.getEquipmentId());
            if (eq == null) throw BizException.of("校准设备不存在");
            rec.setEquipmentCode(eq.getCode());
            rec.setEquipmentName(eq.getName());
            if (StrUtil.isBlank(rec.getRecordNo())) {
                rec.setRecordNo(billNoGenerator.generate("CR"));
            }
            // 推 valid_until (按 equipment.intvMonths)
            if (rec.getValidUntil() == null && eq.getIntvMonths() != null && eq.getIntvMonths() > 0) {
                rec.setValidUntil(rec.getCalDate().plusMonths(eq.getIntvMonths()));
            }
            if (StrUtil.isBlank(rec.getResult())) rec.setResult("PENDING");
            mapper.insert(rec);
            log.info("[QA] 新建校准记录 recordNo={} equipment={}", rec.getRecordNo(), rec.getEquipmentCode());
            // X3 校准全链路审计: 新建校准记录 (McpAuditService 教训: 同步直写, 非 @Async)
            xkAuditService.writeAudit("QA_CAL_REC", rec.getRecordNo(), "CREATE",
                    "新建校准记录 设备=" + rec.getEquipmentName() + " 校准日期=" + rec.getCalDate(),
                    null, null, null);
        } else {
            permService.requirePerm("qa:calibration-record:edit");
            QaCalibrationRecord origin = mapper.selectById(rec.getId());
            if (origin == null) throw BizException.of("校准记录不存在");
            rec.setRecordNo(origin.getRecordNo());
            rec.setEquipmentId(origin.getEquipmentId());
            rec.setEquipmentCode(origin.getEquipmentCode());
            rec.setEquipmentName(origin.getEquipmentName());
            mapper.updateById(rec);
            log.info("[QA] 更新校准记录 recordNo={}", rec.getRecordNo());
            // X3 校准全链路审计: 更新校准记录
            xkAuditService.writeAudit("QA_CAL_REC", rec.getRecordNo(), "UPDATE",
                    "更新校准记录 设备=" + rec.getEquipmentName(),
                    null, null, null);
        }
        return rec;
    }

    /**
     * 审核校准记录 (QA 主管) -> 联动推 equipment.last_cal_date / next_cal_date.
     *
     * <p>v1.1.98 X1 XK 合规强化: FAILED 时把设备 status 推 EXPIRED, 防止失效设备仍可出检验报告.
     * X3 审核动作同步写 xk_audit_log (McpAuditService 教训: 必须同步, 不允许 @Async).
     */
    @Transactional(rollbackFor = Exception.class)
    public QaCalibrationRecord check(Long id, String result, String deviation, String reviewer) {
        permService.requirePerm("qa:calibration:check");
        QaCalibrationRecord rec = mapper.selectById(id);
        if (rec == null) throw BizException.of("校准记录不存在");
        if (rec.getResult() != null && !"PENDING".equals(rec.getResult())) {
            throw BizException.of("校准记录已审核, 不可重复");
        }
        if (!"PASSED".equals(result) && !"FAILED".equals(result)) {
            throw BizException.of("校准结果只能填 PASSED/FAILED");
        }
        if (StrUtil.isBlank(reviewer)) reviewer = SecurityContext.getUsername();
        rec.setResult(result);
        rec.setDeviation(deviation);
        rec.setReviewer(reviewer);
        rec.setReviewTime(LocalDateTime.now());
        mapper.updateById(rec);

        // 联动推 equipment: PASSED 推 next_cal_date, FAILED 推 status=EXPIRED (X1 强化)
        if (rec.getEquipmentId() != null) {
            QaCalibrationEquipment eq = equipmentMapper.selectById(rec.getEquipmentId());
            if (eq != null) {
                if ("PASSED".equals(result)) {
                    eq.setLastCalDate(rec.getCalDate());
                    if (eq.getIntvMonths() != null && eq.getIntvMonths() > 0) {
                        eq.setNextCalDate(rec.getCalDate().plusMonths(eq.getIntvMonths()));
                    }
                    eq.setLastOrg(rec.getCalOrg());
                    eq.setLastCertNo(rec.getCertNo());
                } else {
                    // X1: 校准失败, 设备立即标失效, 防止 OQC/PAT 报告继续出
                    eq.setStatus("EXPIRED");
                }
                equipmentMapper.updateById(eq);
            }
        }
        // X3: 校准审核必须留痕 (XK16-204 必查项 — 谁在何时审核了哪台设备的校准)
        xkAuditService.writeAudit("QA_CAL_REC", rec.getRecordNo(), "CHECK_" + result,
                "审核人=" + reviewer + " 设备=" + rec.getEquipmentName()
                        + " 结果=" + result + (StrUtil.isNotBlank(deviation) ? " 偏差=" + deviation : ""),
                null, null, null);
        log.info("[QA] 校准审核 recordNo={} result={} reviewer={}", rec.getRecordNo(), result, reviewer);
        return rec;
    }

    /**
     * 删除校准记录. X2 XK 合规强化: 仅 PENDING 可删, 已审核 (PASSED/FAILED) 禁止删除 —
     * 校准历史是 XK 必查项, 审核后不可逆地保留.
     * X3 删除动作也走 xk_audit_log.
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        permService.requirePerm("qa:calibration-record:delete");
        QaCalibrationRecord origin = mapper.selectById(id);
        if (origin == null) throw BizException.of("校准记录不存在");
        // X2: 已审核 (result 非空且 ≠PENDING) 禁止删除
        if (StrUtil.isNotBlank(origin.getResult()) && !"PENDING".equals(origin.getResult())) {
            throw BizException.of("校准记录已审核 (result=" + origin.getResult() + "), 不可删除以保留合规历史");
        }
        mapper.deleteById(id);
        log.info("[QA] 删除校准记录 recordNo={}", origin.getRecordNo());
        // X3: 删除动作留痕
        xkAuditService.writeAudit("QA_CAL_REC", origin.getRecordNo(), "DELETE",
                "删除 PENDING 校准记录 设备=" + origin.getEquipmentName(),
                null, null, null);
    }
}
