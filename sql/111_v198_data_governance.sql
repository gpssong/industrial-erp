-- =====================================================================
-- v1.1.98 数据治理 — 商品主单位补全 + 库存单位回填 (幂等, 不动 product_type)
--
-- 背景 (飞牛 v1.1.95 审计 P2): 127 商品仅 1 个 FG, 7 个商品缺主单位 (is_main=1),
-- 大量 inv_stock.unit_id=NULL (v1.1.16 历史脏数据) → 库存/单位换算体系不完整.
--
-- 本 SQL 只做两件安全的事:
--   1) 缺主单位的商品: 从该商品现有 base_product_unit 行里挑一条 (或 默认卷) 补 is_main=1
--   2) inv_stock.unit_id=NULL: 按商品 main_unit_id 回填
-- 商品类型归类 (GOODS→FG/RAW/SEMI) 需用户勾选, 单独走 sql/112, 本 SQL 不动.
-- =====================================================================

-- 1) 缺主单位的商品: 每个 product 若无 is_main=1 的 unit 行, 取该 product 现有 unit 行的第一条设 is_main=1
--    (MySQL 不允许 UPDATE 子查询引用同表, 改用 LEFT JOIN 判"是否已有主单位"; 守门: 已有主单位不动)
UPDATE base_product_unit u
LEFT JOIN (
    SELECT product_id FROM base_product_unit
    WHERE deleted = 0 AND is_main = 1
    GROUP BY product_id
) hm ON hm.product_id = u.product_id
SET u.is_main = 1
WHERE u.deleted = 0
  AND hm.product_id IS NULL          -- 该 product 没有任何 is_main=1 的 unit 行
  AND u.unit_id IS NOT NULL;

--    特例: 商品 base_product_unit 整表无行 (7 个里的极端情况) → 插一条"卷"主单位兜底
--    (先确保 base_unit 有 卷 行; v1.1.16 已 seed 卷=JUAN)
INSERT INTO base_product_unit (product_id, unit_id, unit_name, is_main, conversion_rate, deleted)
SELECT p.id, bunit.id, bunit.unit_name, 1, 1.0000, 0
FROM base_product p
CROSS JOIN base_unit bunit
WHERE p.deleted = 0
  AND bunit.unit_name = '卷' AND bunit.unit_code = 'JUAN' AND bunit.deleted = 0
  AND NOT EXISTS (
    SELECT 1 FROM base_product_unit u WHERE u.product_id = p.id AND u.deleted = 0
  );

-- 1b) 回填 base_product.main_unit_id (若空): 取该商品 is_main=1 的 unit_id
UPDATE base_product p
SET p.main_unit_id = (
    SELECT u.unit_id FROM base_product_unit u
    WHERE u.product_id = p.id AND u.is_main = 1 AND u.deleted = 0
    ORDER BY u.id LIMIT 1
)
WHERE p.deleted = 0
  AND (p.main_unit_id IS NULL OR p.main_unit_id = 0);

-- 2) inv_stock.unit_id=NULL → 回填商品 main_unit_id (幂等, 可重跑)
UPDATE inv_stock s
JOIN base_product p ON p.id = s.product_id AND p.deleted = 0
SET s.unit_id = p.main_unit_id
WHERE s.deleted = 0
  AND (s.unit_id IS NULL OR s.unit_id = 0)
  AND p.main_unit_id IS NOT NULL AND p.main_unit_id > 0;

-- 2b) 回填 inv_stock.unit_name (若空): 取 base_unit 名 (best-effort, 不强制)
UPDATE inv_stock s
JOIN base_product p ON p.id = s.product_id AND p.deleted = 0
LEFT JOIN base_unit u ON u.id = s.unit_id AND u.deleted = 0
SET s.unit_name = u.unit_name
WHERE s.deleted = 0
  AND s.unit_id IS NOT NULL AND s.unit_id > 0
  AND (s.unit_name IS NULL OR s.unit_name = '');
