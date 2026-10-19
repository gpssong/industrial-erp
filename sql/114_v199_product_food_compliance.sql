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
