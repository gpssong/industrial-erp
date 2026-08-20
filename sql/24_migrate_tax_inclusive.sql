-- =============================================================================
-- v1.1.19 含税单价口径重构 - 历史数据修复
-- 日期: 2026-08-20
--
-- 前置备份 (执行前必跑):
--   mysqldump -uroot -p industrial_erp \
--     sal_delivery sal_delivery_detail sal_return sal_return_detail \
--     pur_receipt pur_receipt_detail pur_return pur_return_detail \
--     fin_arap base_customer base_supplier \
--     > /tmp/backup_24_migrate_$(date +%Y%m%d_%H%M%S).sql
--
-- 设计原则:
--   1. 8 张单据主表/明细: tax_amount=0; total_amount=total_amount_tax;
--      amount_tax=amount. 无副作用 (这些金额不参与已核销/已开票追踪).
--   2. fin_arap.amount 是应收/应付核心字段, 已核销/已开票记录不能盲目缩
--      (否则 balance=amount-paidAmount 错位).
--   3. 策略: paidAmount=0 AND invoicedAmount=0 → 直接缩 amount;
--      paidAmount>0 OR invoicedAmount>0 → 写入审查表供人工处理.
--   4. base_customer.credit_used 不自动缩, 详情页加文案.
--
-- 公式推导:
--   旧: total_amount = sum(amount), total_amount_tax = sum(amount) + sum(tax)
--   新: total_amount = total_amount_tax = 开单金额 (= 旧 total_amount_tax)
--   旧 fin_arap.amount = 1.13×开单金额 (基于源单据 taxRate)
--   新 fin_arap.amount = 开单金额 = 旧 amount / (1+taxRate/100)
--
-- 受影响表 (8 张单据):
--   sal_delivery / sal_delivery_detail
--   sal_return / sal_return_detail
--   pur_receipt / pur_receipt_detail
--   pur_return / pur_return_detail
-- 受影响 AR/AP 表 (1 张):
--   fin_arap
-- =============================================================================

USE industrial_erp;
SET NAMES utf8mb4;

START TRANSACTION;

-- =============================================================================
-- 1. 8 张主表: tax_amount=0, total_amount=total_amount_tax
-- =============================================================================
UPDATE sal_delivery
   SET tax_amount = 0,
       total_amount = total_amount_tax
 WHERE deleted = 0;

UPDATE sal_return
   SET tax_amount = 0,
       total_amount = total_amount_tax
 WHERE deleted = 0;

UPDATE pur_receipt
   SET tax_amount = 0,
       total_amount = total_amount_tax
 WHERE deleted = 0;

UPDATE pur_return
   SET tax_amount = 0,
       total_amount = total_amount_tax
 WHERE deleted = 0;

-- =============================================================================
-- 2. 8 张明细: tax_amount=0, amount_tax=amount
-- =============================================================================
UPDATE sal_delivery_detail
   SET tax_amount = 0,
       amount_tax = amount
 WHERE deleted = 0;

UPDATE sal_return_detail
   SET tax_amount = 0,
       amount_tax = amount
 WHERE deleted = 0;

UPDATE pur_receipt_detail
   SET tax_amount = 0,
       amount_tax = amount
 WHERE deleted = 0;

UPDATE pur_return_detail
   SET tax_amount = 0,
       amount_tax = amount
 WHERE deleted = 0;

-- =============================================================================
-- 3. fin_arap: 分情况处理
--    旧 amount = 1.13 × 开单金额 (基于源单据 taxRate)
--    新 amount = 开单金额 = 旧 amount / (1+taxRate/100)
--
--    步骤 A: paidAmount>0 OR invoicedAmount>0 → 写入审查表 (不修改 amount)
--    步骤 B: paidAmount=0 AND invoicedAmount=0 → 按源单据 taxRate 缩 amount
-- =============================================================================

-- 3.1 建临时审查表
DROP TABLE IF EXISTS fin_arap_migration_review;
CREATE TABLE fin_arap_migration_review (
  id              BIGINT PRIMARY KEY,
  bill_type       VARCHAR(10),
  source_bill_type VARCHAR(30),
  source_bill_id  BIGINT,
  source_bill_no  VARCHAR(50),
  customer_id     BIGINT,
  supplier_id     BIGINT,
  amount_old      DECIMAL(18,4),
  amount_should   DECIMAL(18,4),
  tax_rate_used   DECIMAL(5,2),
  paid_amount     DECIMAL(18,4),
  invoiced_amount DECIMAL(18,4),
  reason          VARCHAR(50),
  migrated        TINYINT DEFAULT 0,
  create_time     DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_migrated (migrated),
  KEY idx_source (source_bill_type, source_bill_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='v1.1.19 fin_arap 缩 amount 审查表 (paidAmount>0 或 invoicedAmount>0)';

-- 3.2 收集所有需人工审查的 fin_arap 记录 (paidAmount>0 OR invoicedAmount>0)
-- 税率优先取源明细行的 tax_rate (主表无税率字段). 取明细行任意一条 (GROUP BY delivery_id), fallback 13.
INSERT INTO fin_arap_migration_review
  (id, bill_type, source_bill_type, source_bill_id, source_bill_no,
   customer_id, supplier_id, amount_old, amount_should, tax_rate_used,
   paid_amount, invoiced_amount, reason)
SELECT
  ar.id, ar.bill_type, ar.source_bill_type, ar.source_bill_id, ar.source_bill_no,
  ar.customer_id, ar.supplier_id, ar.amount,
  CASE WHEN COALESCE(NULLIF(src.tax_rate, 0), 13) > 0
       THEN ROUND(ar.amount / (1 + COALESCE(NULLIF(src.tax_rate, 0), 13) / 100), 4)
       ELSE ROUND(ar.amount / 1.13, 4)
  END,
  COALESCE(NULLIF(src.tax_rate, 0), 13),
  ar.paid_amount, ar.invoiced_amount,
  CASE
    WHEN ar.paid_amount > 0 AND ar.invoiced_amount > 0 THEN 'PAID_AND_INVOICED'
    WHEN ar.paid_amount > 0 THEN 'HAS_PAID'
    ELSE 'HAS_INVOICED'
  END
FROM fin_arap ar
LEFT JOIN (
  -- 取每张源单任意一条明细的税率 (delivery_id / return_id / receipt_id 是单据级, 同一单据税率应一致)
  SELECT delivery_id AS src_id, MAX(tax_rate) AS tax_rate FROM sal_delivery_detail WHERE deleted=0 GROUP BY delivery_id
  UNION ALL
  SELECT return_id   AS src_id, MAX(tax_rate) AS tax_rate FROM sal_return_detail   WHERE deleted=0 GROUP BY return_id
  UNION ALL
  SELECT receipt_id  AS src_id, MAX(tax_rate) AS tax_rate FROM pur_receipt_detail  WHERE deleted=0 GROUP BY receipt_id
  UNION ALL
  SELECT return_id   AS src_id, MAX(tax_rate) AS tax_rate FROM pur_return_detail   WHERE deleted=0 GROUP BY return_id
) src ON src.src_id = ar.source_bill_id
WHERE ar.deleted = 0
  AND (COALESCE(ar.paid_amount, 0) > 0 OR COALESCE(ar.invoiced_amount, 0) > 0);

-- 3.3 paidAmount=0 AND invoicedAmount=0 的记录: 缩 amount, 同步 balance / uninvoiced_amount
UPDATE fin_arap ar
LEFT JOIN (
  SELECT delivery_id AS src_id, MAX(tax_rate) AS tax_rate FROM sal_delivery_detail WHERE deleted=0 GROUP BY delivery_id
  UNION ALL
  SELECT return_id   AS src_id, MAX(tax_rate) AS tax_rate FROM sal_return_detail   WHERE deleted=0 GROUP BY return_id
  UNION ALL
  SELECT receipt_id  AS src_id, MAX(tax_rate) AS tax_rate FROM pur_receipt_detail  WHERE deleted=0 GROUP BY receipt_id
  UNION ALL
  SELECT return_id   AS src_id, MAX(tax_rate) AS tax_rate FROM pur_return_detail   WHERE deleted=0 GROUP BY return_id
) src ON src.src_id = ar.source_bill_id
SET
  ar.amount          = ROUND(ar.amount          / (1 + COALESCE(NULLIF(src.tax_rate, 0), 13) / 100), 4),
  ar.balance         = ROUND(ar.balance         / (1 + COALESCE(NULLIF(src.tax_rate, 0), 13) / 100), 4),
  ar.uninvoiced_amount = ROUND(ar.uninvoiced_amount / (1 + COALESCE(NULLIF(src.tax_rate, 0), 13) / 100), 4)
WHERE ar.deleted = 0
  AND COALESCE(ar.paid_amount, 0) = 0
  AND COALESCE(ar.invoiced_amount, 0) = 0;

-- =============================================================================
-- 4. 校验: 抽样 5 行, 验证 amount = qty*price 关系
-- =============================================================================
SELECT id, bill_no, total_amount, total_amount_tax, tax_amount
  FROM sal_delivery WHERE deleted = 0 ORDER BY id DESC LIMIT 5;

SELECT id, bill_no, total_amount, total_amount_tax, tax_amount
  FROM pur_receipt WHERE deleted = 0 ORDER BY id DESC LIMIT 5;

-- =============================================================================
-- 5. 收尾报告 (供 DBA 决策 review 表迁移路径)
-- =============================================================================
SELECT
  reason,
  COUNT(*) AS cnt,
  SUM(amount_old) AS sum_old,
  SUM(amount_should) AS sum_should,
  SUM(amount_should - amount_old) AS sum_diff
FROM fin_arap_migration_review
GROUP BY reason
ORDER BY reason;

-- 完成后请审查 fin_arap_migration_review 表.
-- 每条 paidAmount>0 的 AR/AP 建议走「开红字发票/收退款红冲」处理, 而非 SQL 直接缩 amount.

-- COMMIT;  -- (确认上述 UPDATE + SELECT 都成功后, 手动取消本注释执行 COMMIT)
-- ROLLBACK; -- (若发现异常, 取消本注释执行 ROLLBACK 撤销所有改动)