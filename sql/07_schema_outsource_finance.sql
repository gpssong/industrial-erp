-- =====================================================================
-- 七、委外加工模块 (out_*)
-- =====================================================================

DROP TABLE IF EXISTS `out_issue`;
CREATE TABLE `out_issue` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT,
  `bill_no`       VARCHAR(32)   NOT NULL,
  `bill_date`     DATE          NOT NULL,
  `supplier_id`   BIGINT        NOT NULL COMMENT '外协厂',
  `supplier_name` VARCHAR(128)  DEFAULT NULL,
  `warehouse_id`  BIGINT        NOT NULL,
  `process_type`  VARCHAR(64)   DEFAULT NULL COMMENT '加工类型: 印刷/复合/分切/涂布...',
  `total_qty`     DECIMAL(18,4) DEFAULT 0,
  `total_cost`    DECIMAL(18,4) DEFAULT 0,
  `expect_date`   DATE          DEFAULT NULL,
  `bill_status`   VARCHAR(32)   DEFAULT 'DRAFT',
  `remark`        VARCHAR(500)  DEFAULT NULL,
  `create_by`     BIGINT        DEFAULT NULL,
  `create_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`     BIGINT        DEFAULT NULL,
  `update_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT       DEFAULT 0,
  `tenant_id`     BIGINT        DEFAULT 1,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_out_issue_bill_no` (`bill_no`, `deleted`),
  KEY `idx_out_issue_supplier` (`supplier_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='委外发料单';

DROP TABLE IF EXISTS `out_issue_detail`;
CREATE TABLE `out_issue_detail` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT,
  `issue_id`      BIGINT        NOT NULL,
  `product_id`    BIGINT        NOT NULL,
  `product_code`  VARCHAR(64)   DEFAULT NULL,
  `product_name`  VARCHAR(128)  DEFAULT NULL,
  `unit_id`       BIGINT        DEFAULT NULL,
  `unit_name`     VARCHAR(32)   DEFAULT NULL,
  `qty`           DECIMAL(18,4) NOT NULL DEFAULT 0,
  `price`         DECIMAL(18,4) DEFAULT 0,
  `amount`        DECIMAL(18,4) DEFAULT 0,
  `batch_no`      VARCHAR(64)   DEFAULT NULL,
  `remark`        VARCHAR(255)  DEFAULT NULL,
  `create_by`     BIGINT        DEFAULT NULL,
  `create_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`     BIGINT        DEFAULT NULL,
  `update_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT       DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_out_id_issue` (`issue_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='委外发料明细';

DROP TABLE IF EXISTS `out_processing_in`;
CREATE TABLE `out_processing_in` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT,
  `bill_no`       VARCHAR(32)   NOT NULL,
  `bill_date`     DATE          NOT NULL,
  `supplier_id`   BIGINT        NOT NULL,
  `supplier_name` VARCHAR(128)  DEFAULT NULL,
  `warehouse_id`  BIGINT        NOT NULL,
  `process_type`  VARCHAR(64)   DEFAULT NULL,
  `process_fee`   DECIMAL(18,4) DEFAULT 0 COMMENT '加工费',
  `material_fee`  DECIMAL(18,4) DEFAULT 0 COMMENT '原料费',
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
  UNIQUE KEY `uniq_out_pi_bill_no` (`bill_no`, `deleted`),
  KEY `idx_out_pi_supplier` (`supplier_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='委外入库单';

DROP TABLE IF EXISTS `out_processing_in_detail`;
CREATE TABLE `out_processing_in_detail` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT,
  `pi_id`         BIGINT        NOT NULL,
  `product_id`    BIGINT        NOT NULL,
  `product_code`  VARCHAR(64)   DEFAULT NULL,
  `product_name`  VARCHAR(128)  DEFAULT NULL,
  `spec`          VARCHAR(128)  DEFAULT NULL,
  `unit_id`       BIGINT        DEFAULT NULL,
  `unit_name`     VARCHAR(32)   DEFAULT NULL,
  `qty`           DECIMAL(18,4) NOT NULL DEFAULT 0,
  `price`         DECIMAL(18,4) DEFAULT 0,
  `amount`        DECIMAL(18,4) DEFAULT 0,
  `process_price` DECIMAL(18,4) DEFAULT 0 COMMENT '加工单价',
  `process_amount` DECIMAL(18,4) DEFAULT 0,
  `batch_no`      VARCHAR(64)   DEFAULT NULL,
  `remark`        VARCHAR(255)  DEFAULT NULL,
  `create_by`     BIGINT        DEFAULT NULL,
  `create_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`     BIGINT        DEFAULT NULL,
  `update_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT       DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_out_pid_pi` (`pi_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='委外入库明细';

DROP TABLE IF EXISTS `out_process_fee`;
CREATE TABLE `out_process_fee` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT,
  `bill_no`       VARCHAR(32)   NOT NULL,
  `bill_date`     DATE          NOT NULL,
  `supplier_id`   BIGINT        NOT NULL,
  `supplier_name` VARCHAR(128)  DEFAULT NULL,
  `source_pi_id`  BIGINT        DEFAULT NULL,
  `amount`        DECIMAL(18,4) NOT NULL DEFAULT 0,
  `pay_type`      VARCHAR(32)   DEFAULT 'CASH',
  `bill_status`   VARCHAR(32)   DEFAULT 'DRAFT',
  `remark`        VARCHAR(500)  DEFAULT NULL,
  `create_by`     BIGINT        DEFAULT NULL,
  `create_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`     BIGINT        DEFAULT NULL,
  `update_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT       DEFAULT 0,
  `tenant_id`     BIGINT        DEFAULT 1,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_out_pf_bill_no` (`bill_no`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='加工费结算单';

-- =====================================================================
-- 八、财务往来模块 (fin_*)
-- =====================================================================

-- 8.1  应收应付台账
DROP TABLE IF EXISTS `fin_arap`;
CREATE TABLE `fin_arap` (
  `id`             BIGINT        NOT NULL AUTO_INCREMENT,
  `bill_type`      VARCHAR(32)   NOT NULL COMMENT 'AR=应收 AP=应付',
  `source_bill_type` VARCHAR(32) DEFAULT NULL COMMENT 'PUR_RECEIPT/SAL_DELIVERY/...',
  `source_bill_id` BIGINT        DEFAULT NULL,
  `source_bill_no` VARCHAR(32)   DEFAULT NULL,
  `customer_id`    BIGINT        DEFAULT NULL,
  `customer_name`  VARCHAR(128)  DEFAULT NULL,
  `supplier_id`    BIGINT        DEFAULT NULL,
  `supplier_name`  VARCHAR(128)  DEFAULT NULL,
  `biz_date`       DATE          NOT NULL,
  `amount`         DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '发生金额',
  `paid_amount`    DECIMAL(18,4) NOT NULL DEFAULT 0,
  `balance`        DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '未结金额',
  `bill_status`    VARCHAR(32)   DEFAULT 'UNPAID' COMMENT 'UNPAID/PARTIAL/PAID/CANCELLED',
  `due_date`       DATE          DEFAULT NULL,
  `overdue_days`   INT           DEFAULT 0,
  `remark`         VARCHAR(500)  DEFAULT NULL,
  `create_by`      BIGINT        DEFAULT NULL,
  `create_time`    DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`      BIGINT        DEFAULT NULL,
  `update_time`    DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`        TINYINT       DEFAULT 0,
  `tenant_id`      BIGINT        DEFAULT 1,
  PRIMARY KEY (`id`),
  KEY `idx_fin_arap_type` (`bill_type`),
  KEY `idx_fin_arap_customer` (`customer_id`),
  KEY `idx_fin_arap_supplier` (`supplier_id`),
  KEY `idx_fin_arap_date` (`biz_date`),
  KEY `idx_fin_arap_status` (`bill_status`),
  KEY `idx_fin_arap_source` (`source_bill_type`, `source_bill_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应收应付台账';

-- 8.2  收付款流水
DROP TABLE IF EXISTS `fin_cash_flow`;
CREATE TABLE `fin_cash_flow` (
  `id`             BIGINT        NOT NULL AUTO_INCREMENT,
  `bill_no`        VARCHAR(32)   NOT NULL,
  `bill_type`      VARCHAR(32)   NOT NULL COMMENT 'RECEIPT=收款 PAYMENT=付款 TRANSFER=转账',
  `bill_date`      DATE          NOT NULL,
  `customer_id`    BIGINT        DEFAULT NULL,
  `customer_name`  VARCHAR(128)  DEFAULT NULL,
  `supplier_id`    BIGINT        DEFAULT NULL,
  `supplier_name`  VARCHAR(128)  DEFAULT NULL,
  `pay_type`       VARCHAR(32)   DEFAULT 'CASH' COMMENT 'CASH/BANK/WECHAT/ALIPAY/OTHER',
  `bank_account`   VARCHAR(64)   DEFAULT NULL,
  `amount`         DECIMAL(18,4) NOT NULL DEFAULT 0,
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
  UNIQUE KEY `uniq_fin_cf_bill_no` (`bill_no`, `deleted`),
  KEY `idx_fin_cf_type` (`bill_type`),
  KEY `idx_fin_cf_date` (`bill_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收付款流水';

-- 8.3  收付款核销明细
DROP TABLE IF EXISTS `fin_cash_writeoff`;
CREATE TABLE `fin_cash_writeoff` (
  `id`             BIGINT        NOT NULL AUTO_INCREMENT,
  `cash_flow_id`   BIGINT        NOT NULL,
  `arap_id`        BIGINT        NOT NULL,
  `amount`         DECIMAL(18,4) NOT NULL DEFAULT 0,
  `bill_type`      VARCHAR(32)   DEFAULT NULL,
  `source_bill_no` VARCHAR(32)   DEFAULT NULL,
  `remark`         VARCHAR(255)  DEFAULT NULL,
  `create_by`      BIGINT        DEFAULT NULL,
  `create_time`    DATETIME      DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_fin_cw_cash` (`cash_flow_id`),
  KEY `idx_fin_cw_arap` (`arap_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收付款核销明细';

-- 8.4  对账单
DROP TABLE IF EXISTS `fin_reconciliation`;
CREATE TABLE `fin_reconciliation` (
  `id`             BIGINT        NOT NULL AUTO_INCREMENT,
  `bill_no`        VARCHAR(32)   NOT NULL,
  `bill_date`      DATE          NOT NULL,
  `bill_type`      VARCHAR(32)   DEFAULT 'AR' COMMENT 'AR/AP',
  `customer_id`    BIGINT        DEFAULT NULL,
  `customer_name`  VARCHAR(128)  DEFAULT NULL,
  `supplier_id`    BIGINT        DEFAULT NULL,
  `supplier_name`  VARCHAR(128)  DEFAULT NULL,
  `start_date`     DATE          DEFAULT NULL,
  `end_date`       DATE          DEFAULT NULL,
  `total_amount`   DECIMAL(18,4) DEFAULT 0,
  `paid_amount`    DECIMAL(18,4) DEFAULT 0,
  `balance`        DECIMAL(18,4) DEFAULT 0,
  `bill_status`    VARCHAR(32)   DEFAULT 'DRAFT',
  `remark`         VARCHAR(500)  DEFAULT NULL,
  `create_by`      BIGINT        DEFAULT NULL,
  `create_time`    DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`      BIGINT        DEFAULT NULL,
  `update_time`    DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`        TINYINT       DEFAULT 0,
  `tenant_id`      BIGINT        DEFAULT 1,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_fin_rec_bill_no` (`bill_no`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对账单';

-- 8.5  客户/供应商账目汇总
DROP TABLE IF EXISTS `fin_partner_balance`;
CREATE TABLE `fin_partner_balance` (
  `id`             BIGINT        NOT NULL AUTO_INCREMENT,
  `partner_type`   VARCHAR(16)   NOT NULL COMMENT 'CUSTOMER/SUPPLIER',
  `partner_id`     BIGINT        NOT NULL,
  `partner_name`   VARCHAR(128)  DEFAULT NULL,
  `total_ar`       DECIMAL(18,4) DEFAULT 0 COMMENT '总应收',
  `total_ap`       DECIMAL(18,4) DEFAULT 0 COMMENT '总应付',
  `prepay`         DECIMAL(18,4) DEFAULT 0 COMMENT '预收/预付',
  `balance`        DECIMAL(18,4) DEFAULT 0,
  `credit_limit`   DECIMAL(18,4) DEFAULT 0,
  `update_time`    DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_fin_pb` (`partner_type`, `partner_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户/供应商账目汇总';

