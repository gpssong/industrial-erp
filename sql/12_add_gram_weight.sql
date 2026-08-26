-- ====================================================================
-- v1.0.7 商品字段优化: gram_weight (克重)
-- ====================================================================
-- 合并自:
--   12_product_gram_weight.sql  — ADD COLUMN (幂等, 动态 SQL 守门)
--   12_add_gram_weight.sql      — MODIFY COLUMN COMMENT 改为 g/个
--
-- 背景:
--   v1.0.7 引入商品克重字段, 初始单位 g/m²
--   v1.0.8 起改为 g/件, 老数据保留 decimal(18,4)
--
-- 幂等: ADD COLUMN 用动态 SQL 守门; MODIFY COLUMN 重复执行无副作用
-- ====================================================================

SET NAMES utf8mb4;

-- 1. 添加 gram_weight 列 (幂等, 已存在则跳过)
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE()
                     AND TABLE_NAME = 'base_product'
                     AND COLUMN_NAME = 'gram_weight');

SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE base_product ADD COLUMN gram_weight DECIMAL(18,4) NULL COMMENT ''克重 (g/件)'' AFTER density',
  'SELECT ''gram_weight 列已存在, 跳过'' AS msg');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. 更新列注释为 g/个 (幂等, MODIFY COLUMN 无副作用)
ALTER TABLE base_product
  MODIFY COLUMN gram_weight DECIMAL(18,4) DEFAULT NULL COMMENT '克重(g/个)';
