-- =====================================================================
--  16. 打印模板菜单 (myprint-design)
--  父菜单 205 = 系统设置 (01_schema_system.sql seed)
--  perm: system:print:list (PermissionService 自动覆盖 :add/:edit/:delete)
-- =====================================================================
INSERT INTO `sys_menu`(`id`,`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`perms`,`icon`,`sort_no`,`is_visible`,`status`) VALUES
(605, 205, '打印模板', 'M', '/system/print-template', 'system/PrintTemplate.vue', 'system:print:list', 'Printer', 6, 1, 1);

-- 分配给超级管理员 (role_id=1)
INSERT IGNORE INTO `sys_role_menu`(`role_id`,`menu_id`) VALUES
(1, 605);