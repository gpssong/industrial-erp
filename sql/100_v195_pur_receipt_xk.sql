-- ============================================================================
-- v1.1.95  XK 三步采购入库 — pur_receipt 加 XK 检验/附件/照片字段 (幂等)
-- ----------------------------------------------------------------------------
-- 背景: 采购入库改造成 XK 合规三步流程 (采购计划 → 到货与原料检验 → 自动入库).
--       门禁: 供应商须合格 (base_supplier.status=1) + 关联 IQC 检验单 result=PASS
--       才允许 CHECKED 入库 (先检验后入库). 检验数据复用 v1.1.90 QMS 模块
--       (mes_quality_inspection, source_bill_type='PUR_RECEIPT'), 不新建检验表.
--
-- 本 DDL 只给 pur_receipt 主表补 XK 展示/留痕字段 (检验报告号/判定结果/抽样数/
--       检验人/附件/照片), 全部幂等 (information_schema 探测, 存在则跳过, 可双站各跑一次):
--   · inspection_report_no   VARCHAR   XK 检验报告号 (如 BG-YL-0418-31, 供展示)
--   · inspection_result      VARCHAR   冗余判定结果 (PASS/FAIL/HOLD, 门禁快照)
--   · sample_qty             DECIMAL   抽样数量 (5 袋/1 kg)
--   · inspector_name         VARCHAR   放行人员
--   · report_files           TEXT      检验报告/采购合同/发票 附件 URL (JSON 数组)
--   · label_photos           TEXT      原料标签/抽样照片 URL (JSON 数组, 最多 6)
-- 不改变现有 check() 扣库存/应付逻辑, 仅新增门禁 + 字段落库.
-- 执行: mysql --default-character-set=utf8mb4 -uroot -p ... industrial_erp < sql/100_v195_pur_receipt_xk.sql
-- ============================================================================

-- 幂等加列: 用 information_schema.COLUMNS 探测, 缺哪列补哪列
SET @db := DATABASE();

SET @sql := (SELECT IF(COUNT(*)=0,
  'ALTER TABLE pur_receipt ADD COLUMN inspection_report_no VARCHAR(64) DEFAULT NULL COMMENT ''XK 检验报告号'' AFTER delivery_no',
  'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='pur_receipt' AND COLUMN_NAME='inspection_report_no');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := (SELECT IF(COUNT(*)=0,
  'ALTER TABLE pur_receipt ADD COLUMN inspection_result VARCHAR(32) DEFAULT NULL COMMENT ''IQC 判定结果快照 PASS/FAIL/HOLD'' AFTER inspection_report_no',
  'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='pur_receipt' AND COLUMN_NAME='inspection_result');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := (SELECT IF(COUNT(*)=0,
  'ALTER TABLE pur_receipt ADD COLUMN sample_qty DECIMAL(18,4) DEFAULT 0 COMMENT ''抽样数量'' AFTER inspection_result',
  'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='pur_receipt' AND COLUMN_NAME='sample_qty');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := (SELECT IF(COUNT(*)=0,
  'ALTER TABLE pur_receipt ADD COLUMN inspector_name VARCHAR(64) DEFAULT NULL COMMENT ''放行人员'' AFTER sample_qty',
  'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='pur_receipt' AND COLUMN_NAME='inspector_name');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := (SELECT IF(COUNT(*)=0,
  'ALTER TABLE pur_receipt ADD COLUMN report_files TEXT DEFAULT NULL COMMENT ''检验报告/合同/发票 附件 URL (JSON 数组)'' AFTER inspector_name',
  'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='pur_receipt' AND COLUMN_NAME='report_files');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := (SELECT IF(COUNT(*)=0,
  'ALTER TABLE pur_receipt ADD COLUMN label_photos TEXT DEFAULT NULL COMMENT ''原料标签/抽样照片 URL (JSON 数组, 最多6)'' AFTER report_files',
  'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='pur_receipt' AND COLUMN_NAME='label_photos');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 校验 (只读; 执行后跑一遍确认 6 列全在)
-- SELECT COLUMN_NAME FROM information_schema.COLUMNS
--  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pur_receipt'
--    AND COLUMN_NAME IN ('inspection_report_no','inspection_result','sample_qty','inspector_name','report_files','label_photos');
