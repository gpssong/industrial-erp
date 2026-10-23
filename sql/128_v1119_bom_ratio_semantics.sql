-- =====================================================================
-- v1.1.119: BOM「基础用量」语义正名 (配比份数) + 原料主单位统一 kg
-- ---------------------------------------------------------------------
-- 背景 (用户指出, DB 实测印证):
--   BOM202607160001「普通B机料」明细 = ldpe2426h 25 / lldpe7042 175 /
--   kkj 0.6 / nmtc 6, Σ=206.6 份, 添加剂仅占 3.2% (吹膜合理).
--   这是**重量配比份数**, 不是「生产 1 个成品需要多少料」. 该配方被
--   **29 个成品共用**, 成品克重跨度 3.3g ~ 64.7g (20 倍) — 同一张配方
--   不可能有固定的每成品用量.
--
--   原算法 (PrdOrderService.java:256-283):
--     ratio = planQty / bom.base_qty        ← bom.base_qty 对本配方是 NULL → 回落 1
--     need  = detail.base_qty × ratio        ← 退化成 base_qty × planQty
--   实测工单 RQ202610210001 plan_qty=750 → 领 lldpe7042 **131250 卷** /
--   ldpe2426h **18750 包**, 荒谬. 另两张配方 base_qty=1, 同样全错.
--
--   正确公式 (用户拍板):
--     成品总重(kg) = planQty × gram_weight / 1000        (g/件 → kg)
--     原料用量(kg) = 成品总重 × (base_qty / Σ base_qty) × (1 + loss_rate/100)
--
-- 为什么主单位必须是 kg (用户拍板): 配比是**重量**配比, 算法产出天然是 kg.
-- 若原料主单位是「包」「卷」, 领料量算得出 kg 却存不进包/卷口径, 库存
-- 扣减必然对不上. 所以先把原料主单位统一到 kg, 算法与库存口径才自洽.
--
-- 本脚本做三件事:
--   1) ldpe2426h / lldpe7050 / lldpe7042 三个膜料主单位 包/卷 → kg
--      (用户拍板保留原单位行, 靠 is_main 位切换, 不软删不硬删)
--   2) 回填 prd_bom_detail.unit_id / unit_name — 实测 **11/11 行全 NULL**.
--      根因: Bom.vue 的 addDetail()/onSubmit() 只同步 productCode/
--      productName/spec, 从不写单位. 后果链: 领料明细 unitId 全 NULL (51/51 行)
--      → StockService.convertToMain() 的 `if (unitId == null) return qty`
--      静默跳过单位折算, 扣错库存.
--   3) base_qty 列注释正名「配比份数」— 纯 COMMENT, 防下一个维护者
--      再按「用量」写出 base_qty × planQty 的错算法.
--
-- ⚠️ 执行前提 (改主单位前必读):
--   1) 已核实这 3 个原料的 inv_stock / inv_ledger / pur_receipt_detail /
--      sal_delivery_detail / inv_check_detail **全部 0 行** — 唯一引用是
--      18 行 DRAFT 领料明细 (本脚本不删, 用户自行处理). 改主单位零历史风险.
--   2) 全库还有 49 个产品用「卷」作主单位 (胶带/碳带/打包带等), 本脚本
--      **不动它们** — 它们不是 BOM 原料, 口径不同不该混改.
--   3) is_main 位互斥: base_product_unit 没有 (product_id, is_main) 唯一索引,
--      所以要先 SET is_main=0 再插/改, 不能直接插一条 is_main=1 (会出现
--      两条主单位, 前端 usable[0] 兜底会静默选错 — 见 memory R-id==0 陷阱).
--   4) base_product.main_unit_id 必须同步指向 kg 字典行, 否则编辑商品页
--      读 main_unit_id 会和 base_product_unit 的 is_main 对不上.
--   5) 中文查询必须 --default-character-set=utf8mb4 (MySQL 容器默认 latin1).
--   6) 部署顺序: 飞牛 canary 先行, 验证后同步 home.
--
-- 用法:
--   docker cp 本文件 <mysql容器>:/tmp/ && \
--   docker exec <mysql容器> mysql -uroot -p<pwd> --default-character-set=utf8mb4 \
--     -D industrial_erp -e 'source /tmp/128_v1119_bom_ratio_semantics.sql'
-- =====================================================================

SET NAMES utf8mb4;

-- ---------- 0. 备份 (必做, 永远先备份原值再动) ----------
-- CREATE TABLE IF NOT EXISTS bak_bpu_20261023 LIKE base_product_unit;
-- INSERT INTO bak_bpu_20261023
--   SELECT * FROM base_product_unit WHERE deleted = 0;
-- CREATE TABLE IF NOT EXISTS bak_bp_mainunit_20261023 LIKE base_product;
-- INSERT INTO bak_bp_mainunit_20261023
--   SELECT id, product_code, main_unit_id FROM base_product
--    WHERE deleted = 0 AND product_code IN ('ldpe2426h','lldpe7050','lldpe7042');
-- CREATE TABLE IF NOT EXISTS bak_prd_bom_detail_20261023 LIKE prd_bom_detail;
-- INSERT INTO bak_prd_bom_detail_20261023
--   SELECT * FROM prd_bom_detail WHERE deleted = 0;

-- ---------- 1. 三个膜料: 包/卷 → kg (靠 is_main 位切换, 保留原行) ----------
-- 用户拍板「保留包/卷为副单位」→ 这里**只改 is_main 位**, 不删任何行.
-- kg 字典行 unit_code='001' id=2074412408060821506 (kkj/nmtc 已在用, 非新建).
--
-- 1a. 找 kg 字典行 id (动态取, 不写死 id — 两站 id 可能不同)
SET @kg_unit_id = (SELECT MIN(id) FROM base_unit
                   WHERE deleted = 0 AND (unit_code = '001' OR unit_name = 'kg'));
SELECT 'kg 字典行 id' AS info, @kg_unit_id;

-- 1b. 降级这三个产品的**非 kg** 单位行为副单位 (保留行, 只清 is_main 位)
--     ⚠️ 必须带 `unit_name <> 'kg'` — 否则第 2 次执行会把上一轮插进来的
--     kg 行也降成 is_main=0, 而 1c 的 NOT EXISTS 又因"kg 已存在"不补插,
--     结果三个产品**没有任何主单位** (is_main 全 0) → 前端显示
--     「该商品未配置单位」. 幂等测试第 2 轮实测踩过, 别去掉这个条件.
UPDATE base_product_unit
SET is_main = 0
WHERE deleted = 0
  AND unit_name <> 'kg'
  AND product_id IN (SELECT id FROM base_product
                     WHERE deleted = 0
                       AND product_code IN ('ldpe2426h','lldpe7050','lldpe7042'));

-- 1c. 插入 kg 单位行 (NOT EXISTS 守门, 幂等; 重复执行不插第二行)
--     **不写 id 列** —— 跟 sql/111_v198_data_governance.sql:28 同一写法,
--     让 MySQL 用表自身的 AUTO_INCREMENT 分配 (本项目雪花 id 由应用侧生成,
--     SQL 脚本层一律交回 DB, 避免两套 id 生成器打架).
--     unit_id 指向 kg 字典行, conversion_rate=1 (kg 换算 kg).
INSERT INTO base_product_unit (product_id, unit_id, unit_name, is_main, conversion_rate, deleted)
SELECT p.id, @kg_unit_id, 'kg', 1, 1.000000, 0
FROM base_product p
WHERE p.deleted = 0
  AND p.product_code IN ('ldpe2426h','lldpe7050','lldpe7042')
  AND NOT EXISTS (
    SELECT 1 FROM (SELECT product_id, unit_name, deleted FROM base_product_unit) x
    WHERE x.product_id = p.id AND x.unit_name = 'kg' AND x.deleted = 0
  );

-- 1c-2. 把 kg 行**无条件**提为 is_main=1 (与 1c 配对做幂等闭环)
--      1c 只在"首次插入"时带上 is_main=1; 若 kg 行已存在 (第 2+ 次执行),
--      这条 UPDATE 保证主单位位一定回到 kg 上, 与 1b 降级动作解耦.
UPDATE base_product_unit u
JOIN base_product p ON p.id = u.product_id
SET u.is_main = 1
WHERE u.deleted = 0 AND u.unit_name = 'kg'
  AND p.deleted = 0
  AND p.product_code IN ('ldpe2426h','lldpe7050','lldpe7042')
  AND u.is_main <> 1;

-- 1d. base_product.main_unit_id 同步指向 kg
UPDATE base_product p
JOIN base_product_unit u
  ON u.product_id = p.id AND u.deleted = 0 AND u.unit_name = 'kg'
SET p.main_unit_id = u.unit_id
WHERE p.deleted = 0
  AND p.product_code IN ('ldpe2426h','lldpe7050','lldpe7042');

-- 1e. 被降级为副单位的原行 — conversion_rate 留 NULL 交给人工填
--     (采购若按卷入库, 填「每卷多少 kg」即可自动折算, 见 StockService.convertToMain)
--     ⚠️ 保持 is_main=0, 不设 conversion_rate — 填错值比 NULL 更危险
--        (NULL 时 convertToMain 直接返回原值, 不折算, 至少不会算错数量).

-- ---------- 2. 回填 prd_bom_detail.unit_id / unit_name ----------
-- 从该原料的**主单位**取 (base_product_unit 里 is_main=1 那条).
-- ⚠️ 兜底: 若某原料没标 is_main=1, 取 MIN(id) 那行当主单位 — 与
--    sql/127 hotfix-3 的 main_unit_id 回填同一套兜底逻辑, 两侧行为对齐.
UPDATE prd_bom_detail d
JOIN (
  SELECT product_id, unit_id, unit_name FROM (
    SELECT u.product_id, u.unit_id, u.unit_name,
           ROW_NUMBER() OVER (PARTITION BY u.product_id
                              ORDER BY u.is_main DESC, u.id) AS rn
    FROM base_product_unit u
    WHERE u.deleted = 0 AND u.unit_id IS NOT NULL AND u.unit_id <> 0
  ) ranked WHERE rn = 1
) t ON t.product_id = d.product_id
SET d.unit_id = t.unit_id, d.unit_name = t.unit_name
WHERE d.deleted = 0 AND d.product_id IS NOT NULL
  AND (d.unit_id IS NULL OR d.unit_name IS NULL);

-- ---------- 3. base_qty 列注释正名 (纯 COMMENT, 不动数据/类型) ----------
-- 「基础用量」是误称 — 实为重量配比份数. 改注释避免下一个维护者
-- 再按「用量」理解而写出 base_qty × planQty 的错算法.
ALTER TABLE prd_bom_detail
  MODIFY COLUMN base_qty DECIMAL(18,4) DEFAULT 1
    COMMENT '配比份数(重量份数, 非用量; 领料量 = 成品总重 × 本项/Σ全部)';

-- ---------- 4. 验证 ----------
-- 应全部为 0:
SELECT '膜料残留非kg主单位 (期望 0)' AS chk, COUNT(*) c
FROM base_product_unit u
JOIN base_product p ON p.id = u.product_id
WHERE u.deleted = 0 AND u.is_main = 1
  AND p.product_code IN ('ldpe2426h','lldpe7050','lldpe7042')
  AND u.unit_name <> 'kg';

-- 应全部为 1 (每个产品恰一条主单位):
SELECT p.product_code, COUNT(*) AS main_rows
FROM base_product_unit u
JOIN base_product p ON p.id = u.product_id
WHERE u.deleted = 0 AND u.is_main = 1
  AND p.product_code IN ('ldpe2426h','lldpe7050','lldpe7042')
GROUP BY p.product_code;

-- 应全部为 0 (回填后不该再有悬空/空单位):
SELECT 'BOM 明细残留空单位 (期望 0)' AS chk, COUNT(*) c
FROM prd_bom_detail
WHERE deleted = 0 AND (unit_id IS NULL OR unit_name IS NULL);

-- 应全部为 0 (回填后不该有指向已删字典行的悬空外键):
SELECT 'BOM 明细悬空单位外键 (期望 0)' AS chk, COUNT(*) c
FROM prd_bom_detail d
LEFT JOIN base_unit b ON b.id = d.unit_id
WHERE d.deleted = 0 AND d.unit_id IS NOT NULL AND (b.id IS NULL OR b.deleted <> 0);

-- 回填结果明细 (应全是 kg):
SELECT b.bom_code, d.line_no, d.product_code, d.product_name,
       d.unit_name, d.base_qty, b.unit_name AS bom_unit
FROM prd_bom_detail d
JOIN prd_bom b ON b.id = d.bom_id
WHERE d.deleted = 0 AND b.deleted = 0
ORDER BY b.bom_code, d.line_no;

-- 各配方的 Σ 配比 (供人工核对重量配比是否合理)
-- ⚠️ 别名别用 `lines` — MySQL 8 保留字, 会 1064 语法错 (本脚本已踩过)
SELECT b.bom_code, SUM(d.base_qty) AS sum_ratio, COUNT(*) AS line_cnt
FROM prd_bom_detail d
JOIN prd_bom b ON b.id = d.bom_id
WHERE d.deleted = 0 AND b.deleted = 0
GROUP BY b.bom_code
ORDER BY b.bom_code;

-- 回填明细 (4 条验证 SELECT 期望值):
--   膜料残留非kg主单位          = 0
--   每产品 main_rows            = 1 (三行各 1)
--   BOM 明细残留空单位          = 0
--   BOM 明细悬空单位外键        = 0
--   Σ配比: BOM202607160001=206.6 / 202607210001=4 / 202607250001=7.058