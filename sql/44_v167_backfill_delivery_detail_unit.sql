-- ============================================================
-- v1.1.67: 历史销售出库单明细 unit_id/unit_name 回填
-- 背景: App 扫码出库 (及早期客户端) 提交明细时未带 unit_id, 导致
--       sal_delivery_detail.unit_id/unit_name 落 0/NULL, 打印送货单
--       "单位" 栏回退异常. 商品编码/名称/规格 (product_code/name/spec)
--       实际已落库 (0 NULL), 只需补单位.
-- 数据源优先级: base_product_unit (is_main=1) > base_product.main_unit_id
-- 幂等: 只改 (unit_id IS NULL OR unit_id=0) 的行, 跑多遍无副作用.
-- 双站 MySQL 主从 replication 曾断开, 需 home + 飞牛 各跑一次.
-- ============================================================

UPDATE sal_delivery_detail d
JOIN base_product p ON p.id = d.product_id AND p.deleted = 0
LEFT JOIN base_product_unit bu ON bu.product_id = d.product_id AND bu.is_main = 1 AND bu.deleted = 0
SET d.unit_id   = COALESCE(bu.unit_id, p.main_unit_id),
    d.unit_name = (SELECT u.unit_name FROM base_unit u
                    WHERE u.id = COALESCE(bu.unit_id, p.main_unit_id) AND u.deleted = 0
                    LIMIT 1)
WHERE d.deleted = 0
  AND (d.unit_id IS NULL OR d.unit_id = 0)
  AND COALESCE(bu.unit_id, p.main_unit_id) IS NOT NULL
  AND COALESCE(bu.unit_id, p.main_unit_id) <> 0;

-- 校验: 剩余 still-bad 行数 = product 无主单位映射 + product 已删 的无法回填行
SELECT COUNT(*) AS still_bad_unit FROM sal_delivery_detail
WHERE deleted = 0 AND (unit_id IS NULL OR unit_id = 0);
