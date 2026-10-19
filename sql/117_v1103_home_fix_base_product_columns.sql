-- 合并灌库脚本 (幂等): 108 + 109 + 114 + 116 (v1.1.99~v1.1.101 food grade + IQC 字段)
-- 灌法: docker cp 此文件进 erp-mysql 容器, 再 docker exec mysql -uroot -perp_root_pwd --default-character-set=utf8mb4 -D industrial_erp < fix.sql
-- v1.1.99: 商品表加 食品级 开关 (XK 食品接触合规字段)
-- 用户反馈 2026-10-14: 新增商品弹窗需要 食品级 开关 (亮=食品级, 暗=工业品)
-- 沿用 isBatch/isSn 的 Integer TINYINT 范式, 默认 0 (工业品)
-- 兼容 MySQL 5.7 + 8.0, information_schema 守门幂等
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'base_product' AND COLUMN_NAME = 'is_food_grade');
SET @sql := IF(@col_exists = 0,
  'ALTER TABLE base_product ADD COLUMN is_food_grade TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''食品级 (XK16-204 食品接触原料): 0=工业品 1=食品级'' AFTER is_sn',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- v1.1.99: 生产工单 (prd_order) 加 开工时间 + 原料批号 字段
-- 用户反馈 2026-10-14: 新增工单弹窗需要 开工时间(默认今日) + 原料批号(食品级成品必填, XK 食品接触合规)
-- startDate 沿用 billDate 同 LocalDate 语义, 但语义上是计划开工 vs 单据日期, 拆开更清晰
-- batchNo: 食品级成品 (base_product.is_food_grade=1) 必填, 普通成品可选
-- 兼容 MySQL 5.7 + 8.0, information_schema 守门幂等
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'prd_order' AND COLUMN_NAME = 'start_date');
SET @sql := IF(@col_exists = 0,
  'ALTER TABLE prd_order ADD COLUMN start_date DATE DEFAULT NULL COMMENT ''计划开工日期 (默认 = billDate)'' AFTER bill_date',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'prd_order' AND COLUMN_NAME = 'batch_no');
SET @sql := IF(@col_exists = 0,
  'ALTER TABLE prd_order ADD COLUMN batch_no VARCHAR(64) DEFAULT NULL COMMENT ''原料批号 (食品级成品 XK16-204 必填)'' AFTER warehouse_id',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- v1.1.100: 食品接触合规数据补齐 (XK16-204 / 食品相关产品生产许可证 / GB 4806 系列)
-- 对照 PE 膜食品包装监管要求, 把之前只在前端 mock 的合规字段结构化落库:
--   A. base_product     食品合规 4 列: 执行标准号 / 备案编号 / 总迁移量限值 / 食品级用途
--   B. base_supplier    食品资质 3 列: 食品级证明编号 / 资质有效期 / 资质类型
--   C. mes_quality_inspection OQC 成品单 7 列: 审核/批准 2 签人(含姓名冗余) + 留样编号/位置/期限
-- 决策: 限值/标准号字段全部建为可编辑空字段 (DEFAULT NULL), 由用户填真实 GB 号/限值,
--       不硬编码可能过期的数值 (本环境外网受限, 不写死具体限值)。
-- 兼容 MySQL 5.7 + 8.0, information_schema 守门幂等 (仿 sql/108/109)。
-- 零新表, 全部在现有表加列; 回滚 = 删这些列。

-- ============================================================
-- A. base_product 食品合规 4 列
-- ============================================================
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'base_product' AND COLUMN_NAME = 'food_use_desc');
SET @sql := IF(@c = 0, 'ALTER TABLE base_product ADD COLUMN food_use_desc VARCHAR(128) DEFAULT NULL COMMENT ''食品级用途/适用范围 (如 食品接触/食品保鲜袋)'' AFTER is_food_grade', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'base_product' AND COLUMN_NAME = 'food_exec_std');
SET @sql := IF(@c = 0, 'ALTER TABLE base_product ADD COLUMN food_exec_std VARCHAR(64) DEFAULT NULL COMMENT ''执行标准号 (如 GB 4806.7 食品接触材料, 用户填)'' AFTER food_use_desc', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'base_product' AND COLUMN_NAME = 'food_filing_no');
SET @sql := IF(@c = 0, 'ALTER TABLE base_product ADD COLUMN food_filing_no VARCHAR(64) DEFAULT NULL COMMENT ''备案编号 (食品相关产品生产许可证/备案)'' AFTER food_exec_std', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'base_product' AND COLUMN_NAME = 'food_migration_limit');
SET @sql := IF(@c = 0, 'ALTER TABLE base_product ADD COLUMN food_migration_limit DECIMAL(10,3) DEFAULT NULL COMMENT ''总迁移量限值 mg/dm2 (GB 4806.1, 用户填)'' AFTER food_filing_no', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'base_product' AND COLUMN_NAME = 'food_use_desc');
SET @sql := IF(@c = 0, 'ALTER TABLE base_product ADD COLUMN food_use_desc VARCHAR(128) DEFAULT NULL COMMENT ''食品级用途/适用范围 (如 食品接触/食品保鲜袋)'' AFTER is_food_grade', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ============================================================
-- B. base_supplier 食品资质 3 列
-- ============================================================
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'base_supplier' AND COLUMN_NAME = 'food_cert_no');
SET @sql := IF(@c = 0, 'ALTER TABLE base_supplier ADD COLUMN food_cert_no VARCHAR(64) DEFAULT NULL COMMENT ''食品级证明编号 (食品接触原料证明/食品相关产品生产许可证号)'' AFTER tax_rate', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'base_supplier' AND COLUMN_NAME = 'food_cert_exp');
SET @sql := IF(@c = 0, 'ALTER TABLE base_supplier ADD COLUMN food_cert_exp DATE DEFAULT NULL COMMENT ''食品资质有效期至'' AFTER food_cert_no', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'base_supplier' AND COLUMN_NAME = 'food_cert_type');
SET @sql := IF(@c = 0, 'ALTER TABLE base_supplier ADD COLUMN food_cert_type VARCHAR(32) DEFAULT NULL COMMENT ''资质类型 (食品级原料/食品相关产品生产许可/其他)'' AFTER food_cert_exp', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ============================================================
-- C. mes_quality_inspection OQC 成品单 3 签 + 留样 7 列
--    检验人已有 (inspector/inspector_name), 补 审核/批准 2 签 + 留样 3 字段
-- ============================================================
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mes_quality_inspection' AND COLUMN_NAME = 'reviewer');
SET @sql := IF(@c = 0, 'ALTER TABLE mes_quality_inspection ADD COLUMN reviewer BIGINT DEFAULT NULL COMMENT ''审核人 user_id (OQC 3 签: 检验/审核/批准)'' AFTER inspector_name', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mes_quality_inspection' AND COLUMN_NAME = 'reviewer_name');
SET @sql := IF(@c = 0, 'ALTER TABLE mes_quality_inspection ADD COLUMN reviewer_name VARCHAR(64) DEFAULT NULL COMMENT ''审核人姓名冗余'' AFTER reviewer', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mes_quality_inspection' AND COLUMN_NAME = 'approver');
SET @sql := IF(@c = 0, 'ALTER TABLE mes_quality_inspection ADD COLUMN approver BIGINT DEFAULT NULL COMMENT ''批准人 user_id (OQC 3 签)'' AFTER reviewer_name', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mes_quality_inspection' AND COLUMN_NAME = 'approver_name');
SET @sql := IF(@c = 0, 'ALTER TABLE mes_quality_inspection ADD COLUMN approver_name VARCHAR(64) DEFAULT NULL COMMENT ''批准人姓名冗余'' AFTER approver', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mes_quality_inspection' AND COLUMN_NAME = 'retain_no');
SET @sql := IF(@c = 0, 'ALTER TABLE mes_quality_inspection ADD COLUMN retain_no VARCHAR(64) DEFAULT NULL COMMENT ''留样编号 (缺则按 LY-{billNo} 生成)'' AFTER approver_name', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mes_quality_inspection' AND COLUMN_NAME = 'retain_loc');
SET @sql := IF(@c = 0, 'ALTER TABLE mes_quality_inspection ADD COLUMN retain_loc VARCHAR(64) DEFAULT NULL COMMENT ''留样位置 (如 质量室/留样柜 A-06)'' AFTER retain_no', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mes_quality_inspection' AND COLUMN_NAME = 'retain_due_date');
SET @sql := IF(@c = 0, 'ALTER TABLE mes_quality_inspection ADD COLUMN retain_due_date DATE DEFAULT NULL COMMENT ''留样期限 (缺则 check 时 +24 月)'' AFTER retain_loc', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

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
