-- v1.0.7 商品字段优化
-- 1. 克重单位改为 g/个
-- 2. 成本价(safety_stock/换算率也调整精度) 字段精度统一

-- 更新克重字段注释
ALTER TABLE base_product
  MODIFY COLUMN gram_weight DECIMAL(18,4) DEFAULT NULL COMMENT '克重(g/个)';
