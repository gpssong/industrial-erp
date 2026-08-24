-- =============================================================================
-- v1.1.20: 库存台账添加规格/型号字段
-- 在 inv_ledger 表增加 spec / model 列, 并与 base_product 关联回填历史数据
-- 日期: 2026-08-24
--
-- 幂等设计: 用 information_schema + 动态 SQL 守门, 可反复执行不会报错.
-- 同模式参见 sql/14_add_prd_requisition_detail_columns.sql
-- =============================================================================
SET NAMES utf8mb4;
USE industrial_erp;

-- 1. 添加列 (幂等)
SET @cnt_spec = (SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema = DATABASE()
                   AND table_name = 'inv_ledger'
                   AND column_name = 'spec');
SET @sql = IF(@cnt_spec = 0,
  'ALTER TABLE `inv_ledger` ADD COLUMN `spec` VARCHAR(128) DEFAULT NULL COMMENT ''规格'' AFTER `product_name`',
  'SELECT ''spec column exists, skipped'' AS note');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @cnt_model = (SELECT COUNT(*) FROM information_schema.columns
                  WHERE table_schema = DATABASE()
                    AND table_name = 'inv_ledger'
                    AND column_name = 'model');
SET @sql = IF(@cnt_model = 0,
  'ALTER TABLE `inv_ledger` ADD COLUMN `model` VARCHAR(128) DEFAULT NULL COMMENT ''型号'' AFTER `spec`',
  'SELECT ''model column exists, skipped'' AS note');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. 回填历史数据 (幂等: WHERE 条件已过滤 spec/model 已有值的情况)
UPDATE `inv_ledger` l
JOIN `base_product` p ON p.id = l.product_id AND p.deleted = 0
SET
  l.spec = p.spec,
  l.model = p.model
WHERE l.deleted = 0
  AND (l.spec IS NULL OR l.model IS NULL);

-- 3. 校验
SELECT
  'spec NULL 剩余' AS check_item,
  COUNT(*) AS cnt
FROM inv_ledger
WHERE deleted = 0 AND spec IS NULL;

SELECT
  'model NULL 剩余' AS check_item,
  COUNT(*) AS cnt
FROM inv_ledger
WHERE deleted = 0 AND model IS NULL;

SELECT
  '已填充 spec/model 记录数' AS check_item,
  COUNT(*) AS cnt
FROM inv_ledger
WHERE deleted = 0 AND (spec IS NOT NULL OR model IS NOT NULL);