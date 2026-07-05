-- =====================================================================
-- 二、基础资料模块 (base_*)
--    工业版特性: 商品含 厚度/幅宽/密度/色号/批次, 多单位自动换算
-- =====================================================================

-- 2.1  商品分类 (支持树形)
DROP TABLE IF EXISTS `base_product_category`;
CREATE TABLE `base_product_category` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `parent_id`   BIGINT       DEFAULT 0,
  `category_code` VARCHAR(64) NOT NULL,
  `category_name` VARCHAR(64) NOT NULL,
  `category_type` VARCHAR(32) DEFAULT 'GOODS' COMMENT 'GOODS=商品 RAW=原料 SEMI=半成品 FG=成品 SERVICE=服务',
  `sort_no`     INT          DEFAULT 0,
  `status`      TINYINT      DEFAULT 1,
  `remark`      VARCHAR(255) DEFAULT NULL,
  `create_by`   BIGINT       DEFAULT NULL,
  `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `update_by`   BIGINT       DEFAULT NULL,
  `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`     TINYINT      DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_base_category_code` (`category_code`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品分类';

-- 2.2  商品主表 (工业版核心)
DROP TABLE IF EXISTS `base_product`;
CREATE TABLE `base_product` (
  `id`              BIGINT        NOT NULL AUTO_INCREMENT,
  `product_code`    VARCHAR(64)   NOT NULL COMMENT '商品编码',
  `product_name`    VARCHAR(128)  NOT NULL COMMENT '商品名称',
  `category_id`     BIGINT        DEFAULT NULL COMMENT '分类ID',
  `product_type`    VARCHAR(32)   DEFAULT 'GOODS' COMMENT 'GOODS/RAW/SEMI/FG/SERVICE',
  `spec`            VARCHAR(128)  DEFAULT NULL COMMENT '规格',
  `model`           VARCHAR(128)  DEFAULT NULL COMMENT '型号',
  `material`        VARCHAR(64)   DEFAULT NULL COMMENT '材质(PE/PET/PVC/PP/ABS...)',
  `thickness`       DECIMAL(18,4) DEFAULT NULL COMMENT '厚度(mm/um)',
  `width`           DECIMAL(18,4) DEFAULT NULL COMMENT '幅宽(mm)',
  `density`         DECIMAL(18,6) DEFAULT NULL COMMENT '密度(g/cm3)',
  `gram_weight`     DECIMAL(18,4) DEFAULT NULL COMMENT '克重 (g/件, v1.0.7+)',
  `color_no`        VARCHAR(32)   DEFAULT NULL COMMENT '色号',
  `batch_no`        VARCHAR(64)   DEFAULT NULL COMMENT '批次号',
  `barcode`         VARCHAR(64)   DEFAULT NULL COMMENT '条形码',
  `qrcode`          VARCHAR(255)  DEFAULT NULL COMMENT '二维码',
  `main_unit_id`    BIGINT        DEFAULT NULL COMMENT '主单位ID',
  `min_unit_id`     BIGINT        DEFAULT NULL COMMENT '最小单位ID',
  `purchase_price`  DECIMAL(18,4) DEFAULT 0 COMMENT '参考采购价',
  `sales_price`     DECIMAL(18,4) DEFAULT 0 COMMENT '参考零售价',
  `cost_price`      DECIMAL(18,4) DEFAULT 0 COMMENT '单位成本价',
  `cost_price`      DECIMAL(18,4) DEFAULT 0 COMMENT '当前移动加权平均成本',
  `tax_rate`        DECIMAL(8,2)  DEFAULT 13.00 COMMENT '默认税率(%)',
  `is_weigh`        TINYINT       DEFAULT 0 COMMENT '是否称重商品 1=是',
  `is_batch`        TINYINT       DEFAULT 1 COMMENT '是否批次管理 1=是',
  `is_sn`           TINYINT       DEFAULT 0 COMMENT '是否序列号管理',
  `shelf_life_days` INT           DEFAULT 0 COMMENT '保质期天数(0=永久)',
  `safety_stock`    DECIMAL(18,4) DEFAULT 0 COMMENT '安全库存',
  `image_url`       VARCHAR(255)  DEFAULT NULL,
  `status`          TINYINT       DEFAULT 1,
  `remark`          VARCHAR(500)  DEFAULT NULL,
  `create_by`       BIGINT        DEFAULT NULL,
  `create_time`     DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`       BIGINT        DEFAULT NULL,
  `update_time`     DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`         TINYINT       DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_base_product_code` (`product_code`, `deleted`),
  UNIQUE KEY `uniq_base_product_barcode` (`barcode`, `deleted`),
  KEY `idx_base_product_category` (`category_id`),
  KEY `idx_base_product_name` (`product_name`),
  KEY `idx_base_product_material` (`material`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品信息表(工业版)';

-- 2.3  商品多单位 (支持 卷/米/公斤/张/件/千克 自动换算)
DROP TABLE IF EXISTS `base_product_unit`;
CREATE TABLE `base_product_unit` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT,
  `product_id`    BIGINT        NOT NULL,
  `unit_id`       BIGINT        NOT NULL COMMENT '单位ID',
  `unit_name`     VARCHAR(32)   DEFAULT NULL,
  `is_main`       TINYINT       DEFAULT 0 COMMENT '是否主单位 1=是',
  `conversion_rate` DECIMAL(18,6) DEFAULT 1 COMMENT '换算率: 1主单位 = conversion_rate 此单位',
  `purchase_price`  DECIMAL(18,4) DEFAULT 0,
  `sales_price`     DECIMAL(18,4) DEFAULT 0,
  `cost_price`      DECIMAL(18,4) DEFAULT 0,
  `sort_no`       INT           DEFAULT 0,
  `create_by`     BIGINT        DEFAULT NULL,
  `create_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`     BIGINT        DEFAULT NULL,
  `update_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT       DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_base_pu_product` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品多单位换算';

-- 2.4  计量单位字典
DROP TABLE IF EXISTS `base_unit`;
CREATE TABLE `base_unit` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `unit_code`  VARCHAR(32)  NOT NULL COMMENT '单位编码: JUAN/MI/KG/ZHANG/JIAN...',
  `unit_name`  VARCHAR(32)  NOT NULL COMMENT '单位名称',
  `status`     TINYINT      DEFAULT 1,
  `create_by`  BIGINT       DEFAULT NULL,
  `create_time` DATETIME    DEFAULT CURRENT_TIMESTAMP,
  `update_by`  BIGINT       DEFAULT NULL,
  `update_time` DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`    TINYINT      DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_base_unit_code` (`unit_code`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='计量单位';

-- 2.5  客户表
DROP TABLE IF EXISTS `base_customer`;
CREATE TABLE `base_customer` (
  `id`             BIGINT        NOT NULL AUTO_INCREMENT,
  `customer_code`  VARCHAR(64)   NOT NULL,
  `customer_name`  VARCHAR(128)  NOT NULL,
  `customer_type`  VARCHAR(32)   DEFAULT 'NORMAL' COMMENT 'NORMAL/VIP/DISTRIBUTOR',
  `price_level`    VARCHAR(32)   DEFAULT 'RETAIL' COMMENT 'RETAIL/WHOLESALE/VIP',
  `contact_person` VARCHAR(64)   DEFAULT NULL,
  `phone`          VARCHAR(20)   DEFAULT NULL,
  `email`          VARCHAR(64)   DEFAULT NULL,
  `address`        VARCHAR(255)  DEFAULT NULL,
  `tax_no`         VARCHAR(64)   DEFAULT NULL COMMENT '税号',
  `bank_name`      VARCHAR(64)   DEFAULT NULL,
  `bank_account`   VARCHAR(64)   DEFAULT NULL,
  `credit_limit`   DECIMAL(18,4) DEFAULT 0 COMMENT '信用额度',
  `credit_used`    DECIMAL(18,4) DEFAULT 0 COMMENT '已用额度',
  `tax_rate`       DECIMAL(8,2)  DEFAULT 13.00,
  `status`         TINYINT       DEFAULT 1,
  `remark`         VARCHAR(500)  DEFAULT NULL,
  `create_by`      BIGINT        DEFAULT NULL,
  `create_time`    DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`      BIGINT        DEFAULT NULL,
  `update_time`    DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`        TINYINT       DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_base_customer_code` (`customer_code`, `deleted`),
  KEY `idx_base_customer_name` (`customer_name`),
  KEY `idx_base_customer_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户表';

-- 2.6  供应商表
DROP TABLE IF EXISTS `base_supplier`;
CREATE TABLE `base_supplier` (
  `id`             BIGINT        NOT NULL AUTO_INCREMENT,
  `supplier_code`  VARCHAR(64)   NOT NULL,
  `supplier_name`  VARCHAR(128)  NOT NULL,
  `supplier_type`  VARCHAR(32)   DEFAULT 'NORMAL' COMMENT 'NORMAL/MANUFACTURER/AGENT',
  `is_outsource`   TINYINT       DEFAULT 0 COMMENT '是否外协厂',
  `contact_person` VARCHAR(64)   DEFAULT NULL,
  `phone`          VARCHAR(20)   DEFAULT NULL,
  `email`          VARCHAR(64)   DEFAULT NULL,
  `address`        VARCHAR(255)  DEFAULT NULL,
  `tax_no`         VARCHAR(64)   DEFAULT NULL,
  `bank_name`      VARCHAR(64)   DEFAULT NULL,
  `bank_account`   VARCHAR(64)   DEFAULT NULL,
  `tax_rate`       DECIMAL(8,2)  DEFAULT 13.00,
  `status`         TINYINT       DEFAULT 1,
  `remark`         VARCHAR(500)  DEFAULT NULL,
  `create_by`      BIGINT        DEFAULT NULL,
  `create_time`    DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`      BIGINT        DEFAULT NULL,
  `update_time`    DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`        TINYINT       DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_base_supplier_code` (`supplier_code`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应商表';

-- 2.7  仓库
DROP TABLE IF EXISTS `base_warehouse`;
CREATE TABLE `base_warehouse` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT,
  `warehouse_code` VARCHAR(64)  NOT NULL,
  `warehouse_name` VARCHAR(64)  NOT NULL,
  `warehouse_type` VARCHAR(32)  DEFAULT 'NORMAL' COMMENT 'NORMAL/RAW/FG/SEMI',
  `manager`        VARCHAR(64)  DEFAULT NULL,
  `phone`          VARCHAR(20)  DEFAULT NULL,
  `address`        VARCHAR(255) DEFAULT NULL,
  `is_default`     TINYINT      DEFAULT 0,
  `status`         TINYINT      DEFAULT 1,
  `remark`         VARCHAR(255) DEFAULT NULL,
  `create_by`      BIGINT       DEFAULT NULL,
  `create_time`    DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `update_by`      BIGINT       DEFAULT NULL,
  `update_time`    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`        TINYINT      DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_base_warehouse_code` (`warehouse_code`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓库';

-- 2.8  库区
DROP TABLE IF EXISTS `base_warehouse_area`;
CREATE TABLE `base_warehouse_area` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT,
  `warehouse_id`   BIGINT       NOT NULL,
  `area_code`      VARCHAR(64)  NOT NULL,
  `area_name`      VARCHAR(64)  NOT NULL,
  `sort_no`        INT          DEFAULT 0,
  `status`         TINYINT      DEFAULT 1,
  `create_by`      BIGINT       DEFAULT NULL,
  `create_time`    DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `update_by`      BIGINT       DEFAULT NULL,
  `update_time`    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`        TINYINT      DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_base_area_code` (`warehouse_id`, `area_code`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库区';

-- 2.9  库位
DROP TABLE IF EXISTS `base_warehouse_location`;
CREATE TABLE `base_warehouse_location` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT,
  `warehouse_id` BIGINT       NOT NULL,
  `area_id`      BIGINT       NOT NULL,
  `location_code` VARCHAR(64) NOT NULL,
  `location_name` VARCHAR(64) DEFAULT NULL,
  `barcode`      VARCHAR(64)  DEFAULT NULL,
  `status`       TINYINT      DEFAULT 1,
  `create_by`    BIGINT       DEFAULT NULL,
  `create_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `update_by`    BIGINT       DEFAULT NULL,
  `update_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`      TINYINT      DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_base_location_code` (`area_id`, `location_code`, `deleted`),
  KEY `idx_base_loc_warehouse` (`warehouse_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库位';

-- 2.10 价格等级
DROP TABLE IF EXISTS `base_price_level`;
CREATE TABLE `base_price_level` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT,
  `level_code`    VARCHAR(32)  NOT NULL,
  `level_name`    VARCHAR(64)  NOT NULL,
  `discount_rate` DECIMAL(8,4) DEFAULT 1.0000,
  `sort_no`       INT          DEFAULT 0,
  `status`        TINYINT      DEFAULT 1,
  `create_by`     BIGINT       DEFAULT NULL,
  `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `update_by`     BIGINT       DEFAULT NULL,
  `update_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT      DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_base_price_level_code` (`level_code`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='价格等级';

