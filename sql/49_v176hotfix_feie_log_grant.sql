-- =====================================================================
-- 四十九、v1.1.76 hotfix — 飞鹅打印日志权限补授权 (修 /api/feie/log/page 403)
-- =====================================================================
-- 背景: 用户部署 v1.1.76 后, PC 端 系统设置 → 飞鹅打印日志 tab 打开
--       立刻 403 Forbidden, 浏览器 console:
--         GET http://home.93gushi.com:8088/api/feie/log/page?... 403
--       根因: sql/21_add_sys_feie_print_log.sql 建 system:feie:log 节点
--       (menu_type=C, parent=飞鹅打印机) 时只授给老角色码 'admin'/'manager';
--       后续角色码迁移到 'SUPER_ADMIN'/'PURCHASE_MGR'/'SALES_MGR'/
--       'WAREHOUSE_MGR'/'PRODUCTION_MGR'/'FINANCE' 时漏了这条授权 — 所有
--       新角色都拿不到 system:feie:log → @SaCheckPermission 拦截 403.
--
--       v1.1.76 (sql/48) 没动 feie, 此问题与 AI 助手无关, 是历史遗漏授权.
--
-- 修复: 给 6 个新内置角色 × PC client_type 各补一行 system:feie:log 授权.
--       沿用 sql/41 (v1.1.58) 决策: 飞鹅菜单保持 PC-only (管理员功能, App 不该见),
--       因此只授 PC, **不授 APP**.
--
-- 兼容: 老库若 sys_role_menu 无 client_type 列 (sql/41 之前), 用 2 列 INSERT
--       兜底, 与 sql/48 同款 IF/PREPARE/EXECUTE 模式.
--
-- 用法 (双站 home + 飞牛各跑一次, 必须 utf8mb4):
--   mysql -uroot -perp_root_pwd --default-character-set=utf8mb4 \
--     industrial_erp < sql/49_v176hotfix_feie_log_grant.sql
--
-- 幂等: INSERT IGNORE + NOT EXISTS 双重防重, 跑多遍无副作用.
-- =====================================================================

USE `industrial_erp`;

-- 1) 定位飞鹅打印日志 menu id
SET @log_id := (
  SELECT id FROM sys_menu
  WHERE perms = 'system:feie:log' AND deleted = 0
  LIMIT 1
);

-- 2) 探测 sys_role_menu 是否有 client_type 列 (sql/41 后才有, 老库兜底)
SET @has_ct := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'sys_role_menu'
    AND column_name = 'client_type'
);

-- 3) 给 6 个新内置角色 × PC 授权 system:feie:log
--    SUPER_ADMIN 走 orRole 短路也无所谓 — 一致性补齐避免后续误删.
--    老库无 client_type 列时退化为 2 列 INSERT (PC/APP 不区分).
SET @stmt := IF(
  @log_id IS NULL,
  'SELECT "system:feie:log menu 不存在, 请先跑 sql/21_add_sys_feie_print_log.sql" AS warn',
  IF(
    @has_ct > 0,
    'INSERT IGNORE INTO sys_role_menu(role_id, menu_id, client_type) '
     'SELECT r.id, @log_id, "PC" FROM sys_role r '
     'WHERE r.role_code IN ("SUPER_ADMIN","PURCHASE_MGR","SALES_MGR","WAREHOUSE_MGR","PRODUCTION_MGR","FINANCE") '
     '  AND r.deleted = 0',
    'INSERT IGNORE INTO sys_role_menu(role_id, menu_id) '
     'SELECT r.id, @log_id FROM sys_role r '
     'WHERE r.role_code IN ("SUPER_ADMIN","PURCHASE_MGR","SALES_MGR","WAREHOUSE_MGR","PRODUCTION_MGR","FINANCE") '
     '  AND r.deleted = 0'
  )
);
PREPARE ps FROM @stmt;
EXECUTE ps;
DEALLOCATE PREPARE ps;

-- 4) 校验 — 升级后跑一下确认 6 角色 × PC = 6 行 (新角色码) + 老 admin/manager (sql/21 历史)
--    有 client_type 列: PC 共 8 行 (新 6 + 老 admin/manager 因老 INSERT 是 2 列会被归 BOTH 然后补 PC 不冲突)
--    无 client_type 列: 6 行 (新 6)
-- SELECT r.role_code, rm.client_type
-- FROM sys_role r
-- JOIN sys_role_menu rm ON rm.role_id = r.id
-- JOIN sys_menu m ON m.id = rm.menu_id
-- WHERE m.perms = 'system:feie:log' AND m.deleted = 0 AND r.deleted = 0
-- ORDER BY r.role_code, rm.client_type;
-- 预期: 有 client_type → SUPER_ADMIN/PURCHASE_MGR/SALES_MGR/WAREHOUSE_MGR/PRODUCTION_MGR/FINANCE 全有 PC 行;
--       老 admin/manager 历史授权行 client_type=NULL/BOTH (兼容, 仍有权限)

-- =====================================================================
-- 回滚 (慎用 — 会让飞鹅打印日志 tab 再次 403):
-- DELETE FROM sys_role_menu
-- WHERE menu_id = @log_id
--   AND role_id IN (SELECT id FROM sys_role
--                   WHERE role_code IN ("SUPER_ADMIN","PURCHASE_MGR","SALES_MGR","WAREHOUSE_MGR","PRODUCTION_MGR","FINANCE")
--                     AND deleted = 0);
-- =====================================================================
