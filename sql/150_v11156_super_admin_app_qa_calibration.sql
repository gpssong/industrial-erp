-- =====================================================================
-- v1.1.156 — SUPER_ADMIN 的 App 端「检验设备校准」授权
--   背景: App 端 /me 带 X-Client-Type: APP, 返回的 perm 只来自 client_type IN ('BOTH','APP') 的授权行.
--         SUPER_ADMIN 这 5 个 perm 历史上只有 PC 行 (sql/105 建 F 型载体时只授了 PC),
--         sql/149 又只补「PC 有 + APP 已在用设备模块」的角色 → 超管被漏掉,
--         结果超管登录 App 工作台看不到「检验设备校准」入口.
--   本脚本: 给 SUPER_ADMIN 补 APP 行. 幂等 (PK: role_id+menu_id+client_type).
--   只灌主库 (.150), .32 靠复制自动获得. 灌前确认 SHOW SLAVE STATUS 正常.
-- =====================================================================
USE `industrial_erp`;

SET @has_ct := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_role_menu' AND COLUMN_NAME = 'client_type'
);

SET @perm_list := '''qa:calibration-equipment:list'',''qa:calibration-equipment:add'',
  ''qa:calibration-record:list'',''qa:calibration-record:add'',''qa:calibration:check''';

SET @sql_grant := IF(@has_ct > 0,
  CONCAT('
  INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
  SELECT r.id, m.id, ''APP''
  FROM sys_role r
  JOIN sys_menu m ON m.perms IN (', @perm_list, ') AND m.status = 1 AND m.deleted = 0
  WHERE r.role_code = ''SUPER_ADMIN'''),
  CONCAT('
  INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
  SELECT r.id, m.id
  FROM sys_role r
  JOIN sys_menu m ON m.perms IN (', @perm_list, ') AND m.status = 1 AND m.deleted = 0
  WHERE r.role_code = ''SUPER_ADMIN'''));
PREPARE s FROM @sql_grant; EXECUTE s; DEALLOCATE PREPARE s;

-- 校验
SELECT r.role_code, rm.client_type, m.perms
FROM sys_role_menu rm
JOIN sys_role r ON r.id = rm.role_id
JOIN sys_menu m ON m.id = rm.menu_id
WHERE r.role_code = 'SUPER_ADMIN' AND m.perms LIKE 'qa:calibration%'
ORDER BY m.perms, rm.client_type;
