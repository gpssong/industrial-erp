-- ============================================================================
-- v1.1.80+: App 模块开关 (admin 全局一刀切)
--
-- 用户决策 (2026-10-09):
--   - 粒度: 两层 (业务模块级 + 功能卡片 override)
--   - 存储: sys_config K-V (key=APP_MODULE_ENABLED, value=JSON)
--   - 作用: 全局一刀切 (admin 改 → 所有角色立即生效, 不联动 sys_role_menu)
--   - 实时性: 审计 + dashboard onMounted 拉新 (5min cache)
--
-- JSON 结构 (8 个固定模块, 顺序也用于 UI 渲染):
--   {
--     "purchase":   { "enabled": true,  "overrides": {} },
--     "sales":      { "enabled": true,  "overrides": {} },
--     "inventory":  { "enabled": true,  "overrides": {} },
--     "production": { "enabled": true,  "overrides": {} },
--     "base":       { "enabled": true,  "overrides": {} },
--     "finance":    { "enabled": false, "overrides": {} },  -- 暂未实现 App 端
--     "report":     { "enabled": true,  "overrides": {} },
--     "workflow":   { "enabled": false, "overrides": {} }   -- 仅 PC 端 (v1.1.79 决策)
--   }
--
-- 部署: 双站各跑一次 (home 192.168.0.150 + 飞牛 192.168.0.32)
-- 字符集: utf8mb4 (避免 unit_name 双重编码 mojibake, memory erp-mysql-utf8mb4-mojibake)
-- ============================================================================

INSERT IGNORE INTO sys_config
  (config_name, config_key, config_value, config_type, remark, create_by, update_by)
VALUES
  (
    'App模块开关',
    'APP_MODULE_ENABLED',
    '{"purchase":{"enabled":true,"overrides":{}},"sales":{"enabled":true,"overrides":{}},"inventory":{"enabled":true,"overrides":{}},"production":{"enabled":true,"overrides":{}},"base":{"enabled":true,"overrides":{}},"finance":{"enabled":false,"overrides":{}},"report":{"enabled":true,"overrides":{}},"workflow":{"enabled":false,"overrides":{}}}',
    1,
    'v1.1.80+: 业务模块的App端总开关 (admin级, 全局一刀切, 覆盖所有角色). 8 模块固定, finance/workflow 默认关.',
    1,
    1
  );

-- 校验:
SELECT config_key, LENGTH(config_value) AS len,
       JSON_EXTRACT(config_value, '$.sales.enabled') AS sales_enabled,
       JSON_EXTRACT(config_value, '$.workflow.enabled') AS workflow_enabled
FROM sys_config WHERE config_key = 'APP_MODULE_ENABLED';

-- 回滚:
-- DELETE FROM sys_config WHERE config_key = 'APP_MODULE_ENABLED';
