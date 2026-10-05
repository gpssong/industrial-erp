-- =====================================================================
-- 四十八、v1.1.76 — AI 助手独立权限 report:ai (进 PC + App 双权限树)
-- =====================================================================
-- 背景: v1.1.75 AI 助手页面 (pc-web/src/views/report/Ai.vue, 路由 report/ai,
--       门禁 report:view) 上线后, 角色管理「分配权限」里 PC 端 / App 端菜单
--       权限树都**没有 AI 助手**这一项, 无法勾选授权 — 因为从没建 sys_menu 行:
--         - PC 树 = menuApi.list() 全量 sys_menu 按 parentId 挂树; 没有 sys_menu 行
--           → AI 助手不出现.
--         - App 树 = Role.vue 硬编码 APP_MENU_WHITELIST; AI 不在其中.
--       本脚本给 AI 助手建独立权限码 report:ai (同回收站 v1.1.72 模式):
--         - 建 sys_menu M 型节点 (挂 报表中心 parent_id=9, perms='report:ai'),
--           M + 非空 perms = 可授权叶子 (isGrantableMenu / markDisabled 自动放行).
--         - 给 6 内置角色授 PC + APP 各一行 (sys_role_menu.client_type):
--           PC 行 → PC 权限树可勾/保存; APP 行 → App 权限树 + App 登录 perm.
--
-- 配套代码改动 (见 v1.1.76 代码 diff):
--   - 后端 AI 端点门禁 report:view → report:ai (AiController/AiAgentController
--     + AiService/AgentService), orRole='admin' 保留.
--   - PC 路由 report/ai meta.perm → report:ai; Role.vue APP_MENU_WHITELIST 加项.
--   - App 新增 pages/report/ai 页面 + 工作台入口.
--
-- 用法 (双站 home + 飞牛各跑一次, 必须 utf8mb4):
--   mysql -uroot -perp_root_pwd --default-character-set=utf8mb4 \
--     industrial_erp < sql/48_v176_ai_perm.sql
--
-- 幂等: sys_menu 用 NOT EXISTS 查重 (path='/report/ai'); sys_role_menu 用
--       INSERT IGNORE 防重复. 跑多遍无副作用.
-- =====================================================================

USE `industrial_erp`;

-- 1) sys_menu 加 M 型节点: AI 助手, 挂 报表中心 (parent_id=9).
--    id 留空走雪花/AUTO_INCREMENT; M 型 + perms='report:ai' = 可授权叶子.
--    PC 侧边栏授权后自动出现「AI 助手」入口 (path=/report/ai).
INSERT INTO `sys_menu`
  (`parent_id`, `menu_name`, `menu_type`, `path`, `component`, `perms`, `icon`, `sort_no`, `is_visible`, `status`)
SELECT 9, 'AI 助手', 'M', '/report/ai', 'report/Ai.vue', 'report:ai', 'ChatDotRound', 4, 1, 1
WHERE NOT EXISTS (
  SELECT 1 FROM `sys_menu` WHERE `path` = '/report/ai' AND `deleted` = 0
);

-- 2) 角色授权 (PC + APP 各一行). 6 个内置角色.
--    sys_role_menu.client_type 列在 v1.1.56 (sql/41) 后存在; 老库若无该列, 走 2 列退化.
--    定位 AI 助手 menu id:
SET @ai_id := (SELECT `id` FROM `sys_menu` WHERE `path` = '/report/ai' AND `deleted` = 0 LIMIT 1);

SET @has_ct := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'sys_role_menu'
    AND column_name = 'client_type'
);

-- 2a) PC 端授权 (让 PC 权限树可勾/保存) — 有 client_type 列走 3 列, 无则 2 列.
SET @stmt := IF(
  @has_ct > 0,
  'INSERT IGNORE INTO sys_role_menu(role_id, menu_id, client_type) '
   'SELECT r.id, @ai_id, "PC" FROM sys_role r '
   'WHERE r.role_code IN ("SUPER_ADMIN","PURCHASE_MGR","SALES_MGR","WAREHOUSE_MGR","PRODUCTION_MGR","FINANCE") AND r.deleted = 0',
  'INSERT IGNORE INTO sys_role_menu(role_id, menu_id) '
   'SELECT r.id, @ai_id FROM sys_role r '
   'WHERE r.role_code IN ("SUPER_ADMIN","PURCHASE_MGR","SALES_MGR","WAREHOUSE_MGR","PRODUCTION_MGR","FINANCE") AND r.deleted = 0'
);
PREPARE ps FROM @stmt;
EXECUTE ps;
DEALLOCATE PREPARE ps;

-- 2b) APP 端授权 (让 App 权限树 + App 登录 perm 有 report:ai).
--     老库无 client_type 列时跳过 (老库 App 端无分端概念, 2 列模式下 PC/APP 不区分).
--     用 PREPARE/EXECUTE + 空语句兜底 (避免 IF..THEN — mysql 客户端脚本模式不认复合 IF).
SET @stmt := IF(
  @has_ct > 0,
  'INSERT IGNORE INTO sys_role_menu(role_id, menu_id, client_type) '
   'SELECT r.id, @ai_id, "APP" FROM sys_role r '
   'WHERE r.role_code IN ("SUPER_ADMIN","PURCHASE_MGR","SALES_MGR","WAREHOUSE_MGR","PRODUCTION_MGR","FINANCE") AND r.deleted = 0',
  'SELECT 1 WHERE FALSE'
);
PREPARE ps FROM @stmt;
EXECUTE ps;
DEALLOCATE PREPARE ps;

-- 3) 校验 — 升级后跑一下确认节点 + 授权到位
-- SELECT m.id, m.menu_name, m.perms, rm.client_type
-- FROM sys_menu m JOIN sys_role_menu rm ON rm.menu_id = m.id
-- WHERE m.path = '/report/ai' AND m.deleted = 0;
-- 预期: perms='report:ai'; 有 client_type 列 → 6 角色 × (PC+APP) = 12 行.

-- =====================================================================
-- 回滚 (整条反向, 慎用 — 会把 AI 助手门禁退回 report:view 时代, 并从两棵权限树移除):
-- DELETE FROM sys_role_menu WHERE menu_id =
--   (SELECT id FROM sys_menu WHERE path='/report/ai' AND deleted=0);
-- DELETE FROM sys_menu WHERE path = '/report/ai' AND deleted = 0;
-- (代码侧同步把 AiController/AiAgentController/AiService/AgentService/router/
--  Role.vue/App 的 report:ai 改回 report:view, 并移除 App AI 页面/白名单项)
-- =====================================================================
