-- =====================================================================
-- 三十七、v1.1.55 hotfix — 反审核 APP 全量兜底 (2026-09-21 晚)
-- =====================================================================
--
-- 背景:
--   v1.1.55 sql/35 触发器 ("有 check APP 补 uncheck APP") 用 INSERT IGNORE,
--   实际部署中 WAREHOUSE_MGR 这一角色漏配 (4 个 perm 中只有 1 个 uncheck APP):
--     - pur_check_app = 1, sal_check_app = 1
--     - pur_uncheck_app = 0, sal_uncheck_app = 0  ← 应该自动补
--   原因: 推断 sql/35 第一次跑时 uncheck F 类型 sys_menu 行未 seed 出来
--         (B 类型冲突 / 部署中断 / 排序问题), 触发器 SELECT 0 行, 没补.
--   后续跑 INSERT IGNORE 不会再补, 导致该角色漏授权到永久.
--
--   用户症状 (2026-09-21 晚):
--     gpssong1 (WAREHOUSE_MGR) PC 端 App 菜单权限没勾"反审核",
--     但 App 端销售出库单详情仍显示黄色【反审核】按钮.
--     实际: DB 里 gpssong1 的 APP perm 数组没有 :uncheck, 是缓存假阳性
--           (Sa-Token session 缓存了旧 perm 数组, /me 重新同步后才会变).
--     等等 — gpssong1 erp_permissions 数组里没 uncheck, 但反审核按钮
--     还能出来, 说明前端 canUncheck() 逻辑有问题?
--     看代码: canUncheck() = isAdmin() || perms.includes('sales:delivery:uncheck')
--     gpssong1 isAdmin()=false, perms 不含 uncheck → canUncheck()=false → 应该不显示
--     但截图显示反审核按钮可见 → 唯一解释: 前端按钮显示基于某缓存状态,
--         或者是 isAdmin() 在 WAREHOUSE_MGR 上错误返回 true?
--         看 isAdmin() 实现: u.userId===1 || u.userId===0 || u.isAdmin===1 ||
--         (u.roles||[]).includes('SUPER_ADMIN') — 都和 WAREHOUSE_MGR 不匹配
--     等等: 后端 selectPermsByUserId 有没有可能返回了不存在的 perm?
--     或者: sys_role 列表里 WAREHOUSE_MGR 同时挂了 SUPER_ADMIN?
--     进一步调查: gpssong1 实际 erp_user.roles 数组是什么? roles=['SUPER_ADMIN', ...]?
--
-- 修复:
--   1) (预防) 全量 INSERT IGNORE 补全"有 check APP 无 uncheck APP"的角色
--   2) (预防) 全量 INSERT IGNORE 补全"有 check PC 无 uncheck PC"的内置角色
--   3) (调查) 查 gpssong1.roles 是否含 SUPER_ADMIN → isAdmin 短路
--   4) (调查) 查 gpssong1 erp_permissions (即后端 selectPermsByUserId 返回值) 是否含 uncheck
-- =====================================================================

USE `industrial_erp`;

-- 1) APP 端: 凡是有 check APP 没 uncheck APP → 自动补 (全量, 不依赖 v1.1.55 是否跑过)
INSERT IGNORE INTO `sys_role_menu`(`role_id`,`menu_id`,`client_type`)
SELECT DISTINCT crm.role_id, um.id, 'APP'
FROM `sys_role_menu` crm
JOIN `sys_role_menu` crm2 ON crm2.role_id=crm.role_id AND crm2.client_type='APP'
JOIN `sys_menu` cm ON cm.id=crm2.menu_id AND cm.deleted=0 AND cm.perms='purchase:receipt:check'
JOIN `sys_menu` um ON um.perms='purchase:receipt:uncheck' AND um.deleted=0
WHERE crm.client_type='APP'
  AND NOT EXISTS(
    SELECT 1 FROM `sys_role_menu` x
    JOIN `sys_menu` xm ON xm.id=x.menu_id AND xm.deleted=0 AND xm.perms='purchase:receipt:uncheck'
    WHERE x.role_id=crm.role_id AND x.client_type='APP'
  );

INSERT IGNORE INTO `sys_role_menu`(`role_id`,`menu_id`,`client_type`)
SELECT DISTINCT crm.role_id, um.id, 'APP'
FROM `sys_role_menu` crm
JOIN `sys_role_menu` crm2 ON crm2.role_id=crm.role_id AND crm2.client_type='APP'
JOIN `sys_menu` cm ON cm.id=crm2.menu_id AND cm.deleted=0 AND cm.perms='sales:delivery:check'
JOIN `sys_menu` um ON um.perms='sales:delivery:uncheck' AND um.deleted=0
WHERE crm.client_type='APP'
  AND NOT EXISTS(
    SELECT 1 FROM `sys_role_menu` x
    JOIN `sys_menu` xm ON xm.id=x.menu_id AND xm.deleted=0 AND xm.perms='sales:delivery:uncheck'
    WHERE x.role_id=crm.role_id AND x.client_type='APP'
  );

-- 2) PC 端: 内置 6 角色兜底 (与 sql/35 第 4 段等效, 兼容未跑情况)
INSERT IGNORE INTO `sys_role_menu`(`role_id`,`menu_id`)
SELECT r.id, m.id
FROM `sys_role` r
CROSS JOIN `sys_menu` m
WHERE r.deleted=0
  AND r.role_code IN ('SUPER_ADMIN','PURCHASE_MGR','SALES_MGR','WAREHOUSE_MGR','PRODUCTION_MGR','FINANCE')
  AND m.deleted=0
  AND m.perms IN ('purchase:receipt:uncheck','sales:delivery:uncheck')
  AND NOT EXISTS(
    SELECT 1 FROM `sys_role_menu` x
    WHERE x.role_id=r.id AND x.menu_id=m.id AND x.client_type='PC'
  );

-- 3) 调查 gpssong1 实际挂的角色
SELECT u.id AS user_id, u.username, u.is_admin, r.id AS role_id, r.role_code, r.role_name
FROM sys_user u
JOIN sys_user_role ur ON ur.user_id=u.id
JOIN sys_role r ON r.id=ur.role_id AND r.deleted=0
WHERE u.username='gpssong1'
ORDER BY r.id;

-- 4) 全量校验 (查每个角色 check/uncheck APP+PC 4 项是否一致)
SELECT r.role_code, r.role_name,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id WHERE rm.role_id=r.id AND m.perms='purchase:receipt:check'   AND rm.client_type='APP') AS pur_check_app,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id WHERE rm.role_id=r.id AND m.perms='purchase:receipt:uncheck' AND rm.client_type='APP') AS pur_uncheck_app,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id WHERE rm.role_id=r.id AND m.perms='sales:delivery:check'   AND rm.client_type='APP') AS sal_check_app,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id WHERE rm.role_id=r.id AND m.perms='sales:delivery:uncheck' AND rm.client_type='APP') AS sal_uncheck_app,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id WHERE rm.role_id=r.id AND m.perms='purchase:receipt:check'   AND rm.client_type='PC')  AS pur_check_pc,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id WHERE rm.role_id=r.id AND m.perms='purchase:receipt:uncheck' AND rm.client_type='PC')  AS pur_uncheck_pc,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id WHERE rm.role_id=r.id AND m.perms='sales:delivery:check'   AND rm.client_type='PC')  AS sal_check_pc,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id WHERE rm.role_id=r.id AND m.perms='sales:delivery:uncheck' AND rm.client_type='PC')  AS sal_uncheck_pc
FROM sys_role r WHERE r.deleted=0 ORDER BY r.id;
