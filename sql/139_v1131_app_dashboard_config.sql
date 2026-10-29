-- ============================================================================
-- v1.1.130+: App 端工作台区块开关 (admin 全局一刀切)
--
-- 背景:
--   PC 端「系统管理 → 系统设置 → 经营驾驶舱管理」原只管理 PC 驾驶舱
--   (sys_config.DASHBOARD_CONFIG, 7 子模块). 本次新增 App 端工作台的
--   "显示模块" 管理, 走独立 sys_config 键 APP_DASHBOARD_CONFIG,
--   不新增后端端点 (复用通用 /system/config/key/{key} + /system/config/value).
--
-- 用户决策 (2026-10-27):
--   - 粒度: 区块级开关 (全部功能 / KPI 四卡 / 库存预警 / 现场待办,
--           外加 全部功能 的 7 个功能子分组, 每个独立启停)
--   - 默认: 全开 (首次加载 / 未配置时所有区块显示, 与现状一致, 不藏内容)
--   - 作用: 全局一刀切 (admin 改 → 所有 App 用户一致), 叠加在现有 perm 之上
--
-- JSON 结构:
--   {
--     "allFunc":  { "enabled": true,
--                   "groups": { "生产":true,"采购":true,"销售":true,"库存":true,
--                                "XK记录":true,"现场填报":true,"经营报表":true } },
--     "kpi":      { "enabled": true },
--     "warning":  { "enabled": true },
--     "todo":     { "enabled": true }
--   }
--   全部功能子分组名与 app/src/pages/dashboard/index.vue 的 FUNC_GROUP_ORDER 严格对齐
--   (生产/采购/销售/库存/XK记录/现场填报/经营报表), 顺序即渲染顺序.
--
-- 部署: 仅灌 .150 主库 (docker exec erp-mysql ... utf8mb4), 经复制链到
--       .32 / 阿里云备库. 不双写.
-- 字符集: utf8mb4 (见 CLAUDE.md / memory erp-mysql-utf8mb4-mojibake)
-- ============================================================================

INSERT IGNORE INTO sys_config
  (config_name, config_key, config_value, config_type, remark, create_by, update_by)
VALUES
  (
    'App工作台区块开关',
    'APP_DASHBOARD_CONFIG',
    '{"allFunc":{"enabled":true,"groups":{"生产":true,"采购":true,"销售":true,"库存":true,"XK记录":true,"现场填报":true,"经营报表":true}},"kpi":{"enabled":true},"warning":{"enabled":true},"todo":{"enabled":true}}',
    1,
    'v1.1.130+: App 端工作台 4 区块 (全部功能/KPI四卡/库存预警/现场待办) + 全部功能 7 子分区的显示开关 (admin 全局一刀切, 默认全开). PC 端「经营驾驶舱管理 → App 工作台」tab 管理.',
    1,
    1
  );

-- 幂等 UPDATE: 键已存在时用默认全开值覆盖 (仅首次部署需要, 后续以 UI 保存为准)
-- 说明: 生产环境已有历史配置时不要用这条覆盖, 下面默认注释. 需要重置可手动放开.
-- UPDATE sys_config SET config_value =
--   '{"allFunc":{"enabled":true,"groups":{"生产":true,"采购":true,"销售":true,"库存":true,"XK记录":true,"现场填报":true,"经营报表":true}},"kpi":{"enabled":true},"warning":{"enabled":true},"todo":{"enabled":true}}'
-- WHERE config_key = 'APP_DASHBOARD_CONFIG';

-- 校验:
SELECT config_key, LENGTH(config_value) AS len,
       JSON_UNQUOTE(JSON_EXTRACT(config_value, '$.kpi.enabled'))  AS kpi_enabled,
       JSON_UNQUOTE(JSON_EXTRACT(config_value, '$.todo.enabled')) AS todo_enabled,
       JSON_UNQUOTE(JSON_EXTRACT(config_value, '$.allFunc.groups.生产')) AS grp_prod
FROM sys_config WHERE config_key = 'APP_DASHBOARD_CONFIG';

-- 回滚:
-- DELETE FROM sys_config WHERE config_key = 'APP_DASHBOARD_CONFIG';
