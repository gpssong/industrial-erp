-- v1.1.92: 侧边栏新增「领料记录」「成品入库」两个菜单节点
-- 此前 v1.1.80 MES 上线时只 seed 了 F 类型按钮 perms (production:requisition:{add,check,delete} +
-- production:finished-in:{add,check}), 没有 M 类型菜单节点, 也没有 list perm, 路由/sidebar 都进不来.
-- Hub 页 (购销存与经营管理) 4 项生产功能里有这 2 个 tile, 但 sidebar 一直缺.
-- 信息守门幂等 (information_schema 探测 + INSERT IGNORE / NOT EXISTS).

-- 1) 两条 list perm (F 类型 perm 载体, parent_id=0, is_visible=0, sort_no 与既有 3020/3030 续号)
INSERT IGNORE INTO sys_menu (menu_type, menu_name, parent_id, path, component, perms, icon, sort_no, is_visible, status)
VALUES
  ('F', '领料单查询', 0, '', NULL, 'production:requisition:list',  NULL, 3020, 0, 1),
  ('F', '成品入库查询', 0, '', NULL, 'production:finished-in:list', NULL, 3030, 0, 1);

-- 2) 两条 M 类型菜单 (parent_id 由「生产管理」反查, 跨环境兼容)
SET @prod_parent = (SELECT id FROM sys_menu WHERE menu_name='生产管理' AND deleted=0 LIMIT 1);

INSERT INTO sys_menu (menu_type, menu_name, parent_id, path, component, perms, icon, sort_no, is_visible, status)
SELECT 'M', '领料记录', @prod_parent, '/production/requisition', 'production/Requisition', '', 'Notebook', 3025, 1, 1
FROM DUAL WHERE @prod_parent IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE path='/production/requisition' AND deleted=0);

INSERT INTO sys_menu (menu_type, menu_name, parent_id, path, component, perms, icon, sort_no, is_visible, status)
SELECT 'M', '成品入库', @prod_parent, '/production/finished-in', 'production/FinishedIn', '', 'Box', 3035, 1, 1
FROM DUAL WHERE @prod_parent IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE path='/production/finished-in' AND deleted=0);

-- 3) 把 list perm 反向挂到 M 菜单 (菜单节点 perms 列), 让 el-tree markDisabled 把它当「可授权叶子」放行
UPDATE sys_menu SET perms='production:requisition:list'  WHERE path='/production/requisition' AND deleted=0 AND perms='';
UPDATE sys_menu SET perms='production:finished-in:list' WHERE path='/production/finished-in'  AND deleted=0 AND perms='';

-- 4) 给 6 个内置业务角色补 list perm 授权 (PC + APP + BOTH 三态自适应)
-- 用 CROSS JOIN 派生角色候选集 + 子查询反查 menu_id (避开 @var NULL 隐式短路)
SET @safe_guard = 1;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
SELECT r.id, m.id, 'PC'
FROM sys_role r
CROSS JOIN (SELECT id FROM sys_menu WHERE path='/production/requisition' AND deleted=0 LIMIT 1) m
WHERE r.role_code IN ('SUPER_ADMIN','PURCHASE_MGR','SALES_MGR','WAREHOUSE_MGR','PRODUCTION_MGR','FINANCE')
  AND r.deleted=0 AND @safe_guard=1;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
SELECT r.id, m.id, 'APP'
FROM sys_role r
CROSS JOIN (SELECT id FROM sys_menu WHERE path='/production/requisition' AND deleted=0 LIMIT 1) m
WHERE r.role_code IN ('SUPER_ADMIN','PURCHASE_MGR','SALES_MGR','WAREHOUSE_MGR','PRODUCTION_MGR','FINANCE')
  AND r.deleted=0 AND @safe_guard=1;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
SELECT r.id, m.id, 'BOTH'
FROM sys_role r
CROSS JOIN (SELECT id FROM sys_menu WHERE path='/production/requisition' AND deleted=0 LIMIT 1) m
WHERE r.role_code IN ('SUPER_ADMIN','PURCHASE_MGR','SALES_MGR','WAREHOUSE_MGR','PRODUCTION_MGR','FINANCE')
  AND r.deleted=0 AND @safe_guard=1;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
SELECT r.id, m.id, 'PC'
FROM sys_role r
CROSS JOIN (SELECT id FROM sys_menu WHERE path='/production/finished-in' AND deleted=0 LIMIT 1) m
WHERE r.role_code IN ('SUPER_ADMIN','PURCHASE_MGR','SALES_MGR','WAREHOUSE_MGR','PRODUCTION_MGR','FINANCE')
  AND r.deleted=0 AND @safe_guard=1;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
SELECT r.id, m.id, 'APP'
FROM sys_role r
CROSS JOIN (SELECT id FROM sys_menu WHERE path='/production/finished-in' AND deleted=0 LIMIT 1) m
WHERE r.role_code IN ('SUPER_ADMIN','PURCHASE_MGR','SALES_MGR','WAREHOUSE_MGR','PRODUCTION_MGR','FINANCE')
  AND r.deleted=0 AND @safe_guard=1;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
SELECT r.id, m.id, 'BOTH'
FROM sys_role r
CROSS JOIN (SELECT id FROM sys_menu WHERE path='/production/finished-in' AND deleted=0 LIMIT 1) m
WHERE r.role_code IN ('SUPER_ADMIN','PURCHASE_MGR','SALES_MGR','WAREHOUSE_MGR','PRODUCTION_MGR','FINANCE')
  AND r.deleted=0 AND @safe_guard=1;