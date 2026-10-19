-- v1.1.99: 商品表加 食品级 开关 (XK 食品接触合规字段)
-- 用户反馈 2026-10-14: 新增商品弹窗需要 食品级 开关 (亮=食品级, 暗=工业品)
-- 沿用 isBatch/isSn 的 Integer TINYINT 范式, 默认 0 (工业品)
-- 兼容 MySQL 5.7 + 8.0, information_schema 守门幂等
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'base_product' AND COLUMN_NAME = 'is_food_grade');
SET @sql := IF(@col_exists = 0,
  'ALTER TABLE base_product ADD COLUMN is_food_grade TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''食品级 (XK16-204 食品接触原料): 0=工业品 1=食品级'' AFTER is_sn',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
