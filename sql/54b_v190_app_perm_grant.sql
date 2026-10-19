-- =====================================================================
-- 五十四-b, v1.1.90 — APP 端新增 perm 补全 (grant-only, 不动 sys_menu)
--   模型: sql/53b_v180_feie_all_grant.sql (PC + APP 同源授权)
--
-- 上下文:
--   sql/54 已经给 6 内置角色 × 21 perm × client_type='PC' 授权
--   本文件给 APP 端 (production / sales / inventory 模块) 需要的角色补 APP 行
--   设计原则 R9: 后端按 X-Client-Type 分流 APP/PC, 本次同源授权, 保持一致
--   Quality 业务流: 工人在 App 扫码发起 FAI/PAT, 仓管在 App 查 IQC
--   Process 业务流: 工人在 App 查 SOP (read-only, 不需要 SOP 编辑 perm)
--   Equipment 业务流: 工人 App 报修, 仓管/生产 App 查设备 + 维保
-- =====================================================================

USE `industrial_erp`;

SET @has_ct := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_role_menu' AND COLUMN_NAME = 'client_type'
);

-- Quality: 给 PRODUCTION_MGR + WAREHOUSE_MGR 补 APP (qc 扫码/查都好用)
-- SUPER_ADMIN APP 默认全开放 (旧 sql 用 admin 或Role 短路, 这里只补 6 内置非 admin 角色)
SET @sql_app_quality := IF(@has_ct > 0,
  'INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
   SELECT r.id, m.id, ''APP''
   FROM sys_role r CROSS JOIN sys_menu m
   WHERE m.perms IN (
       ''quality:inspection:list'',
       ''quality:inspection:add'',
       ''quality:inspection:list:iqc'',
       ''quality:inspection:check:iqc'',
       ''quality:inspection:list:fai'',
       ''quality:inspection:add'',   -- FAI 提交 = quality:inspection:add
       ''quality:inspection:check:fai'',
       ''quality:inspection:list:pat'',
       ''quality:inspection:list:oqc''
     )
     AND r.role_code IN (''WAREHOUSE_MGR'', ''PRODUCTION_MGR'')
     AND r.deleted = 0',
  'SELECT 1');
PREPARE s FROM @sql_app_quality; EXECUTE s; DEALLOCATE PREPARE s;

-- Process: 给 PRODUCTION_MGR 补 APP (pdm:sop:list read-only, 给工人 App 查)
SET @sql_app_process := IF(@has_ct > 0,
  'INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
   SELECT r.id, m.id, ''APP''
   FROM sys_role r CROSS JOIN sys_menu m
   WHERE m.perms IN (''pdm:sop:list'', ''pdm:param:list'')
     AND r.role_code IN (''PRODUCTION_MGR'')
     AND r.deleted = 0',
  'SELECT 1');
PREPARE s FROM @sql_app_process; EXECUTE s; DEALLOCATE PREPARE s;

-- Equipment: 给 WAREHOUSE_MGR + PRODUCTION_MGR 补 APP
SET @sql_app_equipment := IF(@has_ct > 0,
  'INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
   SELECT r.id, m.id, ''APP''
   FROM sys_role r CROSS JOIN sys_menu m
   WHERE m.perms IN (''equipment:master:list'', ''equipment:maintain:list'', ''equipment:maintain:add'')
     AND r.role_code IN (''WAREHOUSE_MGR'', ''PRODUCTION_MGR'')
     AND r.deleted = 0',
  'SELECT 1');
PREPARE s FROM @sql_app_equipment; EXECUTE s; DEALLOCATE PREPARE s;

-- =====================================================================
-- 校验
-- =====================================================================
SELECT 'phase2 APP perms granted' AS check_item, COUNT(*) AS cnt
FROM sys_role_menu rm
JOIN sys_menu m ON m.id = rm.menu_id
JOIN sys_role r ON r.id = rm.role_id
WHERE rm.client_type = 'APP'
  AND (m.perms LIKE 'quality:inspection%' OR m.perms LIKE 'pdm:%' OR m.perms LIKE 'equipment:%')
  AND r.deleted = 0;

SELECT 'sample granted rows (warehouse_mgr)' AS info, m.perms AS perm
FROM sys_role_menu rm
JOIN sys_menu m ON m.id = rm.menu_id
JOIN sys_role r ON r.id = rm.role_id
WHERE r.role_code = 'WAREHOUSE_MGR'
  AND rm.client_type = 'APP'
  AND (m.perms LIKE 'quality:inspection%' OR m.perms LIKE 'equipment:%')
ORDER BY m.perms LIMIT 10;
