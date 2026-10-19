-- v1.1.102 home 热修: 补 production:requisition:list perm 节点 + 6 业务角色 PC 授权
-- 现象: home.93gushi.com 领料记录页 GET /api/production/requisition/page?pageNum=1&pageSize=20 → 403
-- 根因: home sys_menu 只有 add/check/delete 三条 requisition perm (293/294/295), 缺 list 节点.
--       后端 PrdRequisitionController.page() @SaCheckPermission("production:requisition:list", orRole="admin"),
--       SUPER_ADMIN role_code=SUPER_ADMIN 不命中 orRole 字面量 "admin" → 403.
-- 注: home 的 sys_role_menu PK = (role_id, menu_id, client_type) 三列, client_type 默认 'PC'.
--     sql/103_v196 是老库两列退化版本, 此处显式带 client_type 以适配三列 PK.
-- 幂等: NOT EXISTS / INSERT ... WHERE NOT EXISTS.
USE industrial_erp;

-- 1. 插 list perm 节点 (F 型, 与现有 add/check/delete 对称, parent=0 root 下)
INSERT INTO sys_menu (
  id, parent_id, menu_name, menu_type, path, component, perms, icon,
  sort_no, is_visible, dashboard_perm, status, client_type,
  create_by, create_time, deleted
)
SELECT
  (SELECT IFNULL(MAX(id), 0) + 1 FROM sys_menu), 0, '领料单查询', 'F', '#', '',
  'production:requisition:list', '#',
  4, 0, 0, 1, 'PC',
  1, NOW(3), 0
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'production:requisition:list' AND deleted = 0);

-- 2. 给 6 业务角色授 PC 端 list 权限 (显式 client_type='PC', 适配三列 PK; NOT EXISTS 防重复)
INSERT INTO sys_role_menu (role_id, menu_id, client_type)
SELECT r.id, m.id, 'PC'
FROM sys_role r
JOIN sys_menu m ON m.perms = 'production:requisition:list' AND m.deleted = 0
CROSS JOIN (SELECT 'PC' AS ct) c
WHERE r.role_code IN (
  'SUPER_ADMIN', 'PURCHASE_MGR', 'SALES_MGR',
  'WAREHOUSE_MGR', 'PRODUCTION_MGR', 'FINANCE'
)
AND NOT EXISTS (
  SELECT 1 FROM sys_role_menu rm
  WHERE rm.role_id = r.id AND rm.menu_id = m.id AND rm.client_type = 'PC'
);

-- 3. 验证
SELECT m.id, m.perms, m.menu_name, m.menu_type, m.status
FROM sys_menu m WHERE m.perms = 'production:requisition:list' AND m.deleted = 0;
SELECT r.role_code, rm.client_type, m.id
FROM sys_role_menu rm
JOIN sys_role r ON r.id = rm.role_id
JOIN sys_menu m ON m.id = rm.menu_id
WHERE m.perms = 'production:requisition:list'
ORDER BY r.role_code, rm.client_type, m.id;
