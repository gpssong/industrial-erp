package com.industrial.erp.modules.xk.service;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.industrial.erp.modules.system.service.SysConfigService;
import com.industrial.erp.modules.xk.entity.XkAuditLog;
import com.industrial.erp.modules.xk.mapper.XkAuditLogMapper;
import com.industrial.erp.security.SecurityContext;
import com.industrial.erp.utils.BillNoGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * v1.1.91 XK 合规中心 — 审计流水 (XK16-204 R2/R3/R4/R5/R6).
 *
 * <p>两个职责:
 * <ol>
 *   <li><b>同步直写</b> {@link #writeAudit} — 供其它 service (PurReceipt / PrdFinishedIn /
 *       维保 / 卫生 / 打印) 在关键动作后调用, 失败 try/catch 不阻塞主流程
 *       (McpAuditService @Async 漏写教训: 同步 1 行 INSERT 开销可忽略, 100% 落库)。</li>
 *   <li><b>归档聚合</b> {@link #listLogs} / {@link #archiveRecords} — XK 审计页 2 tab 数据源。</li>
 * </ol>
 *
 * <p>v1.1.98 X4: 留存年限从 sys_config.XK_AUDIT_RETENTION_YEARS 动态读 (缺省 2 年),
 * 解决"长期可查"文案与硬编码 2 年不一致的合规脱节.
 */
@Service
public class XkAuditService {

    private static final Logger log = LoggerFactory.getLogger(XkAuditService.class);

    private final XkAuditLogMapper mapper;
    private final BillNoGenerator billNoGenerator;
    private final SysConfigService sysConfigService;

    public XkAuditService(XkAuditLogMapper mapper, BillNoGenerator billNoGenerator, SysConfigService sysConfigService) {
        this.mapper = mapper;
        this.billNoGenerator = billNoGenerator;
        this.sysConfigService = sysConfigService;
    }

    /**
     * 同步写一条 XK 审计流水 (XK16-204 R5 用服务器时间戳)。
     *
     * <p>expire_at 按 {@code XK_AUDIT_RETENTION_YEARS} (默认 2 年) 计算 (R4 数据留存)。
     * 失败不抛 — 审计是旁路, 主流程优先。
     */
    public void writeAudit(String bizType, String bizBillNo, String action,
                           String changeDesc, String sha256, String ossPath, Integer sizeKb) {
        try {
            XkAuditLog l = new XkAuditLog();
            l.setLogNo(billNoGenerator.generate("XA"));
            l.setBizType(bizType);
            l.setBizBillNo(bizBillNo);
            l.setAction(action);
            if (StrUtil.isNotBlank(changeDesc)) l.setChangeDesc(StrUtil.maxLength(changeDesc, 512));
            l.setOperator(SecurityContext.getUserId());
            l.setOperatorName(SecurityContext.getUsername());
            // XK16-204 R3 审计留痕: 记录操作人角色 (取首个角色, 无则"未知"). 供 XK 审计页展示
            String role = null;
            try {
                if (SecurityContext.isLogin()) {
                    List<String> roles = StpUtil.getRoleList();
                    if (roles != null && !roles.isEmpty()) role = roles.get(0);
                }
            } catch (Exception ignore) { /* 无 Sa-Token 上下文时兜底 */ }
            l.setOperatorRole(role == null ? "未知" : role);
            l.setSha256(sha256);
            l.setOssPath(ossPath);
            l.setSizeKb(sizeKb);
            l.setOperTime(LocalDateTime.now());   // R5: 服务器时间戳
            // X4: 留存年限从 sys_config.XK_AUDIT_RETENTION_YEARS 读, 缺省 2 年.
            // 与"长期可查"文案和打印 preview 暴露的 retentionYears 配置保持一致.
            l.setExpireAt(LocalDate.now().plusYears(resolveRetentionYears()));
            mapper.insert(l);
            log.info("[XK] audit ok bizType={} bill={} action={} op={}",
                    bizType, bizBillNo, action, l.getOperatorName());
        } catch (Exception e) {
            log.warn("[XK] 审计写入失败 (不阻塞主流程): {}", e.getMessage());
        }
    }

    /**
     * X4: 读 sys_config.XK_AUDIT_RETENTION_YEARS 留存年限, 数字 1~30 合法, 否则 2.
     */
    private int resolveRetentionYears() {
        try {
            String v = sysConfigService.getByKey("XK_AUDIT_RETENTION_YEARS");
            if (v != null && !v.isEmpty()) {
                int y = Integer.parseInt(v.trim());
                if (y >= 1 && y <= 30) return y;
            }
        } catch (Exception e) {
            log.warn("[XK] 读 XK_AUDIT_RETENTION_YEARS 失败, 兜底 2 年: {}", e.getMessage());
        }
        return 2;
    }

    /** 审计日志 tab: 按时间倒序取 N 条。 */
    public List<XkAuditLog> listLogs(int limit) {
        return mapper.selectList(new LambdaQueryWrapper<XkAuditLog>()
                .orderByDesc(XkAuditLog::getOperTime)
                .last("LIMIT " + Math.max(1, Math.min(limit, 200))));
    }

    /** 记录归档 tab: 按 biz_type 聚合 (单号/创建时间/大小/OSS 路径)。 */
    public List<XkAuditLog> archiveRecords(String bizType) {
        return mapper.selectList(new LambdaQueryWrapper<XkAuditLog>()
                .eq(StrUtil.isNotBlank(bizType), XkAuditLog::getBizType, bizType)
                .orderByDesc(XkAuditLog::getOperTime)
                .last("LIMIT 200"));
    }
}
