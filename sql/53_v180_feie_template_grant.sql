-- =====================================================================
-- 五十三、v1.1.80 — 飞鹅打印模板权限补授权 (修 /api/feie/templates/page 403)
-- =====================================================================
-- 背景: 用户浏览器 console 报:
--   GET https://home.93gushi.com:8089/api/feie/templates/page?... 403
-- 根因 (与 sql/49 feie:log 同款, 但 perm 不同):
--   sql/21 建 system:feie:template 节点 (menu_type=M, id=960) 时只授给老角色码
--   'admin'/'manager'; 后续角色码迁移到 6 个新内置角色时漏了这条授权.
--   FeiePrintController 的 6 个 /feie/template/* 端点 (L167/178/185/213/243/252)
--   都 @SaCheckPermission("system:feie:template") 且 **无 orRole=admin 兜底** —
--   导致没有 system:feie:template perm 的账号一律 403.
--
--   实测差异:
--     飞牛 failover DB: SUPER_ADMIN 有 1 行 PC 授权 (id=960), 但 5 个业务角色全无
--     home 主站 DB: 完全无授权行 (6 角色 + SUPER_ADMIN 都缺)
--   浏览器登录的账号 (gpssong 等, 非 SUPER_ADMIN 角色) 拿不到 perm → 403.
--
-- 修复: 给 6 个新内置角色 × PC client_type 各补一行 system:feie:template (id=960) 授权.
--       沿用 sql/41 (v1.1.58) + sql/49 决策: 飞鹅菜单保持 PC-only (管理员功能, App 不该见),
--       因此只授 PC, **不授 APP**. SUPER_ADMIN 走 orRole 短路也无所谓 — 一致性补齐.
--
-- 兼容: 老库若 sys_role_menu 无 client_type 列 (sql/41 之前), 用 2 列 INSERT
--       兜底, 与 sql/49 同款 IF/PREPARE/EXECUTE 模式.
--
-- 用法 (双站 home + 飞牛各跑一次, 必须 utf8mb4):
--   mysql -uroot -perp_root_pwd --default-character-set=utf8mb4 \
--     industrial_erp < sql/53_v180_feie_template_grant.sql
--
-- 幂等: INSERT IGNORE + role_id/menu_id 唯一键防重, 跑多遍无副作用.
-- =====================================================================

USE `industrial_erp`;

-- 1) 定位飞鹅打印模板 menu id
SET @tpl_id := (
  SELECT id FROM sys_menu
  WHERE perms = 'system:feie:template' AND deleted = 0
  LIMIT 1
);

-- 2) 探测 sys_role_menu 是否有 client_type 列 (sql/41 后才有, 老库兜底)
SET @has_ct := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'sys_role_menu'
    AND column_name = 'client_type'
);

-- 3) 给 6 个新内置角色 × PC 授权 system:feie:template
SET @stmt := IF(
  @tpl_id IS NULL,
  'SELECT "system:feie:template menu (id=960) 不存在, 请先检查 sql/21" AS warn',
  IF(
    @has_ct > 0,
    'INSERT IGNORE INTO sys_role_menu(role_id, menu_id, client_type) '
     'SELECT r.id, @tpl_id, "PC" FROM sys_role r '
     'WHERE r.role_code IN ("SUPER_ADMIN","PURCHASE_MGR","SALES_MGR","WAREHOUSE_MGR","PRODUCTION_MGR","FINANCE") '
     '  AND r.deleted = 0',
    'INSERT IGNORE INTO sys_role_menu(role_id, menu_id) '
     'SELECT r.id, @tpl_id FROM sys_role r '
     'WHERE r.role_code IN ("SUPER_ADMIN","PURCHASE_MGR","SALES_MGR","WAREHOUSE_MGR","PRODUCTION_MGR","FINANCE") '
     '  AND r.deleted = 0'
  )
);
PREPARE ps FROM @stmt;
EXECUTE ps;
DEALLOCATE PREPARE ps;

-- 4) 校验 — 升级后跑一下确认 6 角色 × PC 全有 (新角色码)
-- SELECT r.role_code, IFNULL(rm.client_type,'NULL') AS ct
-- FROM sys_role r
-- LEFT JOIN sys_role_menu rm ON rm.role_id = r.id
-- JOIN sys_menu m ON m.id = rm.menu_id
-- WHERE m.perms = 'system:feie:template' AND m.deleted = 0 AND r.deleted = 0
-- ORDER BY r.role_code;
-- 预期: 6 行 (SUPER_ADMIN/PURCHASE_MGR/SALES_MGR/WAREHOUSE_MGR/PRODUCTION_MGR/FINANCE 全 PC)

-- =====================================================================
-- 回滚 (慎用 — 会让飞鹅打印模板 tab 再次 403):
-- DELETE FROM sys_role_menu
-- WHERE menu_id = (SELECT id FROM sys_menu WHERE perms = 'system:feie:template' AND deleted=0)
--   AND role_id IN (SELECT id FROM sys_role
--                   WHERE role_code IN ("SUPER_ADMIN","PURCHASE_MGR","SALES_MGR","WAREHOUSE_MGR","PRODUCTION_MGR","FINANCE")
--                     AND deleted = 0);
-- =====================================================================
