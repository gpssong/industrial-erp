-- =====================================================================
-- 五十三-b、v1.1.80 — 飞鹅打印全部 perm 一次性补齐 6 内置角色 (PC-only)
-- =====================================================================
-- 背景: /api/feie/log/page 403 (2026-10-09). 根因与 sql/49/53 同款:
--   飞鹅 8 个 perm 节点 (system:feie:list/add/edit/delete/test/log/template)
--   在 sql/20-22 seed 时只授给老角色码 'admin'/'manager', 角色码迁移到 6 新
--   内置角色后, 漏授 (log 被 sql/49 补了一半只 4 角色, template 被 sql/53
--   补全 6 角色, 其余 list/add/edit/delete/test 5 节点完全 0 授权).
--   FeiePrintController 各 /feie/{printers,log,template} 端点都
--   @SaCheckPermission("system:feie:*") 且 **无 orRole=admin 兜底** —
--   拿不到 perm 的账号一律 403 (SUPER_ADMIN 走 orRole 短路的 print 端点
--   除外, 那 2 个有 orRole).
--
-- 实测 home 现状 (2026-10-09, 6 角色 SUPER_ADMIN/PURCHASE_MGR/SALES_MGR/
--   WAREHOUSE_MGR/PRODUCTION_MGR/FINANCE):
--     952/953 system:feie:list      -> 无授权
--     954 system:feie:add           -> 无授权
--     955 system:feie:edit          -> 无授权
--     956 system:feie:delete        -> 无授权
--     957 system:feie:test          -> 无授权
--     958 system:feie:log          -> 4 角色 (FINANCE/PRODUCTION_MGR/PURCHASE_MGR/SALES_MGR), 缺 SUPER_ADMIN/WAREHOUSE_MGR
--     960 system:feie:template      -> 6 角色全 (sql/53 已补)
--   登录账号若角色是 SUPER_ADMIN 或 WAREHOUSE_MGR, 进飞鹅日志 tab 就 403.
--
-- 修复: 给 6 内置业务角色 × PC client_type 补齐全部 8 节点 (list/add/edit/
--   delete/test/log/template + 它们的 C/M 父节点) 授权. 沿用 sql/41 (v1.1.58)
--   决策: 飞鹅菜单 PC-only (管理员功能, App 不该见), 因此只授 PC, **不授 APP**.
--   SUPER_ADMIN 一致性补齐 (即使 orRole 短路也无副作用).
--
-- 兼容: 老库若 sys_role_menu 无 client_type 列 (sql/41 之前), 用 2 列 INSERT
--   兜底, 与 sql/49/53 同款 IF/PREPARE/EXECUTE 模式.
--
-- 用法 (双站 home + 飞牛各跑一次, 必须 utf8mb4):
--   mysql -uroot -perp_root_pwd --default-character-set=utf8mb4 \
--     industrial_erp < sql/53b_v180_feie_all_grant.sql
--
-- 幂等: INSERT IGNORE + role_id/menu_id/client_type 唯一键防重, 跑多遍无副作用.
-- =====================================================================

USE `industrial_erp`;

-- 1) 探测 sys_role_menu 是否有 client_type 列 (sql/41 后才有, 老库兜底)
SET @has_ct := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'sys_role_menu'
    AND column_name = 'client_type'
);

-- 2) 给 6 内置角色 × PC 补齐全部 system:feie:* 节点
--    8 个 perms: list / add / edit / delete / test / log / template
--    (用 perms IN (...) 而非 id, 跨双站雪花/自增 id 都能命中, 幂等靠唯一键)
SET @stmt := IF(
  @has_ct > 0,
  'INSERT IGNORE INTO sys_role_menu(role_id, menu_id, client_type) '
   'SELECT r.id, m.id, "PC" FROM sys_role r '
   'JOIN sys_menu m ON m.deleted = 0 '
   '  AND m.perms IN ("system:feie:list","system:feie:add","system:feie:edit","system:feie:delete","system:feie:test","system:feie:log","system:feie:template") '
   'WHERE r.role_code IN ("SUPER_ADMIN","PURCHASE_MGR","SALES_MGR","WAREHOUSE_MGR","PRODUCTION_MGR","FINANCE") '
   '  AND r.deleted = 0',
  'INSERT IGNORE INTO sys_role_menu(role_id, menu_id) '
   'SELECT r.id, m.id FROM sys_role r '
   'JOIN sys_menu m ON m.deleted = 0 '
   '  AND m.perms IN ("system:feie:list","system:feie:add","system:feie:edit","system:feie:delete","system:feie:test","system:feie:log","system:feie:template") '
   'WHERE r.role_code IN ("SUPER_ADMIN","PURCHASE_MGR","SALES_MGR","WAREHOUSE_MGR","PRODUCTION_MGR","FINANCE") '
   '  AND r.deleted = 0'
);
PREPARE ps FROM @stmt;
EXECUTE ps;
DEALLOCATE PREPARE ps;

-- 3) 校验 — 升级后跑一下确认 7 perms × 6 角色 × PC 全有 (老 admin/manager 历史行保留, 不影响)
-- SELECT m.perms, r.role_code, rm.client_type
-- FROM sys_menu m
-- JOIN sys_role_menu rm ON rm.menu_id = m.id
-- JOIN sys_role r ON r.id = rm.role_id AND r.deleted = 0
-- WHERE m.perms LIKE "system:feie:%" AND m.deleted = 0
--   AND r.role_code IN ("SUPER_ADMIN","PURCHASE_MGR","SALES_MGR","WAREHOUSE_MGR","PRODUCTION_MGR","FINANCE")
--   AND rm.client_type = "PC"
-- ORDER BY m.perms, r.role_code;
-- 预期: 7 perms × 6 角色 = 42 行 (含 list 的 M+C 两个节点则 48 行; M/C 同 perm 时 list 出现 2 节点各 6 角色)

-- =====================================================================
-- 回滚 (慎用 — 会让飞鹅打印机/日志/模板 全部 403):
-- DELETE FROM sys_role_menu
-- WHERE menu_id IN (SELECT id FROM sys_menu WHERE perms LIKE "system:feie:%" AND deleted = 0)
--   AND role_id IN (SELECT id FROM sys_role
--                   WHERE role_code IN ("SUPER_ADMIN","PURCHASE_MGR","SALES_MGR","WAREHOUSE_MGR","PRODUCTION_MGR","FINANCE")
--                     AND deleted = 0);
-- =====================================================================
