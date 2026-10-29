-- v1.1.134 同日修补 — 吹膜首检 (FAI) 检验项按现场口径收敛, 清理存量老草稿
-- 用户原话: 「app端生产单详情点击吹膜首检不检测断裂伸长率、雾度、蒸发残渣、高锰酸钾消耗量、重金属迁移、食品接触标识」
-- 后端模板同批已改 (QmsInspectionService):
--   吹膜首检通用项 8 → 6      去掉 BLOW_ELONGATION(断裂伸长率)、BLOW_HAZE(雾度)
--   吹膜首检食品级追加 4 → 2  去掉 迁移检测(蒸发残渣/高锰酸钾消耗量/重金属迁移)与食品接触标识, 只留符合性文件 + 清洁异物
--   吹膜巡检 (PAT)、制袋首检/巡检、原料来料检验 (IQC)、成品出厂检验 (OQC) 一律不变, 这 6 项该检的仍在出厂检验里。
-- 本脚本只管存量: 旧草稿的检验项是按老模板灌死的, 新代码校验模板代码集合会报「检验模板代码集合不完整」而无法签核。
--   → 把**整单还没填过任何实测**的吹膜首检 DRAFT 明细整单软删 (deleted=1),
--     App 重开该首检时 ensureProcessTemplateItems 见明细为空, 自动按新模板重灌 6 项 / 食品级 8 项。
--   → 已填过实测 (result_value / judgment_basis) 或**判过不合格** (pass_flag=0) 或已签核 (status<>'DRAFT') 的单子一律不动,
--     XK 过程检验留痕不可被脚本改写。
--     注意 pass_flag=1 不算人工痕迹: v1.1.130 那版模板灌的就是默认 1 (源码里写「默认 PASS, App 端按实情改」),
--     现版模板灌 -1 (待检); 所以只用 0 (=判不合格) 作为「有人动过判定」的信号。
-- 注意: 本文件含中文, 灌库必须 --default-character-set=utf8mb4.
-- 幂等: 可重复执行 — 第二次跑时候选单已无 deleted=0 明细, 备份与软删都命中 0 行。

SET NAMES utf8mb4;

-- 0) 备份表 (结构随主表, 幂等)
CREATE TABLE IF NOT EXISTS `qms_iqc_item_bak_v1134b` LIKE `qms_iqc_item`;

-- 1) 候选单: 吹膜首检 DRAFT + 明细含被剔除的老代码 + 整单无任何实测
DROP TEMPORARY TABLE IF EXISTS `tmp_fai_head_v1134b`;
CREATE TEMPORARY TABLE `tmp_fai_head_v1134b` AS
SELECT i.`id`
  FROM `mes_quality_inspection` i
 WHERE i.`deleted` = 0
   AND i.`inspection_type` = 'FAI'
   AND i.`status` = 'DRAFT'
   AND EXISTS (SELECT 1 FROM `qms_iqc_item` t
                WHERE t.`iqc_id` = i.`id` AND t.`deleted` = 0
                  AND t.`item_code` IN ('BLOW_ELONGATION', 'BLOW_HAZE', 'EVAP_RESIDUE',
                                        'KMnO4_CONSUMPTION', 'HEAVY_METAL_MIG',
                                        'FOOD_CONTACT_MARK', 'FOOD_MIGRATION_REPORT'))
   AND NOT EXISTS (SELECT 1 FROM `qms_iqc_item` t2
                WHERE t2.`iqc_id` = i.`id` AND t2.`deleted` = 0
                  AND ((t2.`result_value` IS NOT NULL AND t2.`result_value` <> '')
                       OR t2.`pass_flag` = 0
                       OR (t2.`judgment_basis` IS NOT NULL AND t2.`judgment_basis` <> '')));

-- 2) 先落备份, 再整单软删 (顺序不能反, 否则备份不到)
INSERT INTO `qms_iqc_item_bak_v1134b`
SELECT t.* FROM `qms_iqc_item` t
  JOIN `tmp_fai_head_v1134b` h ON h.`id` = t.`iqc_id`
 WHERE t.`deleted` = 0;

UPDATE `qms_iqc_item` t
  JOIN `tmp_fai_head_v1134b` h ON h.`id` = t.`iqc_id`
   SET t.`deleted` = 1
 WHERE t.`deleted` = 0;

DROP TEMPORARY TABLE IF EXISTS `tmp_fai_head_v1134b`;

-- 3) 抽查: 期望 FAI/PAT 单据的现存明细里不再有那 6 项; 老草稿 active_items=0 (下次打开重灌)
SELECT i.`bill_no`, i.`inspection_type`, i.`status`,
       COUNT(t.`id`) AS `active_items`,
       GROUP_CONCAT(t.`item_code` ORDER BY t.`line_no`) AS `codes`
  FROM `mes_quality_inspection` i
  LEFT JOIN `qms_iqc_item` t ON t.`iqc_id` = i.`id` AND t.`deleted` = 0
 WHERE i.`deleted` = 0 AND i.`inspection_type` IN ('FAI', 'PAT')
 GROUP BY i.`id`, i.`bill_no`, i.`inspection_type`, i.`status`
 ORDER BY i.`bill_no`;

SELECT COUNT(*) AS `bak_rows`, COUNT(DISTINCT `iqc_id`) AS `bak_heads` FROM `qms_iqc_item_bak_v1134b`;
