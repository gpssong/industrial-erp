-- ====================================================================
--  工业 ERP - BOM 当配方, 成品选配方
--  编号 19: BOM = 配方, 1 成品对应 1 配方, 不同成品可共用 1 配方
--  幂等: 多次执行结果一致
-- =====================================================================
SET NAMES utf8mb4;
USE `industrial_erp`;

-- ---------------------------------------------------------------------
-- 19.1  成品加配方外键 (若已存在则跳过)
-- ---------------------------------------------------------------------
SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'base_product' AND COLUMN_NAME = 'bom_id');
SET @sql := IF(@col_exists = 0,
  'ALTER TABLE `base_product` ADD COLUMN `bom_id` BIGINT DEFAULT NULL COMMENT ''配方(BOM)ID, 一个成品对应一个配方'', ADD INDEX `idx_base_product_bom` (`bom_id`)',
  'SELECT ''base_product.bom_id 已存在, 跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------
-- 19.2  prd_bom.product_id 从 NOT NULL 改为可空 (新模型下配方不再绑死单一成品)
-- ---------------------------------------------------------------------
SET @col_nullable := (SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'prd_bom' AND COLUMN_NAME = 'product_id');
SET @sql := IF(@col_nullable = 'NO',
  'ALTER TABLE `prd_bom` MODIFY COLUMN `product_id` BIGINT DEFAULT NULL COMMENT ''已废弃: 成品现在通过 base_product.bom_id 反向关联''',
  'SELECT ''prd_bom.product_id 已经是 nullable, 跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 19.3  清空 prd_bom 的 product 关联字段
UPDATE `prd_bom`
SET product_code = NULL, product_name = NULL,
    spec = NULL, unit_id = NULL, unit_name = NULL
WHERE deleted = 0;

-- 19.4  数据迁移: 老 BOM.product_id 关联反向写到 product.bom_id (增量, 不覆盖已有)
UPDATE `base_product` p
JOIN `prd_bom` b ON b.product_id = p.id
SET p.bom_id = b.id
WHERE p.deleted = 0 AND b.deleted = 0
  AND p.bom_id IS NULL;

-- 19.5  校验
SELECT
  (SELECT COUNT(*) FROM base_product WHERE deleted = 0 AND bom_id IS NOT NULL) AS linked,
  (SELECT COUNT(*) FROM prd_bom WHERE deleted = 0) AS total_boms,
  (SELECT COUNT(*) FROM base_product WHERE deleted = 0) AS total_products;
