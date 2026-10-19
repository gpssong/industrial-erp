-- v1.1.101 — IQC 进货查验 3 perm 节点 + 6 内置角色×PC 授权
-- 一期全幂等: INSERT IGNORE + information_schema 探测 client_type 列 (老库 2 列退化)
-- 部署: mysql -uroot -p --default-character-set=utf8mb4 industrial_erp < 118_v1101_iqc_menu.sql

-- 3 perm 节点 (雪花 ID 范围 2090345792472715301+, 与历史菜单不冲突)
INSERT IGNORE INTO `sys_menu` (`id`, `menu_name`, `parent_id`, `menu_type`, `perms`, `path`, `is_visible`, `sort_no`, `create_by`, `create_time`, `deleted`)
VALUES
  (2090345792472715301, '进货查验台账',  0, 'F', 'qms:iqc:list',    '', 0, 8015, 1, NOW(), 0),
  (2090345792472715302, '进货查验详情',  0, 'F', 'qms:iqc:detail',  '', 0, 8016, 1, NOW(), 0),
  (2090345792472715303, '进货查验报告',  0, 'F', 'qms:iqc:report',  '', 0, 8017, 1, NOW(), 0);

-- 6 角色 × PC 授权 (information_schema 探测 client_type 列, 老库 2 列退化)
INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`, `client_type`)
SELECT r.id, m.id, 'PC'
FROM `sys_role` r, `sys_menu` m
WHERE r.role_code IN ('SUPER_ADMIN','PURCHASE_MGR','WAREHOUSE_MGR','PRODUCTION_MGR','FINANCE','SALES_MGR')
  AND m.perms IN ('qms:iqc:list','qms:iqc:detail','qms:iqc:report')
  AND EXISTS (SELECT 1 FROM information_schema.columns
              WHERE table_schema = DATABASE() AND table_name = 'sys_role_menu' AND column_name = 'client_type');
