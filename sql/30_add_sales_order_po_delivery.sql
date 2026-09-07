-- v1.1.37: 销售订单新增采购订单号、交货方式字段
-- 用途: 用户需要记录客户采购订单号 (PO号) 和交货方式 (送货/自提等)

-- 守门: 避免重复执行报错
SET @dbname = DATABASE();
SET @tablename = 'sal_order';
SET @colname_po = 'po_no';
SET @colname_dm = 'delivery_method';

SELECT COUNT(*) INTO @cnt FROM information_schema.columns
  WHERE table_schema=@dbname AND table_name=@tablename AND column_name=@colname_po;

SET @sql_add_po = IF(@cnt=0,
  CONCAT('ALTER TABLE ', @tablename, ' ADD COLUMN `po_no` VARCHAR(64) DEFAULT NULL COMMENT ''客户采购订单号'' AFTER `phone`'),
  'SELECT ''po_no 列已存在, 跳过'' AS msg');

SET @sql_add_dm = IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=@dbname AND table_name=@tablename AND column_name=@colname_dm)=0,
  CONCAT('ALTER TABLE ', @tablename, ' ADD COLUMN `delivery_method` VARCHAR(32) DEFAULT NULL COMMENT ''交货方式'' AFTER `po_no`'),
  'SELECT ''delivery_method 列已存在, 跳过'' AS msg');

SET @sql_add_idx = IF((SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema=@dbname AND table_name=@tablename AND index_name='idx_po_no')=0,
  'ALTER TABLE sal_order ADD INDEX idx_po_no (po_no)',
  'SELECT ''idx_po_no 索引已存在, 跳过'' AS msg');

PREPARE stmt1 FROM @sql_add_po;
EXECUTE stmt1;
DEALLOCATE PREPARE stmt1;

PREPARE stmt2 FROM @sql_add_dm;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;

PREPARE stmt3 FROM @sql_add_idx;
EXECUTE stmt3;
DEALLOCATE PREPARE stmt3;

SELECT 'sal_order po_no + delivery_method 添加完成' AS result;
