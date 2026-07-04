-- v1.0.7 商品字段优化
-- 1. 价格合并: 三个价格合并为一个 sales_price (兼容保留 wholesale_price/vip_price 但前端不再使用)
-- 2. 字段标签调整: thickness(厚度) / width(幅宽) / density(密度) -> 长度/宽度/厚度

-- 注释调整 (label-only, 不改列名以保证兼容性)
ALTER TABLE base_product
  MODIFY COLUMN thickness DECIMAL(18,4) DEFAULT NULL COMMENT '长度(m/mm)',
  MODIFY COLUMN width DECIMAL(18,4) DEFAULT NULL COMMENT '宽度(mm)',
  MODIFY COLUMN density DECIMAL(18,6) DEFAULT NULL COMMENT '厚度(mm/um)';
