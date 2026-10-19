-- v1.1.96 hotfix: 补 production:requisition:list perm 节点 + 给 6 业务角色授权
-- 现象: gpssong (SUPER_ADMIN) 调 GET /api/production/requisition/page 返 403
-- 根因: PrdRequisitionController 第 21/30 行 @SaCheckPermission 用的是 production:requisition:list,
--       但 sys_menu 只 seed 了 add/check/delete 三条, 没有 list 节点.
--       超级管理员的 role_code=SUPER_ADMIN, 不命中 SaCheckPermission.orRole="admin" 字面量, 所以被拒.
-- 方案: INSERT list perm 节点 + 给 6 业务角色 (SUPER_ADMIN/PURCHASE_MGR/SALES_MGR/WAREHOUSE_MGR/PRODUCTION_MGR/FINANCE) 授权
-- 不给 2 个工人角色 (zdg/cmg) 授权, 工人不进领料页面 (与现有 add/check/delete 一致)
-- 幂等: NOT EXISTS + INSERT IGNORE
-- Idempotent on re-run.

-- 1. 插 list perm 节点 (列名按真实表结构: sort_no / is_visible / client_type / dashboard_perm / deleted)
--    id 用 SELECT MAX(id)+1 推算, 避开硬编码冲突 (2090345792472715296 已被 production:finished-in:add 占用)
INSERT INTO sys_menu (
  id, parent_id, menu_name, menu_type, path, component, perms, icon,
  sort_no, is_visible, dashboard_perm, status, client_type,
  create_by, create_time, deleted
)
SELECT
  (SELECT IFNULL(MAX(id), 0) + 1 FROM sys_menu), 0, '领料单查询', 'F', '#', '',
  'production:requisition:list', '#',
  4, 0, 0, 1, 'BOTH',
  1, NOW(3), 0
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'production:requisition:list');

-- 2. 给 6 业务角色授权 (按 perm 反查刚插的 id, 不硬编码)
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
FROM sys_role r
JOIN sys_menu m ON m.perms = 'production:requisition:list'
WHERE r.role_code IN (
  'SUPER_ADMIN', 'PURCHASE_MGR', 'SALES_MGR',
  'WAREHOUSE_MGR', 'PRODUCTION_MGR', 'FINANCE'
);
