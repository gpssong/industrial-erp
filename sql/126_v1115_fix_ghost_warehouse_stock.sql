-- v1.1.115: 修复 App 扫码成品入库产生的「幽灵仓库」库存行
--
-- 现象: 库存查询页部分产品仓库列空白, 且同一产品裂成两行 (用户截图 2/3/6/20 行).
--
-- 根因 (R21 雪花 id 精度丢失):
--   app/src/pages/scan/finished-in.vue:283 提交时 `warehouseId: Number(this.warehouseId)`。
--   后端 WebMvcConfig 已用 ToStringSerializer 把 Long 序列化成字符串 (防这个坑),
--   但前端这行 Number() 又转回 JS Number —— 双精度在 2^60 量级 ulp = 256:
--       Number("2073701024629047297") === 2073701024629047300
--   真实成品仓 id ...297 被低位抹成 ...300, 该 id 在 base_warehouse 里查无此仓
--   → 仓库列空白; 且 InvStockPageQueryMapper.xml GROUP BY s.warehouse_id
--   把 7297 / 7300 当两个分组 → 同一产品裂成两行。
--
-- 影响面 (home 192.168.0.150 实际数据):
--   inv_stock       4 行孤儿 (product_code: 0018/0012/0013/dyd7570)
--   prd_finished_in 10 张单 remark='App 成品入库'
--   inv_ledger      18 条台账
--
-- 修法 (方案 A 合并, 库存总量守恒):
--   1. 4 行孤儿的 qty/available_qty 累加进成品仓 7297 同产品行
--   2. 4 行孤儿软删 (deleted=1, 保留可追溯)
--   3. prd_finished_in 10 张单 warehouse_id 7300 → 7297 (台账自洽)
--   4. inv_ledger 18 条 warehouse_id 同步 (历史流水列不改数量)
--
-- 幂等: 全程 NOT EXISTS / WHERE deleted=0 守门, 重复灌安全。
-- 回滚: 见文件末尾 ROLLBACK 段 (需人工执行)。

USE industrial_erp;

START TRANSACTION;

-- ===== 1. 幽灵行 → 合并进成品仓同产品行 =====
-- 唯一键 uniq_inv_stock_key(warehouse_id, location_id, product_id, batch_no, deleted)
-- 7297 侧 4 个产品均已存在同 key 行, 故用「累加」而非「改 id」。
UPDATE inv_stock t
JOIN inv_stock src
  ON src.warehouse_id = 2073701024629047300
 AND src.product_id  = t.product_id
 AND src.deleted      = 0
 AND t.warehouse_id   = 2073701024629047297
 AND t.deleted        = 0
SET t.qty            = t.qty + src.qty,
    t.available_qty  = t.available_qty + src.available_qty,
    t.lock_qty       = t.lock_qty + src.lock_qty,
    t.total_cost     = t.total_cost + src.total_cost,
    t.last_in_date   = GREATEST(IFNULL(t.last_in_date,'1000-01-01'), IFNULL(src.last_in_date,'1000-01-01')),
    t.update_time    = NOW();

-- ===== 2. 软删 4 行幽灵 =====
UPDATE inv_stock
SET deleted = 1, update_time = NOW()
WHERE warehouse_id = 2073701024629047300
  AND deleted = 0;

-- ===== 3. 成品入库单仓库回正 (10 张) =====
UPDATE prd_finished_in
SET warehouse_id = 2073701024629047297, update_time = NOW()
WHERE warehouse_id = 2073701024629047300;

-- ===== 4. 库存台账仓库回正 (18 条, 只改仓库维度不改数量) =====
-- 注意: inv_ledger 只有 create_time, 没有 update_time (已核对 information_schema),
--       写 update_time 会直接报 Unknown column, 整个事务回滚。
-- warehouse_name 跟着来源走 (7297 侧多数是 NULL / '仓库' 两种历史值), 不硬编码覆盖。
UPDATE inv_ledger
SET warehouse_id = 2073701024629047297
WHERE warehouse_id = 2073701024629047300;

COMMIT;

-- ===== 5. 修复被同一 bug 毁掉的 prd_order_id (10 张 App 成品入库单) =====
-- 同一个 `prdOrderId: Number(this.orderId)` 精度丢失, 让 prd_order_id 全部挂空:
--   存的 2111262449375719400 (尾 ...400) vs 真实 2111262449375719426 (尾 ...426)
-- 双精度在 2^60 量级 ulp = 256, 低位被抹平 → JOIN prd_order 全部 matched_id = NULL。
--
-- 救回依据: prd_order_no 文本字段**未被精度影响**(走的是 billNo 字符串),
-- 且已验证 4 个工单号在 prd_order 全库唯一 (每个 COUNT(*)=1) → 无歧义精确挂回。
-- 顺带加 o.deleted=0 守门, 指向软删工单的单据不动 (宁可留空不错挂)。
START TRANSACTION;

UPDATE prd_finished_in f
JOIN prd_order o
  ON o.bill_no = f.prd_order_no
 AND o.deleted = 0
SET f.prd_order_id = o.id,
    f.update_time  = NOW()
WHERE f.prd_order_id <> o.id
  AND NOT EXISTS (SELECT 1 FROM prd_order d WHERE d.id = f.prd_order_id AND d.deleted = 0);

COMMIT;

-- ===== 验证 =====
-- V1: 应返回 0 行 (无残留孤儿库存)
SELECT COUNT(*) AS should_be_zero FROM inv_stock
WHERE warehouse_id = 2073701024629047300 AND deleted = 0;

-- V2: 合并后成品仓 4 产品数量 (期望 108500 / 32500 / 5600 / 86300)
SELECT product_code, product_name, qty, warehouse_id, warehouse_name
FROM inv_stock
WHERE deleted = 0 AND warehouse_id = 2073701024629047297
  AND product_code IN ('4-06-003-0018','4-06-003-0012','4-06-003-0013','dyd7570')
ORDER BY product_code;

-- V3: 单据 + 台账残留检查 (均应为 0)
SELECT
  (SELECT COUNT(*) FROM prd_finished_in WHERE warehouse_id=2073701024629047300) AS docs_left,
  (SELECT COUNT(*) FROM inv_ledger      WHERE warehouse_id=2073701024629047300) AS ledger_left;

-- V4: 库存总量守恒校验 (4 产品合并前后总额应一致)
SELECT product_code, SUM(qty) AS total_qty
FROM inv_stock
WHERE deleted = 0 AND product_code IN ('4-06-003-0018','4-06-003-0012','4-06-003-0013','dyd7570')
GROUP BY product_code ORDER BY product_code;

-- V5: prd_order_id 关联修复校验 (orphan_order_ref 应为 0)
SELECT COUNT(*) AS total_app_in,
       SUM(o.id IS NULL) AS orphan_order_ref
FROM prd_finished_in f
LEFT JOIN prd_order o ON o.id = f.prd_order_id
WHERE f.remark = 'App 成品入库';

-- ===== ROLLBACK (人工执行, 需先恢复备份 /tmp/ghost_20261022_122454.sql) =====
-- UPDATE inv_stock SET deleted = 0, warehouse_id = 2073701024629047300
--   WHERE id IN (2112157464633356289, 2112784267546800129, 2112779823337779201, 2112147506151895041);
-- UPDATE inv_stock SET qty = qty - 49000, available_qty = available_qty - 49000
--   WHERE id = 2083397320336551937;