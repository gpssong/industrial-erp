-- =====================================================================
-- 九、扫码/序列号/附件等辅助表
-- =====================================================================

-- 9.1  序列号台账
DROP TABLE IF EXISTS `inv_serial_no`;
CREATE TABLE `inv_serial_no` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT,
  `sn_no`         VARCHAR(128)  NOT NULL,
  `product_id`    BIGINT        NOT NULL,
  `product_code`  VARCHAR(64)   DEFAULT NULL,
  `product_name`  VARCHAR(128)  DEFAULT NULL,
  `batch_no`      VARCHAR(64)   DEFAULT NULL,
  `warehouse_id`  BIGINT        DEFAULT NULL,
  `location_id`   BIGINT        DEFAULT NULL,
  `status`        VARCHAR(32)   DEFAULT 'IN_STOCK' COMMENT 'IN_STOCK/OUT/RETURN/SCRAP',
  `in_bill_type`  VARCHAR(32)   DEFAULT NULL,
  `in_bill_id`    BIGINT        DEFAULT NULL,
  `in_time`       DATETIME      DEFAULT NULL,
  `out_bill_type` VARCHAR(32)   DEFAULT NULL,
  `out_bill_id`   BIGINT        DEFAULT NULL,
  `out_time`      DATETIME      DEFAULT NULL,
  `create_by`     BIGINT        DEFAULT NULL,
  `create_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`     BIGINT        DEFAULT NULL,
  `update_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT       DEFAULT 0,
  `tenant_id`     BIGINT        DEFAULT 1,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_inv_sn` (`sn_no`, `deleted`),
  KEY `idx_inv_sn_product` (`product_id`),
  KEY `idx_inv_sn_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品序列号';

-- 9.2  单据附件
DROP TABLE IF EXISTS `sys_bill_attachment`;
CREATE TABLE `sys_bill_attachment` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `bill_type`   VARCHAR(32)  NOT NULL,
  `bill_id`     BIGINT       NOT NULL,
  `file_name`   VARCHAR(255) NOT NULL,
  `file_path`   VARCHAR(255) NOT NULL,
  `file_size`   BIGINT       DEFAULT 0,
  `file_ext`    VARCHAR(16)  DEFAULT NULL,
  `upload_by`   BIGINT       DEFAULT NULL,
  `upload_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `deleted`     TINYINT      DEFAULT 0,
  `tenant_id`   BIGINT       DEFAULT 1,
  PRIMARY KEY (`id`),
  KEY `idx_sba_bill` (`bill_type`, `bill_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单据附件';

-- 9.3  通知公告
DROP TABLE IF EXISTS `sys_notice`;
CREATE TABLE `sys_notice` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `title`      VARCHAR(128) NOT NULL,
  `content`    TEXT         DEFAULT NULL,
  `notice_type` TINYINT     DEFAULT 1 COMMENT '1=通知 2=公告',
  `status`     TINYINT      DEFAULT 0 COMMENT '0=草稿 1=已发布',
  `create_by`  BIGINT       DEFAULT NULL,
  `create_time` DATETIME    DEFAULT CURRENT_TIMESTAMP,
  `update_by`  BIGINT       DEFAULT NULL,
  `update_time` DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`    TINYINT      DEFAULT 0,
  `tenant_id`  BIGINT       DEFAULT 1,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知公告';

-- 9.4  定时任务日志
DROP TABLE IF EXISTS `sys_job_log`;
CREATE TABLE `sys_job_log` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `job_name`   VARCHAR(64)  NOT NULL,
  `invoke_target` VARCHAR(255) DEFAULT NULL,
  `status`     TINYINT      DEFAULT 1,
  `error_msg`  TEXT         DEFAULT NULL,
  `cost_time`  BIGINT       DEFAULT 0,
  `create_time` DATETIME    DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_sjl_job` (`job_name`),
  KEY `idx_sjl_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务日志';

-- 9.5  报表快照 (用于大屏/日结)
DROP TABLE IF EXISTS `rpt_daily_snapshot`;
CREATE TABLE `rpt_daily_snapshot` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT,
  `snap_date`     DATE          NOT NULL,
  `total_sales`   DECIMAL(18,4) DEFAULT 0,
  `total_cost`    DECIMAL(18,4) DEFAULT 0,
  `total_profit`  DECIMAL(18,4) DEFAULT 0,
  `total_purchase` DECIMAL(18,4) DEFAULT 0,
  `total_receipt`  DECIMAL(18,4) DEFAULT 0,
  `total_payment`  DECIMAL(18,4) DEFAULT 0,
  `total_in_qty`   DECIMAL(18,4) DEFAULT 0,
  `total_out_qty`  DECIMAL(18,4) DEFAULT 0,
  `create_time`    DATETIME      DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_rpt_daily` (`snap_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='经营日报';

