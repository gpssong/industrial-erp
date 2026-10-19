-- v1.1.102 hotfix-3: App 端补 work:maintain:list (设备维保 perm) 授权
-- 与 PC 端 6 内置角色对称, 让 GPS 现场填表 子分类能看到 XK 设备维保入口
USE industrial_erp;

INSERT INTO sys_role_menu (role_id, menu_id, client_type)
SELECT r.id, m.id, 'APP'
FROM sys_role r
JOIN sys_menu m ON m.perms='work:maintain:list' AND m.deleted=0
WHERE r.role_code IN ('SUPER_ADMIN','PURCHASE_MGR','SALES_MGR','WAREHOUSE_MGR','PRODUCTION_MGR','FINANCE')
ON DUPLICATE KEY UPDATE client_type='APP';

-- 验证
SELECT r.role_code, rm.client_type, m.id AS menu_id
FROM sys_role_menu rm
JOIN sys_role r ON r.id=rm.role_id
JOIN sys_menu m ON m.id=rm.menu_id
WHERE m.perms='work:maintain:list'
ORDER BY r.role_code, rm.client_type, m.id;
