-- =====================================================================
-- 四十五、v1.1.68 — 回收站菜单节点 + 角色授权
-- =====================================================================
-- 背景: 报表中心新增「回收站」(/report/recycle), 集中展示 11 类被软删
--       (deleted=1) 的业务单据, 支持恢复 / 彻底删除. 复用 report:view 门禁,
--       不新增权限点. 本脚本只补菜单可见性 (M 型节点) + 给内置角色授权.
--
-- 用法 (双站 home + 飞牛各跑一次, 必须 utf8mb4):
--   mysql -uroot -perp_root_pwd --default-character-set=utf8mb4 \
--     industrial_erp < sql/45_v168_recycle_bin_menu.sql
--
-- 幂等: 先 SELECT 查重 sys_menu.path='/report/recycle', 存在则跳过 INSERT;
--       sys_role_menu 用 INSERT IGNORE 防重复. 跑多遍无副作用.
--
-- 门禁说明: 回收站页面/恢复/彻底删 全部走 report:view (ReportController
--   @SaCheckPermission + RecycleBinService requirePerm). 本节点 M 型 perms=''
--   仅做「侧边栏可见」载体, 真正能不能操作取决于该角色是否已授权 report:view
--   (menu id=951, seed/14 已 grant 6 内置角色).
-- =====================================================================

USE `industrial_erp`;

-- 1) sys_menu 加 M 型节点: 回收站, 挂 报表中心 (parent_id=9).
--    id 留空走雪花/AUTO_INCREMENT; perms='' (M 型可见菜单, 门禁靠 report:view).
INSERT INTO `sys_menu`
  (`parent_id`, `menu_name`, `menu_type`, `path`, `component`, `perms`, `icon`, `sort_no`, `is_visible`, `status`)
SELECT 9, '回收站', 'M', '/report/recycle', 'report/Recycle.vue', '', 'Delete', 3, 1, 1
WHERE NOT EXISTS (
  SELECT 1 FROM `sys_menu` WHERE `path` = '/report/recycle' AND `deleted` = 0
);

-- 2) 角色授权 (PC 端可见). 给 6 个内置角色加 /report/recycle 菜单授权.
--    sys_role_menu.client_type 列在 v1.1.56 (sql/41) 后存在; 老库若无该列, 走 2 列退化.
--
-- 2a) 有 client_type 列: 走 3 列 (client_type='PC')
SET @has_ct := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'sys_role_menu'
    AND column_name = 'client_type'
);
SET @stmt := IF(
  @has_ct > 0,
  'INSERT IGNORE INTO sys_role_menu(role_id, menu_id, client_type) '
   'SELECT r.id, m.id, "PC" FROM sys_role r CROSS JOIN sys_menu m '
   'WHERE m.path = "/report/recycle" AND m.deleted = 0 '
   'AND r.role_code IN ("SUPER_ADMIN","PURCHASE_MGR","SALES_MGR","WAREHOUSE_MGR","PRODUCTION_MGR","FINANCE") AND r.deleted = 0',
  'INSERT IGNORE INTO sys_role_menu(role_id, menu_id) '
   'SELECT r.id, m.id FROM sys_role r CROSS JOIN sys_menu m '
   'WHERE m.path = "/report/recycle" AND m.deleted = 0 '
   'AND r.role_code IN ("SUPER_ADMIN","PURCHASE_MGR","SALES_MGR","WAREHOUSE_MGR","PRODUCTION_MGR","FINANCE") AND r.deleted = 0'
);
PREPARE ps FROM @stmt;
EXECUTE ps;
DEALLOCATE PREPARE ps;

-- 3) 校验 — 升级后跑一下确认节点 + 授权到位
-- SELECT m.id, m.menu_name, m.path, rm.role_id
-- FROM sys_menu m JOIN sys_role_menu rm ON rm.menu_id = m.id
-- WHERE m.path = '/report/recycle';
-- 预期: 6 行 (SUPER_ADMIN/PURCHASE_MGR/SALES_MGR/WAREHOUSE_MGR/PRODUCTION_MGR/FINANCE 各 1)

-- =====================================================================
-- 回滚 (整条反向, 慎用 — 已删除单据仍会保留在 DB, 只是菜单消失):
-- DELETE FROM sys_role_menu WHERE menu_id =
--   (SELECT id FROM sys_menu WHERE path='/report/recycle' AND deleted=0);
-- DELETE FROM sys_menu WHERE path = '/report/recycle' AND deleted = 0;
-- =====================================================================
