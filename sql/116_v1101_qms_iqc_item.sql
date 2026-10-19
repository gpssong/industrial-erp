-- v1.1.101 — IQC 检验项明细表 (1 检验单 N 检验项)
-- 与 mes_quality_defect 互补: defect 是不合格缺陷行, item 是必检项目 (PASS 也登记)
-- 一期全幂等: CREATE TABLE IF NOT EXISTS
-- 部署: mysql -uroot -p --default-character-set=utf8mb4 industrial_erp < 116_v1101_qms_iqc_item.sql

CREATE TABLE IF NOT EXISTS `qms_iqc_item` (
  `id`                   BIGINT       NOT NULL                COMMENT '雪花 ID',
  `iqc_id`               BIGINT       NOT NULL                COMMENT '所属检验单 (mes_quality_inspection.id, inspection_type=IQC)',
  `line_no`              INT          NOT NULL DEFAULT 1      COMMENT '行号 1..N',
  `item_code`            VARCHAR(64)           DEFAULT NULL   COMMENT '检验项编码 (PACKAGING/DENSITY/MFR/SENSORY/FOOD_CONTACT_HEAVY_METAL 等)',
  `item_name`            VARCHAR(64)  NOT NULL                COMMENT '检验项名称 (包装与标签/外观与感官/...)',
  `requirement`          VARCHAR(255)          DEFAULT NULL   COMMENT '要求',
  `method`               VARCHAR(64)           DEFAULT NULL   COMMENT '方法 (目测/感官/熔融指数仪/...)',
  `result_value`         VARCHAR(128)          DEFAULT NULL   COMMENT '实测值 (2.18/0.918/符合/已核验)',
  `unit`                 VARCHAR(32)            DEFAULT NULL   COMMENT '实测单位 (g/10min/g/cm³/无)',
  `pass_flag`            TINYINT      NOT NULL DEFAULT 1      COMMENT '1=合格 / 0=不合格',
  `judgment_basis`       VARCHAR(255)          DEFAULT NULL   COMMENT '判定依据 (合格范围文字描述)',
  `is_food_grade_item`   TINYINT      NOT NULL DEFAULT 0      COMMENT '是否食品级专用检验项 (base_product.is_food_grade=1 自动加载)',
  `source`               VARCHAR(32)  NOT NULL DEFAULT 'TEMPLATE' COMMENT 'TEMPLATE=模板自动生成 / MANUAL=手工录入',
  `version`              INT         NOT NULL DEFAULT 0        COMMENT '@Version 乐观锁',
  `create_by`            BIGINT                 DEFAULT NULL,
  `create_time`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_by`            BIGINT                 DEFAULT NULL,
  `update_time`          DATETIME               DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`              TINYINT     NOT NULL DEFAULT 0        COMMENT '@TableLogic',
  PRIMARY KEY (`id`),
  KEY `idx_iqc` (`iqc_id`, `deleted`, `line_no`),
  KEY `idx_item_code` (`item_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='IQC 检验项明细 (v1.1.101)';
