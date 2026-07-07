-- =====================================================================
-- 修复 prd_requisition_detail 表 schema 漂移
-- 现象: 开工生成领料单时 PrdRequisitionDetailMapper.insert 报
--        Unknown column 'line_no' in 'field list'
-- 根因: 实体 PrdRequisitionDetail 含 line_no + material_type 字段,
--       但基线 sql/06_schema_production.sql 创建的 prd_requisition_detail
--       表没这两列. 其他 8 个 detail 表 (pur/sal/prd_bom 等) 都有 line_no,
--       是唯一漏掉的.
-- 修复: ALTER TABLE 加列, 并同步到 06_schema_production.sql 基线.
-- =====================================================================

USE `industrial_erp`;

-- 加列 (IF NOT EXISTS 在 MySQL 8.0.29+ 才支持, 这里用 information_schema 守门兼容低版本)
SET @col_line_no := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'industrial_erp'
    AND TABLE_NAME = 'prd_requisition_detail'
    AND COLUMN_NAME = 'line_no'
);
SET @sql := IF(@col_line_no = 0,
  'ALTER TABLE `prd_requisition_detail` ADD COLUMN `line_no` INT DEFAULT NULL COMMENT ''行号'' AFTER `requisition_id`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_material_type := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'industrial_erp'
    AND TABLE_NAME = 'prd_requisition_detail'
    AND COLUMN_NAME = 'material_type'
);
SET @sql := IF(@col_material_type = 0,
  'ALTER TABLE `prd_requisition_detail` ADD COLUMN `material_type` VARCHAR(32) DEFAULT NULL COMMENT ''物料类型'' AFTER `line_no`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 校验
SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'industrial_erp'
  AND TABLE_NAME = 'prd_requisition_detail'
  AND COLUMN_NAME IN ('line_no', 'material_type')
ORDER BY ORDINAL_POSITION;