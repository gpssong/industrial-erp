-- v1.1.102 home 热修: 补 qms:iqc:* 3 perm 节点 + 6 角色 PC 授权 (sql/118 撞固定雪花 id 在 home 静默失败)
-- 现象: home.93gushi.com IQC 进货查验相关端点 403
-- 根因: sql/118_v1101_iqc_menu.sql 用固定雪花 id 2090345792472715301/302/303 做 INSERT IGNORE 建 perm 节点,
--       但 home 上这三个 id 已被 outsource:issue:add / outsource:issue:check / outsource:pi:add 占用,
--       INSERT IGNORE 撞主键静默吞掉 → qms:iqc:* 节点在 home 一条都没有 → 门禁 403.
-- 修法: 不用固定 id, 按 perms 动态 INSERT...SELECT (新记录走雪花/自增, 由 home 自行分配 id),
--       授权段 WHERE perms IN (...) 反查 menu_id, 不写死 id. 幂等: NOT EXISTS 守门.
-- IQC 表 (qms_iqc_item / qms_iqc_attachment) home 已有, 本 seed 只补 perm + 授权.
USE industrial_erp;

-- 1. 3 perm 节点 (id 不指定, 让 home 自分配; NOT EXISTS 守门, 重复灌安全)
INSERT INTO sys_menu (menu_name, parent_id, menu_type, path, component, perms, icon, sort_no, is_visible, status, create_by, create_time, deleted)
SELECT '进货查验台账', 0, 'F', '', NULL, 'qms:iqc:list',   NULL, 8015, 0, 1, 1, NOW(), 0
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='qms:iqc:list' AND deleted=0);

INSERT INTO sys_menu (menu_name, parent_id, menu_type, path, component, perms, icon, sort_no, is_visible, status, create_by, create_time, deleted)
SELECT '进货查验详情', 0, 'F', '', NULL, 'qms:iqc:detail', NULL, 8016, 0, 1, 1, NOW(), 0
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='qms:iqc:detail' AND deleted=0);

INSERT INTO sys_menu (menu_name, parent_id, menu_type, path, component, perms, icon, sort_no, is_visible, status, create_by, create_time, deleted)
SELECT '进货查验报告', 0, 'F', '', NULL, 'qms:iqc:report', NULL, 8017, 0, 1, 1, NOW(), 0
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='qms:iqc:report' AND deleted=0);

-- 2. 6 业务角色 × PC 授权 (按 perms 反查 menu_id, home sys_role_menu 三列 PK, client_type='PC')
INSERT INTO sys_role_menu (role_id, menu_id, client_type)
SELECT r.id, m.id, 'PC'
FROM sys_role r
CROSS JOIN sys_menu m
WHERE m.perms IN ('qms:iqc:list','qms:iqc:detail','qms:iqc:report')
  AND m.deleted=0
  AND r.role_code IN ('SUPER_ADMIN','PURCHASE_MGR','SALES_MGR','WAREHOUSE_MGR','PRODUCTION_MGR','FINANCE')
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id=r.id AND rm.menu_id=m.id AND rm.client_type='PC'
  );

-- 3. 验证
SELECT id, perms, menu_name, menu_type, status FROM sys_menu WHERE perms LIKE 'qms:iqc:%' AND deleted=0;
SELECT r.role_code, rm.client_type, m.perms FROM sys_role_menu rm
JOIN sys_role r ON r.id=rm.role_id JOIN sys_menu m ON m.id=rm.menu_id
WHERE m.perms LIKE 'qms:iqc:%' ORDER BY r.role_code, m.perms;
