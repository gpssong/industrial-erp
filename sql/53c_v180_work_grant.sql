-- =====================================================================
-- 五十三-c、v1.1.80 — 工作流 (work:*) 全部 perm 补齐 6 内置角色 (PC-only)
-- =====================================================================
-- 背景: /api/workflow/safety/page 403 (2026-10-09). gpssong (SUPER_ADMIN)
--   进 系统管理 → 工作流/设备安全 → 安全检查 tab 立刻 403.
-- 根因 (sql/50 遗留):
--   WfSafetyRecordController /page + /{id} 都
--     @SaCheckPermission(value={"work:safety:list"}, orRole="admin")
--   orRole 兜底要求 **角色码 == 'admin'**, 而 6 新内置角色码是
--     SUPER_ADMIN / PURCHASE_MGR / SALES_MGR / WAREHOUSE_MGR /
--     PRODUCTION_MGR / FINANCE (SUPER_ADMIN ≠ 'admin'), 兜底失效.
--   只能靠 sys_role_menu 里真有 work:safety:list 授权行. 但 sql/50 的
--   授权段用 `GROUP_CONCAT(id)` 拼 @grant_ids 再 `IN (...)`, 当节点多
--   时 group_concat_max_len (默认 1024B) 截断, 4 个 C 型 list 节点的
--   SUPER_ADMIN 授权行漏写 — 实测 home 库 2026-10-09:
--     work:tenant:list   (C) 5 角色 (缺 SUPER_ADMIN)
--     work:maintain:list (C) 5 角色 (缺 SUPER_ADMIN; M 型目录有 7 角色含 SUPER_ADMIN)
--     work:safety:list   (C) 5 角色 (缺 SUPER_ADMIN)
--     work:emergency:list (C) 5 角色 (缺 SUPER_ADMIN)
--   其余 B 型 add/edit/delete/check/uncheck 节点 6 角色全 (M 型目录
--   work:maintain:list 也全). 唯独 4 个 C 型 list 漏 SUPER_ADMIN.
--
-- 修复: 一次性把 全部 work:* 节点 × 6 内置角色 × PC 补齐 (INSERT IGNORE
--   + 唯一键幂等, 已授的 B/M 型不重复写). 比 sql/50 的 GROUP_CONCAT
--   截断写法稳 — 直接 `JOIN sys_menu WHERE perms LIKE 'work:%'` 逐节点
--   授权, 无 GROUP_CONCAT 长度风险. 沿用 sql/41 (v1.1.58) 决策: 工作流
--   模块 PC-only (App 不该见, 用户 v1.1.79 拍板「仅 PC 端」), 因此只授
--   PC, **不授 APP**.
--
-- 兼容: 老库若 sys_role_menu 无 client_type 列 (sql/41 之前), 用 2 列
--   INSERT 兜底, 与 sql/49/53/53b 同款 IF/PREPARE/EXECUTE 模式.
--
-- 用法 (双站 home + 飞牛各跑一次, 必须 utf8mb4):
--   mysql -uroot -perp_root_pwd --default-character-set=utf8mb4 \
--     industrial_erp < sql/53c_v180_work_grant.sql
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

-- 2) 给 6 内置角色 × PC 补齐全部 work:* 节点 (tenant/maintain/safety/
--    emergency 各自 list/add/edit/delete/check/uncheck + M 型目录)
--    用 perms LIKE 'work:%' 而非硬编码 21 个 perms, 未来扩节点也自动命中.
SET @stmt := IF(
  @has_ct > 0,
  'INSERT IGNORE INTO sys_role_menu(role_id, menu_id, client_type) '
   'SELECT r.id, m.id, "PC" FROM sys_role r '
   'JOIN sys_menu m ON m.deleted = 0 AND m.perms LIKE "work:%" '
   'WHERE r.role_code IN ("SUPER_ADMIN","PURCHASE_MGR","SALES_MGR","WAREHOUSE_MGR","PRODUCTION_MGR","FINANCE") '
   '  AND r.deleted = 0',
  'INSERT IGNORE INTO sys_role_menu(role_id, menu_id) '
   'SELECT r.id, m.id FROM sys_role r '
   'JOIN sys_menu m ON m.deleted = 0 AND m.perms LIKE "work:%" '
   'WHERE r.role_code IN ("SUPER_ADMIN","PURCHASE_MGR","SALES_MGR","WAREHOUSE_MGR","PRODUCTION_MGR","FINANCE") '
   '  AND r.deleted = 0'
);
PREPARE ps FROM @stmt;
EXECUTE ps;
DEALLOCATE PREPARE ps;

-- 3) 校验 — 升级后跑一下确认 4 个 C 型 list 节点都有 SUPER_ADMIN:
-- SELECT m.perms, m.menu_type, GROUP_CONCAT(DISTINCT r.role_code ORDER BY r.role_code) AS roles
-- FROM sys_menu m
-- JOIN sys_role_menu rm ON rm.menu_id = m.id AND rm.client_type = "PC"
-- JOIN sys_role r ON r.id = rm.role_id AND r.deleted = 0
-- WHERE m.perms LIKE "work:%" AND m.deleted = 0
--   AND m.perms LIKE "%:list"
-- GROUP BY m.perms, m.menu_type
-- ORDER BY m.perms, m.menu_type;
-- 预期: work:tenant:list / work:maintain:list(C) / work:safety:list /
--       work:emergency:list 各含 SUPER_ADMIN + 5 业务角色 = 6 角色

-- =====================================================================
-- 回滚 (慎用 — 会让 6 内置角色工作流模块全部 403):
-- DELETE FROM sys_role_menu
-- WHERE menu_id IN (SELECT id FROM sys_menu WHERE perms LIKE "work:%" AND deleted = 0)
--   AND role_id IN (SELECT id FROM sys_role
--                   WHERE role_code IN ("SUPER_ADMIN","PURCHASE_MGR","SALES_MGR","WAREHOUSE_MGR","PRODUCTION_MGR","FINANCE")
--                     AND deleted = 0);
-- =====================================================================
