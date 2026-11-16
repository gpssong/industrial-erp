-- v1.1.195 — 吹膜首检 (FAI) 取消「纵横向拉伸强度 (3.4.1)」与「食品接触符合性文件 (3.5)」两项检测要求
--
-- 背景 (用户 2026-11-16 现场口径):
--   车间机台首检不做这两项 —— 拉伸强度按 Q/ZPC 1-2017 5.2.1.2 属出厂检验项目
--   (改由出厂检验 OQC_FILM_MECHANICAL 实测), 食品接触符合性文件属文件核验项
--   (改由出厂检验 OQC_FOOD_DOC 与吹膜巡检 FOOD_COMPLIANCE_DOC 承担)。
--
-- 后端同批改动 (QmsInspectionTemplateItems):
--   DEFAULT_FAI_ITEMS_COMMON 6 → 5 行 (删 BLOW_TENSILE)
--   DEFAULT_FAI_ITEMS_FOOD   2 → 1 行 (删 FOOD_COMPLIANCE_DOC)
--   => 食品级吹膜首检 8 → 6 项, 非食品级 6 → 5 项。制袋首检/巡检、成品检验 OQC 模板不变。
--
-- 本脚本只管存量草稿: validateTemplateCodes 会比对「明细代码集合 == 当前模板代码集合」,
--   老草稿里还留着 BLOW_TENSILE / FOOD_COMPLIANCE_DOC 两行, 签核会被拦在
--   「检验模板代码集合不完整或与 BLOW 工序不匹配，请重新打开首检/巡检页按最新模板灌入」。
--   → 定点软删 (deleted=1) 这两行, 其它行 (含已填实测值与判定) 一律不动。
--   → 已签核 (status<>'DRAFT') 的单子一律不动: XK 过程检验留痕不可被脚本改写。
--   → 只处理 inspection_type='FAI': 吹膜巡检 (PAT) 的 FOOD_COMPLIANCE_DOC 与制袋 FAI 不在范围内。
--
-- 幂等: 可重复执行 —— 第二次跑时候选行已 deleted=1, 备份用 INSERT IGNORE, 命中 0 行。
-- 注意: 本文件含中文, 灌库必须 --default-character-set=utf8mb4。

SET NAMES utf8mb4;

-- 0) 备份表 (结构随主表, 幂等)
CREATE TABLE IF NOT EXISTS `qms_iqc_item_bak_v1195` LIKE `qms_iqc_item`;

-- 1) 候选明细: DRAFT 的 FAI 单里的 BLOW_TENSILE / FOOD_COMPLIANCE_DOC
DROP TEMPORARY TABLE IF EXISTS `tmp_item_v1195`;
CREATE TEMPORARY TABLE `tmp_item_v1195` AS
SELECT t.`id`
  FROM `qms_iqc_item` t
  JOIN `mes_quality_inspection` i ON i.`id` = t.`iqc_id`
 WHERE t.`deleted` = 0
   AND t.`item_code` IN ('BLOW_TENSILE', 'FOOD_COMPLIANCE_DOC')
   AND i.`deleted` = 0
   AND i.`inspection_type` = 'FAI'
   AND i.`status` = 'DRAFT';

SELECT COUNT(*) AS `candidate_rows` FROM `tmp_item_v1195`;

-- 2) 落备份 (先备份, 再软删, 顺序不能反)
INSERT IGNORE INTO `qms_iqc_item_bak_v1195`
SELECT t.* FROM `qms_iqc_item` t
  JOIN `tmp_item_v1195` c ON c.`id` = t.`id`;

UPDATE `qms_iqc_item` t
  JOIN `tmp_item_v1195` c ON c.`id` = t.`id`
   SET t.`deleted` = 1
 WHERE t.`deleted` = 0;

-- 3) 行号重排: 定点删掉第 5/7 行后, 剩余行号会留空洞 (车间看到 1,2,3,4,6,8 会以为漏项)
--    只重排「本次被删过项」的那些 FAI 草稿 (按备份表反查 iqc_id), 不动其它单据。
--    qms_iqc_item 的 line_no 只有普通索引 (无唯一约束), 重排不会撞键; 行号本就允许重复, 重排后连续。
--    幂等: 重排后再跑一次结果不变。
UPDATE `qms_iqc_item` t
  JOIN (
    SELECT `id`, ROW_NUMBER() OVER (PARTITION BY `iqc_id` ORDER BY `line_no`, `id`) AS `rn`
      FROM `qms_iqc_item`
     WHERE `deleted` = 0
       AND `iqc_id` IN (SELECT DISTINCT `iqc_id` FROM `qms_iqc_item_bak_v1195`)
  ) x ON x.`id` = t.`id`
   SET t.`line_no` = x.`rn`
 WHERE t.`deleted` = 0;

-- 4) 对账: 剩余未删的同码行应为 0 (已签核历史单据允许存在, 故按状态分组看)
SELECT i.`status`, COUNT(*) AS `left_rows`
  FROM `qms_iqc_item` t
  JOIN `mes_quality_inspection` i ON i.`id` = t.`iqc_id`
 WHERE t.`deleted` = 0
   AND t.`item_code` IN ('BLOW_TENSILE', 'FOOD_COMPLIANCE_DOC')
   AND i.`deleted` = 0
 GROUP BY i.`status`;

-- 5) 行号对账: 本次受影响草稿的行号应为 1..N 连续
SELECT t.`iqc_id`, i.`bill_no`, GROUP_CONCAT(t.`line_no` ORDER BY t.`line_no`) AS `line_nos`
  FROM `qms_iqc_item` t
  JOIN `mes_quality_inspection` i ON i.`id` = t.`iqc_id`
 WHERE t.`deleted` = 0
   AND t.`iqc_id` IN (SELECT DISTINCT `iqc_id` FROM `qms_iqc_item_bak_v1195`)
 GROUP BY t.`iqc_id`, i.`bill_no`;
