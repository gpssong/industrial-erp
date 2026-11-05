-- =====================================================================
-- v1.1.167 — 存量 FG/SEMI 批次的生产日期回填 (mes_batch.production_date)
--   规格: docs/32_标签层设计规格.md §3; 计划: docs/33_标签层实施计划.md Task B6/#296
--
-- 为什么要有这一份: 成品卷标的「生产日期」元素取值链路是
--   FgRollLabelContextLoader.productionDateOf(batchNo) → mes_batch.production_date,
--   而该列在建列至今**全库没有写入方**, 所以存量 FG/SEMI 行全是 NULL, 标签印不出日期。
--   写入侧已补 (MesReportService.registerFgOnFinish/registerSemiOnFinish 在入库审核时写当天),
--   这份只管历史行。
--
-- ⚠ 只在 .150 主库跑一次！.32 / 阿里云靠复制自动拿到，不要在它们上面手动执行。
--   本项目 binlog_format=ROW 且 .32 是 .150 的下游：先在 canary 跑写脚本、再灌主库，
--   主库事件复制到 .32 会撞 1062 → Slave_SQL_Running: No，备库从此不跟主库
--   （sql/142/143/152 三次真实翻车）。canary 只做**只读演练**（下方第 1 段就是只读的）。
--
-- 为什么这份**不含 ALTER**：production_date DATE DEFAULT NULL 早在 sql/53_v180_mes_workorder.sql:97
--   建 mes_batch 时就有（实体 MesBatch.productionDate 也已映射），加列只会把 DDL 事件灌进复制链，
--   平白多出一次翻车机会。跑前用第 1 段的 SHOW COLUMNS 心里过一遍即可。
--
-- 为什么这份**不建备份表**：仓库老规矩是「CREATE TABLE xxx_bak_vNNN + INSERT … SELECT t.*」，
--   而备份行沿用源行主键 —— 这些 INSERT 复制到 canary（或 canary 先跑过再灌主库）必撞 1062，
--   正是上面那三次翻车的根因。本脚本是**纯 UPDATE**：只按已存在行的主键改一列，
--   不产生新行，因此不需要备份表，天然也不会有主键冲突。
--   要留底就用第 1 段的只读 SELECT 把结果贴进工单，别把行复制进数据库。
--
-- 口径（拍板）：
--   · 只动 batch_type IN ('FG','SEMI') 且 production_date IS NULL 且 source_prd_order_no IS NOT NULL
--     的行 ⇒ 幂等，跑第二次 0 行。
--   · RAW 一行都不碰：原料批在领料时登记，那天只是领用日，原料的真实生产日期属于供应商
--     （pur_receipt_detail.production_date，另一条链）。给食品接触材料的追溯记录编造日期，
--     XK 检查时是要命的问题。
--   · 取值 DATE(create_time)：FG 批在成品入库审核、SEMI 卷批在半成品入库审核时登记，
--     create_time 就是那次审核，记账日≈生产日。**create_time IS NULL 的行跳过不猜**
--     （mes_batch.create_time 是 DATETIME DEFAULT CURRENT_TIMESTAMP，正常不为空，
--       真要遇上空行，宁可标签继续留白也不印一个编造的日期）。
--   · 只补 source_prd_order_no IS NOT NULL 的行：该列只有 registerFgOnFinish/
--     registerSemiOnFinish 会填（来自入库单上的生产单号），出库侧的 registerFgOutToCustomer
--     不填 ⇒ 有它才证明这行是「我们自己那批货的入库那天」登记的。没有它的 FG 行分两种，
--     光看这一列区分不了：① 销售出库时首次登记的（存量直接出库 / 外购成品），create_time
--     是出库日，刷成生产日期等于造假；② 入库时那张入库单没带生产单号的，日期其实是事实
--     但无从证明。两种都继续留空 —— 标签印不出日期也比印一个错日期好。
--   · 写入侧与回填侧的口径差异：写入侧是「入库审核这个事件发生」，当天就是事实；
--     回填侧是事后推断，只能推到「有生产单号的入库登记」为止，推不动的一律不动。
--   · 只动 deleted = 0 的行：应用侧（@TableLogic）读不到软删行，给它们写日期没有消费方，
--     只是多灌一批无用的行事件到备库。
--
-- 字符集：本文件不含中文数据（中文只在注释里），但兄弟脚本统一口径，仍然要求
--   mysql --default-character-set=utf8mb4 执行，避免注释在部分终端落成乱码后误读。
-- 执行：mysql -h192.168.0.150 --default-character-set=utf8mb4 -uroot -p industrial_erp < sql/157_v11167_backfill_batch_prod_date.sql
-- =====================================================================
USE `industrial_erp`;
SET NAMES utf8mb4;

-- ------------------------------------------------------------------
-- 1) 灌前现状（只读）：先让人看见将要动多少行，再决定继续不继续
-- ------------------------------------------------------------------
SELECT '--- 1) 列存在性核对 (期望 Type=date, Null=YES, Default=NULL; 不该在这里加列) ---' AS step;
SHOW COLUMNS FROM `mes_batch` LIKE 'production_date';

SELECT '--- 2) 灌前各批次的空值分布 (FG/SEMI 的 null_date = 空日期行数; null_date_no_prd_order = 本次不动的留空行, null_date_and_prd_order 才是要动的上限) ---' AS step;
SELECT batch_type,
       COUNT(*)                                                   AS total,
       SUM(production_date IS NULL)                               AS null_date,
       SUM(production_date IS NULL AND create_time IS NULL)       AS null_date_no_create_time,
       SUM(production_date IS NULL AND source_prd_order_no IS NULL) AS null_date_no_prd_order,
       SUM(production_date IS NULL AND source_prd_order_no IS NOT NULL) AS null_date_and_prd_order,
       SUM(production_date IS NOT NULL)                           AS has_date
FROM mes_batch
WHERE deleted = 0
GROUP BY batch_type
ORDER BY batch_type;

-- ------------------------------------------------------------------
-- 3) 回填：纯 UPDATE，只补「有生产单号」的 FG/SEMI 空日期行；
--    RAW、无生产单号（出库首见登记）与已有日期一概不碰
--    (update_time = update_time 是为了压住 ON UPDATE CURRENT_TIMESTAMP ——
--     回填不是业务写入，别把历史行的更新时间刷成今天，否则审计上分不清
--     哪些行是当天入库登记的、哪些只是被这次脚本补过日期。)
-- ------------------------------------------------------------------
SELECT '--- 3) 执行回填 ---' AS step;
UPDATE `mes_batch`
SET production_date = DATE(`create_time`),
    `update_time`   = `update_time`
WHERE `batch_type` IN ('FG', 'SEMI')
  AND `production_date` IS NULL
  AND `create_time` IS NOT NULL
  AND `source_prd_order_no` IS NOT NULL
  AND `deleted` = 0;

SELECT ROW_COUNT() AS rows_updated;

-- ------------------------------------------------------------------
-- 4) 灌后校验（人工核对每一行，不符就停下来查，别接着发布）
--    期望：FG / SEMI 的 null_date **不是**归零，而是只剩两类设计上不动的行 ——
--          null_date_no_prd_order（无生产单号 = 出库首见登记，日期不可推断）与
--          null_date_no_create_time（create_time 为空 → 跳过不猜）；
--          RAW 的 null_date 与第 2 段**完全相同**（一行都不该被动）。
-- ------------------------------------------------------------------
SELECT '--- 4) 灌后各批次的空值分布 (RAW 各列应与灌前一致; FG/SEMI 剩余空行应全部落在 no_prd_order / no_create_time 两类) ---' AS step;
SELECT batch_type,
       COUNT(*)                                                   AS total,
       SUM(production_date IS NULL)                               AS null_date,
       SUM(production_date IS NULL AND create_time IS NULL)       AS null_date_no_create_time,
       SUM(production_date IS NULL AND source_prd_order_no IS NULL) AS null_date_no_prd_order,
       SUM(production_date IS NULL AND source_prd_order_no IS NOT NULL) AS null_date_and_prd_order,
       SUM(production_date IS NOT NULL)                           AS has_date
FROM mes_batch
WHERE deleted = 0
GROUP BY batch_type
ORDER BY batch_type;

SELECT '--- 5) 抽样核对回填值 = DATE(create_time) (期望 diff_days 全 0) ---' AS step;
SELECT batch_no, batch_type, production_date, create_time,
       DATEDIFF(production_date, DATE(create_time)) AS diff_days
FROM mes_batch
WHERE deleted = 0 AND batch_type IN ('FG', 'SEMI') AND production_date IS NOT NULL
ORDER BY id DESC
LIMIT 20;

SELECT '--- 6) 幂等复核：再跑一次本 UPDATE 应当 0 行 (下面这句只读，与第 3 段 UPDATE 的 WHERE 逐条同条件) ---' AS step;
SELECT COUNT(*) AS should_be_zero_after_run
FROM mes_batch
WHERE batch_type IN ('FG', 'SEMI')
  AND production_date IS NULL
  AND create_time IS NOT NULL
  AND source_prd_order_no IS NOT NULL
  AND deleted = 0;

-- ------------------------------------------------------------------
-- 7) 万一误在 canary 上跑过本脚本：这里是**纯 UPDATE**，不会造成主键冲突，
--    主库事件复制到 canary 只会把同一批行刷成同一个值 ⇒ 无需补救语句。
--    （这也是本脚本不设 bak 表、不设 DELETE 前置段的原因。）
-- ------------------------------------------------------------------
