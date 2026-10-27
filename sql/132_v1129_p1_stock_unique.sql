-- =============================================================================
-- v1.1.129 / P1-3: inv_stock 批次唯一键修正
-- 背景: 原唯一键 (warehouse_id, location_id, product_id, batch_no, deleted)
--       batch_no / location_id / deleted 均可为 NULL, MySQL 唯一索引对 NULL 不去重
--       → 同一 仓库+商品+批次 可以插入多行, 并发出库会各扣各的, 库存虚增.
--       业务查询 selectForUpdate 只按 warehouse_id + product_id + batch_no 定位
--       (不含 location), 因此唯一键必须与查询键一致.
--
-- 方案: 新增两个 STORED 生成列把 NULL 归一 (batch_key/deleted_key),
--       删除旧唯一键, 新建 uniq_inv_stock_biz(warehouse_id, product_id, batch_key, deleted_key).
--
-- 幂等: 全部用 information_schema 守门, 可重复执行.
-- 执行前自检 (应返回 0 行):
--   SELECT warehouse_id, product_id, IFNULL(batch_no,''), IFNULL(deleted,0), COUNT(*)
--   FROM inv_stock GROUP BY 1,2,3,4 HAVING COUNT(*) > 1;
-- =============================================================================
SET NAMES utf8mb4;
USE industrial_erp;

-- ---------- 前置校验: 有重复数据直接报错, 避免建索引时半途失败 ----------
DROP PROCEDURE IF EXISTS _inv_stock_check_dup;
DELIMITER //
CREATE PROCEDURE _inv_stock_check_dup()
BEGIN
  DECLARE dup INT DEFAULT 0;
  SELECT COUNT(*) INTO dup FROM (
    SELECT 1 FROM inv_stock
    GROUP BY warehouse_id, product_id, IFNULL(batch_no, ''), IFNULL(deleted, 0)
    HAVING COUNT(*) > 1
  ) t;
  IF dup > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'inv_stock 存在重复业务键, 请先合并重复库存行再执行本脚本';
  END IF;
END //
DELIMITER ;
CALL _inv_stock_check_dup();
DROP PROCEDURE _inv_stock_check_dup;

-- ---------- 生成列: NULL → '' / 0 ----------
SET @has_batch_key = (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'inv_stock' AND column_name = 'batch_key'
);
SET @sql = IF(@has_batch_key = 0,
  'ALTER TABLE inv_stock ADD COLUMN batch_key VARCHAR(64) GENERATED ALWAYS AS (IFNULL(batch_no, '''')) STORED',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_deleted_key = (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'inv_stock' AND column_name = 'deleted_key'
);
SET @sql = IF(@has_deleted_key = 0,
  'ALTER TABLE inv_stock ADD COLUMN deleted_key TINYINT GENERATED ALWAYS AS (IFNULL(deleted, 0)) STORED',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 旧唯一键 (含可空列) 删除 ----------
SET @has_old = (
  SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'inv_stock' AND index_name = 'uniq_inv_stock_key'
);
SET @sql = IF(@has_old > 0, 'ALTER TABLE inv_stock DROP INDEX uniq_inv_stock_key', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 新唯一键 ----------
SET @has_new = (
  SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'inv_stock' AND index_name = 'uniq_inv_stock_biz'
);
SET @sql = IF(@has_new = 0,
  'ALTER TABLE inv_stock ADD UNIQUE KEY uniq_inv_stock_biz (warehouse_id, product_id, batch_key, deleted_key)',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 验证 (期望: uniq_inv_stock_biz 4 列, 无 uniq_inv_stock_key) ----------
SELECT index_name, seq_in_index, column_name
FROM information_schema.statistics
WHERE table_schema = DATABASE() AND table_name = 'inv_stock'
  AND index_name IN ('uniq_inv_stock_key', 'uniq_inv_stock_biz')
ORDER BY index_name, seq_in_index;
