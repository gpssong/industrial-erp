package com.industrial.erp.modules.system.aspect;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.industrial.erp.common.Constants;
import com.industrial.erp.modules.system.entity.SysOperLog;
import com.industrial.erp.modules.system.event.OperLogEvent;
import com.industrial.erp.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;

/**
 * 发布操作日志事件 (统一序列化 + 拼接 User/IP/URL, 业务侧仅传业务信息即可)
 */
@Component
public class OperLogPublisher {

    public static final String BIZ_ADD = "ADD";
    public static final String BIZ_EDIT = "EDIT";
    public static final String BIZ_DELETE = "DELETE";
    public static final String BIZ_QUERY = "QUERY";
    public static final String BIZ_EXPORT = "EXPORT";
    public static final String BIZ_OTHER = "OTHER";

    private final ApplicationEventPublisher publisher;
    /**
     * 注入 Spring 配置好的 ObjectMapper (含 JSR310 / JavaTimeModule / Long 转 String 等),
     * 而不是 new 一个裸的 — 否则 LocalDateTime / BigDecimal 等会序列化失败.
     */
    private final ObjectMapper om;

    public OperLogPublisher(ApplicationEventPublisher publisher, ObjectMapper om) {
        this.publisher = publisher;
        this.om = om;
    }

    /**
     * 通用发布
     */
    public void publish(String module, String businessType, String method, Object requestParam, Object responseData, Throwable error, long costTime) {
        try {
            SysOperLog l = new SysOperLog();
            l.setModule(module);
            l.setBusinessType(businessType);
            l.setMethod(method);
            HttpServletRequest req = currentRequest();
            if (req != null) {
                l.setRequestUrl(req.getRequestURI());
                l.setRequestMethod(req.getMethod());
                l.setIpAddress(clientIp(req));
            }
            if (requestParam != null) {
                try { l.setRequestParam(serialize(requestParam)); } catch (Exception ignore) {}
            }
            if (responseData != null) {
                try { l.setResponseData(serialize(responseData)); } catch (Exception ignore) {}
            }
            l.setUserId(SecurityContext.getUserId());
            l.setUsername(SecurityContext.getUsername());
            l.setCostTime(costTime);
            l.setStatus(error == null ? 1 : 0);
            if (error != null) l.setErrorMsg(StrUtil.maxLength(error.getMessage(), 1000));
            l.setOperTime(LocalDateTime.now());
            publisher.publishEvent(new OperLogEvent(this, l));
        } catch (Exception e) {
            // 切面/事件失败不能影响主流程
        }
    }

    /**
     * 删除快照专用: 把删除前的整对象序列化到 snapshot_json
     *
     * @param module     模块名 (如 "客户管理")
     * @param entityId   实体 ID
     * @param entity     实体对象 (删除前从 DB 取出来)
     * @param details    子表集合 (可为 null)
     */
    public void publishDeleteSnapshot(String module, String entityId, Object entity, Object details) {
        try {
            SysOperLog l = new SysOperLog();
            l.setModule(module);
            l.setBusinessType(BIZ_DELETE);
            l.setMethod("delete:" + entityId);
            HttpServletRequest req = currentRequest();
            if (req != null) {
                l.setRequestUrl(req.getRequestURI());
                l.setRequestMethod(req.getMethod());
                l.setIpAddress(clientIp(req));
            }
            // requestParam = 主实体 ID (便于回查)
            l.setRequestParam("{\"id\":" + entityId + "}");
            // snapshotJson = 主+子表完整快照
            try {
                Map<String, Object> snap = new java.util.LinkedHashMap<>();
                snap.put("entity", entity);
                if (details != null) snap.put("details", details);
                l.setSnapshotJson(om.writeValueAsString(snap));
            } catch (JsonProcessingException e) {
                // 序列化失败不能影响主删除流程; snapshot 字段留空, 业务级日志仍记录成功
                org.slf4j.LoggerFactory.getLogger(OperLogPublisher.class)
                        .warn("删除快照序列化失败: {}", e.getMessage());
            }
            l.setUserId(SecurityContext.getUserId());
            l.setUsername(SecurityContext.getUsername());
            l.setCostTime(0L);
            l.setStatus(1);
            l.setOperTime(LocalDateTime.now());
            publisher.publishEvent(new OperLogEvent(this, l));
        } catch (Exception e) {
            // 切面失败不能影响主删除流程
            org.slf4j.LoggerFactory.getLogger(OperLogPublisher.class)
                    .warn("publishDeleteSnapshot error: {}", e.getMessage());
        }
    }

    private String serialize(Object o) throws JsonProcessingException {
        if (o == null) return null;
        if (o instanceof String) return (String) o;
        return om.writeValueAsString(o);
    }

    private HttpServletRequest currentRequest() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attrs != null ? attrs.getRequest() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String clientIp(HttpServletRequest req) {
        String ip = req.getHeader("X-Forwarded-For");
        if (StrUtil.isBlank(ip) || "unknown".equalsIgnoreCase(ip)) ip = req.getHeader("X-Real-IP");
        if (StrUtil.isBlank(ip) || "unknown".equalsIgnoreCase(ip)) ip = req.getRemoteAddr();
        return ip;
    }
}
