-- v1.1.103: home 主站补灌 production 全 perm 的 APP + PC 双端授权
-- 现象: App H5 端 生产管理 tab 只显示 1 个「生产加工单」, 缺
--       扫码报工 / 半成品入库 / 成品入库 / 生产排程 / 设备 OEE 共 5 个入口
-- 根因: sql/115/116/117 同步到 home 时只灌了部分 APP 授权, 8 个核心 perm
--       在 home 主站 sys_role_menu 上 PC 端和 APP 端都没有对应行
-- 修法: 给 6 内置角色 × 8 核心 perm 灌 PC + APP 双端授权 (ON DUPLICATE KEY 幂等)
USE industrial_erp;

-- 1. PC 端 8 perm × 6 角色 授权
INSERT INTO sys_role_menu (role_id, menu_id, client_type)
SELECT r.id, m.id, 'PC'
FROM sys_role r
CROSS JOIN sys_menu m
WHERE m.perms IN (
    'production:report:list',
    'production:report:add',
    'production:semi-in:list',
    'production:semi-in:add',
    'production:finished-in:list',
    'production:finished-in:add',
    'production:schedule:list',
    'production:schedule:edit'
  )
  AND m.deleted=0
  AND r.role_code IN ('SUPER_ADMIN','PURCHASE_MGR','SALES_MGR','WAREHOUSE_MGR','PRODUCTION_MGR','FINANCE')
  AND r.deleted=0
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm
    WHERE rm.role_id=r.id AND rm.menu_id=m.id AND rm.client_type='PC'
  );

-- 2. APP 端 8 perm × 6 角色 授权
INSERT INTO sys_role_menu (role_id, menu_id, client_type)
SELECT r.id, m.id, 'APP'
FROM sys_role r
CROSS JOIN sys_menu m
WHERE m.perms IN (
    'production:report:list',
    'production:report:add',
    'production:semi-in:list',
    'production:semi-in:add',
    'production:finished-in:list',
    'production:finished-in:add',
    'production:schedule:list',
    'production:schedule:edit'
  )
  AND m.deleted=0
  AND r.role_code IN ('SUPER_ADMIN','PURCHASE_MGR','SALES_MGR','WAREHOUSE_MGR','PRODUCTION_MGR','FINANCE')
  AND r.deleted=0
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm
    WHERE rm.role_id=r.id AND rm.menu_id=m.id AND rm.client_type='APP'
  );

-- 3. 验证
SELECT r.role_code, rm.client_type, m.perms
FROM sys_role r
JOIN sys_role_menu rm ON rm.role_id=r.id
JOIN sys_menu m ON m.id=rm.menu_id
WHERE r.role_code IN ('SUPER_ADMIN','PURCHASE_MGR','SALES_MGR','WAREHOUSE_MGR','PRODUCTION_MGR','FINANCE')
  AND m.perms IN (
    'production:report:list','production:report:add',
    'production:semi-in:list','production:semi-in:add',
    'production:finished-in:list','production:finished-in:add',
    'production:schedule:list','production:schedule:edit'
  )
  AND m.deleted=0
ORDER BY r.role_code, m.perms, rm.client_type;
