-- =============================================================================
-- v1.1.24: 权限点补齐 seed (Controller 鉴权加固配套)
-- 日期: 2026-08-26
--
-- 背景: v1.1.24 给 26 个 Controller 加了 @SaCheckPermission 注解, 但很多
--       权限码 (比如 sales:delivery:add, sales:delivery:check, finance:receipt:add 等)
--       在 sys_menu 里还没有记录, 不 seed 进去则现有用户调不到这些 API.
--
-- 策略:
--   1) 缺失的权限码以 menu_type='F' (按钮) 形式插入 sys_menu, parent_id=0, is_visible=0
--      这样它们不出现在菜单树但作为权限载体存在.
--   2) 给所有内置业务角色 (SUPER_ADMIN/PURCHASE_MGR/SALES_MGR/WAREHOUSE_MGR/
--      PRODUCTION_MGR/FINANCE) 一次性绑所有新权限, 不区分细颗粒度
--      (按"超管默认给所有权限"的设计, orRole="admin" 已绕过此校验;
--        非超管角色按"业务部门拥有本部门全权限"的原则).
--   3) 幂等: INSERT IGNORE 全部包, 可反复执行.
--
-- 用法:  mysql -uroot -p industrial_erp < sql/28_v124_permissions.sql
-- =============================================================================
SET NAMES utf8mb4;
USE industrial_erp;

-- ---------- 1) 新增权限点 ----------
-- 销售域
INSERT IGNORE INTO `sys_menu`(`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`perms`,`icon`,`sort_no`,`is_visible`,`status`) VALUES
(0,'销售订单新增','F','',NULL,'sales:order:add',NULL,1001,0,1),
(0,'销售订单编辑','F','',NULL,'sales:order:edit',NULL,1002,0,1),
(0,'销售订单删除','F','',NULL,'sales:order:delete',NULL,1003,0,1),
(0,'销售订单审核','F','',NULL,'sales:order:check',NULL,1004,0,1),
(0,'销售出库新增','F','',NULL,'sales:delivery:add',NULL,1011,0,1),
(0,'销售出库编辑','F','',NULL,'sales:delivery:edit',NULL,1012,0,1),
(0,'销售出库删除','F','',NULL,'sales:delivery:delete',NULL,1013,0,1),
(0,'销售出库审核','F','',NULL,'sales:delivery:check',NULL,1014,0,1),
(0,'销售退货新增','F','',NULL,'sales:return:add',NULL,1021,0,1),
(0,'销售退货审核','F','',NULL,'sales:return:check',NULL,1022,0,1);

-- 采购域
INSERT IGNORE INTO `sys_menu`(`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`perms`,`icon`,`sort_no`,`is_visible`,`status`) VALUES
(0,'采购订单新增','F','',NULL,'purchase:order:add',NULL,2001,0,1),
(0,'采购订单编辑','F','',NULL,'purchase:order:edit',NULL,2002,0,1),
(0,'采购订单删除','F','',NULL,'purchase:order:delete',NULL,2003,0,1),
(0,'采购订单审核','F','',NULL,'purchase:order:check',NULL,2004,0,1),
(0,'采购入库新增','F','',NULL,'purchase:receipt:add',NULL,2011,0,1),
(0,'采购入库编辑','F','',NULL,'purchase:receipt:edit',NULL,2012,0,1),
(0,'采购入库删除','F','',NULL,'purchase:receipt:delete',NULL,2013,0,1),
(0,'采购入库审核','F','',NULL,'purchase:receipt:check',NULL,2014,0,1),
(0,'采购退货新增','F','',NULL,'purchase:return:add',NULL,2021,0,1),
(0,'采购退货审核','F','',NULL,'purchase:return:check',NULL,2022,0,1);

-- 生产域
INSERT IGNORE INTO `sys_menu`(`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`perms`,`icon`,`sort_no`,`is_visible`,`status`) VALUES
(0,'BOM新增','F','',NULL,'production:bom:add',NULL,3001,0,1),
(0,'BOM编辑','F','',NULL,'production:bom:edit',NULL,3002,0,1),
(0,'BOM删除','F','',NULL,'production:bom:delete',NULL,3003,0,1),
(0,'生产订单编辑','F','',NULL,'production:order:edit',NULL,3012,0,1),
(0,'生产订单删除','F','',NULL,'production:order:delete',NULL,3013,0,1),
(0,'生产订单下达','F','',NULL,'production:order:release',NULL,3014,0,1),
(0,'生产订单完工','F','',NULL,'production:order:finish',NULL,3015,0,1),
(0,'领料单新增','F','',NULL,'production:requisition:add',NULL,3021,0,1),
(0,'领料单审核','F','',NULL,'production:requisition:check',NULL,3022,0,1),
(0,'领料单删除','F','',NULL,'production:requisition:delete',NULL,3023,0,1),
(0,'成品入库新增','F','',NULL,'production:finished-in:add',NULL,3031,0,1),
(0,'成品入库审核','F','',NULL,'production:finished-in:check',NULL,3032,0,1);

-- 库存域
INSERT IGNORE INTO `sys_menu`(`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`perms`,`icon`,`sort_no`,`is_visible`,`status`) VALUES
(0,'库存调拨新增','F','',NULL,'inventory:transfer:add',NULL,4001,0,1),
(0,'库存调拨审核','F','',NULL,'inventory:transfer:check',NULL,4002,0,1),
(0,'库存预警','F','',NULL,'inventory:warning:list',NULL,4011,0,1);

-- 委外域
INSERT IGNORE INTO `sys_menu`(`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`perms`,`icon`,`sort_no`,`is_visible`,`status`) VALUES
(0,'委外发出新增','F','',NULL,'outsource:issue:add',NULL,5001,0,1),
(0,'委外发出审核','F','',NULL,'outsource:issue:check',NULL,5002,0,1),
(0,'委外加工入库新增','F','',NULL,'outsource:pi:add',NULL,5011,0,1),
(0,'委外加工入库审核','F','',NULL,'outsource:pi:check',NULL,5012,0,1);

-- 财务域
INSERT IGNORE INTO `sys_menu`(`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`perms`,`icon`,`sort_no`,`is_visible`,`status`) VALUES
(0,'收款','F','',NULL,'finance:receipt:add',NULL,6001,0,1),
(0,'付款','F','',NULL,'finance:payment:add',NULL,6002,0,1);

-- 基础数据
INSERT IGNORE INTO `sys_menu`(`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`perms`,`icon`,`sort_no`,`is_visible`,`status`) VALUES
(0,'商品编辑','F','',NULL,'base:product:edit',NULL,7001,0,1),
(0,'商品删除','F','',NULL,'base:product:delete',NULL,7002,0,1),
(0,'客户新增','F','',NULL,'base:customer:add',NULL,7011,0,1),
(0,'客户编辑','F','',NULL,'base:customer:edit',NULL,7012,0,1),
(0,'客户删除','F','',NULL,'base:customer:delete',NULL,7013,0,1),
(0,'供应商新增','F','',NULL,'base:supplier:add',NULL,7021,0,1),
(0,'供应商编辑','F','',NULL,'base:supplier:edit',NULL,7022,0,1),
(0,'供应商删除','F','',NULL,'base:supplier:delete',NULL,7023,0,1),
(0,'仓库新增','F','',NULL,'base:warehouse:add',NULL,7031,0,1),
(0,'仓库编辑','F','',NULL,'base:warehouse:edit',NULL,7032,0,1),
(0,'仓库删除','F','',NULL,'base:warehouse:delete',NULL,7033,0,1),
(0,'单位新增','F','',NULL,'base:unit:add',NULL,7041,0,1),
(0,'单位编辑','F','',NULL,'base:unit:edit',NULL,7042,0,1),
(0,'单位删除','F','',NULL,'base:unit:delete',NULL,7043,0,1);

-- 系统管理
INSERT IGNORE INTO `sys_menu`(`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`perms`,`icon`,`sort_no`,`is_visible`,`status`) VALUES
(0,'用户新增','F','',NULL,'system:user:add',NULL,8001,0,1),
(0,'用户编辑','F','',NULL,'system:user:edit',NULL,8002,0,1),
(0,'用户删除','F','',NULL,'system:user:delete',NULL,8003,0,1),
(0,'用户分配角色','F','',NULL,'system:user:assign-role',NULL,8004,0,1),
(0,'用户重置密码','F','',NULL,'system:user:reset-pwd',NULL,8005,0,1),
(0,'角色新增','F','',NULL,'system:role:add',NULL,8011,0,1),
(0,'角色编辑','F','',NULL,'system:role:edit',NULL,8012,0,1),
(0,'角色删除','F','',NULL,'system:role:delete',NULL,8013,0,1),
(0,'角色分配用户','F','',NULL,'system:role:assign-user',NULL,8014,0,1),
(0,'角色授权菜单','F','',NULL,'system:role:grant',NULL,8015,0,1),
(0,'菜单新增','F','',NULL,'system:menu:add',NULL,8021,0,1),
(0,'菜单编辑','F','',NULL,'system:menu:edit',NULL,8022,0,1),
(0,'菜单删除','F','',NULL,'system:menu:delete',NULL,8023,0,1),
(0,'部门新增','F','',NULL,'system:dept:add',NULL,8031,0,1),
(0,'部门编辑','F','',NULL,'system:dept:edit',NULL,8032,0,1),
(0,'部门删除','F','',NULL,'system:dept:delete',NULL,8033,0,1),
(0,'系统配置新增','F','',NULL,'system:config:add',NULL,8041,0,1),
(0,'系统配置编辑','F','',NULL,'system:config:edit',NULL,8042,0,1),
(0,'系统配置删除','F','',NULL,'system:config:delete',NULL,8043,0,1),
(0,'打印模板新增','F','',NULL,'system:print:add',NULL,8051,0,1),
(0,'打印模板编辑','F','',NULL,'system:print:edit',NULL,8052,0,1),
(0,'打印模板删除','F','',NULL,'system:print:delete',NULL,8053,0,1),
(0,'操作日志查看','F','',NULL,'system:oper-log:list',NULL,8061,0,1),
(0,'操作日志清理','F','',NULL,'system:oper-log:delete',NULL,8062,0,1),
(0,'登录日志查看','F','',NULL,'system:login-log:list',NULL,8071,0,1);

-- ---------- 2) 给所有内置业务角色绑所有新权限 ----------
-- 策略: 一个 CROSS JOIN 一次性把所有新权限插入到 sys_role_menu.
-- 已存在的 (role_id, menu_id) 由 INSERT IGNORE 自动去重.

INSERT IGNORE INTO `sys_role_menu`(`role_id`,`menu_id`)
SELECT r.id, m.id
FROM `sys_role` r
CROSS JOIN `sys_menu` m
WHERE r.deleted = 0
  AND r.role_code IN ('SUPER_ADMIN','PURCHASE_MGR','SALES_MGR','WAREHOUSE_MGR','PRODUCTION_MGR','FINANCE')
  AND m.deleted = 0
  AND m.perms IN (
    -- 本次新增的全部权限码
    'sales:order:add','sales:order:edit','sales:order:delete','sales:order:check',
    'sales:delivery:add','sales:delivery:edit','sales:delivery:delete','sales:delivery:check',
    'sales:return:add','sales:return:check',
    'purchase:order:add','purchase:order:edit','purchase:order:delete','purchase:order:check',
    'purchase:receipt:add','purchase:receipt:edit','purchase:receipt:delete','purchase:receipt:check',
    'purchase:return:add','purchase:return:check',
    'production:bom:add','production:bom:edit','production:bom:delete',
    'production:order:edit','production:order:delete','production:order:release','production:order:finish',
    'production:requisition:add','production:requisition:check','production:requisition:delete',
    'production:finished-in:add','production:finished-in:check',
    'inventory:transfer:add','inventory:transfer:check','inventory:warning:list',
    'outsource:issue:add','outsource:issue:check','outsource:pi:add','outsource:pi:check',
    'finance:receipt:add','finance:payment:add',
    'base:product:edit','base:product:delete',
    'base:customer:add','base:customer:edit','base:customer:delete',
    'base:supplier:add','base:supplier:edit','base:supplier:delete',
    'base:warehouse:add','base:warehouse:edit','base:warehouse:delete',
    'base:unit:add','base:unit:edit','base:unit:delete',
    'system:user:add','system:user:edit','system:user:delete','system:user:assign-role','system:user:reset-pwd',
    'system:role:add','system:role:edit','system:role:delete','system:role:assign-user','system:role:grant',
    'system:menu:add','system:menu:edit','system:menu:delete',
    'system:dept:add','system:dept:edit','system:dept:delete',
    'system:config:add','system:config:edit','system:config:delete',
    'system:print:add','system:print:edit','system:print:delete',
    'system:oper-log:list','system:oper-log:delete','system:login-log:list'
  );

-- ---------- 3) 校验 ----------
-- 期望: 每个新权限码有 6 个角色绑定 (SUPER_ADMIN + 5 个业务角色)
SELECT m.perms, COUNT(rm.role_id) AS role_count
FROM sys_menu m
LEFT JOIN sys_role_menu rm ON rm.menu_id = m.id
WHERE m.perms IN (
  'sales:delivery:add','sales:delivery:check',
  'purchase:receipt:add','purchase:receipt:check',
  'finance:receipt:add','finance:payment:add',
  'system:print:add','system:oper-log:list'
)
GROUP BY m.perms
ORDER BY m.perms;