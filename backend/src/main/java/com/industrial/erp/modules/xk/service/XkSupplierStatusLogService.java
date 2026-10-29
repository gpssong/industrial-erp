package com.industrial.erp.modules.xk.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseSupplier;
import com.industrial.erp.modules.base.mapper.BaseSupplierMapper;
import com.industrial.erp.modules.xk.entity.XkSupplierStatusLog;
import com.industrial.erp.modules.xk.mapper.XkSupplierStatusLogMapper;
import com.industrial.erp.security.PermissionService;
import com.industrial.erp.security.SecurityContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * v1.1.130 XK 合格供应商 — 暂停/恢复台账.
 *
 * <p>暂停/恢复改为「改状态 + 落台账 + 写 XK 审计」三件套 (同一事务), 原因必填。
 * 名录统计 / 准入评审 / 证照报告 / 年度绩效见 {@link XkSupplierRecordService}。
 */
@Service
public class XkSupplierStatusLogService {

    private final XkSupplierStatusLogMapper logMapper;
    private final BaseSupplierMapper supplierMapper;
    private final PermissionService permService;
    private final XkAuditService auditService;

    public XkSupplierStatusLogService(XkSupplierStatusLogMapper logMapper,
                                      BaseSupplierMapper supplierMapper,
                                      PermissionService permService,
                                      XkAuditService auditService) {
        this.logMapper = logMapper;
        this.supplierMapper = supplierMapper;
        this.permService = permService;
        this.auditService = auditService;
    }

    @Transactional(rollbackFor = Exception.class)
    public XkSupplierStatusLog suspend(Long supplierId, String reasonType, String reason,
                                       String effectiveDate, String evidenceUrl) {
        permService.requirePerm("base:supplier:edit");
        if (StrUtil.isBlank(reason)) throw BizException.of("请填写暂停原因");
        BaseSupplier s = requireSupplier(supplierId);
        if (s.getStatus() != null && s.getStatus() != 1) {
            throw BizException.of("供应商 " + s.getSupplierName() + " 已处于暂停供货状态");
        }
        s.setStatus(0);
        supplierMapper.updateById(s);

        XkSupplierStatusLog log = buildLog(s, "SUSPEND", reasonType, reason, effectiveDate, evidenceUrl);
        logMapper.insert(log);
        auditService.writeAudit("SUPPLIER_SUSPEND", s.getSupplierCode(), "EDIT",
                "暂停供货 [" + StrUtil.blankToDefault(reasonType, "其他") + "]: " + reason,
                null, evidenceUrl, null);
        return log;
    }

    @Transactional(rollbackFor = Exception.class)
    public XkSupplierStatusLog resume(Long supplierId, String reason, String effectiveDate, String evidenceUrl) {
        permService.requirePerm("base:supplier:edit");
        if (StrUtil.isBlank(reason)) throw BizException.of("请填写恢复原因 (整改/评审结论)");
        BaseSupplier s = requireSupplier(supplierId);
        if (s.getStatus() != null && s.getStatus() == 1) {
            throw BizException.of("供应商 " + s.getSupplierName() + " 当前为合格状态, 无需恢复");
        }
        s.setStatus(1);
        supplierMapper.updateById(s);

        XkSupplierStatusLog log = buildLog(s, "RESUME", "恢复评审通过", reason, effectiveDate, evidenceUrl);
        logMapper.insert(log);
        auditService.writeAudit("SUPPLIER_RESUME", s.getSupplierCode(), "EDIT",
                "恢复供货: " + reason, null, evidenceUrl, null);
        return log;
    }

    /** 台账查询 (倒序); limit 上限 500 */
    public List<XkSupplierStatusLog> listLogs(String action, Long supplierId, Integer limit) {
        permService.requirePerm("base:supplier:list");
        int size = (limit == null || limit <= 0) ? 100 : Math.min(limit, 500);
        LambdaQueryWrapper<XkSupplierStatusLog> w = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(action)) w.eq(XkSupplierStatusLog::getAction, action);
        if (supplierId != null) w.eq(XkSupplierStatusLog::getSupplierId, supplierId);
        w.orderByDesc(XkSupplierStatusLog::getOperTime).last("LIMIT " + size);
        return logMapper.selectList(w);
    }

    private BaseSupplier requireSupplier(Long id) {
        BaseSupplier s = id == null ? null : supplierMapper.selectById(id);
        if (s == null) throw BizException.of("供应商不存在或已删除");
        return s;
    }

    private XkSupplierStatusLog buildLog(BaseSupplier s, String action, String reasonType, String reason,
                                         String effectiveDate, String evidenceUrl) {
        XkSupplierStatusLog log = new XkSupplierStatusLog();
        log.setSupplierId(s.getId());
        log.setSupplierCode(s.getSupplierCode());
        log.setSupplierName(s.getSupplierName());
        log.setAction(action);
        log.setReasonType(reasonType);
        log.setReason(reason);
        if (StrUtil.isNotBlank(effectiveDate)) {
            try {
                log.setEffectiveDate(LocalDate.parse(effectiveDate));
            } catch (Exception e) {
                throw BizException.of("生效日期格式应为 YYYY-MM-DD");
            }
        } else {
            log.setEffectiveDate(LocalDate.now());
        }
        log.setEvidenceUrl(StrUtil.maxLength(evidenceUrl, 500));
        log.setOperator(SecurityContext.getUserId());
        log.setOperatorName(SecurityContext.getUsername());
        log.setOperTime(LocalDateTime.now());
        return log;
    }
}
