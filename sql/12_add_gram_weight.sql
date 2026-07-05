-- ============================================================
-- v1.0.7+ schema 漂移修复: base_product 添加 gram_weight (克重)
-- v1.0.7 引入商品克重字段 (g/m² 或 g/件), 后端 BaseProduct 实体已映射该字段
-- v1.0.8 起改为 g/件, 老数据保留 decimal(18,4)
-- ============================================================

SET NAMES utf8mb4;

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