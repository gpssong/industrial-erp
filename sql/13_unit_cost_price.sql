-- v1.0.7 商品单位表调整: 批发价/大客户价 改为 单位成本价
ALTER TABLE base_product_unit
  DROP COLUMN wholesale_price,
  DROP COLUMN vip_price,
  ADD COLUMN cost_price DECIMAL(18,4) DEFAULT 0 COMMENT '单位成本价' AFTER sales_price;

-- 同样把 base_product 的批发价/大客户价改成成本价
ALTER TABLE base_product
  DROP COLUMN wholesale_price,
  DROP COLUMN vip_price;
