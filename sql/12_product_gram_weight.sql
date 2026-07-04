-- v1.0.7 补充: 商品增加克重字段
ALTER TABLE base_product
  ADD COLUMN gram_weight DECIMAL(18,4) DEFAULT NULL COMMENT '克重(g/m² 或 g/件, 视业务)' AFTER density;