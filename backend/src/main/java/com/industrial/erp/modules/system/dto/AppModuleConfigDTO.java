package com.industrial.erp.modules.system.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v1.1.80+ App 模块开关配置 DTO.
 *
 * <p>前端 POST 结构:
 * <pre>
 * {
 *   "modules": {
 *     "purchase":   { "enabled": true,  "overrides": {} },
 *     "sales":      { "enabled": true,  "overrides": {"sales:delivery:list": false} },
 *     ...
 *   }
 * }
 * </pre>
 *
 * <p>与 sys_config 表的 JSON 字符串双向映射 — 服务层做解析/校验/默认值兜底.
 * 8 个固定模块 key 见 {@link #DEFAULT_KEYS}.
 */
public class AppModuleConfigDTO {

    /** 业务模块 key 固定 8 个 (与 MainLayout.vue menuTree 对齐). 顺序也用于 UI 渲染. */
    public static final String[] DEFAULT_KEYS = {
            "purchase", "sales", "inventory", "production",
            "base", "finance", "report", "workflow"
    };

    /** key → ModuleEntry. 用 LinkedHashMap 保留顺序. */
    private Map<String, ModuleEntry> modules = new LinkedHashMap<>();

    public Map<String, ModuleEntry> getModules() { return modules; }
    public void setModules(Map<String, ModuleEntry> modules) { this.modules = modules; }

    public static class ModuleEntry {
        private Boolean enabled = Boolean.TRUE;
        /** 子功能 override, key=perm 字符串, value=boolean. null/缺省=跟随 enabled. */
        private Map<String, Boolean> overrides = new LinkedHashMap<>();

        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean enabled) { this.enabled = enabled; }
        public Map<String, Boolean> getOverrides() { return overrides; }
        public void setOverrides(Map<String, Boolean> overrides) { this.overrides = overrides; }
    }
}
