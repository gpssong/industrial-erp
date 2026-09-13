-- v1.1.40: 销售出库单新增交货方式 (主表) 和采购订单号 (明细表)
-- 用途: 出库单记录交货方式 (送货/自提等), 商品明细记录客户采购订单号

-- 守门: 避免重复执行报错
SET @dbname = DATABASE();

-- 1. sal_delivery 加 delivery_method
SET @tablename = 'sal_delivery';
SET @colname = 'delivery_method';
SELECT COUNT(*) INTO @cnt FROM information_schema.columns
  WHERE table_schema=@dbname AND table_name=@tablename AND column_name=@colname;
SET @sql = IF(@cnt=0,
  CONCAT('ALTER TABLE ', @tablename, ' ADD COLUMN `delivery_method` VARCHAR(32) DEFAULT NULL COMMENT ''交货方式'' AFTER `phone`'),
  'SELECT ''delivery_method 列已存在, 跳过'' AS msg');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. sal_delivery_detail 加 po_no
SET @tablename = 'sal_delivery_detail';
SET @colname = 'po_no';
SELECT COUNT(*) INTO @cnt FROM information_schema.columns
  WHERE table_schema=@dbname AND table_name=@tablename AND column_name=@colname;
SET @sql = IF(@cnt=0,
  CONCAT('ALTER TABLE ', @tablename, ' ADD COLUMN `po_no` VARCHAR(64) DEFAULT NULL COMMENT ''采购订单号'' AFTER `remark`'),
  'SELECT ''po_no 列已存在, 跳过'' AS msg');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SELECT 'v1.1.40: sal_delivery.delivery_method + sal_delivery_detail.po_no 添加完成' AS result;
