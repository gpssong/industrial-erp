-- =====================================================================
-- 51_hermes_mcp_bot.sql
-- v1.1.79: Hermes → ERP MCP 自动建档 — 专用 bot 账号 + 最小权限角色
--
-- 目的: 给 Hermes (fnOS gateway) 一个**只授 work:maintain:add + work:maintain:list**
--       的机器账号, 让它能通过 MCP 工具建维保草稿 / 查单, 但**绝无法**
--       check / uncheck / delete / update (审核永远留 PC 人工).
--       权限边界落在 ERP RBAC 里 — 即使 Hermes 崩了也不会越权.
--
-- 双站各跑一次 (home 192.168.0.150 + 飞牛 192.168.0.32), MySQL 8, --default-character-set=utf8mb4:
--   mysql -h <host> -u root -p industrial_erp --default-character-set=utf8mb4 < sql/51_hermes_mcp_bot.sql
--
-- 幂等: 全部 INSERT...SELECT WHERE NOT EXISTS / INSERT IGNORE, 可重复跑.
--
-- 跑完给 bot 设个强密码 (bcrypt), 或固定用测试密码后续改:
--   UPDATE sys_user SET password='<bcrypt-hash>' WHERE username='hermes_bot';
--   (MCP 桥脚本的 ERP_MCP_TOKEN = hermes_bot 登录 /auth/login 拿到的 Sa-Token)
-- =====================================================================

-- 1) 角色 HERMES_BOT (不可登录, 纯机器授权载体)
INSERT INTO sys_role (role_code, role_name, data_scope, client_scope, sort_no, status, remark)
SELECT 'HERMES_BOT', 'Hermes MCP 自动建档', 1, 'PC', 99, 1,
       'Hermes 拍照识别工单→MCP 建维保草稿, 仅 work:maintain:add/list, 无 check/delete'
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_code = 'HERMES_BOT' AND deleted = 0);

-- 2) 用户 hermes_bot (is_admin=0, 必须走角色授权, 不短路超管)
INSERT INTO sys_user (username, password, real_name, is_admin, status)
SELECT 'hermes_bot',
       '$2a$10$N9qo8uLOickgx2Z8ZoVknuTQgVtCWr4eTgWg6iZdHv0sGjxh5QF6u',  -- 占位 bcrypt (部署后改真密码)
       'Hermes MCP 建档', 0, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'hermes_bot' AND deleted = 0);

-- 3) 角色 → 用户 绑定
INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id FROM sys_user u, sys_role r
WHERE u.username = 'hermes_bot' AND r.role_code = 'HERMES_BOT' AND u.deleted = 0 AND r.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_user_role ur WHERE ur.user_id = u.id AND ur.role_id = r.id);

-- 4) 授权: 仅 work:maintain:add + work:maintain:list 两个 perm (client_type 探测, 老库退化 2 列)
--    故意**不**授 work:maintain:check / uncheck / delete / edit — 审核留 PC 人工
SET @has_ct := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'sys_role_menu' AND column_name = 'client_type'
);

SET @perm_ids := (
  SELECT GROUP_CONCAT(`id`) FROM `sys_menu`
  WHERE `perms` IN ('work:maintain:add', 'work:maintain:list') AND `deleted` = 0
);

SET @stmt := IF(
  @has_ct > 0,
  CONCAT('INSERT IGNORE INTO sys_role_menu(role_id, menu_id, client_type) '
        'SELECT r.id, m.id, "PC" FROM sys_role r CROSS JOIN sys_menu m '
        'WHERE m.id IN (', @perm_ids, ') AND r.role_code = "HERMES_BOT" AND r.deleted = 0'),
  CONCAT('INSERT IGNORE INTO sys_role_menu(role_id, menu_id) '
        'SELECT r.id, m.id FROM sys_role r CROSS JOIN sys_menu m '
        'WHERE m.id IN (', @perm_ids, ') AND r.role_code = "HERMES_BOT" AND r.deleted = 0')
);
PREPARE ps FROM @stmt; EXECUTE ps; DEALLOCATE PREPARE ps;

-- =====================================================================
-- 校验
-- =====================================================================
-- SELECT u.username, u.is_admin, u.status, r.role_code
--   FROM sys_user u JOIN sys_user_role ur ON ur.user_id=u.id JOIN sys_role r ON r.id=ur.role_id
--   WHERE u.username='hermes_bot' AND u.deleted=0;
-- SELECT rm.menu_id, m.perms, rm.client_type
--   FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id
--   JOIN sys_role r ON r.id=rm.role_id WHERE r.role_code='HERMES_BOT';
-- 期望: hermes_bot 绑定 HERMES_BOT; 授权行仅 work:maintain:add + work:maintain:list, 无 check/delete.

-- =====================================================================
-- 回滚
-- =====================================================================
-- DELETE FROM sys_role_menu WHERE menu_id IN (SELECT id FROM sys_menu WHERE perms IN ('work:maintain:add','work:maintain:list') AND deleted=0) AND role_id = (SELECT id FROM sys_role WHERE role_code='HERMES_BOT');
-- DELETE FROM sys_user_role WHERE role_id = (SELECT id FROM sys_role WHERE role_code='HERMES_BOT');
-- UPDATE sys_user SET deleted=1 WHERE username='hermes_bot';
-- UPDATE sys_role SET deleted=1 WHERE role_code='HERMES_BOT';
