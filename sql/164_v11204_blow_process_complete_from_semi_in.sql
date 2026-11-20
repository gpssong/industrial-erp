-- v1.1.204 回填: 吹膜工序的完工按「半成品入库 = 吹膜的报工」补齐
--
-- 背景: 工序 status=2 的唯一写方是报工 (MesReportService.reportWork), 而 App 的吹膜链路是
--   「开工 → 吹膜首检 → 半成品入库」, 报工入口只挂在制袋 ⇒ 吹膜工序永远停在 status=0/1,
--   v1.1.99 C1 的成品入库工序门禁 (PrdFinishedInService.check) 对「配了吹膜工序的食品级单」
--   从来没可能被满足 —— 线上表现: PD202611030002 所有工序都做完了, 点「审核入库」报
--   「生产工单存在未完工工序, 不能成品入库: 吹膜」。取证: 库内 13 行吹膜工序 status 只有 0/1。
--
-- 代码侧自 v1.1.204 起由半成品入库审核自动回写 (MesReportService.recordBlowOutput),
-- 本脚本只补存量: 已经审核过的 PSI 单不会重放, 所以要把它们的量补回吹膜工序。
--
-- 口径 (与代码一致):
--   * good_qty 取 GREATEST(原值, 该单已 CHECKED 的半成品入库总量) —— 只抬高不压低,
--     现场手工报工过的量不被抹掉;
--   * status=2 (已完工) 与 status=3 (已红冲) 的行一律不动 —— 不回退也不复活;
--   * 达量 (good_qty >= plan_qty 且 plan_qty>0) 才置完工, 否则只算进度 status=1;
--   * end_time 只在为空时写, 已有真实完工时间的不覆盖。
-- 幂等: 三步都可重复执行 (GREATEST + WHERE status NOT IN (2,3) + COALESCE)。
--
-- ⚠ 只在 .150 主库跑 (写 UPDATE 靠复制带到 .32 / 阿里云; .32 是 super_read_only 也写不进去)。
-- ⚠ 含中文注释, 执行必须带 --default-character-set=utf8mb4。
-- ⚠ 不动 prd_order.good_qty: 工单头是「全部工序 good_qty 之和」(吹膜是米/半成品、制袋是只,
--   相加是既有看板口径, 本脚本不改动它), 工序门禁只读 prd_process.status。

-- 0) 执行前先看现状 (期望: PD202611030002 的吹膜行 status=1 / good_qty=0)
-- SELECT p.id, p.prd_order_no, p.process_name, p.status, p.plan_qty, p.good_qty, p.end_time
--   FROM prd_process p WHERE p.deleted = 0 AND p.from_def_id IS NOT NULL
--     AND p.process_name LIKE '%吹膜%' AND p.process_name NOT LIKE '%制袋%'
--   ORDER BY p.prd_order_no;

-- 1) 补齐产出量 (重算不是累加)
UPDATE prd_process p
JOIN (
    SELECT s.prd_order_id, SUM(COALESCE(s.qty, 0)) AS semi_total
      FROM prd_semi_in s
     WHERE s.deleted = 0
       AND s.bill_status = 'CHECKED'
       AND s.prd_order_id IS NOT NULL
     GROUP BY s.prd_order_id
) t ON t.prd_order_id = p.prd_order_id
SET p.good_qty = GREATEST(COALESCE(p.good_qty, 0), t.semi_total)
WHERE p.deleted = 0
  AND p.from_def_id IS NOT NULL
  AND p.process_name LIKE '%吹膜%'
  AND p.process_name NOT LIKE '%制袋%'
  AND p.status NOT IN (2, 3);

-- 2) 有产出但还标着未开工的吹膜行 → 进行中 (半成品入库即已生产的证据)
UPDATE prd_process p
SET p.status = 1
WHERE p.deleted = 0
  AND p.from_def_id IS NOT NULL
  AND p.process_name LIKE '%吹膜%'
  AND p.process_name NOT LIKE '%制袋%'
  AND p.status = 0
  AND COALESCE(p.good_qty, 0) > 0;

-- 3) 达量 → 完工 (单独一条: MySQL 的多列 SET 从左到右用已更新值, 和判断混在一条易读错)
UPDATE prd_process p
SET p.status = 2,
    p.end_time = COALESCE(p.end_time, NOW())
WHERE p.deleted = 0
  AND p.from_def_id IS NOT NULL
  AND p.process_name LIKE '%吹膜%'
  AND p.process_name NOT LIKE '%制袋%'
  AND p.status = 1
  AND p.plan_qty IS NOT NULL
  AND p.plan_qty > 0
  AND p.good_qty >= p.plan_qty;

-- 4) 执行后核对: 该单吹膜行应为 status=2 / good_qty=2000, 制袋行原本就是 2
-- SELECT p.id, p.prd_order_no, p.process_name, p.status, p.plan_qty, p.good_qty, p.end_time
--   FROM prd_process p
--  WHERE p.prd_order_no = 'PD202611030002'
--  ORDER BY p.process_no, p.id;
-- 全库残留 (期望只剩「确实还没入够半成品」的单):
-- SELECT p.prd_order_no, p.process_name, p.status, p.plan_qty, p.good_qty
--   FROM prd_process p WHERE p.deleted = 0 AND p.from_def_id IS NOT NULL
--     AND p.process_name LIKE '%吹膜%' AND p.process_name NOT LIKE '%制袋%'
--     AND p.status NOT IN (2, 3)
--   ORDER BY p.prd_order_no;
