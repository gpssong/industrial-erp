-- =============================================================================
-- v1.1.19 修正：从明细行重新计算 total_amount / total_amount_tax
-- 问题：之前的迁移脚本只做了 total_amount = total_amount_tax，但两者都是旧
--       的双计税值（1.13×开单金额），没有从明细行重新汇总
-- 修复：直接 UPDATE 从 sal_*_detail.amount 汇总重新计算
-- 执行时间: 2026-08-20
-- =============================================================================

USE industrial_erp;
SET NAMES utf8mb4;

START TRANSACTION;

-- 1. sal_delivery: 从明细行重新计算（含折扣/抹零）
UPDATE sal_delivery d
SET
  d.total_amount = (
    SELECT COALESCE(SUM(dd.amount), 0) - IFNULL(d.discount_amount,0) - IFNULL(d.tail_amount,0)
    FROM sal_delivery_detail dd
    WHERE dd.delivery_id = d.id AND dd.deleted = 0
  ),
  d.total_amount_tax = (
    SELECT COALESCE(SUM(dd.amount), 0) - IFNULL(d.discount_amount,0) - IFNULL(d.tail_amount,0)
    FROM sal_delivery_detail dd
    WHERE dd.delivery_id = d.id AND dd.deleted = 0
  )
WHERE d.deleted = 0;

-- 2. sal_return: 无折扣/抹零列
UPDATE sal_return d
SET
  d.total_amount = (
    SELECT COALESCE(SUM(dd.amount), 0)
    FROM sal_return_detail dd
    WHERE dd.return_id = d.id AND dd.deleted = 0
  ),
  d.total_amount_tax = (
    SELECT COALESCE(SUM(dd.amount), 0)
    FROM sal_return_detail dd
    WHERE dd.return_id = d.id AND dd.deleted = 0
  )
WHERE d.deleted = 0;

-- 3. pur_receipt: 无折扣/抹零列
UPDATE pur_receipt d
SET
  d.total_amount = (
    SELECT COALESCE(SUM(dd.amount), 0)
    FROM pur_receipt_detail dd
    WHERE dd.receipt_id = d.id AND dd.deleted = 0
  ),
  d.total_amount_tax = (
    SELECT COALESCE(SUM(dd.amount), 0)
    FROM pur_receipt_detail dd
    WHERE dd.receipt_id = d.id AND dd.deleted = 0
  )
WHERE d.deleted = 0;

-- 4. pur_return: 无折扣/抹零列
UPDATE pur_return d
SET
  d.total_amount = (
    SELECT COALESCE(SUM(dd.amount), 0)
    FROM pur_return_detail dd
    WHERE dd.return_id = d.id AND dd.deleted = 0
  ),
  d.total_amount_tax = (
    SELECT COALESCE(SUM(dd.amount), 0)
    FROM pur_return_detail dd
    WHERE dd.return_id = d.id AND dd.deleted = 0
  )
WHERE d.deleted = 0;

-- 5. fin_arap: 未核销未开票的记录，按新口径（开单金额）重新计算
UPDATE fin_arap ar
LEFT JOIN (
  SELECT delivery_id AS src_id,
         SUM(amount) - IFNULL(discount_amount,0) - IFNULL(tail_amount,0) AS bill_amount
  FROM sal_delivery_detail dd
  JOIN sal_delivery d ON d.id = dd.delivery_id
  WHERE dd.deleted=0 AND d.deleted=0
  GROUP BY dd.delivery_id
  UNION ALL
  SELECT return_id, SUM(amount)
  FROM sal_return_detail dd
  JOIN sal_return d ON d.id = dd.return_id
  WHERE dd.deleted=0 AND d.deleted=0
  GROUP BY dd.return_id
  UNION ALL
  SELECT receipt_id, SUM(amount)
  FROM pur_receipt_detail dd
  JOIN pur_receipt d ON d.id = dd.receipt_id
  WHERE dd.deleted=0 AND d.deleted=0
  GROUP BY dd.receipt_id
  UNION ALL
  SELECT return_id, SUM(amount)
  FROM pur_return_detail dd
  JOIN pur_return d ON d.id = dd.return_id
  WHERE dd.deleted=0 AND d.deleted=0
  GROUP BY dd.return_id
) src ON src.src_id = ar.source_bill_id
SET
  ar.amount = COALESCE(src.bill_amount, ar.amount),
  ar.balance = COALESCE(src.bill_amount, ar.balance),
  ar.uninvoiced_amount = COALESCE(src.bill_amount, ar.uninvoiced_amount)
WHERE ar.deleted = 0
  AND COALESCE(ar.paid_amount, 0) = 0
  AND COALESCE(ar.invoiced_amount, 0) = 0;

-- 6. 校验
SELECT d.id, d.bill_no, d.total_amount, d.total_amount_tax,
       (SELECT SUM(dd.amount) FROM sal_delivery_detail dd
        WHERE dd.delivery_id=d.id AND dd.deleted=0) as detail_sum
FROM sal_delivery d WHERE d.deleted=0 ORDER BY d.id DESC LIMIT 10;

SELECT 'sal_delivery不一致' as check_item, COUNT(*) as cnt
FROM sal_delivery WHERE deleted=0 AND total_amount != total_amount_tax
UNION ALL SELECT 'pur_receipt不一致', COUNT(*) FROM pur_receipt WHERE deleted=0 AND total_amount != total_amount_tax
UNION ALL SELECT 'sal_return不一致', COUNT(*) FROM sal_return WHERE deleted=0 AND total_amount != total_amount_tax
UNION ALL SELECT 'pur_return不一致', COUNT(*) FROM pur_return WHERE deleted=0 AND total_amount != total_amount_tax;

COMMIT;
