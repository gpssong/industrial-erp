-- ===================================================================
-- v1.0.8+ 库存盘点菜单与权限
-- ===================================================================
-- 新增 1 个菜单 (库存盘点) + 4 个权限点 (list/add/check/delete)
-- 自动追加到 sys_role_menu 给所有角色授予 list 权限 (超管保留全权)
-- ===================================================================

-- 1. 菜单 (父节点 = 6 库存管理, 排序在 ledger 之后)
INSERT IGNORE INTO `sys_menu`(`id`,`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`perms`,`icon`,`sort_no`,`is_visible`,`status`) VALUES
(603, 6, '库存盘点', 'M', '/inventory/check', 'inventory/Check.vue', 'inventory:check:list', 'Document', 3, 1, 1);

-- 2. 权限点 (type=B 按钮, 不显示在左侧菜单, 但 perms 用于按钮级控制)
INSERT IGNORE INTO `sys_menu`(`id`,`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`perms`,`icon`,`sort_no`,`is_visible`,`status`) VALUES
(6031, 603, '新增盘点单', 'B', '', '', 'inventory:check:add', '', 1, 0, 1),
(6032, 603, '审核盘点单', 'B', '', '', 'inventory:check:check', '', 2, 0, 1),
(6033, 603, '删除盘点单', 'B', '', '', 'inventory:check:delete', '', 3, 0, 1);

-- 3. 角色-菜单授权 (所有角色都能查盘点单 — 仓库主管/超管更高级)
-- super_admin (id=1) 已通过 Sa-Token 通配, 不需要单独分配
-- WAREHOUSE_MGR (id=4) 给全部权限 — 盘点是仓库核心业务
INSERT IGNORE INTO `sys_role_menu`(`role_id`,`menu_id`) VALUES
(4, 603), (4, 6031), (4, 6032), (4, 6033);

-- PURCHASE_MGR (id=2) 和 SALES_MGR (id=3) 给 list — 让采购/销售能查盘点结果
INSERT IGNORE INTO `sys_role_menu`(`role_id`,`menu_id`) VALUES
(2, 603),
(3, 603);

-- FINANCE (id=6) 给 list — 财务可能需要查差异金额
INSERT IGNORE INTO `sys_role_menu`(`role_id`,`menu_id`) VALUES
(6, 603);
