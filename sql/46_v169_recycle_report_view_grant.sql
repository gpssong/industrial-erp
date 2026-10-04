-- =====================================================================
-- 四十六、v1.1.69 — 回收站门禁修复: 把 report:view 补齐授权给 6 内置角色
-- =====================================================================
-- 背景 (bug 根因):
--   v1.1.68 回收站 (菜单 /report/recycle) 的门禁点是 report:view
--   (sys_menu id=951). 当时 sql/45 只给 6 个内置角色授权了「回收站」
--   这个 M 型菜单节点 (perms='', 仅做侧边栏可见载体), 但 report:view
--   (id=951) 本身在历史上只被授权给了 SUPER_ADMIN(1) / PURCHASE_MGR(2).
--
--   结果: WAREHOUSE_MGR(4) / FINANCE(6) / SALES_MGR(3) / PRODUCTION_MGR(5)
--   这些角色**看得到回收站菜单**, 但一进入就命中 /report/recycle/bin 的
--   @SaCheckPermission("report:view") → 403 无权限访问. 前端 Recycle.vue
--   的 catch 又把 403 静默吞掉 (只 console.warn), 于是整页所有 Tab 都显示
--   「暂无被删除的单据」—— 库里明明有 50 条 deleted=1 的 sal_delivery.
--
--   这就是「被删除的销售出库单没在回收站出现」的直接原因: 不是数据/查询
--   逻辑错, 而是登录角色缺 report:view 授权.
--
-- 修复: 把 report:view (menu id=951) 补授给全部 6 个内置角色, 三种
--   client_type (PC/APP/BOTH) 各一. 与 报表中心子页面 (report/sales,
--   report/inventory 的 :view 门禁) 和回收站共用同一门禁码, 一次补齐.
--
-- 用法 (双站 home + 飞牛各跑一次, 必须 utf8mb4):
--   mysql -uroot -perp_root_pwd --default-character-set=utf8mb4 \
--     industrial_erp < sql/46_v169_recycle_report_view_grant.sql
--
-- 幂等: 全部走 INSERT IGNORE (有 (role_id,menu_id,client_type) 唯一约束的
--       库靠它去重; 老库 2 列无 client_type 约束也安全). 跑多遍无副作用.
-- =====================================================================

USE `industrial_erp`;

-- 定位 report:view 菜单 id (seed/14 固定插 951, 这里再 SELECT 兜底防漂移).
SET @rv := (SELECT id FROM `sys_menu` WHERE `perms` = 'report:view' AND `deleted` = 0 LIMIT 1);

-- 老库兼容: 若 report:view 菜单行都查不到 (极端), 先按 seed/14 规范补建
-- (F 型, parent_id=0 — 注意 F 型 perm 载体必须挂 root 否则工作台树里找不到,
--  但它是纯权限点不在侧边栏渲染, parent_id=0 即现状规范).
INSERT INTO `sys_menu`(`id`,`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`perms`,`icon`,`sort_no`,`is_visible`,`status`)
SELECT 951,0,'报表查看','F','',NULL,'report:view',NULL,951,0,1
WHERE @rv IS NULL;
SET @rv := (SELECT id FROM `sys_menu` WHERE `perms` = 'report:view' AND `deleted` = 0 LIMIT 1);

-- 判 sys_role_menu 是否有 client_type 列 (v1.1.56 / sql/41 之后才有).
SET @has_ct := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'sys_role_menu'
    AND column_name = 'client_type'
);

-- 动态拼 INSERT: 有 client_type 列 → 3 列 (PC/APP/BOTH 各一); 无 → 2 列.
SET @stmt := IF(
  @has_ct > 0,
  'INSERT IGNORE INTO sys_role_menu(role_id, menu_id, client_type) '
   'SELECT r.id, @rv, c.ct FROM sys_role r '
   'CROSS JOIN (SELECT "PC" AS ct UNION SELECT "APP" UNION SELECT "BOTH") c '
   'WHERE r.deleted = 0 AND r.role_code IN ("SUPER_ADMIN","PURCHASE_MGR","SALES_MGR","WAREHOUSE_MGR","PRODUCTION_MGR","FINANCE")',
  'INSERT IGNORE INTO sys_role_menu(role_id, menu_id) '
   'SELECT r.id, @rv FROM sys_role r '
   'WHERE r.deleted = 0 AND r.role_code IN ("SUPER_ADMIN","PURCHASE_MGR","SALES_MGR","WAREHOUSE_MGR","PRODUCTION_MGR","FINANCE")'
);
PREPARE ps FROM @stmt;
EXECUTE ps;
DEALLOCATE PREPARE ps;

-- 3) 校验 — 升级后跑一下.
--    有 client_type 的库: 应见 6 角色 × 3 client_type = 18 行 report:view.
-- SELECT rm.role_id, r.role_code, rm.client_type
-- FROM sys_role_menu rm JOIN sys_role r ON r.id = rm.role_id
-- WHERE rm.menu_id = (SELECT id FROM sys_menu WHERE perms='report:view')
-- ORDER BY rm.role_id, rm.client_type;

-- =====================================================================
-- 回滚 (整条反向; 被删单据仍在 DB, 只是菜单可见但数据仍 403):
-- DELETE FROM sys_role_menu WHERE menu_id =
--   (SELECT id FROM sys_menu WHERE perms='report:view')
--   AND role_id IN (SELECT id FROM sys_role
--                   WHERE role_code IN ('SALES_MGR','WAREHOUSE_MGR','PRODUCTION_MGR','FINANCE'));
-- =====================================================================
