-- v1.1.130: App 端三对共用权限拆分 — 新 sys_menu 行 + 授权迁移
-- 背景: App 权限树白名单三对入口共用同一 perms, 无法分开授权:
--   经营简报 ↔ XK 单据打印  (report:view)
--   扫码报工 ↔ XK 批次溯源  (production:report:list)
--   生产排程 ↔ 设备 OEE     (production:schedule:list)
-- 拆法: 后者各自独立新 perm, App 端页面/入口按新 perm 显隐;
--       后端对应端点用 SaMode.OR 同时认新旧 perm (旧授权不失效);
--       本脚本把旧权限行的既有授权复制到新行, 保证 App 端入口显示不丢。
-- 幂等: INSERT IGNORE / NOT EXISTS, 可重复执行。
-- 执行库: .150 主库 (industrial_erp), 由实时同步带到 .32 与阿里云只读副本。

-- ---------------------------------------------------------------------------
-- 1. 新菜单行 (F 类型 perm 载体, status=1, client BOTH)
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO `sys_menu` (`id`, `parent_id`, `menu_name`, `menu_type`, `path`, `perms`, `status`, `client_type`)
VALUES
  (10045, 10004, '单据打印',       'F', '', 'report:print',             1, 'BOTH'),
  (10046, 10034, '批次溯源',       'F', '', 'production:report:trace',  1, 'BOTH'),
  (10047, 10032, '设备OEE',        'F', '', 'production:schedule:oee',  1, 'BOTH');

-- ---------------------------------------------------------------------------
-- 2. 授权迁移: 旧权限行 (所有 client_type) → 新行, 保持现有用户入口不丢
--    经营简报保留 report:view 不动; 另两项同理只迁拆出的那一侧。
-- ---------------------------------------------------------------------------
-- 2.1 单据打印 ← 951 (报表查看 B) + 10039 (审计归档 M, perms 也是 report:view)
INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`, `client_type`)
SELECT `role_id`, 10045, `client_type` FROM `sys_role_menu` WHERE `menu_id` IN (951, 10039);

-- 2.2 批次溯源 ← 10034 (库存与追溯 M) + 2090345792472715377 (MES报工 F)
INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`, `client_type`)
SELECT `role_id`, 10046, `client_type` FROM `sys_role_menu` WHERE `menu_id` IN (10034, 2090345792472715377);

-- 2.3 设备 OEE ← 10032 (生产排程 M) + 2090345792472715413 (生产排程查看 F)
INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`, `client_type`)
SELECT `role_id`, 10047, `client_type` FROM `sys_role_menu` WHERE `menu_id` IN (10032, 2090345792472715413);

-- ---------------------------------------------------------------------------
-- 3. 结果核对
-- ---------------------------------------------------------------------------
SELECT m.id, m.menu_name, m.perms,
       (SELECT COUNT(*) FROM sys_role_menu rm WHERE rm.menu_id = m.id) AS grant_rows
FROM sys_menu m WHERE m.id IN (10045, 10046, 10047);
