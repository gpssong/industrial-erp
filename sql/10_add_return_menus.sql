-- 添加采购退货和销售退货菜单
INSERT INTO `sys_menu`(`id`,`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`perms`,`icon`,`sort_no`,`is_visible`,`status`) VALUES
(403, 4, '采购退货', 'M', '/purchase/return', 'purchase/Return.vue', 'purchase:return:list', 'Back', 3, 1, 1),
(503, 5, '销售退货', 'M', '/sales/return', 'sales/Return.vue', 'sales:return:list', 'Refresh', 3, 1, 1);

-- 为超级管理员分配退货权限
INSERT IGNORE INTO `sys_role_menu`(`role_id`,`menu_id`) VALUES
(1, 403), (1, 503);
