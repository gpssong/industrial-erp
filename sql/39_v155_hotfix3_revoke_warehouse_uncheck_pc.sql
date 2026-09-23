-- =====================================================================
-- 三十九、v1.1.55 hotfix-3 — 撤销 WAREHOUSE_MGR 的 uncheck PC 授权
-- 2026-09-22
-- =====================================================================
--
-- 背景:
--   sql/35 1.3 CROSS JOIN 给 6 内置角色 (含 WAREHOUSE_MGR) 都加了
--   purchase:receipt:uncheck PC + sales:delivery:uncheck PC. 这是 sql/38
--   撤销 APP uncheck 的对称操作 (PC 端也撤).
--
--   业务决策:
--     - WAREHOUSE_MGR (仓库主管) 不应有反审核权限 (高风险, 通常只给老板)
--     - 保留 check PC (审核是日常正向操作, 仓管员正常可做)
--     - 保留 check APP + uncheck APP = 0 (sql/38 已撤)
--
--   R9 后端分流 (AuthService 按 X-Client-Type header 选 SQL) 落地后:
--     App 端: /me 返回 APP-only perms, 即使 PC uncheck 还在, 也不影响 App
--     PC 端: /me 返回 PC-only perms, gpssong1 登录 PC 后反审核按钮也会消失
--
-- 注意:
--   - 这是物理 DELETE, 可重跑 (DELETE 幂等)
--   - 不影响其他 5 内置角色 (SUPER_ADMIN/PURCHASE_MGR/SALES_MGR/
--     PRODUCTION_MGR/FINANCE) 的 PC uncheck, 他们业务上需要
--   - 用户已经手动取消勾选 "采购入库反审核/销售出库反审核" PC 行, 但 sys_role_menu
--     行还在 (popup 显示 UNCHECKED 是因为前端 el-tree 状态, 后端 perm 数组仍含
--     这两个 perm). 本 SQL 物理删除 sys_role_menu 行, 让后端 selectPermsByUserId
--     返回的 PC perm 也不含 :uncheck
-- =====================================================================

USE `industrial_erp`;

-- 1. 撤销 WAREHOUSE_MGR 的 uncheck PC (物理 DELETE)
DELETE rm FROM `sys_role_menu` rm
JOIN `sys_role` r ON r.id=rm.role_id AND r.deleted=0
JOIN `sys_menu` m ON m.id=rm.menu_id AND m.deleted=0
WHERE r.role_code='WAREHOUSE_MGR'
  AND rm.client_type='PC'
  AND m.perms IN ('purchase:receipt:uncheck','sales:delivery:uncheck');

-- 2. 校验: WAREHOUSE_MGR 现在 PC + APP 端 uncheck 都=0
SELECT r.role_code, r.role_name,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id WHERE rm.role_id=r.id AND m.perms='purchase:receipt:check'   AND rm.client_type='APP') AS pur_check_app,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id WHERE rm.role_id=r.id AND m.perms='purchase:receipt:uncheck' AND rm.client_type='APP') AS pur_uncheck_app,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id WHERE rm.role_id=r.id AND m.perms='sales:delivery:check'   AND rm.client_type='APP') AS sal_check_app,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id WHERE rm.role_id=r.id AND m.perms='sales:delivery:uncheck' AND rm.client_type='APP') AS sal_uncheck_app,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id WHERE rm.role_id=r.id AND m.perms='purchase:receipt:check'   AND rm.client_type='PC')  AS pur_check_pc,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id WHERE rm.role_id=r.id AND m.perms='purchase:receipt:uncheck' AND rm.client_type='PC')  AS pur_uncheck_pc,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id WHERE rm.role_id=r.id AND m.perms='sales:delivery:check'   AND rm.client_type='PC')  AS sal_check_pc,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id WHERE rm.role_id=r.id AND m.perms='sales:delivery:uncheck' AND rm.client_type='PC')  AS sal_uncheck_pc
FROM sys_role r WHERE r.deleted=0 AND r.role_code='WAREHOUSE_MGR';

-- 预期:
--   pur_check_app=1, pur_uncheck_app=0
--   sal_check_app=1, sal_uncheck_app=0
--   pur_check_pc=1,  pur_uncheck_pc=0
--   sal_check_pc=1,  sal_uncheck_pc=0

-- 3. 验证其他 5 内置角色 PC uncheck 保留 (sanity check)
SELECT r.role_code,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id WHERE rm.role_id=r.id AND m.perms='purchase:receipt:uncheck' AND rm.client_type='PC') AS pur_uncheck_pc,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id WHERE rm.role_id=r.id AND m.perms='sales:delivery:uncheck' AND rm.client_type='PC') AS sal_uncheck_pc
FROM sys_role r
WHERE r.deleted=0 AND r.role_code IN ('SUPER_ADMIN','PURCHASE_MGR','SALES_MGR','PRODUCTION_MGR','FINANCE')
ORDER BY r.id;

-- 预期: 5 行都 pur_uncheck_pc=1 AND sal_uncheck_pc=1

-- 4. 部署后操作
--    - 跑完立即生效 (Sa-Token 不缓存 perm, 下次请求重查)
--    - App 用户: 完全卸载 + 装新 APK (R9 后端分流 + getAppPermissions fallback
--      链已修, 老 storage 残留不再影响)
--    - PC 用户: gpssong1 重新登录 → 反审核按钮消失
--    - 其他角色 (老板/超管/采购/销售/生产/财务): PC uncheck 保留, 业务不受影响
