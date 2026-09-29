-- ============================================================
-- v1.1.67: 历史销售出库单明细 unit_id/unit_name 回填
-- 背景: App 扫码出库 (及早期客户端) 提交明细时未带 unit_id, 导致
--       sal_delivery_detail.unit_id/unit_name 落 0/NULL, 打印送货单
--       "单位" 栏回退异常. 商品编码/名称/规格 (product_code/name/spec)
--       实际已落库 (0 NULL), 只需补单位.
-- 数据源优先级 (逐级兜底):
--   1. base_product_unit 里 is_main=1 且 unit_id<>0 的行 (最可靠)
--   2. base_product.main_unit_id
--   3. base_product_unit 里任意一条 unit_id<>0 的行 (商品有单位但没打主标记时救回)
-- 幂等: 只改 (unit_id IS NULL OR unit_id=0) 的行, 跑多遍无副作用.
-- 双站 MySQL 主从 replication 曾断开, 需 home + 飞牛 各跑一次.
--
-- ⚠️ 无法回填的剩余行 (本脚本改不到的): 商品在 base_product_unit 里的
--    unit_id 历史全为 0 (v1.1.16+ 遗留脏数据, "所有单位全为 0") 或彻底无单位,
--    本脚本的 COALESCE(...)<>0 守门会正确跳过, 不能凭空造 unit_id.
--    这类需先单独做 v1.1.16 base_product_unit.unit_id 0→真实 base_unit.id
--    的回填 (按 unit_name 匹配 active base_unit), 再重跑本脚本.
--    彻底无单位商品 (如 jydbd 机用打包带) 需业务在 PC 商品页补主单位.
-- ============================================================

-- 预演: 看将被更新的行
SELECT d.id, p.product_code, p.product_name,
       COALESCE(im.unit_id, p.main_unit_id, any_u.unit_id) AS new_unit
FROM sal_delivery_detail d
JOIN base_product p ON p.id = d.product_id AND p.deleted = 0
LEFT JOIN (SELECT product_id, MIN(unit_id) AS unit_id
           FROM base_product_unit WHERE deleted=0 AND is_main=1 AND unit_id<>0 GROUP BY product_id) im
       ON im.product_id = d.product_id
LEFT JOIN (SELECT product_id, MIN(unit_id) AS unit_id
           FROM base_product_unit WHERE deleted=0 AND unit_id<>0 GROUP BY product_id) any_u
       ON any_u.product_id = d.product_id
WHERE d.deleted = 0 AND (d.unit_id IS NULL OR d.unit_id = 0)
ORDER BY p.product_code;

-- 正式回填
UPDATE sal_delivery_detail d
JOIN base_product p ON p.id = d.product_id AND p.deleted = 0
LEFT JOIN (SELECT product_id, MIN(unit_id) AS unit_id
           FROM base_product_unit WHERE deleted=0 AND is_main=1 AND unit_id<>0 GROUP BY product_id) im
       ON im.product_id = d.product_id
LEFT JOIN (SELECT product_id, MIN(unit_id) AS unit_id
           FROM base_product_unit WHERE deleted=0 AND unit_id<>0 GROUP BY product_id) any_u
       ON any_u.product_id = d.product_id
SET d.unit_id = COALESCE(im.unit_id, p.main_unit_id, any_u.unit_id),
    d.unit_name = (SELECT u.unit_name FROM base_unit u
                   WHERE u.id = COALESCE(im.unit_id, p.main_unit_id, any_u.unit_id)
                     AND u.deleted = 0
                   LIMIT 1)
WHERE d.deleted = 0
  AND (d.unit_id IS NULL OR d.unit_id = 0)
  AND COALESCE(im.unit_id, p.main_unit_id, any_u.unit_id) IS NOT NULL
  AND COALESCE(im.unit_id, p.main_unit_id, any_u.unit_id) <> 0;

-- 校验: 剩余 still-bad 行 = base_product_unit.unit_id 全为 0 (历史脏数据)
--   或彻底无单位的商品 (业务需补主数据后重跑). 见文件头说明.
SELECT COUNT(*) AS still_bad_unit FROM sal_delivery_detail
WHERE deleted = 0 AND (unit_id IS NULL OR unit_id = 0);

-- 附: 剩余不可回填商品清单 (业务在 PC 商品页补主单位 / 或做 base_product_unit.unit_id
--   0→真实 unit 修复后重跑本脚本)
SELECT p.product_code, p.product_name, p.main_unit_id,
       (SELECT GROUP_CONCAT(DISTINCT bu.unit_name) FROM base_product_unit bu
        WHERE bu.product_id=p.id AND bu.deleted=0) AS ppu_units,
       COUNT(*) AS blocked_rows
FROM sal_delivery_detail d
JOIN base_product p ON p.id=d.product_id AND p.deleted=0
WHERE d.deleted=0 AND (d.unit_id IS NULL OR d.unit_id=0)
GROUP BY p.id, p.product_code, p.product_name, p.main_unit_id
ORDER BY blocked_rows DESC, p.product_code;
