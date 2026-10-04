-- =====================================================================
-- 四十六、v1.1.68-hotfix — dyd975007 成本价回填 (移动加权平均成本链修复)
-- =====================================================================
-- 背景: 销售出库"成本 0 / 毛利≈销售额"根因在采购入库。dyd975007
--       (带鱼袋9*75*0.07) 的 4 张采购入库单 (RKP202609290002/0006,
--       RKP202610020003, RKP202610030001) 成本价 price 全部填 0,
--       StockService.inStock() 移动加权平均 avg_cost 从头就是 0,
--       导致 outStock() 算出的出库成本 = avg_cost*qty = 0.
--
-- 本脚本把 dyd975007 的成本价回填为 0.0546 元/只 (用户 2026-10-04 确认),
-- 覆盖: inv_stock / base_product / 4 张采购入库明细+主表 / 2 张已审核
--       销售出库单. 纯数据修复, 无 schema DDL.
--
-- 用法 (home 生产库 192.168.0.150, utf8mb4):
--   mysql -uroot -perp_root_pwd --default-character-set=utf8mb4 \
--     industrial_erp < sql/46_v168hotfix_cost_backfill.sql
--
-- 飞牛热备库 (192.168.0.32): 无 dyd975007 业务数据 (0 行), 无需跑.
--
-- 幂等: 备份表 IF NOT EXISTS; UPDATE 语句可重复跑 (值固定 0.0546).
-- 回滚: 用 4 张 *_bak_v168 备份表 (本脚本第 1 段自动建, 保留旧值).
-- =====================================================================

USE `industrial_erp`;

SET @COST := 0.0546;   -- dyd975007 采购成本单价 (元/只)

-- 1) 备份 (保留修复前旧值, 可回滚). IF NOT EXISTS 保证已建过不报错.
CREATE TABLE IF NOT EXISTS inv_stock_bak_v168_avg_cost AS
  SELECT * FROM inv_stock WHERE product_code='dyd975007';
CREATE TABLE IF NOT EXISTS base_product_bak_v168_cost AS
  SELECT * FROM base_product WHERE product_code='dyd975007';
CREATE TABLE IF NOT EXISTS pur_receipt_detail_bak_v168 AS
  SELECT d.* FROM pur_receipt_detail d
  JOIN pur_receipt r ON r.id=d.receipt_id
  WHERE r.bill_no IN ('RKP202609290002','RKP202609290006','RKP202610020003','RKP202610030001');
CREATE TABLE IF NOT EXISTS sal_delivery_bak_v168_cost AS
  SELECT * FROM sal_delivery WHERE bill_no IN ('CKP202610040001','CKP202610030001');

-- 2) 库存表: avg_cost=0.0546, total_cost 重算为 0.0546*qty
UPDATE inv_stock
SET avg_cost=@COST, total_cost=ROUND(@COST*qty,4)
WHERE product_code='dyd975007' AND deleted=0;

-- 3) 商品主数据成本价
UPDATE base_product SET cost_price=@COST WHERE product_code='dyd975007';

-- 4) 采购入库明细成本价补齐
UPDATE pur_receipt_detail d JOIN pur_receipt r ON r.id=d.receipt_id
SET d.price=@COST,
    d.amount=ROUND(@COST*d.qty,4),
    d.amount_tax=ROUND(@COST*d.qty,4)
WHERE r.bill_no IN ('RKP202609290002','RKP202609290006','RKP202610020003','RKP202610030001')
  AND d.deleted=0;

-- 采购入库主表金额同步重算
UPDATE pur_receipt r
SET r.total_amount=(SELECT SUM(d.amount) FROM pur_receipt_detail d WHERE d.receipt_id=r.id AND d.deleted=0),
    r.total_amount_tax=(SELECT SUM(d.amount_tax) FROM pur_receipt_detail d WHERE d.receipt_id=r.id AND d.deleted=0)
WHERE r.bill_no IN ('RKP202609290002','RKP202609290006','RKP202610020003','RKP202610030001');

-- 5) 已审核销售出库单成本/毛利补齐 + 明细行成本同步
UPDATE sal_delivery
SET cost_amount=ROUND(@COST*total_qty,4),
    profit_amount=total_amount-ROUND(@COST*total_qty,4)
WHERE bill_no IN ('CKP202610040001','CKP202610030001');
UPDATE sal_delivery_detail d JOIN sal_delivery h ON h.id=d.delivery_id
SET d.cost_price=@COST, d.cost_amount=ROUND(@COST*d.qty,4)
WHERE h.bill_no IN ('CKP202610040001','CKP202610030001');

-- 6) 校验 — 跑完确认:
-- SELECT product_code,qty,avg_cost,total_cost FROM inv_stock WHERE product_code='dyd975007';
--   预期 avg_cost=0.0546
-- SELECT bill_no,cost_amount,profit_amount FROM sal_delivery
--   WHERE bill_no IN ('CKP202610040001','CKP202610030001');
--   预期 36000 只 → cost=1965.60, profit=230.40 (毛利 2196-1965.60), 毛利率≈10.49%

-- =====================================================================
-- 回滚 (用备份表还原, 慎用 — 会丢掉 0.0546 的正确成本):
-- UPDATE inv_stock s JOIN inv_stock_bak_v168_avg_cost b ON b.id=s.id
--   SET s.avg_cost=b.avg_cost, s.total_cost=b.total_cost WHERE s.product_code='dyd975007';
-- UPDATE base_product p JOIN base_product_bak_v168_cost b ON b.id=p.id
--   SET p.cost_price=b.cost_price WHERE p.product_code='dyd975007';
-- =====================================================================
