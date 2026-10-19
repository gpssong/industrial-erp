-- v1.1.101 XK 原料进货检验 — 采购计划表 (承载 ① 步上下文)
-- 一期全幂等: CREATE TABLE IF NOT EXISTS + information_schema 探测
-- 部署: mysql -uroot -p --default-character-set=utf8mb4 industrial_erp < 115_v1101_pur_inbound_plan.sql

-- ========== 1. pur_inbound_plan 采购计划表 ==========
CREATE TABLE IF NOT EXISTS `pur_inbound_plan` (
  `id`              BIGINT       NOT NULL                COMMENT '雪花 ID',
  `plan_no`         VARCHAR(32)  NOT NULL                COMMENT '计划单号 (CGJH-yyyyMMdd-NNN)',
  `plan_date`       DATE         NOT NULL                COMMENT '计划到货日期',
  `supplier_id`     BIGINT       NOT NULL                COMMENT '供应商 (base_supplier.id, 合格名录内)',
  `supplier_name`   VARCHAR(128)          DEFAULT NULL   COMMENT '冗余供应商名 (录入快照)',
  `product_id`      BIGINT       NOT NULL                COMMENT '原料商品 (base_product.id)',
  `product_code`    VARCHAR(64)           DEFAULT NULL   COMMENT '冗余商品编码',
  `product_name`    VARCHAR(128)          DEFAULT NULL   COMMENT '冗余商品名',
  `spec`            VARCHAR(128)          DEFAULT NULL   COMMENT '规格 (快照)',
  `plan_qty`        DECIMAL(18,4) NOT NULL DEFAULT 0     COMMENT '计划到货数量',
  `unit_id`         BIGINT                 DEFAULT NULL   COMMENT '单位 (base_unit.id)',
  `unit_name`       VARCHAR(32)            DEFAULT NULL   COMMENT '冗余单位名',
  `warehouse_id`    BIGINT                 DEFAULT NULL   COMMENT '建议入库仓库 (base_warehouse.id)',
  `source_type`     VARCHAR(32)            DEFAULT 'MANUAL' COMMENT '来源 MANUAL/AUTO(由采购订单生成)',
  `source_order_id` BIGINT                 DEFAULT NULL   COMMENT '源采购订单 (pur_order.id, 可空)',
  `plan_status`     VARCHAR(32) NOT NULL DEFAULT 'PLANNED' COMMENT '状态 PLANNED/ARRIVED/INSPECTED/INBOUNDED/CLOSED',
  `buyer_id`        BIGINT                 DEFAULT NULL   COMMENT '采购员 (sys_user.id)',
  `remark`          VARCHAR(512)           DEFAULT NULL,
  `version`         INT         NOT NULL DEFAULT 0        COMMENT '@Version 乐观锁',
  `create_by`       BIGINT                 DEFAULT NULL,
  `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_by`       BIGINT                 DEFAULT NULL,
  `update_time`     DATETIME               DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`         TINYINT     NOT NULL DEFAULT 0        COMMENT '@TableLogic',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_plan_no` (`plan_no`, `deleted`),
  KEY `idx_supplier` (`supplier_id`, `plan_status`),
  KEY `idx_product` (`product_id`, `plan_status`),
  KEY `idx_plan_date` (`plan_date`, `plan_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='采购计划表 (v1.1.101 XK 工作流)';
