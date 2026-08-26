-- =============================================================================
-- v1.1.24: 数据库性能索引补齐
-- 日期: 2026-08-26
-- 背景: 此前 16 个 Mapper 含 ORDER BY 但 SQL 文件 0 个 INDEX 定义,
--       销售出库按 bill_no/customer_id/create_time 查, 库存按 product_id/warehouse_id
--       查, 财务按 source_bill_id/source_bill_type 查, 均无索引 → 全表扫描.
--       本脚本为高频查询路径建组合索引.
--
-- 幂等设计: 使用 information_schema 守门, 可反复执行不报错.
-- 同模式参见 sql/26_add_ledger_spec_model.sql
-- =============================================================================
SET NAMES utf8mb4;
USE industrial_erp;

-- ---------- 辅助: 索引已存在则跳过 ----------
DROP PROCEDURE IF EXISTS _add_index_if_missing;
DELIMITER //
CREATE PROCEDURE _add_index_if_missing(
  IN p_table VARCHAR(64),
  IN p_index VARCHAR(64),
  IN p_cols  TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = p_table
      AND index_name = p_index
  ) THEN
    SET @sql = CONCAT('ALTER TABLE `', p_table, '` ADD INDEX `', p_index, '` (', p_cols, ')');
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END //
DELIMITER ;

-- =====================================================================
-- 销售域 (sales)
-- =====================================================================

-- 销售出库主表: 高频按 bill_no 查、按 customer_id+create_time 列表、按 bill_status 过滤
CALL _add_index_if_missing('sal_delivery', 'idx_sal_delivery_bill_no',          '`bill_no`');
CALL _add_index_if_missing('sal_delivery', 'idx_sal_delivery_customer_time',    '`customer_id`, `create_time` DESC');
CALL _add_index_if_missing('sal_delivery', 'idx_sal_delivery_bill_status_time', '`bill_status`, `create_time` DESC');
CALL _add_index_if_missing('sal_delivery', 'idx_sal_delivery_warehouse_time',   '`warehouse_id`, `create_time` DESC');

-- 销售出库明细: 按 delivery_id 查 (几乎所有详情/反审核都要)
CALL _add_index_if_missing('sal_delivery_detail', 'idx_sal_delivery_detail_did', '`delivery_id`');
CALL _add_index_if_missing('sal_delivery_detail', 'idx_sal_delivery_detail_pid', '`product_id`');

-- 销售订单主表
CALL _add_index_if_missing('sal_order', 'idx_sal_order_bill_no',                '`bill_no`');
CALL _add_index_if_missing('sal_order', 'idx_sal_order_customer_time',          '`customer_id`, `create_time` DESC');
CALL _add_index_if_missing('sal_order_detail', 'idx_sal_order_detail_oid',      '`order_id`');

-- 销售退货
CALL _add_index_if_missing('sal_return', 'idx_sal_return_bill_no',              '`bill_no`');
CALL _add_index_if_missing('sal_return', 'idx_sal_return_customer_time',        '`customer_id`, `create_time` DESC');
CALL _add_index_if_missing('sal_return_detail', 'idx_sal_return_detail_rid',    '`return_id`');

-- =====================================================================
-- 采购域 (purchase)
-- =====================================================================
CALL _add_index_if_missing('pur_receipt', 'idx_pur_receipt_bill_no',            '`bill_no`');
CALL _add_index_if_missing('pur_receipt', 'idx_pur_receipt_supplier_time',      '`supplier_id`, `create_time` DESC');
CALL _add_index_if_missing('pur_receipt', 'idx_pur_receipt_bill_status_time',   '`bill_status`, `create_time` DESC');
CALL _add_index_if_missing('pur_receipt_detail', 'idx_pur_receipt_detail_rid',  '`receipt_id`');
CALL _add_index_if_missing('pur_receipt_detail', 'idx_pur_receipt_detail_pid',  '`product_id`');

CALL _add_index_if_missing('pur_order', 'idx_pur_order_bill_no',                '`bill_no`');
CALL _add_index_if_missing('pur_order', 'idx_pur_order_supplier_time',          '`supplier_id`, `create_time` DESC');
CALL _add_index_if_missing('pur_order_detail', 'idx_pur_order_detail_oid',      '`order_id`');

CALL _add_index_if_missing('pur_return', 'idx_pur_return_bill_no',              '`bill_no`');
CALL _add_index_if_missing('pur_return', 'idx_pur_return_supplier_time',        '`supplier_id`, `create_time` DESC');
CALL _add_index_if_missing('pur_return_detail', 'idx_pur_return_detail_rid',    '`return_id`');

-- =====================================================================
-- 库存域 (inventory)
-- =====================================================================

-- 库存台账 inv_ledger: 库存查询/盘点/报表高频, 按 product_id+warehouse_id+create_time
CALL _add_index_if_missing('inv_ledger', 'idx_inv_ledger_product_wh_time',     '`product_id`, `warehouse_id`, `create_time` DESC');
-- v1.1.24 修正: inv_ledger 表字段是 source_no (来源单号) 而非 source_bill_type + source_bill_id 组合
CALL _add_index_if_missing('inv_ledger', 'idx_inv_ledger_source_no',           '`source_no`');
CALL _add_index_if_missing('inv_ledger', 'idx_inv_ledger_biz_type_time',       '`product_id`, `create_time` DESC');

-- 库存汇总: 按 (product_id, warehouse_id, batch_no) 唯一定位
CALL _add_index_if_missing('inv_stock', 'idx_inv_stock_prod_wh_batch',         '`product_id`, `warehouse_id`, `batch_no`');

-- 库存盘点主表
CALL _add_index_if_missing('inv_check', 'idx_inv_check_bill_no',                '`bill_no`');
CALL _add_index_if_missing('inv_check', 'idx_inv_check_warehouse_time',         '`warehouse_id`, `create_time` DESC');
CALL _add_index_if_missing('inv_check_detail', 'idx_inv_check_detail_cid',      '`check_id`');

-- 调拨单
CALL _add_index_if_missing('inv_transfer', 'idx_inv_transfer_bill_no',          '`bill_no`');
CALL _add_index_if_missing('inv_transfer_detail', 'idx_inv_transfer_detail_tid','`transfer_id`');

-- =====================================================================
-- 财务域 (finance)
-- =====================================================================

-- 应收应付 fin_arap: AR/AP 列表按 customer_id/supplier_id + bill_status, 反审核按 source 删
CALL _add_index_if_missing('fin_arap', 'idx_fin_arap_customer_status_time',     '`customer_id`, `bill_status`, `create_time` DESC');
CALL _add_index_if_missing('fin_arap', 'idx_fin_arap_supplier_status_time',     '`supplier_id`, `bill_status`, `create_time` DESC');
CALL _add_index_if_missing('fin_arap', 'idx_fin_arap_source_bill',              '`source_bill_type`, `source_bill_id`');

-- 发票 fin_invoice (v1.1.24 修正: 用 partner_id 不是 customer_id, partner_type 区分客户/供应商)
CALL _add_index_if_missing('fin_invoice', 'idx_fin_invoice_bill_no',            '`bill_no`');
CALL _add_index_if_missing('fin_invoice', 'idx_fin_invoice_partner_time',      '`partner_id`, `create_time` DESC');

-- =====================================================================
-- 基础数据 (base)
-- =====================================================================

-- 商品: 列表按 keyword 模糊 (但 LIKE '%x%' 用不上索引), 主要按 product_code 精确查重
CALL _add_index_if_missing('base_product', 'idx_base_product_code',              '`product_code`');
CALL _add_index_if_missing('base_product', 'idx_base_product_status',            '`status`');

-- 客户/供应商
CALL _add_index_if_missing('base_customer', 'idx_base_customer_code',           '`customer_code`');
CALL _add_index_if_missing('base_customer', 'idx_base_customer_name',           '`customer_name`');
CALL _add_index_if_missing('base_supplier', 'idx_base_supplier_code',           '`supplier_code`');

-- =====================================================================
-- 清理: 删存储过程
-- =====================================================================
DROP PROCEDURE _add_index_if_missing;

-- =====================================================================
-- 校验
-- =====================================================================
SELECT table_name, index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS cols
FROM information_schema.statistics
WHERE table_schema = 'industrial_erp'
  AND table_name IN (
    'sal_delivery','sal_delivery_detail','sal_order','sal_order_detail',
    'sal_return','sal_return_detail',
    'pur_receipt','pur_receipt_detail','pur_order','pur_order_detail','pur_return','pur_return_detail',
    'inv_ledger','inv_stock','inv_check','inv_check_detail','inv_transfer','inv_transfer_detail',
    'fin_arap','fin_invoice',
    'base_product','base_customer','base_supplier'
  )
  AND index_name LIKE 'idx_%'
GROUP BY table_name, index_name
ORDER BY table_name, index_name;