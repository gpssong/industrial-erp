-- v1.1.134 同日修补 (第三批) — 检验项模板全面改按本公司企业标准 Q/ZPC 1-2017《包装用聚乙烯吹塑膜、袋》校准, 同步存量草稿
-- 后端同批改动 (QmsInspectionService):
--   吹膜 FAI/PAT、制袋 FAI/PAT、成品出厂检验 OQC (膜卷 / 袋)、食品级追加项、退货复检 OQC_RETURN
--   的 requirement / method / unit 全部换成标准条款号 + 实测限值 + 试验方法出处:
--     表1 外观质量、表2 膜宽度偏差、表3 膜厚度偏差、表4 袋尺寸偏差、表5 跌落性能、表6 抽样方案、
--     3.2 印刷质量、3.4.1 拉伸强度与断裂标称应变、3.4.2 热合强度≥7.0 N/15mm、3.4.4 协商性能、
--     3.5 卫生指标 (GB 4806.7 / GB 4806.6 / GB 9685)、4.1 状态调节、4.3 目测、5.1 组批 (≤7天)、
--     5.2.1 出厂检验项目、5.3.2 抽样、5.4 判定规则、6.1 合格证标志 14 项、6.2 包装、6.4 贮存。
--   新增代码 4 个: BAG_PRINT (制袋首检印刷质量)、OQC_FILM_ELONGATION (膜出厂断裂标称应变)、
--                 OQC_BAG_PRINT (袋出厂印刷质量)、OQC_FOOD_BATCH_RULE (组批抽样判定核验)。
--
-- 本脚本只管存量: 老草稿的检验项是按旧模板灌死的文本, 且 validateTemplateCodes 会比对代码集合,
--   缺 BAG_PRINT / OQC_FILM_ELONGATION / OQC_BAG_PRINT 的老草稿签核会报
--   「检验模板代码集合不完整或与…工序不匹配，请重新打开首检/巡检页按最新模板灌入」。
--   → 把**整单还没填过任何实测**的 FAI/PAT/OQC/OQC_RETURN DRAFT 明细整单软删 (deleted=1),
--     App/PC 重开该单时 ensureProcessTemplateItems / ensureOqcTemplateItems 见明细为空, 自动按新模板重灌。
--   → 已填过实测 (result_value / judgment_basis) 或**判过不合格** (pass_flag=0) 或已签核 (status<>'DRAFT')
--     的单子一律不动, XK 过程/出厂检验留痕不可被脚本改写。
--     注意 pass_flag=1 不算人工痕迹: v1.1.130 那版模板灌的就是默认 1, 现版模板灌 -1 (待检);
--     所以只用 0 (=判不合格) 作为「有人动过判定」的信号。
--   → 含手工项 (source='MANUAL') 的单子不重灌, 避免把人工录入的行一起软删。
-- 注意: 本文件含中文, 灌库必须 --default-character-set=utf8mb4.
-- 幂等: 可重复执行 — 第二次跑时候选单已无 deleted=0 明细, 备份与软删都命中 0 行。

SET NAMES utf8mb4;

-- 0) 备份表 (结构随主表, 幂等)
CREATE TABLE IF NOT EXISTS `qms_iqc_item_bak_v1134c` LIKE `qms_iqc_item`;

-- 1) 候选单: FAI/PAT/OQC/OQC_RETURN 的 DRAFT + 整单无任何实测/不合格判定 + 无手工项
DROP TEMPORARY TABLE IF EXISTS `tmp_head_v1134c`;
CREATE TEMPORARY TABLE `tmp_head_v1134c` AS
SELECT i.`id`
  FROM `mes_quality_inspection` i
 WHERE i.`deleted` = 0
   AND i.`inspection_type` IN ('FAI', 'PAT', 'OQC', 'OQC_RETURN')
   AND i.`status` = 'DRAFT'
   AND EXISTS (SELECT 1 FROM `qms_iqc_item` t
                WHERE t.`iqc_id` = i.`id` AND t.`deleted` = 0 AND t.`source` = 'TEMPLATE')
   AND NOT EXISTS (SELECT 1 FROM `qms_iqc_item` t2
                WHERE t2.`iqc_id` = i.`id` AND t2.`deleted` = 0
                  AND ((t2.`result_value` IS NOT NULL AND t2.`result_value` <> '')
                       OR t2.`pass_flag` = 0
                       OR (t2.`judgment_basis` IS NOT NULL AND t2.`judgment_basis` <> '')))
   AND NOT EXISTS (SELECT 1 FROM `qms_iqc_item` t3
                WHERE t3.`iqc_id` = i.`id` AND t3.`deleted` = 0
                  AND t3.`source` IS NOT NULL AND t3.`source` <> 'TEMPLATE');

SELECT COUNT(*) AS `candidate_drafts` FROM `tmp_head_v1134c`;

-- 2) 先落备份, 再整单软删 (顺序不能反, 否则备份不到)
INSERT INTO `qms_iqc_item_bak_v1134c`
SELECT t.* FROM `qms_iqc_item` t
  JOIN `tmp_head_v1134c` h ON h.`id` = t.`iqc_id`
 WHERE t.`deleted` = 0;

UPDATE `qms_iqc_item` t
  JOIN `tmp_head_v1134c` h ON h.`id` = t.`iqc_id`
   SET t.`deleted` = 1
 WHERE t.`deleted` = 0;

DROP TEMPORARY TABLE IF EXISTS `tmp_head_v1134c`;

-- 3) 抽查: 期望候选单现存明细 active_items=0 (下次打开按 Q/ZPC 1-2017 新模板重灌);
--    未进候选的单子明细与代码集合保持原样, 不受影响。
SELECT i.`bill_no`, i.`inspection_type`, i.`status`,
       COUNT(t.`id`) AS `active_items`,
       GROUP_CONCAT(t.`item_code` ORDER BY t.`line_no`) AS `codes`
  FROM `mes_quality_inspection` i
  LEFT JOIN `qms_iqc_item` t ON t.`iqc_id` = i.`id` AND t.`deleted` = 0
 WHERE i.`deleted` = 0 AND i.`inspection_type` IN ('FAI', 'PAT', 'OQC', 'OQC_RETURN')
 GROUP BY i.`id`, i.`bill_no`, i.`inspection_type`, i.`status`
 ORDER BY i.`bill_no`;

SELECT COUNT(*) AS `bak_rows`, COUNT(DISTINCT `iqc_id`) AS `bak_heads` FROM `qms_iqc_item_bak_v1134c`;
