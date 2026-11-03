-- v1.1.160 — 新建生产工单「原料按 BOM 逐行 + 每行从库存选批号」落库表
-- 背景: 用户原话「新建生产单时原料按bom，批号从库存中选择」. 原先 prd_order 只有一个自由文本
--       batch_no (v1.1.99, 食品级手填一个字符串), 既不能按 BOM 逐行展开, 也不能从 inv_stock 里选批次,
--       于是开工 release() 自动展开的领料明细 batch_no 恒为空 → 领料审核 StockService.outStock 找不到
--       (warehouseId, productId, batchNo) 对应库存行, 报「库存不存在」.
-- 方案: 建 prd_order_material 一行一种原料, 存 BOM 需求数量 + 用户选的出库仓库 + 库存批次;
--       开工时 release() 按 productId 把这些批次回填到生成的领料明细行;
--       领料明细加 warehouse_id (每行可从不同仓出库), check() 优先按行仓库出库.
-- 注意: 本文件含中文, 灌库必须 --default-character-set=utf8mb4, 否则注释与 COMMENT 乱码.
-- 幂等: 可重复执行 (CREATE TABLE IF NOT EXISTS + information_schema 探测加列).

SET NAMES utf8mb4;

-- 1) 工单原料明细表 (BOM 展开, 每行一个原料 + 出库仓 + 库存批号)
CREATE TABLE IF NOT EXISTS `prd_order_material` (
  `id`             BIGINT       NOT NULL COMMENT '雪花 id (MyBatis-Plus ASSIGN_ID)',
  `prd_order_id`   BIGINT       NOT NULL COMMENT 'prd_order.id',
  `line_no`        INT          NOT NULL DEFAULT 0 COMMENT '行号 (按 BOM 顺序)',
  `bom_detail_id`  BIGINT       DEFAULT NULL COMMENT '来源 prd_bom_detail.id (可空: 手工加行)',
  `material_type`  VARCHAR(16)  DEFAULT NULL COMMENT 'MAIN 主料 / AUX 辅料 / PACK 包装 (带出自 BOM)',
  `product_id`     BIGINT       NOT NULL COMMENT '原料商品 base_product.id',
  `product_code`   VARCHAR(64)  DEFAULT NULL COMMENT '原料编码 (快照)',
  `product_name`   VARCHAR(128) DEFAULT NULL COMMENT '原料名称 (快照)',
  `spec`           VARCHAR(128) DEFAULT NULL COMMENT '规格 (快照)',
  `unit_id`        BIGINT       DEFAULT NULL COMMENT '单位 id',
  `unit_name`      VARCHAR(32)  DEFAULT NULL COMMENT '单位名称 (快照)',
  `req_qty`        DECIMAL(18,4) DEFAULT NULL COMMENT '需求数量 (kg) = 成品总重 × 本项配比/Σ配比 × (1+损耗率), 仅展示; 领料量开工时按 BOM 重算',
  `warehouse_id`   BIGINT       DEFAULT NULL COMMENT '该行出库仓库 base_warehouse.id (从库存选批所属仓)',
  `batch_no`       VARCHAR(64)  DEFAULT NULL COMMENT '该行库存批次 inv_stock.batch_no (开工回填领料明细)',
  `remark`         VARCHAR(255) DEFAULT NULL,
  `create_by`      BIGINT       DEFAULT NULL,
  `create_time`    DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `update_by`      BIGINT       DEFAULT NULL,
  `update_time`    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`        TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_pom_order` (`prd_order_id`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生产工单原料明细 (BOM 逐行 + 每行出库仓/库存批号)';

-- 2) 领料明细加出库仓库列 (幂等: 已存在则跳过)
--    原领料单出库仓只有一个表头 warehouse_id; 工单原料行可能来自不同仓, 需按行记录出库仓,
--    check() 优先用行仓库, 为空回落表头仓库 (保持存量领料单行为不变).
SET @col_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'prd_requisition_detail'
     AND COLUMN_NAME = 'warehouse_id'
);
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE `prd_requisition_detail` ADD COLUMN `warehouse_id` BIGINT DEFAULT NULL COMMENT ''v1.1.160 该行出库仓库 (工单原料行按仓选批回填; 空则回落领料单表头仓库)'' AFTER `requisition_id`',
  'SELECT ''prd_requisition_detail.warehouse_id 已存在, 跳过'' AS msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
