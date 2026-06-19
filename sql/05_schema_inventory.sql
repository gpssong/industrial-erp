-- =====================================================================
-- 五、库存管理模块 (inv_*)  - 核心模块
--    核心: 严格禁止负库存出库 (并发锁 + 行锁)
--    工业特性: 米重换算 / 分切 / 复卷 / 裁切
-- =====================================================================

-- 5.1  库存主表 (按 仓库+商品+批次 汇总, 库存行锁)
DROP TABLE IF EXISTS `inv_stock`;
CREATE TABLE `inv_stock` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT,
  `warehouse_id`  BIGINT        NOT NULL,
  `warehouse_name` VARCHAR(64)  DEFAULT NULL,
  `area_id`       BIGINT        DEFAULT NULL,
  `location_id`   BIGINT        DEFAULT NULL,
  `location_name` VARCHAR(64)   DEFAULT NULL,
  `product_id`    BIGINT        NOT NULL,
  `product_code`  VARCHAR(64)   DEFAULT NULL,
  `product_name`  VARCHAR(128)  DEFAULT NULL,
  `spec`          VARCHAR(128)  DEFAULT NULL,
  `unit_id`       BIGINT        DEFAULT NULL,
  `unit_name`     VARCHAR(32)   DEFAULT NULL,
  `batch_no`      VARCHAR(64)   DEFAULT NULL,
  `production_date` DATE        DEFAULT NULL,
  `expire_date`   DATE          DEFAULT NULL,
  `qty`           DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '当前数量',
  `available_qty` DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '可用数量(预留扣减)',
  `lock_qty`      DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '锁定数量',
  `avg_cost`      DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '移动加权平均成本',
  `total_cost`    DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '总成本',
  `last_in_date`  DATE          DEFAULT NULL,
  `last_out_date` DATE          DEFAULT NULL,
  `safety_stock`  DECIMAL(18,4) DEFAULT 0,
  `version`       INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `create_by`     BIGINT        DEFAULT NULL,
  `create_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`     BIGINT        DEFAULT NULL,
  `update_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT       DEFAULT 0,
  `tenant_id`     BIGINT        DEFAULT 1,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_inv_stock_key` (`warehouse_id`, `location_id`, `product_id`, `batch_no`, `deleted`),
  KEY `idx_inv_stock_product` (`product_id`),
  KEY `idx_inv_stock_warehouse` (`warehouse_id`),
  KEY `idx_inv_stock_batch` (`batch_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存主表(汇总)';

-- 5.2  库存台账 (所有出入库流水)
DROP TABLE IF EXISTS `inv_ledger`;
CREATE TABLE `inv_ledger` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT,
  `bill_type`     VARCHAR(32)   NOT NULL COMMENT 'PUR_RECEIPT/PUR_RETURN/SAL_DELIVERY/SAL_RETURN/PROD_IN/PROD_OUT/TRANSFER/CHECK/INIT',
  `bill_id`       BIGINT        NOT NULL,
  `bill_no`       VARCHAR(32)   DEFAULT NULL,
  `bill_detail_id` BIGINT       DEFAULT NULL,
  `biz_direction` TINYINT       NOT NULL COMMENT '1=入库 -1=出库',
  `biz_date`      DATE          NOT NULL,
  `warehouse_id`  BIGINT        NOT NULL,
  `warehouse_name` VARCHAR(64)  DEFAULT NULL,
  `area_id`       BIGINT        DEFAULT NULL,
  `location_id`   BIGINT        DEFAULT NULL,
  `product_id`    BIGINT        NOT NULL,
  `product_code`  VARCHAR(64)   DEFAULT NULL,
  `product_name`  VARCHAR(128)  DEFAULT NULL,
  `unit_id`       BIGINT        DEFAULT NULL,
  `unit_name`     VARCHAR(32)   DEFAULT NULL,
  `batch_no`      VARCHAR(64)   DEFAULT NULL,
  `qty`           DECIMAL(18,4) NOT NULL DEFAULT 0,
  `price`         DECIMAL(18,4) DEFAULT 0,
  `amount`        DECIMAL(18,4) DEFAULT 0,
  `before_qty`    DECIMAL(18,4) DEFAULT 0,
  `after_qty`     DECIMAL(18,4) DEFAULT 0,
  `before_avg_cost` DECIMAL(18,4) DEFAULT 0,
  `after_avg_cost`  DECIMAL(18,4) DEFAULT 0,
  `source_no`     VARCHAR(32)   DEFAULT NULL COMMENT '关联单号',
  `supplier_id`   BIGINT        DEFAULT NULL,
  `customer_id`   BIGINT        DEFAULT NULL,
  `remark`        VARCHAR(500)  DEFAULT NULL,
  `create_by`     BIGINT        DEFAULT NULL,
  `create_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `deleted`       TINYINT       DEFAULT 0,
  `tenant_id`     BIGINT        DEFAULT 1,
  PRIMARY KEY (`id`),
  KEY `idx_inv_ledger_bill` (`bill_type`, `bill_id`),
  KEY `idx_inv_ledger_product` (`product_id`),
  KEY `idx_inv_ledger_warehouse` (`warehouse_id`),
  KEY `idx_inv_ledger_date` (`biz_date`),
  KEY `idx_inv_ledger_batch` (`batch_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存台账';

-- 5.3  库存调拨
DROP TABLE IF EXISTS `inv_transfer`;
CREATE TABLE `inv_transfer` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT,
  `bill_no`       VARCHAR(32)   NOT NULL,
  `bill_date`     DATE          NOT NULL,
  `out_warehouse_id` BIGINT     NOT NULL,
  `in_warehouse_id`  BIGINT     NOT NULL,
  `total_qty`     DECIMAL(18,4) DEFAULT 0,
  `bill_status`   VARCHAR(32)   DEFAULT 'DRAFT' COMMENT 'DRAFT/CHECKED/FINISHED',
  `remark`        VARCHAR(500)  DEFAULT NULL,
  `create_by`     BIGINT        DEFAULT NULL,
  `create_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`     BIGINT        DEFAULT NULL,
  `update_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT       DEFAULT 0,
  `tenant_id`     BIGINT        DEFAULT 1,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_inv_transfer_bill_no` (`bill_no`, `deleted`),
  KEY `idx_inv_transfer_date` (`bill_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存调拨单';

DROP TABLE IF EXISTS `inv_transfer_detail`;
CREATE TABLE `inv_transfer_detail` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT,
  `transfer_id`   BIGINT        NOT NULL,
  `product_id`    BIGINT        NOT NULL,
  `product_code`  VARCHAR(64)   DEFAULT NULL,
  `product_name`  VARCHAR(128)  DEFAULT NULL,
  `unit_id`       BIGINT        DEFAULT NULL,
  `unit_name`     VARCHAR(32)   DEFAULT NULL,
  `qty`           DECIMAL(18,4) NOT NULL DEFAULT 0,
  `batch_no`      VARCHAR(64)   DEFAULT NULL,
  `out_location_id` BIGINT      DEFAULT NULL,
  `in_location_id`  BIGINT      DEFAULT NULL,
  `remark`        VARCHAR(255)  DEFAULT NULL,
  `create_by`     BIGINT        DEFAULT NULL,
  `create_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`     BIGINT        DEFAULT NULL,
  `update_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT       DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_inv_td_transfer` (`transfer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存调拨明细';

-- 5.4  库存盘点
DROP TABLE IF EXISTS `inv_check`;
CREATE TABLE `inv_check` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT,
  `bill_no`       VARCHAR(32)   NOT NULL,
  `bill_date`     DATE          NOT NULL,
  `warehouse_id`  BIGINT        NOT NULL,
  `warehouse_name` VARCHAR(64)  DEFAULT NULL,
  `check_type`    VARCHAR(32)   DEFAULT 'ALL' COMMENT 'ALL/PARTIAL/CATEGORY',
  `total_diff_qty` DECIMAL(18,4) DEFAULT 0,
  `total_diff_amount` DECIMAL(18,4) DEFAULT 0,
  `bill_status`   VARCHAR(32)   DEFAULT 'DRAFT' COMMENT 'DRAFT/CHECKED/ADJUSTED',
  `remark`        VARCHAR(500)  DEFAULT NULL,
  `create_by`     BIGINT        DEFAULT NULL,
  `create_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`     BIGINT        DEFAULT NULL,
  `update_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT       DEFAULT 0,
  `tenant_id`     BIGINT        DEFAULT 1,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_inv_check_bill_no` (`bill_no`, `deleted`),
  KEY `idx_inv_check_date` (`bill_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存盘点单';

DROP TABLE IF EXISTS `inv_check_detail`;
CREATE TABLE `inv_check_detail` (
  `id`             BIGINT        NOT NULL AUTO_INCREMENT,
  `check_id`       BIGINT        NOT NULL,
  `product_id`     BIGINT        NOT NULL,
  `product_code`   VARCHAR(64)   DEFAULT NULL,
  `product_name`   VARCHAR(128)  DEFAULT NULL,
  `unit_id`        BIGINT        DEFAULT NULL,
  `unit_name`      VARCHAR(32)   DEFAULT NULL,
  `batch_no`       VARCHAR(64)   DEFAULT NULL,
  `location_id`    BIGINT        DEFAULT NULL,
  `book_qty`       DECIMAL(18,4) DEFAULT 0 COMMENT '账面数量',
  `actual_qty`     DECIMAL(18,4) DEFAULT 0 COMMENT '实盘数量',
  `diff_qty`       DECIMAL(18,4) DEFAULT 0 COMMENT '差异 = 实盘-账面',
  `price`          DECIMAL(18,4) DEFAULT 0,
  `diff_amount`    DECIMAL(18,4) DEFAULT 0,
  `diff_type`      VARCHAR(16)   DEFAULT 'NORMAL' COMMENT 'NORMAL/PROFIT/LOSS',
  `remark`         VARCHAR(255)  DEFAULT NULL,
  `create_by`      BIGINT        DEFAULT NULL,
  `create_time`    DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`      BIGINT        DEFAULT NULL,
  `update_time`    DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`        TINYINT       DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_inv_cd_check` (`check_id`),
  KEY `idx_inv_cd_product` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存盘点明细';

-- 5.5  库存盈亏单
DROP TABLE IF EXISTS `inv_profit_loss`;
CREATE TABLE `inv_profit_loss` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT,
  `bill_no`       VARCHAR(32)   NOT NULL,
  `bill_date`     DATE          NOT NULL,
  `warehouse_id`  BIGINT        NOT NULL,
  `source_check_id` BIGINT      DEFAULT NULL,
  `total_diff_qty` DECIMAL(18,4) DEFAULT 0,
  `total_diff_amount` DECIMAL(18,4) DEFAULT 0,
  `bill_status`   VARCHAR(32)   DEFAULT 'DRAFT',
  `remark`        VARCHAR(500)  DEFAULT NULL,
  `create_by`     BIGINT        DEFAULT NULL,
  `create_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`     BIGINT        DEFAULT NULL,
  `update_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT       DEFAULT 0,
  `tenant_id`     BIGINT        DEFAULT 1,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_inv_pl_bill_no` (`bill_no`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存盈亏单';

DROP TABLE IF EXISTS `inv_profit_loss_detail`;
CREATE TABLE `inv_profit_loss_detail` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT,
  `profit_loss_id` BIGINT       NOT NULL,
  `product_id`    BIGINT        NOT NULL,
  `product_code`  VARCHAR(64)   DEFAULT NULL,
  `product_name`  VARCHAR(128)  DEFAULT NULL,
  `unit_id`       BIGINT        DEFAULT NULL,
  `unit_name`     VARCHAR(32)   DEFAULT NULL,
  `batch_no`      VARCHAR(64)   DEFAULT NULL,
  `diff_qty`      DECIMAL(18,4) DEFAULT 0,
  `price`         DECIMAL(18,4) DEFAULT 0,
  `diff_amount`   DECIMAL(18,4) DEFAULT 0,
  `remark`        VARCHAR(255)  DEFAULT NULL,
  `create_by`     BIGINT        DEFAULT NULL,
  `create_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`     BIGINT        DEFAULT NULL,
  `update_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT       DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_inv_pld_pl` (`profit_loss_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存盈亏明细';

-- 5.6  库存预警 (可由定时任务刷新)
DROP TABLE IF EXISTS `inv_warning`;
CREATE TABLE `inv_warning` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT,
  `product_id`    BIGINT        NOT NULL,
  `product_code`  VARCHAR(64)   DEFAULT NULL,
  `product_name`  VARCHAR(128)  DEFAULT NULL,
  `warehouse_id`  BIGINT        DEFAULT NULL,
  `warehouse_name` VARCHAR(64)  DEFAULT NULL,
  `qty`           DECIMAL(18,4) DEFAULT 0,
  `safety_stock`  DECIMAL(18,4) DEFAULT 0,
  `warning_type`  VARCHAR(32)   DEFAULT 'LOW' COMMENT 'LOW/EXPIRE/OUT',
  `expire_date`   DATE          DEFAULT NULL,
  `days_to_expire` INT          DEFAULT 0,
  `status`        TINYINT       DEFAULT 0 COMMENT '0=未处理 1=已处理',
  `create_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_inv_warn_product` (`product_id`),
  KEY `idx_inv_warn_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存预警';

-- 5.7  分切/复卷/裁切 (工业版)
DROP TABLE IF EXISTS `inv_cut_process`;
CREATE TABLE `inv_cut_process` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT,
  `bill_no`       VARCHAR(32)   NOT NULL,
  `bill_date`     DATE          NOT NULL,
  `process_type`  VARCHAR(32)   NOT NULL COMMENT 'CUT=分切 REWIND=复卷 SLIT=裁切',
  `warehouse_id`  BIGINT        NOT NULL,
  `total_in_qty`  DECIMAL(18,4) DEFAULT 0 COMMENT '原料消耗数量',
  `total_out_qty` DECIMAL(18,4) DEFAULT 0 COMMENT '成品产出数量',
  `loss_qty`      DECIMAL(18,4) DEFAULT 0 COMMENT '损耗',
  `loss_rate`     DECIMAL(8,4)  DEFAULT 0 COMMENT '损耗率(%)',
  `bill_status`   VARCHAR(32)   DEFAULT 'DRAFT',
  `remark`        VARCHAR(500)  DEFAULT NULL,
  `create_by`     BIGINT        DEFAULT NULL,
  `create_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`     BIGINT        DEFAULT NULL,
  `update_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT       DEFAULT 0,
  `tenant_id`     BIGINT        DEFAULT 1,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_inv_cut_bill_no` (`bill_no`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分切复卷裁切单';

DROP TABLE IF EXISTS `inv_cut_process_detail`;
CREATE TABLE `inv_cut_process_detail` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT,
  `cut_id`        BIGINT        NOT NULL,
  `product_id`    BIGINT        NOT NULL,
  `product_code`  VARCHAR(64)   DEFAULT NULL,
  `product_name`  VARCHAR(128)  DEFAULT NULL,
  `spec`          VARCHAR(128)  DEFAULT NULL,
  `unit_id`       BIGINT        DEFAULT NULL,
  `unit_name`     VARCHAR(32)   DEFAULT NULL,
  `batch_no`      VARCHAR(64)   DEFAULT NULL,
  `length_m`      DECIMAL(18,4) DEFAULT 0 COMMENT '米重换算: 长度(米)',
  `thickness`     DECIMAL(18,4) DEFAULT 0 COMMENT '厚度',
  `width`         DECIMAL(18,4) DEFAULT 0 COMMENT '幅宽',
  `density`       DECIMAL(18,6) DEFAULT 0 COMMENT '密度',
  `weight_kg`     DECIMAL(18,4) DEFAULT 0 COMMENT '重量(kg)',
  `qty`           DECIMAL(18,4) DEFAULT 0 COMMENT '数量',
  `direction`     TINYINT       NOT NULL COMMENT '1=投入 -1=产出',
  `remark`        VARCHAR(255)  DEFAULT NULL,
  `create_by`     BIGINT        DEFAULT NULL,
  `create_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`     BIGINT        DEFAULT NULL,
  `update_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT       DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_inv_cut_d_cut` (`cut_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分切复卷裁切明细';

