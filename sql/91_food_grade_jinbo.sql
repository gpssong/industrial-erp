-- =============================================================================
-- 91_food_grade_jinbo.sql  (v1.1.104 #966 商品食品级归类 — 金博袋 4 条)
--
-- 背景: #966 商品类型归类清单, 用户在 100 条现网商品里勾定「金博袋 4 条」为
--       食品接触 (is_food_grade=1); 氧气袋/胶带/打包带/通风管/原料母粒 全保持 0。
--       本文件只把这 4 条按 product_code 精确置 1, 幂等 (已 1 则不变)。
--
-- 4 条 (home 现网):
--   jb5275      塑料袋52*75
--   jb627802    金博62*78
--   jb6588008   金博65*88*0.08
--   jb7084001   金博70*84*0.02
--
-- 执行 (飞牛 canary 先, 验证后 home; 本机有 mysql 直连时):
--   mysql -h192.168.0.32 -uroot -perp_root_pwd industrial_erp --default-character-set=utf8mb4 < 91_food_grade_jinbo.sql
--   mysql -h192.168.0.150 -uroot -perp_root_pwd industrial_erp --default-character-set=utf8mb4 < 91_food_grade_jinbo.sql
-- =============================================================================
SET NAMES utf8mb4;
USE `industrial_erp`;

UPDATE base_product
SET is_food_grade = 1
WHERE product_code IN ('jb5275','jb627802','jb6588008','jb7084001')
  AND deleted = 0;

-- 自检 (应 4 行, is_food_grade 全 1)
SELECT product_code, product_name, is_food_grade
FROM base_product
WHERE product_code IN ('jb5275','jb627802','jb6588008','jb7084001') AND deleted = 0
ORDER BY product_code;
