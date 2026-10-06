package com.industrial.erp.modules.mcp;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.industrial.erp.modules.system.entity.SysOperLog;
import com.industrial.erp.modules.system.event.OperLogEvent;
import com.industrial.erp.modules.workflow.entity.WfMaintainRecord;
import com.industrial.erp.security.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * v1.1.79: MCP 审计 — 每次 MCP 工具调用写一条 {@code sys_oper_log} (复用现有操作日志表 + 事件),
 * 满足安全要求 "所有操作日志全部保存, 符合安监检查"。
 *
 * <p>method 字段统一带 {@code [MCP]} 前缀, 审计查询时一眼能区分 "Hermes MCP 触发" vs "PC 端手工"。
 * userId/username 从当前 Sa-Token 登录态取 (bot 账号), 归属到调用者。
 */
@Component
public class McpAuditService {

    private static final Logger log = LoggerFactory.getLogger(McpAuditService.class);
    private static final String MODULE = "MCP工具调用";

    private final ApplicationEventPublisher publisher;
    private final ObjectMapper om;

    public McpAuditService(ApplicationEventPublisher publisher, ObjectMapper om) {
        this.publisher = publisher;
        this.om = om;
    }

    public void logCreate(WfMaintainRecord r, long costMs) {
        publish("ERP_CREATE_MAINTENANCE", 1,
                "创建维保草稿 recordNo=" + StrUtil.nullToEmpty(r.getRecordNo()), null, costMs,
                toJson(Map.of("recordNo", StrUtil.nullToEmpty(r.getRecordNo()),
                        "deviceName", StrUtil.nullToEmpty(r.getDeviceName()))));
    }

    public void logQuery(String idOrNo, boolean success, long costMs, String errMsg) {
        publish("ERP_QUERY_MAINTENANCE", success ? 1 : 0,
                "查询维保 idOrNo=" + idOrNo,
                success ? null : errMsg, costMs, null);
    }

    private void publish(String method, int status, String what, String errMsg, long costMs, String respJson) {
        try {
            SysOperLog l = new SysOperLog();
            l.setModule(MODULE);
            l.setBusinessType("MCP");
            l.setMethod("[MCP] " + method);
            l.setRequestMethod("MCP");
            l.setRequestParam(what);
            if (respJson != null) l.setResponseData(respJson);
            l.setUserId(SecurityContext.getUserId());
            l.setUsername(SecurityContext.getUsername());
            l.setCostTime(costMs);
            l.setStatus(status);
            if (errMsg != null) l.setErrorMsg(StrUtil.maxLength(errMsg, 1000));
            l.setOperTime(LocalDateTime.now());
            publisher.publishEvent(new OperLogEvent(this, l));
        } catch (Exception e) {
            // 审计失败不能影响主流程 (与 OperLogPublisher 一致)
            log.warn("MCP 审计日志发布失败: method={}, err={}", method, e.getMessage());
        }
    }

    /** 序列化 MCP 结果 content (给 Hermes 的 JSON 字符串). */
    public String toJson(Map<String, Object> payload) {
        try {
            return om.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"ok\":false,\"msg\":\"审计序列化失败: " + e.getMessage() + "\"}";
        }
    }
}
