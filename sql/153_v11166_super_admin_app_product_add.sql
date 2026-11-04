-- =====================================================================
-- v1.1.166 — SUPER_ADMIN 的 App 端「新增商品」授权
--   背景: App 端 /me 带 X-Client-Type: APP, perm 只来自 client_type IN ('BOTH','APP') 的授权行.
--         sys_menu 6034 (B 型, path=/base/product/add, perms=base:product:add) 建出来时只给了
--         cmg / FINANCE / WAREHOUSE_MGR / zdg 的 APP 行, SUPER_ADMIN 只有 PC 行
--         → 超管在手机工作台看不见「新增商品」(与 sql/150 校准入口同一现象).
--   同时 v1.1.166 把 App 工作台入口从 base:product:list(301) 改挂 base:product:add(6034):
--         此前 PRODUCTION_MGR / PURCHASE_MGR / SALES_MGR 只有查看权也看得见按钮, 点进去 POST 必 403.
--   本脚本: 只给 SUPER_ADMIN 补 APP 行, 不给其他角色扩权 (要不要给主管们建单品权, 由业务单独拍板).
--   幂等 (PK: role_id+menu_id+client_type), 按 perms+path 定位不写死 id.
--   ⚠ 只在 .150 主库跑; .32 靠复制自动获得. 若在 canary 试跑过, 灌主库前先删掉 canary 那行防 1062.
-- =====================================================================
USE `industrial_erp`;

SELECT '--- 目标 menu (应为 1 行) ---' AS step;
SELECT id, menu_name, menu_type, perms, path, status, deleted
FROM sys_menu
WHERE perms = 'base:product:add' AND path = '/base/product/add';

SET @has_ct := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_role_menu' AND COLUMN_NAME = 'client_type'
);

SET @sql_grant := IF(@has_ct > 0,
  'INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
   SELECT r.id, m.id, ''APP''
   FROM sys_role r
   JOIN sys_menu m ON m.perms = ''base:product:add'' AND m.path = ''/base/product/add''
        AND m.status = 1 AND m.deleted = 0
   WHERE r.role_code = ''SUPER_ADMIN''',
  'INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
   SELECT r.id, m.id
   FROM sys_role r
   JOIN sys_menu m ON m.perms = ''base:product:add'' AND m.path = ''/base/product/add''
        AND m.status = 1 AND m.deleted = 0
   WHERE r.role_code = ''SUPER_ADMIN''');
PREPARE s FROM @sql_grant; EXECUTE s; DEALLOCATE PREPARE s;

-- 校验: 哪些角色在手机端能看见并真正调用「新增商品」
SELECT r.role_code, rm.client_type, m.perms
FROM sys_role_menu rm
JOIN sys_role r ON r.id = rm.role_id
JOIN sys_menu m ON m.id = rm.menu_id
WHERE m.perms = 'base:product:add'
ORDER BY rm.client_type, r.role_code;
