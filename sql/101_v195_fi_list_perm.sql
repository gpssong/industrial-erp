-- =====================================================================
-- v1.1.95 成品入库: 补齐 production:finished-in:list 授权
-- =====================================================================
-- 根因: 早期只 seed 了 :add (menu 3031) / :check (menu 3032), 缺 :list.
-- 后端 PrdFinishedInService.page() requirePerm("production:finished-in:list"),
-- 而超管 token 里没有 :list → 列表接口 403 → 新增草稿后列表查不出新单.
-- 表结构 (sql/01): sys_menu 无 client_type/sort_order, 有 sort_no/is_visible/deleted;
--   sys_role_menu 主键 (role_id, menu_id, client_type) 默认 'PC'.
-- 幂等: 按 perms 查重 (WHERE NOT EXISTS), 连跑 2 次结果一致.
--
-- 用法 (飞牛):
--   docker cp sql/101_v195_fi_list_perm.sql <host>/tmp/ &&
--   docker exec -i erp-mysql-failover mysql -ugpssong -p'850225sonG' \
--     --default-character-set=utf8mb4 industrial_erp < /tmp/101_v195_fi_list_perm.sql

-- 1) 新增 M 型菜单节点「成品入库查询」perms=production:finished-in:list
--    parent 挂到现网成品入库路由菜单 (按 path 查), 查不到则挂 0 (根).
INSERT INTO sys_menu
  (id, parent_id, menu_name, menu_type, path, perms, is_visible, status, sort_no, create_time, update_time, deleted)
SELECT 3213,
       COALESCE((SELECT min(m.id) FROM sys_menu m WHERE m.path='/production/finished-in' AND m.deleted=0), 0),
       '成品入库查询', 'M', '/production/finished-in', 'production:finished-in:list',
       1, 1, 5, NOW(), NOW(), 0
FROM DUAL
WHERE NOT EXISTS (
  SELECT 1 FROM sys_menu WHERE perms='production:finished-in:list' AND deleted=0
);

-- 2) 补齐 delete / uncheck 两个按钮 perm 节点 (menu_id 3214/3215, 幂等按 perms 查重)
INSERT INTO sys_menu
  (id, parent_id, menu_name, menu_type, perms, is_visible, status, sort_no, create_time, update_time, deleted)
SELECT 3214, 0, '成品入库删除', 'F', 'production:finished-in:delete', 0, 1, 6, NOW(), NOW(), 0
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='production:finished-in:delete' AND deleted=0);

INSERT INTO sys_menu
  (id, parent_id, menu_name, menu_type, perms, is_visible, status, sort_no, create_time, update_time, deleted)
SELECT 3215, 0, '成品入库反审核', 'F', 'production:finished-in:uncheck', 0, 1, 7, NOW(), NOW(), 0
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='production:finished-in:uncheck' AND deleted=0);

-- 3) 给超管角色 (role_code=SUPER_ADMIN) 授权上述全部 menu, 覆盖 PC/APP/BOTH (幂等)
INSERT INTO sys_role_menu (role_id, menu_id, client_type)
SELECT r.id, m.id, c.ct
FROM sys_role r
CROSS JOIN sys_menu m
CROSS JOIN (SELECT 'PC' AS ct UNION ALL SELECT 'APP' UNION ALL SELECT 'BOTH') c
WHERE r.role_code='SUPER_ADMIN'
  AND m.perms IN ('production:finished-in:list','production:finished-in:delete','production:finished-in:uncheck')
  AND m.deleted=0
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm
    WHERE rm.role_id=r.id AND rm.menu_id=m.id AND rm.client_type=c.ct
  );
