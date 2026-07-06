-- =====================================================================
-- v1.1.3 — 新增权限点 seed
-- 用法:  mysql -uroot -perp_root_pwd industrial_erp < sql/seed/14_v113_permissions.sql
-- 适用:  v1.1.3 鉴权加固 (/print /report /upload /backup 接口)
-- 说明:  print:use 和 report:view 是新增的两个权限码,
--        通过 sys_role_menu 关联到 sys_menu.perms 字段生效
-- =====================================================================

USE `industrial_erp`;

-- 1) 打印使用 — 按钮类型 (F=按钮), 不显示在菜单树, 仅作权限载体
INSERT IGNORE INTO `sys_menu`(`id`,`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`perms`,`icon`,`sort_no`,`is_visible`,`status`) VALUES
(950,0,'打印使用','F','',NULL,'print:use',NULL,950,0,1),
(951,0,'报表查看','F','',NULL,'report:view',NULL,951,0,1);

-- 2) 给所有内置业务角色绑定新权限
--    SUPER_ADMIN (id=1) 必须勾, 否则现有 admin 用户会失去打印/报表权限
--    其他 5 个角色按需自动勾 (避免破坏现有体验, 但保险起见全勾)
INSERT IGNORE INTO `sys_role_menu`(`role_id`,`menu_id`)
SELECT r.id, m.id
FROM `sys_role` r
CROSS JOIN `sys_menu` m
WHERE m.perms IN ('print:use','report:view')
  AND r.deleted = 0
  AND r.role_code IN ('SUPER_ADMIN','PURCHASE_MGR','SALES_MGR','WAREHOUSE_MGR','PRODUCTION_MGR','FINANCE');

-- 3) 校验 — 升级后跑一下确认 admin 有新权限
--    SELECT m.perms FROM sys_user u
--    JOIN sys_user_role ur ON u.id=ur.user_id
--    JOIN sys_role_menu rm ON ur.role_id=rm.role_id
--    JOIN sys_menu m ON rm.menu_id=m.id
--    WHERE u.username='admin' AND m.perms IN ('print:use','report:view');
--    应返回 2 行