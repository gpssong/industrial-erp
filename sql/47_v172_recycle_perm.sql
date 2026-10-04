-- =====================================================================
-- 四十七、v1.1.72 — 回收站独立权限 report:recycle (grantable 修复)
-- =====================================================================
-- 背景: v1.1.68 回收站 (sql/45) seed 成 M 型菜单节点 perms='' (空), 只当
--       「侧边栏可见」载体, 后端门禁复用 report:view. 但权限树机制把
--       「空 perms 的 M 节点」判为不可授权目录:
--         - 前端 Role.vue markDisabled: 只有 B / M+F(带 perms) 才 enabled
--           → 回收站在角色「分配权限」树里灰色不可勾.
--         - 后端 SysRoleService.isGrantableMenu: 空 perms 的 M 返回 false
--           → 每次保存该角色时, seed 写入的回收站授权行被静默删掉.
--       即回收站权限既无法单独授权, 也无法被保存.
--
-- 修复 (v1.1.72): 给回收站独立权限码 report:recycle.
--   1) 把 M 型回收站节点 perms 从 '' 改为 'report:recycle' (变可授权叶子).
--   2) 后端 3 回收站端点 + RecycleBinService 门禁 由 report:view 改 report:recycle
--      (ReportController / RecycleBinService, 见 v1.1.72 代码改动).
--   3) 前端路由 meta.perm 由 report:view 改 report:recycle (router/index.js).
--   本脚本负责 1) + 给 6 内置角色补授权 report:recycle.
--
-- 用法 (双站 home + 飞牛各跑一次, 必须 utf8mb4):
--   mysql -uroot -perp_root_pwd --default-character-set=utf8mb4 \
--     industrial_erp < sql/47_v172_recycle_perm.sql
--
-- 幂等: UPDATE 带 perms='' 条件只命中一次; sys_role_menu 用 INSERT IGNORE 防重复.
--       跑多遍无副作用.
-- =====================================================================

USE `industrial_erp`;

-- 1) 回收站 M 节点赋 perm. 只改 perms 为空的那行 (若已赋过则跳过).
UPDATE `sys_menu`
   SET `perms` = 'report:recycle'
 WHERE `path` = '/report/recycle'
   AND `deleted` = 0
   AND (`perms` = '' OR `perms` IS NULL);

-- 2) 给 6 个内置角色补 report:recycle 授权 (PC 端).
--    沿用 sql/45 的 information_schema 探测: 老库无 sys_role_menu.client_type 列
--    时走 2 列退化; 有该列走 3 列 (client_type='PC').
SET @rv_id := (SELECT `id` FROM `sys_menu` WHERE `path` = '/report/recycle' AND `deleted` = 0 LIMIT 1);

SET @has_ct := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'sys_role_menu'
    AND column_name = 'client_type'
);
SET @stmt := IF(
  @has_ct > 0,
  'INSERT IGNORE INTO sys_role_menu(role_id, menu_id, client_type) '
   'SELECT r.id, @rv_id, "PC" FROM sys_role r '
   'WHERE r.role_code IN ("SUPER_ADMIN","PURCHASE_MGR","SALES_MGR","WAREHOUSE_MGR","PRODUCTION_MGR","FINANCE") AND r.deleted = 0',
  'INSERT IGNORE INTO sys_role_menu(role_id, menu_id) '
   'SELECT r.id, @rv_id FROM sys_role r '
   'WHERE r.role_code IN ("SUPER_ADMIN","PURCHASE_MGR","SALES_MGR","WAREHOUSE_MGR","PRODUCTION_MGR","FINANCE") AND r.deleted = 0'
);
PREPARE ps FROM @stmt;
EXECUTE ps;
DEALLOCATE PREPARE ps;

-- 3) 校验 — 升级后跑一下确认节点已带 perm + 授权到位
-- SELECT m.id, m.menu_name, m.perms, rm.role_id
-- FROM sys_menu m JOIN sys_role_menu rm ON rm.menu_id = m.id
-- WHERE m.path = '/report/recycle';
-- 预期: perms='report:recycle'; 6 行 (6 内置角色各 1)

-- =====================================================================
-- 回滚 (整条反向, 慎用 — 会把回收站门禁退回 report:view 时代):
-- UPDATE sys_menu SET perms='' WHERE path='/report/recycle' AND deleted=0;
-- DELETE FROM sys_role_menu WHERE menu_id =
--   (SELECT id FROM sys_menu WHERE path='/report/recycle' AND deleted=0);
-- (代码侧同步把 ReportController/RecycleBinService/router 的 report:recycle 改回 report:view)
-- =====================================================================
