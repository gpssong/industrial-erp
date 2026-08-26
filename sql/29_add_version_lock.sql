-- =============================================================================
-- v1.1.24: 核心业务表加乐观锁 version 列
-- 日期: 2026-08-26
-- 背景: 之前只给 fin_arap/inv_stock/base_customer 3 张表加了 @Version 乐观锁,
--       但 sal_delivery/pur_receipt/fin_invoice 这 3 张表高频并发 (出库/入库/发票)
--       无版本控制, 双用户同时提交会产生"后写覆盖前写"的脏数据.
--
-- 幂等设计: 用 information_schema 守门, 可反复执行不报错.
-- 同模式参见 sql/26_add_ledger_spec_model.sql
-- =============================================================================
SET NAMES utf8mb4;
USE industrial_erp;

DROP PROCEDURE IF EXISTS _add_version_if_missing;
DELIMITER //
CREATE PROCEDURE _add_version_if_missing(
  IN p_table VARCHAR(64)
)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = p_table
      AND column_name = 'version'
  ) THEN
    SET @sql = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `version` INT NOT NULL DEFAULT 0 COMMENT ''乐观锁版本号'' AFTER `update_time`');
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END //
DELIMITER ;

CALL _add_version_if_missing('sal_delivery');
CALL _add_version_if_missing('pur_receipt');
CALL _add_version_if_missing('fin_invoice');
DROP PROCEDURE _add_version_if_missing;

SELECT table_name, column_name, column_default, extra
FROM information_schema.columns
WHERE table_schema = 'industrial_erp'
  AND table_name IN ('sal_delivery','pur_receipt','fin_invoice','fin_arap','inv_stock','base_customer')
  AND column_name = 'version'
ORDER BY table_name;