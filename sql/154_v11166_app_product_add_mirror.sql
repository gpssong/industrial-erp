-- =====================================================================
-- v1.1.166 (第二批) — 「PC 端已能新增商品」的角色，补 App 端 base:product:add 授权
--   用户拍板原话: 「授权」= 给 PC 端已有「新增商品」权的角色补手机端权限。
--   口径: **只做镜像，不创造新权限** —— 凡 sys_role_menu 里已持有 6034 的 'PC' 或 'BOTH' 授权行的角色，
--         给它补一条 'APP' 行；本来在 PC 也建不了商品的角色，手机端依然不给。
--   背景: App 端 /me 带 X-Client-Type: APP，perm 只来自 client_type IN ('BOTH','APP') 的行，
--         且**没有 admin 通配**（铁律 4：@SaCheckPermission 不过滤端，登录下发按端过滤，
--         两边口径不一致就会出现「接口调得动、按钮看不见」或反过来）。
--   与 sql/153 的关系: 153 只补 SUPER_ADMIN 一行；本脚本按镜像补全，两者都是 INSERT IGNORE，
--         重复跑无害（PK = role_id + menu_id + client_type）。
--   幂等、按 perms+path 定位不写死 id、information_schema 探测 client_type 列。
--   ⚠ 只在 .150 主库跑；.32 靠复制自动获得。
--     若在 canary 端到端测试时先本地灌过，**灌主库前必须先把 canary 这些行删掉**，
--     否则主库的 INSERT 复制回备库撞 1062 → Slave_SQL_Running: No（sql/149/150 两次踩过）。
-- =====================================================================
USE `industrial_erp`;

SELECT '--- 目标 menu (应为 1 行) ---' AS step;
SELECT id, menu_name, menu_type, perms, path, status, deleted
FROM sys_menu
WHERE perms = 'base:product:add' AND path = '/base/product/add';

SELECT '--- 镜像前: 各角色现有授权端 ---' AS step;
SELECT r.role_code, GROUP_CONCAT(rm.client_type ORDER BY rm.client_type) AS has_client_types
FROM sys_role_menu rm
JOIN sys_role r ON r.id = rm.role_id
JOIN sys_menu m ON m.id = rm.menu_id
WHERE m.perms = 'base:product:add' AND m.path = '/base/product/add'
GROUP BY r.role_code
ORDER BY r.role_code;

SET @has_ct := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_role_menu' AND COLUMN_NAME = 'client_type'
);

-- 有 client_type 列: 镜像 PC/BOTH → APP
-- 没这列 (老库): sys_role_menu 不分端，PC 行即等于全端可行 → 空操作，不报错
SET @sql_grant := IF(@has_ct > 0,
  'INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
   SELECT DISTINCT rm.role_id, rm.menu_id, ''APP''
   FROM sys_role_menu rm
   JOIN sys_menu m ON m.id = rm.menu_id
        AND m.perms = ''base:product:add'' AND m.path = ''/base/product/add''
        AND m.status = 1 AND m.deleted = 0
   WHERE rm.client_type IN (''PC'', ''BOTH'')',
  'SELECT ''client_type 列不存在, 本脚本无需执行'' AS skip');
PREPARE s FROM @sql_grant; EXECUTE s; DEALLOCATE PREPARE s;

SELECT '--- 镜像后: 手机端能新增商品的角色 (client_type 含 APP/BOTH) ---' AS step;
SELECT r.role_code, GROUP_CONCAT(rm.client_type ORDER BY rm.client_type) AS has_client_types
FROM sys_role_menu rm
JOIN sys_role r ON r.id = rm.role_id
JOIN sys_menu m ON m.id = rm.menu_id
WHERE m.perms = 'base:product:add' AND m.path = '/base/product/add'
GROUP BY r.role_code
ORDER BY r.role_code;
