-- =====================================================================
-- 六、生产管理模块 (prd_*)  - 工业版核心
-- =====================================================================

-- 6.1  BOM 物料清单
DROP TABLE IF EXISTS `prd_bom`;
CREATE TABLE `prd_bom` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT,
  `bom_code`      VARCHAR(64)   NOT NULL,
  `bom_name`      VARCHAR(128)  NOT NULL,
  `product_id`    BIGINT        NOT NULL COMMENT '成品ID',
  `product_code`  VARCHAR(64)   DEFAULT NULL,
  `product_name`  VARCHAR(128)  DEFAULT NULL,
  `spec`          VARCHAR(128)  DEFAULT NULL,
  `unit_id`       BIGINT        DEFAULT NULL,
  `unit_name`     VARCHAR(32)   DEFAULT NULL,
  `version`       VARCHAR(32)   DEFAULT '1.0',
  `base_qty`      DECIMAL(18,4) DEFAULT 1 COMMENT '基础产量(以多少成品为基数)',
  `output_qty`    DECIMAL(18,4) DEFAULT 1 COMMENT '产出数量',
  `loss_rate`     DECIMAL(8,4)  DEFAULT 0 COMMENT '总损耗率(%)',
  `is_default`    TINYINT       DEFAULT 0,
  `status`        TINYINT       DEFAULT 1,
  `remark`        VARCHAR(500)  DEFAULT NULL,
  `create_by`     BIGINT        DEFAULT NULL,
  `create_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`     BIGINT        DEFAULT NULL,
  `update_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT       DEFAULT 0,
  `tenant_id`     BIGINT        DEFAULT 1,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_prd_bom_code` (`bom_code`, `deleted`),
  KEY `idx_prd_bom_product` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='BOM物料清单';

DROP TABLE IF EXISTS `prd_bom_detail`;
CREATE TABLE `prd_bom_detail` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT,
  `bom_id`        BIGINT        NOT NULL,
  `line_no`       INT           DEFAULT 0,
  `material_type` VARCHAR(32)   DEFAULT 'MAIN' COMMENT 'MAIN=主料 AUX=辅料 PACK=包材',
  `product_id`    BIGINT        NOT NULL COMMENT '原料ID',
  `product_code`  VARCHAR(64)   DEFAULT NULL,
  `product_name`  VARCHAR(128)  DEFAULT NULL,
  `spec`          VARCHAR(128)  DEFAULT NULL,
  `unit_id`       BIGINT        DEFAULT NULL,
  `unit_name`     VARCHAR(32)   DEFAULT NULL,
  `base_qty`      DECIMAL(18,4) DEFAULT 1 COMMENT '基础用量(对应BOM.base_qty)',
  `loss_rate`     DECIMAL(8,4)  DEFAULT 0 COMMENT '本项损耗率(%)',
  `remark`        VARCHAR(255)  DEFAULT NULL,
  `create_by`     BIGINT        DEFAULT NULL,
  `create_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`     BIGINT        DEFAULT NULL,
  `update_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT       DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_prd_bd_bom` (`bom_id`),
  KEY `idx_prd_bd_product` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='BOM明细';

-- 6.2  生产加工单
DROP TABLE IF EXISTS `prd_order`;
CREATE TABLE `prd_order` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT,
  `bill_no`       VARCHAR(32)   NOT NULL,
  `bill_date`     DATE          NOT NULL,
  `bom_id`        BIGINT        DEFAULT NULL,
  `bom_no`        VARCHAR(64)   DEFAULT NULL,
  `product_id`    BIGINT        NOT NULL,
  `product_code`  VARCHAR(64)   DEFAULT NULL,
  `product_name`  VARCHAR(128)  DEFAULT NULL,
  `spec`          VARCHAR(128)  DEFAULT NULL,
  `unit_id`       BIGINT        DEFAULT NULL,
  `unit_name`     VARCHAR(32)   DEFAULT NULL,
  `plan_qty`      DECIMAL(18,4) DEFAULT 0 COMMENT '计划生产数量',
  `actual_qty`    DECIMAL(18,4) DEFAULT 0 COMMENT '实际完成数量',
  `good_qty`      DECIMAL(18,4) DEFAULT 0 COMMENT '合格品数量',
  `loss_qty`      DECIMAL(18,4) DEFAULT 0 COMMENT '损耗数量',
  `loss_rate`     DECIMAL(8,4)  DEFAULT 0,
  `workshop`      VARCHAR(64)   DEFAULT NULL,
  `workshop_id`   BIGINT        DEFAULT NULL,
  `leader`        VARCHAR(64)   DEFAULT NULL,
  `start_date`    DATE          DEFAULT NULL,
  `end_date`      DATE          DEFAULT NULL,
  `cost_amount`   DECIMAL(18,4) DEFAULT 0 COMMENT '生产成本总额',
  `bill_status`   VARCHAR(32)   DEFAULT 'DRAFT' COMMENT 'DRAFT/RELEASED/PRODUCING/FINISHED/CLOSED',
  `source_bill_type` VARCHAR(32) DEFAULT NULL COMMENT 'SAL_ORDER/MANUAL',
  `source_bill_id` BIGINT       DEFAULT NULL,
  `source_bill_no` VARCHAR(32)  DEFAULT NULL,
  `remark`        VARCHAR(500)  DEFAULT NULL,
  `create_by`     BIGINT        DEFAULT NULL,
  `create_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`     BIGINT        DEFAULT NULL,
  `update_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT       DEFAULT 0,
  `tenant_id`     BIGINT        DEFAULT 1,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_prd_order_bill_no` (`bill_no`, `deleted`),
  KEY `idx_prd_order_date` (`bill_date`),
  KEY `idx_prd_order_status` (`bill_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生产加工单';

-- 6.3  领料单
DROP TABLE IF EXISTS `prd_requisition`;
CREATE TABLE `prd_requisition` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT,
  `bill_no`       VARCHAR(32)   NOT NULL,
  `bill_date`     DATE          NOT NULL,
  `prd_order_id`  BIGINT        DEFAULT NULL,
  `prd_order_no`  VARCHAR(32)   DEFAULT NULL,
  `warehouse_id`  BIGINT        NOT NULL,
  `workshop_id`   BIGINT        DEFAULT NULL,
  `workshop`      VARCHAR(64)   DEFAULT NULL,
  `bill_type`     VARCHAR(32)   DEFAULT 'ISSUE' COMMENT 'ISSUE=领料 REPLENISH=补料 RETURN=退料',
  `bill_status`   VARCHAR(32)   DEFAULT 'DRAFT',
  `remark`        VARCHAR(500)  DEFAULT NULL,
  `create_by`     BIGINT        DEFAULT NULL,
  `create_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`     BIGINT        DEFAULT NULL,
  `update_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT       DEFAULT 0,
  `tenant_id`     BIGINT        DEFAULT 1,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_prd_req_bill_no` (`bill_no`, `deleted`),
  KEY `idx_prd_req_order` (`prd_order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='领料/补料/退料单';

DROP TABLE IF EXISTS `prd_requisition_detail`;
CREATE TABLE `prd_requisition_detail` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT,
  `requisition_id` BIGINT       NOT NULL,
  `product_id`    BIGINT        NOT NULL,
  `product_code`  VARCHAR(64)   DEFAULT NULL,
  `product_name`  VARCHAR(128)  DEFAULT NULL,
  `unit_id`       BIGINT        DEFAULT NULL,
  `unit_name`     VARCHAR(32)   DEFAULT NULL,
  `qty`           DECIMAL(18,4) NOT NULL DEFAULT 0,
  `batch_no`      VARCHAR(64)   DEFAULT NULL,
  `location_id`   BIGINT        DEFAULT NULL,
  `price`         DECIMAL(18,4) DEFAULT 0,
  `amount`        DECIMAL(18,4) DEFAULT 0,
  `remark`        VARCHAR(255)  DEFAULT NULL,
  `create_by`     BIGINT        DEFAULT NULL,
  `create_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`     BIGINT        DEFAULT NULL,
  `update_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT       DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_prd_rqd_req` (`requisition_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='领料明细';

-- 6.4  工序记录
DROP TABLE IF EXISTS `prd_process`;
CREATE TABLE `prd_process` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT,
  `prd_order_id`  BIGINT        NOT NULL,
  `prd_order_no`  VARCHAR(32)   DEFAULT NULL,
  `process_no`    INT           DEFAULT 0 COMMENT '工序顺序',
  `process_name`  VARCHAR(64)   NOT NULL,
  `workshop`      VARCHAR(64)   DEFAULT NULL,
  `operator`      VARCHAR(64)   DEFAULT NULL,
  `start_time`    DATETIME      DEFAULT NULL,
  `end_time`      DATETIME      DEFAULT NULL,
  `plan_qty`      DECIMAL(18,4) DEFAULT 0,
  `actual_qty`    DECIMAL(18,4) DEFAULT 0,
  `good_qty`      DECIMAL(18,4) DEFAULT 0,
  `loss_qty`      DECIMAL(18,4) DEFAULT 0,
  `cost_amount`   DECIMAL(18,4) DEFAULT 0,
  `status`        TINYINT       DEFAULT 1,
  `remark`        VARCHAR(500)  DEFAULT NULL,
  `create_by`     BIGINT        DEFAULT NULL,
  `create_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`     BIGINT        DEFAULT NULL,
  `update_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT       DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_prd_process_order` (`prd_order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工序记录';

-- 6.5  成品入库 (从生产)
DROP TABLE IF EXISTS `prd_finished_in`;
CREATE TABLE `prd_finished_in` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT,
  `bill_no`       VARCHAR(32)   NOT NULL,
  `bill_date`     DATE          NOT NULL,
  `prd_order_id`  BIGINT        DEFAULT NULL,
  `prd_order_no`  VARCHAR(32)   DEFAULT NULL,
  `product_id`    BIGINT        NOT NULL,
  `product_code`  VARCHAR(64)   DEFAULT NULL,
  `product_name`  VARCHAR(128)  DEFAULT NULL,
  `spec`          VARCHAR(128)  DEFAULT NULL,
  `unit_id`       BIGINT        DEFAULT NULL,
  `unit_name`     VARCHAR(32)   DEFAULT NULL,
  `qty`           DECIMAL(18,4) NOT NULL DEFAULT 0,
  `price`         DECIMAL(18,4) DEFAULT 0 COMMENT '入库成本(归集)',
  `amount`        DECIMAL(18,4) DEFAULT 0,
  `warehouse_id`  BIGINT        NOT NULL,
  `location_id`   BIGINT        DEFAULT NULL,
  `batch_no`      VARCHAR(64)   DEFAULT NULL,
  `bill_status`   VARCHAR(32)   DEFAULT 'DRAFT',
  `remark`        VARCHAR(500)  DEFAULT NULL,
  `create_by`     BIGINT        DEFAULT NULL,
  `create_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`     BIGINT        DEFAULT NULL,
  `update_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT       DEFAULT 0,
  `tenant_id`     BIGINT        DEFAULT 1,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_prd_fi_bill_no` (`bill_no`, `deleted`),
  KEY `idx_prd_fi_order` (`prd_order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='成品入库单';

