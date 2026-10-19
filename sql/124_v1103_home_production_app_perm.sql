-- v1.1.103 home 主站热修: 补 production 关键 perm 的 APP 端授权
-- 现象: http://192.168.0.150:18090 生产管理 tab 只显示 1 个「生产加工单」, 缺扫码报工/半成品入库/成品入库
-- 根因: sql/53 (MES 一期) / sql/110 (半成品入库) / sql/113 (排程) 这 3 个 seed 都只授了 PC 端,
--       home 同步 PC 端数据时缺 APP 端授权行 → LoginVO.appMenus 不过滤 → dashboard
--       visibleMenus 拿不到这些 perm → 不显示入口.
--       而 erp_menus 的 PC-only 行 client_type='PC', 不会进 APP 端 login response 的 appMenus 数组.
-- 修法: 给 6 内置业务角色补 7 个 production perm 的 APP 端授权 (NOT EXISTS 守门, 幂等, 重复灌安全).
--       不动 sql/53/sql/110/sql/113 源文件 (飞牛 canary 已有 APP 授权是另一次手工加的, 保留历史).
-- 验证: 灌完后 GPSsong (SUPER_ADMIN) 重新登录, appMenus 应含 production:report:list/add /
--       production:semi-in:list/add / production:schedule:list/edit / production:finished-in:add.
USE industrial_erp;

-- 1. 给 6 业务角色 × 7 production perm 补 APP 端授权
INSERT INTO sys_role_menu (role_id, menu_id, client_type)
SELECT r.id, m.id, 'APP'
FROM sys_role r
CROSS JOIN sys_menu m
WHERE m.perms IN (
    'production:report:list',
    'production:report:add',
    'production:semi-in:list',
    'production:semi-in:add',
    'production:semi-in:check',
    'production:schedule:list',
    'production:schedule:edit',
    'production:finished-in:add',
    'production:finished-in:check'
  )
  AND m.deleted=0
  AND r.role_code IN ('SUPER_ADMIN','PURCHASE_MGR','SALES_MGR','WAREHOUSE_MGR','PRODUCTION_MGR','FINANCE')
  AND r.deleted=0
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm
    WHERE rm.role_id=r.id AND rm.menu_id=m.id AND rm.client_type='APP'
  );

-- 2. 验证
SELECT r.role_code, m.perms, rm.client_type
FROM sys_role r
JOIN sys_role_menu rm ON rm.role_id=r.id
JOIN sys_menu m ON m.id=rm.menu_id
WHERE m.perms IN (
    'production:report:list','production:report:add',
    'production:semi-in:list','production:semi-in:add','production:semi-in:check',
    'production:schedule:list','production:schedule:edit',
    'production:finished-in:add','production:finished-in:check'
  )
  AND m.deleted=0
  AND r.role_code IN ('SUPER_ADMIN','PURCHASE_MGR','SALES_MGR','WAREHOUSE_MGR','PRODUCTION_MGR','FINANCE')
  AND rm.client_type='APP'
ORDER BY r.role_code, m.perms;
