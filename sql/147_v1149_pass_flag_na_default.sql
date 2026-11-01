-- v1.1.149 — 本公司不做印刷: 达因/印刷质量 4 项默认「不适用」, 其余检验项默认「合格」
-- 用户原话: 「我们公司不做印刷，所以检验记录和报告中达因和印刷质量全部多一个不适用的选项并作为默认。
--            其他检验记录都以合格作为默认值」
--
-- pass_flag 语义扩展 (v1.1.149 起): -1=待检 / 0=不合格 / 1=合格 / **2=不适用**
--   仅达因/印刷质量 4 个代码允许 2 (后端 validateTemplateCodes / validateFinishedResult 强校验):
--     PAT_CORONA        吹膜巡检 · 润湿张力/电晕
--     OQC_FILM_CORONA   吹膜出厂 · 电晕处理面
--     BAG_PRINT         制袋首检 · 印刷质量
--     OQC_BAG_PRINT     制袋出厂 · 印刷质量
--   后端模板灌入已改为: 上述 4 项 pass_flag=2, 其余 =1; 实测值不算默认, 现场仍须逐项填写
--   (不适用项免填实测值, 合格/不合格项仍必须填)。
--
-- 本脚本只管存量未签核草稿:
--   → 仅 STATUS='DRAFT' 的单子;
--   → 仅 source='TEMPLATE' 且仍是**待检 (pass_flag=-1)**、实测值与判定依据都为空的明细;
--   → 上述 4 个代码改 2 (不适用), 其余改 1 (合格)。
--   → 已填过实测 (result_value / judgment_basis) 或判过不合格 (pass_flag=0) 或已签核 (status<>'DRAFT')
--     或手工项 (source<>'TEMPLATE') 一律不动, XK 检验留痕不可被脚本改写。
-- 注意: 本文件含中文, 灌库必须 --default-character-set=utf8mb4.
-- 幂等: 可重复执行 — 第二次跑时候选明细已无 pass_flag=-1, 备份与更新都命中 0 行。

SET NAMES utf8mb4;

-- 0) 备份表 (结构随主表, 幂等)
CREATE TABLE IF NOT EXISTS `qms_iqc_item_bak_v1149` LIKE `qms_iqc_item`;

-- 1) 候选明细: 未签核草稿里的模板项, 仍待检且无任何实测/判定痕迹
DROP TEMPORARY TABLE IF EXISTS `tmp_untouched_item_v1149`;
CREATE TEMPORARY TABLE `tmp_untouched_item_v1149` AS
SELECT t.`id`, t.`item_code`
  FROM `qms_iqc_item` t
  JOIN `mes_quality_inspection` i ON i.`id` = t.`iqc_id`
 WHERE t.`deleted` = 0
   AND i.`deleted` = 0
   AND i.`status` = 'DRAFT'
   AND t.`source` = 'TEMPLATE'
   AND t.`pass_flag` = -1
   AND (t.`result_value` IS NULL OR t.`result_value` = '')
   AND (t.`judgment_basis` IS NULL OR t.`judgment_basis` = '');

SELECT COUNT(*) AS `candidate_items`,
       SUM(`item_code` IN ('PAT_CORONA', 'OQC_FILM_CORONA', 'BAG_PRINT', 'OQC_BAG_PRINT')) AS `to_na`,
       SUM(`item_code` NOT IN ('PAT_CORONA', 'OQC_FILM_CORONA', 'BAG_PRINT', 'OQC_BAG_PRINT')) AS `to_pass`
  FROM `tmp_untouched_item_v1149`;

-- 2) 先落备份, 再更新 (顺序不能反, 否则备份不到原值)
INSERT INTO `qms_iqc_item_bak_v1149`
SELECT t.* FROM `qms_iqc_item` t
  JOIN `tmp_untouched_item_v1149` c ON c.`id` = t.`id`;

UPDATE `qms_iqc_item` t
  JOIN `tmp_untouched_item_v1149` c ON c.`id` = t.`id`
   SET t.`pass_flag` = CASE WHEN c.`item_code` IN ('PAT_CORONA', 'OQC_FILM_CORONA', 'BAG_PRINT', 'OQC_BAG_PRINT')
                            THEN 2 ELSE 1 END
 WHERE t.`pass_flag` = -1;

-- 3) 复核: 未签核草稿里不应再有「待检且无痕迹」的模板项
SELECT COUNT(*) AS `left_over_untested`
  FROM `qms_iqc_item` t
  JOIN `mes_quality_inspection` i ON i.`id` = t.`iqc_id`
 WHERE t.`deleted` = 0 AND i.`deleted` = 0 AND i.`status` = 'DRAFT'
   AND t.`source` = 'TEMPLATE' AND t.`pass_flag` = -1
   AND (t.`result_value` IS NULL OR t.`result_value` = '')
   AND (t.`judgment_basis` IS NULL OR t.`judgment_basis` = '');
