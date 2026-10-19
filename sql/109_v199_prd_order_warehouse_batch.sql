-- v1.1.99: 生产工单 (prd_order) 加 开工时间 + 原料批号 字段
-- 用户反馈 2026-10-14: 新增工单弹窗需要 开工时间(默认今日) + 原料批号(食品级成品必填, XK 食品接触合规)
-- startDate 沿用 billDate 同 LocalDate 语义, 但语义上是计划开工 vs 单据日期, 拆开更清晰
-- batchNo: 食品级成品 (base_product.is_food_grade=1) 必填, 普通成品可选
-- 兼容 MySQL 5.7 + 8.0, information_schema 守门幂等
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'prd_order' AND COLUMN_NAME = 'start_date');
SET @sql := IF(@col_exists = 0,
  'ALTER TABLE prd_order ADD COLUMN start_date DATE DEFAULT NULL COMMENT ''计划开工日期 (默认 = billDate)'' AFTER bill_date',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'prd_order' AND COLUMN_NAME = 'batch_no');
SET @sql := IF(@col_exists = 0,
  'ALTER TABLE prd_order ADD COLUMN batch_no VARCHAR(64) DEFAULT NULL COMMENT ''原料批号 (食品级成品 XK16-204 必填)'' AFTER warehouse_id',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
