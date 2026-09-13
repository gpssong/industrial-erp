# v1.1.49: Testcontainers fixture
# 关键复现 v1.1.47 bug: 库存=0 但 safety_stock > 0 的产品
INSERT INTO base_warehouse (id, warehouse_code, warehouse_name) VALUES (1, 'WH001', '仓库');

INSERT INTO base_product (id, product_code, product_name, spec, safety_stock, status, deleted) VALUES
  (1, '4-06-003-0018', '塑料袋22*28*0.16', '100只/捆', 70000, 1, 0),
  (2, '4-06-003-0019', '塑料袋30*40*0.20', '50只/捆', 1000, 1, 0),
  (3, '4-06-003-0020', '塑料袋50*70*0.30', '20只/捆', 500, 0, 0),  -- status=0 不进预警
  (4, '4-06-003-0021', '测试商品-有库存', '10只/捆', 100, 1, 0);

-- product 1 (safety=70000): 库存为 0 (v1.1.47 修复场景 — 旧 SQL s.qty > 0 会漏)
-- product 2 (safety=1000): 库存为 500
-- product 4 (safety=100): 库存为 200 (充足)
INSERT INTO inv_stock (warehouse_id, warehouse_name, product_id, product_code, product_name, qty, available_qty, deleted) VALUES
  (1, '仓库', 1, '4-06-003-0018', '塑料袋22*28*0.16', 0,    0,    0),
  (1, '仓库', 2, '4-06-003-0019', '塑料袋30*40*0.20', 500,  500,  0),
  (1, '仓库', 4, '4-06-003-0021', '测试商品-有库存',   200,  200,  0);
