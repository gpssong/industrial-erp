-- =====================================================================
-- 三、采购管理模块 (pur_*)
-- =====================================================================

-- 3.1  采购订单主表
DROP TABLE IF EXISTS `pur_order`;
CREATE TABLE `pur_order` (
  `id`              BIGINT        NOT NULL AUTO_INCREMENT,
  `bill_no`         VARCHAR(32)   NOT NULL COMMENT '订单号: PO+yyyyMMdd+0001',
  `bill_date`       DATE          NOT NULL,
  `supplier_id`     BIGINT        NOT NULL,
  `supplier_name`   VARCHAR(128)  DEFAULT NULL,
  `warehouse_id`    BIGINT        NOT NULL,
  `buyer_id`        BIGINT        DEFAULT NULL,
  `buyer_name`      VARCHAR(64)   DEFAULT NULL,
  `order_type`      VARCHAR(32)   DEFAULT 'NORMAL' COMMENT 'NORMAL/URGENT',
  `pay_type`        VARCHAR(32)   DEFAULT 'CASH' COMMENT 'CASH=货到付款 PREPAY=预付款 TICKET=票到付款',
  `currency`        VARCHAR(8)    DEFAULT 'CNY',
  `exchange_rate`   DECIMAL(12,6) DEFAULT 1.000000,
  `total_qty`       DECIMAL(18,4) DEFAULT 0,
  `total_amount`    DECIMAL(18,4) DEFAULT 0 COMMENT '不含税总额',
  `tax_amount`      DECIMAL(18,4) DEFAULT 0,
  `total_amount_tax` DECIMAL(18,4) DEFAULT 0 COMMENT '含税总额',
  `paid_amount`     DECIMAL(18,4) DEFAULT 0,
  `bill_status`     VARCHAR(32)   DEFAULT 'DRAFT' COMMENT 'DRAFT/CHECKED/FINISHED/CLOSED/CANCELLED',
  `delivery_date`   DATE          DEFAULT NULL,
  `source_bill_id`  BIGINT        DEFAULT NULL COMMENT '来源询价单ID',
  `remark`          VARCHAR(500)  DEFAULT NULL,
  `create_by`       BIGINT        DEFAULT NULL,
  `create_time`     DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`       BIGINT        DEFAULT NULL,
  `update_time`     DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`         TINYINT       DEFAULT 0,
  `tenant_id`       BIGINT        DEFAULT 1 COMMENT '多租户预留',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_pur_order_bill_no` (`bill_no`, `deleted`),
  KEY `idx_pur_order_supplier` (`supplier_id`),
  KEY `idx_pur_order_date` (`bill_date`),
  KEY `idx_pur_order_status` (`bill_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购订单';

-- 3.2  采购订单明细
DROP TABLE IF EXISTS `pur_order_detail`;
CREATE TABLE `pur_order_detail` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT,
  `order_id`      BIGINT        NOT NULL,
  `line_no`       INT           DEFAULT 0,
  `product_id`    BIGINT        NOT NULL,
  `product_code`  VARCHAR(64)   DEFAULT NULL,
  `product_name`  VARCHAR(128)  DEFAULT NULL,
  `spec`          VARCHAR(128)  DEFAULT NULL,
  `unit_id`       BIGINT        DEFAULT NULL,
  `unit_name`     VARCHAR(32)   DEFAULT NULL,
  `qty`           DECIMAL(18,4) NOT NULL DEFAULT 0,
  `price`         DECIMAL(18,4) DEFAULT 0,
  `amount`        DECIMAL(18,4) DEFAULT 0,
  `tax_rate`      DECIMAL(8,2)  DEFAULT 13.00,
  `tax_amount`    DECIMAL(18,4) DEFAULT 0,
  `amount_tax`    DECIMAL(18,4) DEFAULT 0,
  `in_qty`        DECIMAL(18,4) DEFAULT 0 COMMENT '已入库数量',
  `return_qty`    DECIMAL(18,4) DEFAULT 0,
  `batch_no`      VARCHAR(64)   DEFAULT NULL,
  `production_date` DATE        DEFAULT NULL,
  `expire_date`   DATE          DEFAULT NULL,
  `remark`        VARCHAR(255)  DEFAULT NULL,
  `create_by`     BIGINT        DEFAULT NULL,
  `create_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`     BIGINT        DEFAULT NULL,
  `update_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT       DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_pur_od_order` (`order_id`),
  KEY `idx_pur_od_product` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购订单明细';

-- 3.3  采购入库主表
DROP TABLE IF EXISTS `pur_receipt`;
CREATE TABLE `pur_receipt` (
  `id`              BIGINT        NOT NULL AUTO_INCREMENT,
  `bill_no`         VARCHAR(32)   NOT NULL COMMENT '入库单号: RKP+yyyyMMdd+0001',
  `bill_date`       DATE          NOT NULL,
  `order_id`        BIGINT        DEFAULT NULL COMMENT '关联采购订单ID',
  `order_no`        VARCHAR(32)   DEFAULT NULL,
  `supplier_id`     BIGINT        NOT NULL,
  `supplier_name`   VARCHAR(128)  DEFAULT NULL,
  `warehouse_id`    BIGINT        NOT NULL,
  `area_id`         BIGINT        DEFAULT NULL,
  `buyer_id`        BIGINT        DEFAULT NULL,
  `bill_type`       VARCHAR(32)   DEFAULT 'NORMAL' COMMENT 'NORMAL/RETURN',
  `total_qty`       DECIMAL(18,4) DEFAULT 0,
  `total_amount`    DECIMAL(18,4) DEFAULT 0,
  `tax_amount`      DECIMAL(18,4) DEFAULT 0,
  `total_amount_tax` DECIMAL(18,4) DEFAULT 0,
  `paid_amount`     DECIMAL(18,4) DEFAULT 0,
  `pay_type`        VARCHAR(32)   DEFAULT 'CASH',
  `bill_status`     VARCHAR(32)   DEFAULT 'DRAFT',
  `delivery_no`     VARCHAR(64)   DEFAULT NULL COMMENT '供应商送货单号',
  `remark`          VARCHAR(500)  DEFAULT NULL,
  `create_by`       BIGINT        DEFAULT NULL,
  `create_time`     DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`       BIGINT        DEFAULT NULL,
  `update_time`     DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`         TINYINT       DEFAULT 0,
  `tenant_id`       BIGINT        DEFAULT 1,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_pur_receipt_bill_no` (`bill_no`, `deleted`),
  KEY `idx_pur_receipt_supplier` (`supplier_id`),
  KEY `idx_pur_receipt_date` (`bill_date`),
  KEY `idx_pur_receipt_order` (`order_id`),
  KEY `idx_pur_receipt_status` (`bill_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购入库单';

-- 3.4  采购入库明细
DROP TABLE IF EXISTS `pur_receipt_detail`;
CREATE TABLE `pur_receipt_detail` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT,
  `receipt_id`    BIGINT        NOT NULL,
  `line_no`       INT           DEFAULT 0,
  `order_detail_id` BIGINT      DEFAULT NULL,
  `product_id`    BIGINT        NOT NULL,
  `product_code`  VARCHAR(64)   DEFAULT NULL,
  `product_name`  VARCHAR(128)  DEFAULT NULL,
  `spec`          VARCHAR(128)  DEFAULT NULL,
  `unit_id`       BIGINT        DEFAULT NULL,
  `unit_name`     VARCHAR(32)   DEFAULT NULL,
  `qty`           DECIMAL(18,4) NOT NULL DEFAULT 0,
  `price`         DECIMAL(18,4) DEFAULT 0,
  `amount`        DECIMAL(18,4) DEFAULT 0,
  `tax_rate`      DECIMAL(8,2)  DEFAULT 13.00,
  `tax_amount`    DECIMAL(18,4) DEFAULT 0,
  `amount_tax`    DECIMAL(18,4) DEFAULT 0,
  `batch_no`      VARCHAR(64)   DEFAULT NULL,
  `production_date` DATE        DEFAULT NULL,
  `expire_date`   DATE          DEFAULT NULL,
  `location_id`   BIGINT        DEFAULT NULL,
  `location_name` VARCHAR(64)   DEFAULT NULL,
  `sn_no`         VARCHAR(128)  DEFAULT NULL COMMENT '序列号',
  `remark`        VARCHAR(255)  DEFAULT NULL,
  `create_by`     BIGINT        DEFAULT NULL,
  `create_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`     BIGINT        DEFAULT NULL,
  `update_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT       DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_pur_rd_receipt` (`receipt_id`),
  KEY `idx_pur_rd_product` (`product_id`),
  KEY `idx_pur_rd_batch` (`batch_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购入库明细';

-- 3.5  采购退货
DROP TABLE IF EXISTS `pur_return`;
CREATE TABLE `pur_return` (
  `id`              BIGINT        NOT NULL AUTO_INCREMENT,
  `bill_no`         VARCHAR(32)   NOT NULL,
  `bill_date`       DATE          NOT NULL,
  `source_receipt_id` BIGINT      DEFAULT NULL,
  `supplier_id`     BIGINT        NOT NULL,
  `supplier_name`   VARCHAR(128)  DEFAULT NULL,
  `warehouse_id`    BIGINT        NOT NULL,
  `total_qty`       DECIMAL(18,4) DEFAULT 0,
  `total_amount`    DECIMAL(18,4) DEFAULT 0,
  `tax_amount`      DECIMAL(18,4) DEFAULT 0,
  `total_amount_tax` DECIMAL(18,4) DEFAULT 0,
  `bill_status`     VARCHAR(32)   DEFAULT 'DRAFT',
  `remark`          VARCHAR(500)  DEFAULT NULL,
  `create_by`       BIGINT        DEFAULT NULL,
  `create_time`     DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`       BIGINT        DEFAULT NULL,
  `update_time`     DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`         TINYINT       DEFAULT 0,
  `tenant_id`       BIGINT        DEFAULT 1,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_pur_return_bill_no` (`bill_no`, `deleted`),
  KEY `idx_pur_return_supplier` (`supplier_id`),
  KEY `idx_pur_return_date` (`bill_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购退货单';

DROP TABLE IF EXISTS `pur_return_detail`;
CREATE TABLE `pur_return_detail` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT,
  `return_id`     BIGINT        NOT NULL,
  `line_no`       INT           DEFAULT NULL,
  `product_id`    BIGINT        NOT NULL,
  `product_code`  VARCHAR(64)   DEFAULT NULL,
  `product_name`  VARCHAR(128)  DEFAULT NULL,
  `spec`          VARCHAR(128)  DEFAULT NULL,
  `unit_id`       BIGINT        DEFAULT NULL,
  `unit_name`     VARCHAR(32)   DEFAULT NULL,
  `qty`           DECIMAL(18,4) NOT NULL DEFAULT 0,
  `price`         DECIMAL(18,4) DEFAULT 0,
  `amount`        DECIMAL(18,4) DEFAULT 0,
  `tax_rate`      DECIMAL(8,2)  DEFAULT 13.00,
  `tax_amount`    DECIMAL(18,4) DEFAULT 0,
  `amount_tax`    DECIMAL(18,4) DEFAULT 0,
  `batch_no`      VARCHAR(64)   DEFAULT NULL,
  `remark`        VARCHAR(255)  DEFAULT NULL,
  `create_by`     BIGINT        DEFAULT NULL,
  `create_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`     BIGINT        DEFAULT NULL,
  `update_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT       DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_pur_return_d_return` (`return_id`),
  KEY `idx_pur_return_d_product` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购退货明细';

-- 3.6  采购询价
DROP TABLE IF EXISTS `pur_inquiry`;
CREATE TABLE `pur_inquiry` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT,
  `bill_no`       VARCHAR(32)   NOT NULL,
  `bill_date`     DATE          NOT NULL,
  `supplier_id`   BIGINT        NOT NULL,
  `supplier_name` VARCHAR(128)  DEFAULT NULL,
  `expire_date`   DATE          DEFAULT NULL,
  `total_qty`     DECIMAL(18,4) DEFAULT 0,
  `total_amount`  DECIMAL(18,4) DEFAULT 0,
  `bill_status`   VARCHAR(32)   DEFAULT 'DRAFT',
  `remark`        VARCHAR(500)  DEFAULT NULL,
  `create_by`     BIGINT        DEFAULT NULL,
  `create_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`     BIGINT        DEFAULT NULL,
  `update_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT       DEFAULT 0,
  `tenant_id`     BIGINT        DEFAULT 1,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_pur_inquiry_bill_no` (`bill_no`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购询价单';

DROP TABLE IF EXISTS `pur_inquiry_detail`;
CREATE TABLE `pur_inquiry_detail` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT,
  `inquiry_id`    BIGINT        NOT NULL,
  `product_id`    BIGINT        NOT NULL,
  `product_code`  VARCHAR(64)   DEFAULT NULL,
  `product_name`  VARCHAR(128)  DEFAULT NULL,
  `spec`          VARCHAR(128)  DEFAULT NULL,
  `unit_id`       BIGINT        DEFAULT NULL,
  `unit_name`     VARCHAR(32)   DEFAULT NULL,
  `qty`           DECIMAL(18,4) NOT NULL DEFAULT 0,
  `price`         DECIMAL(18,4) DEFAULT 0,
  `amount`        DECIMAL(18,4) DEFAULT 0,
  `remark`        VARCHAR(255)  DEFAULT NULL,
  `create_by`     BIGINT        DEFAULT NULL,
  `create_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`     BIGINT        DEFAULT NULL,
  `update_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT       DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_pur_inq_d_inquiry` (`inquiry_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购询价明细';

-- 3.7  付款单 (采购付款)
DROP TABLE IF EXISTS `pur_payment`;
CREATE TABLE `pur_payment` (
  `id`             BIGINT        NOT NULL AUTO_INCREMENT,
  `bill_no`        VARCHAR(32)   NOT NULL,
  `bill_date`      DATE          NOT NULL,
  `supplier_id`    BIGINT        NOT NULL,
  `supplier_name`  VARCHAR(128)  DEFAULT NULL,
  `pay_type`       VARCHAR(32)   DEFAULT 'CASH' COMMENT 'CASH/BANK/WECHAT/ALIPAY',
  `bank_account`   VARCHAR(64)   DEFAULT NULL,
  `amount`         DECIMAL(18,4) NOT NULL DEFAULT 0,
  `source_bill_type` VARCHAR(32) DEFAULT 'PUR_RECEIPT' COMMENT 'PUR_RECEIPT/PUR_ORDER',
  `source_bill_id` BIGINT        DEFAULT NULL,
  `source_bill_no` VARCHAR(32)   DEFAULT NULL,
  `bill_status`    VARCHAR(32)   DEFAULT 'DRAFT',
  `remark`         VARCHAR(500)  DEFAULT NULL,
  `create_by`      BIGINT        DEFAULT NULL,
  `create_time`    DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`      BIGINT        DEFAULT NULL,
  `update_time`    DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`        TINYINT       DEFAULT 0,
  `tenant_id`      BIGINT        DEFAULT 1,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_pur_payment_bill_no` (`bill_no`, `deleted`),
  KEY `idx_pur_payment_supplier` (`supplier_id`),
  KEY `idx_pur_payment_date` (`bill_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购付款单';

