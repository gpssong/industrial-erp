-- v1.1.198 — 制袋工序 (首检 FAI / 巡检 PAT) 取消「食品接触符合性文件核验（3.5）」「批次追溯与接触标识（6.1）」两项检测要求
--
-- 触发 (用户 2026-11-17 现场口径): App 制袋首检页第 12、13 项 = 食品接触符合性文件核验(3.5) / 批次追溯与接触标识(6.1) → 取消。
--
-- 后端同批改动 (QmsInspectionTemplateItems.BAG_FOOD_ITEMS):
--   3 → 1 行 (只留 BAG_FOOD_HYGIENE 清洁、人员与异物控制)
--   ⇒ 食品级制袋首检 13 → 11 项、制袋巡检 11 → 9 项; 非食品级不受影响 (本来就没有这三项)
--   吹膜首检/巡检、出厂检验 OQC 全套模板一律不动。
--
-- 本脚本只管存量草稿: validateTemplateCodes 要求「明细代码集合 == 当前模板代码集合」,
--   老草稿里还留着这两行, 签核会被拦在「检验模板代码集合不完整或与 BAG 工序不匹配」。
--   → 定点软删 (deleted=1) 制袋工序 DRAFT 首检/巡检单里的这两行, 并把行号重排连续。
--   → 已签核 (status<>'DRAFT') 的历史单一律不动: XK 过程检验留痕不可被脚本改写。
--
-- 幂等: 可重复执行 —— 第二次跑时候选行已 deleted=1, 备份用 INSERT IGNORE, 命中 0 行。
-- 注意: 本文件含中文, 灌库必须 --default-character-set=utf8mb4。

SET NAMES utf8mb4;

-- 0) 备份表 (结构随主表, 幂等)
CREATE TABLE IF NOT EXISTS `qms_iqc_item_bak_v1198` LIKE `qms_iqc_item`;

-- 1) 候选明细: 制袋工序 (prd_process.process_name 含「制袋」) 的 DRAFT 首检/巡检单里的这两项
DROP TEMPORARY TABLE IF EXISTS `tmp_item_v1198`;
CREATE TEMPORARY TABLE `tmp_item_v1198` AS
SELECT t.`id`
  FROM `qms_iqc_item` t
  JOIN `mes_quality_inspection` i ON i.`id` = t.`iqc_id`
  JOIN `prd_process` p ON p.`id` = i.`source_bill_id`
 WHERE t.`deleted` = 0
   AND t.`item_code` IN ('BAG_FOOD_REPORT', 'BAG_FOOD_TRACE')
   AND i.`deleted` = 0
   AND i.`status` = 'DRAFT'
   AND i.`inspection_type` IN ('FAI', 'PAT')
   AND i.`source_bill_type` = 'PRD_PROCESS'
   AND p.`deleted` = 0
   AND p.`process_name` LIKE '%制袋%';

SELECT COUNT(*) AS `candidate_rows` FROM `tmp_item_v1198`;

-- 2) 备份 (先备份, 再软删, 顺序不能反)
INSERT IGNORE INTO `qms_iqc_item_bak_v1198`
SELECT t.* FROM `qms_iqc_item` t
  JOIN `tmp_item_v1198` c ON c.`id` = t.`id`;

UPDATE `qms_iqc_item` t
  JOIN `tmp_item_v1198` c ON c.`id` = t.`id`
   SET t.`deleted` = 1
 WHERE t.`deleted` = 0;

-- 3) 行号重排 (删掉第 12/13 行后剩下的行号会留空洞, 车间看到会以为漏项)
UPDATE `qms_iqc_item` t
  JOIN (
    SELECT `id`, ROW_NUMBER() OVER (PARTITION BY `iqc_id` ORDER BY `line_no`, `id`) AS `rn`
      FROM `qms_iqc_item`
     WHERE `deleted` = 0
       AND `iqc_id` IN (SELECT DISTINCT `iqc_id` FROM `qms_iqc_item_bak_v1198`)
  ) x ON x.`id` = t.`id`
   SET t.`line_no` = x.`rn`
 WHERE t.`deleted` = 0;

-- 4) 对账: 目标单剩余明细与行号
SELECT i.`bill_no`, i.`inspection_type`, i.`status`, COUNT(t.`id`) AS `items`,
       GROUP_CONCAT(t.`line_no` ORDER BY t.`line_no`) AS `line_nos`
  FROM `mes_quality_inspection` i
  JOIN `qms_iqc_item` t ON t.`iqc_id` = i.`id` AND t.`deleted` = 0
 WHERE i.`deleted` = 0
   AND i.`status` = 'DRAFT'
   AND i.`inspection_type` IN ('FAI', 'PAT')
   AND i.`source_bill_type` = 'PRD_PROCESS'
   AND i.`source_bill_id` IN (SELECT `id` FROM `prd_process` WHERE `deleted` = 0 AND `process_name` LIKE '%制袋%')
 GROUP BY i.`id`, i.`bill_no`, i.`inspection_type`, i.`status`;
