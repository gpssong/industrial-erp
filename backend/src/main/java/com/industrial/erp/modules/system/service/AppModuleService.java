package com.industrial.erp.modules.system.service;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.system.dto.AppModuleConfigDTO;
import com.industrial.erp.modules.system.entity.SysOperLog;
import com.industrial.erp.modules.system.mapper.SysOperLogMapper;
import com.industrial.erp.security.PermissionService;
import com.industrial.erp.security.SecurityContext;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v1.1.80+ App 模块开关 (admin 全局一刀切).
 *
 * <p>存储: sys_config 表, config_key = APP_MODULE_ENABLED, config_value = JSON 字符串.
 * 不新增 controller, 复用 {@link SysConfigController} 的 {@code GET /system/config/key/APP_MODULE_ENABLED}
 * 和 {@code PUT /system/config/value}.
 *
 * <p>本服务职责:
 * <ul>
 *   <li>JSON ↔ DTO 双向映射 (校验 + 默认值兜底, 8 模块必须全在)</li>
 *   <li>JVM 内存 cache (ConcurrentHashMap) — App 端高频读不穿透到 DB</li>
 *   <li>写 sys_oper_log 审计 (同步写, 不走 @Async — McpAuditService 教训)</li>
 *   <li>业务 perm 校验 ({@code system:config:edit} or admin)</li>
 * </ul>
 *
 * <p>App 端通过 {@code GET /system/config/key/APP_MODULE_ENABLED} 拉 raw JSON 字符串,
 * 前端解析后用作模块显隐. 内存 cache 只在本服务内 (如未来加 getConfig() 高频入口) 用.
 */
@Service
public class AppModuleService {

    private static final Logger log = LoggerFactory.getLogger(AppModuleService.class);

    /** sys_config 行 config_key 字面量 */
    public static final String CONFIG_KEY = "APP_MODULE_ENABLED";
    /** sys_oper_log 模块名 (查询定位) */
    private static final String AUDIT_MODULE = "App模块管理";
    /** sys_oper_log 业务类型 */
    private static final String AUDIT_BIZ = "UPDATE";

    /** JVM 内存 cache. value 是 raw JSON (避免每次转 DTO 浪费 CPU). */
    private volatile String cachedRawJson = null;

    private final SysConfigService configService;
    private final SysOperLogMapper operLogMapper;
    private final ObjectMapper om;
    private final PermissionService permService;

    public AppModuleService(SysConfigService configService,
                            SysOperLogMapper operLogMapper,
                            ObjectMapper om,
                            PermissionService permService) {
        this.configService = configService;
        this.operLogMapper = operLogMapper;
        this.om = om;
        this.permService = permService;
    }

    /** 启动时预热 cache, 避免首请求穿透到 DB. */
    @PostConstruct
    public void warmup() {
        try {
            cachedRawJson = loadFromDb();
            log.info("[AppModule] 启动预热完成, cachedRawJson.length={}",
                    cachedRawJson == null ? 0 : cachedRawJson.length());
        } catch (Exception e) {
            log.warn("[AppModule] 启动预热失败 (走默认 JSON 兜底): {}", e.getMessage());
            cachedRawJson = null;
        }
    }

    /** App 端读 raw JSON 字符串 (高频, 优先走 cache). */
    public String getRawJson() {
        if (cachedRawJson == null) {
            synchronized (this) {
                if (cachedRawJson == null) {
                    cachedRawJson = loadFromDb();
                }
            }
        }
        return cachedRawJson;
    }

    /** 把 DTO 写回 sys_config + 刷 cache + 写 audit. */
    public void update(AppModuleConfigDTO dto) {
        permService.requirePerm("system:config:edit");

        // 校验: 8 模块必须全在
        if (dto == null || dto.getModules() == null) {
            throw BizException.of("App 模块配置为空");
        }
        for (String key : AppModuleConfigDTO.DEFAULT_KEYS) {
            if (!dto.getModules().containsKey(key)) {
                throw BizException.of("缺少模块: " + key);
            }
        }

        String newJson;
        try {
            // 规范化: 每个 ModuleEntry 即使 enabled=false/overrides={} 也保留, 不丢 key
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (String key : AppModuleConfigDTO.DEFAULT_KEYS) {
                AppModuleConfigDTO.ModuleEntry e = dto.getModules().get(key);
                if (e == null) e = new AppModuleConfigDTO.ModuleEntry();
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("enabled", Boolean.TRUE.equals(e.getEnabled()));
                entry.put("overrides", e.getOverrides() == null ? new LinkedHashMap<>() : e.getOverrides());
                normalized.put(key, entry);
            }
            newJson = om.writeValueAsString(normalized);
        } catch (Exception e) {
            throw BizException.of("App 模块配置 JSON 序列化失败: " + e.getMessage());
        }

        String oldJson = cachedRawJson;

        // 写 sys_config (复用现有 K-V 接口)
        configService.updateValue(CONFIG_KEY, newJson);

        // 刷 cache
        cachedRawJson = newJson;

        // 同步写 audit (McpAuditService 教训: @Async 会漏写)
        writeAudit(oldJson, newJson);
    }

    private String loadFromDb() {
        String raw = configService.getByKey(CONFIG_KEY);
        if (StrUtil.isBlank(raw)) {
            // key 不存在 → 返回 null, App 端 fallback 用硬编码默认 JSON
            log.warn("[AppModule] sys_config.{} 不存在, App 端将用前端默认 JSON 兜底", CONFIG_KEY);
            return null;
        }
        return raw;
    }

    private void writeAudit(String oldJson, String newJson) {
        try {
            SysOperLog l = new SysOperLog();
            l.setModule(AUDIT_MODULE);
            l.setBusinessType(AUDIT_BIZ);
            l.setMethod("updateAppModules");
            l.setRequestMethod("PUT");
            // params 字段塞 diff 摘要 (old→new 太长, 只记字节数 + 变更摘要)
            String diff = buildDiffSummary(oldJson, newJson);
            l.setRequestParam(diff);
            l.setUserId(SecurityContext.getUserId());
            l.setUsername(SecurityContext.getUsername());
            l.setStatus(1);
            l.setOperTime(LocalDateTime.now());
            operLogMapper.insert(l);
            log.info("[AppModule] audit ok userId={} diff={}",
                    SecurityContext.getUserId(), diff);
        } catch (Exception e) {
            // 审计失败不阻塞主流程 (与 OperLogPublisher 一致)
            log.warn("[AppModule] 审计日志写入失败: {}", e.getMessage());
        }
    }

    /** 简化 diff: 列出 enabled=false 的模块 + 有 override 的模块. */
    private String buildDiffSummary(String oldJson, String newJson) {
        try {
            Map<String, Map<String, Object>> oldMap = oldJson == null ? new LinkedHashMap<>()
                    : om.readValue(oldJson, Map.class);
            Map<String, Map<String, Object>> newMap = newJson == null ? new LinkedHashMap<>()
                    : om.readValue(newJson, Map.class);
            StringBuilder sb = new StringBuilder();
            sb.append("disabledModules=[");
            boolean first = true;
            for (String k : AppModuleConfigDTO.DEFAULT_KEYS) {
                Map<String, Object> n = newMap.get(k);
                Map<String, Object> o = oldMap.get(k);
                boolean newDisabled = n != null && Boolean.FALSE.equals(n.get("enabled"));
                boolean oldDisabled = o != null && Boolean.FALSE.equals(o.get("enabled"));
                if (newDisabled != oldDisabled) {
                    if (!first) sb.append(",");
                    sb.append(k).append(newDisabled ? "→OFF" : "→ON");
                    first = false;
                }
            }
            sb.append("], overrideModules=[");
            first = true;
            for (String k : AppModuleConfigDTO.DEFAULT_KEYS) {
                Map<String, Object> n = newMap.get(k);
                Map<String, Object> o = oldMap.get(k);
                Map<String, Object> no = n == null ? null : (Map<String, Object>) n.get("overrides");
                Map<String, Object> oo = o == null ? null : (Map<String, Object>) o.get("overrides");
                if (no != null && !no.isEmpty() && !no.equals(oo)) {
                    if (!first) sb.append(",");
                    sb.append(k).append("=").append(no.size()).append("项");
                    first = false;
                }
            }
            sb.append("]");
            return sb.toString();
        } catch (Exception e) {
            return "diff解析失败: " + e.getMessage();
        }
    }
}
