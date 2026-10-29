-- v1.1.134 — 给存量仓库补 warehouse_type 分类
-- 背景: base_warehouse.warehouse_type 建表时就定义了 NORMAL/RAW/FG/SEMI, 但 PC「仓库管理」表单
--       一直没有这个字段, 用户手工建的仓库全是 NORMAL. 而 PC/App 多处按类型自动选默认仓库:
--         pc-web  production/Order.vue       找 FG
--         pc-web  production/FinishedIn.vue  找 FG
--         pc-web  production/SemiFinishedIn.vue 找 SEMI
--         app     pages/scan/semi-in.vue     找 SEMI
--         app     pages/scan/finished-in.vue 找 FG
--       全都找不到 → 静默回退 warehouses[0] (实际是成品仓库), 于是
--       「半成品入库入到成品仓」「领料单开在成品仓」, 仓库账从一开始就串了.
-- 效果: 分类补齐后, 上述默认值自动命中正确的仓; PC 仓库管理同时新增「类型」可编辑字段, 防止新仓再漏分类.
-- 注意: 本文件含中文, 灌库必须 --default-character-set=utf8mb4, 否则中文条件匹配不上、注释乱码.
-- 幂等: 可重复执行 (只改仍是 NORMAL/未分类的仓)。

SET NAMES utf8mb4;

-- 0) 备份整表 (幂等: 已存在则跳过)
CREATE TABLE IF NOT EXISTS `base_warehouse_bak_v1134` AS SELECT * FROM `base_warehouse`;

-- 1) 半成品仓先判: 「半成品仓库」里含「成品」二字, 必须放在 FG 规则之前并用 NOT LIKE 排除
UPDATE `base_warehouse`
   SET `warehouse_type` = 'SEMI'
 WHERE (`warehouse_type` IS NULL OR `warehouse_type` = 'NORMAL')
   AND `deleted` = 0
   AND `warehouse_name` LIKE '%半成品%';

-- 2) 成品仓 (排除半成品仓)
UPDATE `base_warehouse`
   SET `warehouse_type` = 'FG'
 WHERE (`warehouse_type` IS NULL OR `warehouse_type` = 'NORMAL')
   AND `deleted` = 0
   AND `warehouse_name` LIKE '%成品%'
   AND `warehouse_name` NOT LIKE '%半成品%';

-- 3) 原料仓 (含辅料/配料一类的原料存储, 名称带「原料」)
UPDATE `base_warehouse`
   SET `warehouse_type` = 'RAW'
 WHERE (`warehouse_type` IS NULL OR `warehouse_type` = 'NORMAL')
   AND `deleted` = 0
   AND `warehouse_name` LIKE '%原料%';

-- 抽查: 期望 成品仓库=FG / 原料仓库=RAW / 半成品仓库=SEMI
SELECT `warehouse_code`, `warehouse_name`, `warehouse_type`, `deleted`
  FROM `base_warehouse`
 ORDER BY `deleted`, `id`;
