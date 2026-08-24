-- =============================================================================
-- v1.1.20: 库存台账添加规格/型号字段
-- 在 inv_ledger 表增加 spec / model 列, 并与 base_product 关联回填历史数据
-- 日期: 2026-08-24
-- =============================================================================
SET NAMES utf8mb4;
USE industrial_erp;

-- 1. 添加列
ALTER TABLE `inv_ledger`
  ADD COLUMN `spec`          VARCHAR(128) DEFAULT NULL COMMENT '规格' AFTER `product_name`,
  ADD COLUMN `model`         VARCHAR(128) DEFAULT NULL COMMENT '型号' AFTER `spec`;

-- 2. 回填历史数据: 根据 product_id 关联 base_product 更新 spec / model
UPDATE `inv_ledger` l
JOIN `base_product` p ON p.id = l.product_id AND p.deleted = 0
SET
  l.spec = p.spec,
  l.model = p.model
WHERE l.deleted = 0
  AND (l.spec IS NULL OR l.model IS NULL);

-- 3. 校验
SELECT
  'spec 回填检查' AS check_item,
  COUNT(*) AS cnt
FROM inv_ledger
WHERE deleted = 0 AND spec IS NULL;

SELECT
  'model 回填检查' AS check_item,
  COUNT(*) AS cnt
FROM inv_ledger
WHERE deleted = 0 AND model IS NULL;

SELECT
  '已填充 spec/model 记录数' AS check_item,
  COUNT(*) AS cnt
FROM inv_ledger
WHERE deleted = 0 AND (spec IS NOT NULL OR model IS NOT NULL);
