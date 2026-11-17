-- v1.1.198 同日补丁 A — 已签核的制袋首检/巡检单也取消「食品接触符合性文件核验（3.5）」「批次追溯与接触标识（6.1）」
--
-- 触发 (用户 2026-11-18 现场截图): App 制袋首检页仍显示第 12、13 两项。
--
-- 根因: App 的制袋首检/巡检页走 POST /qms/inspection/ensure-for-process, 该端点对
--   「已有明细」的单据直接返回库里的旧明细 (ensureProcessTemplateItems 自带护栏:
--   非草稿 / 已有明细即返回), 不会按新模板重灌 ⇒ v1.1.198 只改模板 + 只清 DRAFT
--   (sql/162 实测候选 0 行) 对已签核单无效, App 端仍显示这两项。
--
-- 本脚本处理 sql/162 没覆盖到的「已签核」存量: 制袋工序 (process_name 含「制袋」)
--   的 FAI / PAT 检验单里, 定点软删这两个 item_code, 并把 line_no 重排连续。
--   预期命中: QI202611170002 (制袋 FAI 13→11 项)、QI202611170003 (制袋 PAT 11→9 项)。
--
-- 改完后这两张单的商品代码集合 == 当前模板代码集合 (validateTemplateCodes 口径),
--   即已签核单与新标准一致, 不再出现「模板改了但存量单还是老项数」的分裂。
--
-- 只灌 .150 主库 (副本只读取证): 先在 canary 建备份表会导致复制 1062。
-- 注意: 本文件含中文, 灌库必须 --default-character-set=utf8mb4。
-- 幂等: 可重复执行 —— 第二次跑时候选行已 deleted=1, 备份用 INSERT IGNORE, 命中 0 行。

SET NAMES utf8mb4;

-- 0) 备份表 (结构随主表, 幂等)
CREATE TABLE IF NOT EXISTS `qms_iqc_item_bak_v1198b` LIKE `qms_iqc_item`;

-- 1) 候选明细: 制袋工序 FAI/PAT 单里还活着的这两个代码 (不限单据状态)
DROP TEMPORARY TABLE IF EXISTS `tmp_item_v1198b`;
CREATE TEMPORARY TABLE `tmp_item_v1198b` AS
SELECT t.`id`
  FROM `qms_iqc_item` t
  JOIN `mes_quality_inspection` i ON i.`id` = t.`iqc_id`
  JOIN `prd_process` p ON p.`id` = i.`source_bill_id`
 WHERE t.`deleted` = 0
   AND t.`item_code` IN ('BAG_FOOD_REPORT', 'BAG_FOOD_TRACE')
   AND i.`deleted` = 0
   AND i.`inspection_type` IN ('FAI', 'PAT')
   AND i.`source_bill_type` = 'PRD_PROCESS'
   AND p.`deleted` = 0
   AND p.`process_name` LIKE '%制袋%';

SELECT COUNT(*) AS `candidate_rows` FROM `tmp_item_v1198b`;

-- 2) 备份 (先备份, 再软删, 顺序不能反)
INSERT IGNORE INTO `qms_iqc_item_bak_v1198b`
SELECT t.* FROM `qms_iqc_item` t
  JOIN `tmp_item_v1198b` c ON c.`id` = t.`id`;

UPDATE `qms_iqc_item` t
  JOIN `tmp_item_v1198b` c ON c.`id` = t.`id`
   SET t.`deleted` = 1
 WHERE t.`deleted` = 0;

-- 3) 行号重排 (删掉第 12/13 行后剩下的行号会留空洞, 车间看到会以为漏项)
UPDATE `qms_iqc_item` t
  JOIN (
    SELECT `id`, ROW_NUMBER() OVER (PARTITION BY `iqc_id` ORDER BY `line_no`, `id`) AS `rn`
      FROM `qms_iqc_item`
     WHERE `deleted` = 0
       AND `iqc_id` IN (SELECT DISTINCT `iqc_id` FROM `qms_iqc_item_bak_v1198b`)
  ) x ON x.`id` = t.`id`
   SET t.`line_no` = x.`rn`
 WHERE t.`deleted` = 0;

-- 4) 对账: 目标单剩余明细与行号 (期望 QI202611170002 = 11 项 1..11, QI202611170003 = 9 项 1..9)
SELECT i.`bill_no`, i.`inspection_type`, i.`status`, COUNT(t.`id`) AS `items`,
       GROUP_CONCAT(t.`line_no` ORDER BY t.`line_no`) AS `line_nos`,
       SUM(t.`item_code` IN ('BAG_FOOD_REPORT', 'BAG_FOOD_TRACE')) AS `removed_codes_left`
  FROM `mes_quality_inspection` i
  JOIN `qms_iqc_item` t ON t.`iqc_id` = i.`id` AND t.`deleted` = 0
 WHERE i.`deleted` = 0
   AND i.`inspection_type` IN ('FAI', 'PAT')
   AND i.`source_bill_type` = 'PRD_PROCESS'
   AND i.`source_bill_id` IN (SELECT `id` FROM `prd_process` WHERE `deleted` = 0 AND `process_name` LIKE '%制袋%')
 GROUP BY i.`id`, i.`bill_no`, i.`inspection_type`, i.`status`
 ORDER BY i.`bill_no`;
