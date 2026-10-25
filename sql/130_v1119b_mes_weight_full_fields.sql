-- v1.1.119b — ESP32 米克重采集「全字段上云」增量 (冠尚达 MCGS 屏 12 量)
-- 背景: 原 mes_weight_sample 只有 实测/设定克重 + 线速 + 累计产量/长度,
--      现场 MCGS 屏 (高精度米重控制系统 V22.1) 还有 6 个量要上云:
--      设定长度 / 当前重量 / 上牵引Hz / 下牵引Hz / 机器产量 / 克重修正
-- 性质: 纯加列 (ALTER TABLE ADD COLUMN), 全部可空 — 旧数据 NULL, 前端逐格兜底
--      (R24 幂等: ADD COLUMN IF NOT EXISTS 语义用 information_schema 探测守门,
--       连跑 N 次不重复加; 加列不改状态位, 无第 2 轮降级风险)
-- 依赖: 先跑 sql/129 (建 mes_weight_sample 主表), 再跑本脚本.
-- 部署: 双站 (canary-first), docker exec -i ... mysql industrial_erp < 本脚本
USE industrial_erp;

-- ========== 幂等加 6 列 ==========
-- 用 information_schema 探测: 列不存在才 ADD (MySQL 8.0 ADD COLUMN 无 IF NOT EXISTS 语法)
SET @db := DATABASE();

-- 设定长度 mm
SET @c := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=@db AND table_name='mes_weight_sample' AND column_name='set_length');
SET @s := IF(@c=0,
  'ALTER TABLE mes_weight_sample ADD COLUMN `set_length` DECIMAL(18,3) NULL COMMENT ''设定长度 mm (收卷目标)'' AFTER `roll_length`',
  'SELECT 1');
PREPARE p FROM @s; EXECUTE p; DEALLOCATE PREPARE p;

-- 当前重量 kg
SET @c := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=@db AND table_name='mes_weight_sample' AND column_name='current_weight');
SET @s := IF(@c=0,
  'ALTER TABLE mes_weight_sample ADD COLUMN `current_weight` DECIMAL(18,3) NULL COMMENT ''当前重量 kg (收卷辊当前累计)'' AFTER `set_length`',
  'SELECT 1');
PREPARE p FROM @s; EXECUTE p; DEALLOCATE PREPARE p;

-- 上牵引 Hz
SET @c := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=@db AND table_name='mes_weight_sample' AND column_name='upper_hz');
SET @s := IF(@c=0,
  'ALTER TABLE mes_weight_sample ADD COLUMN `upper_hz` DECIMAL(10,3) NULL COMMENT ''上牵引 Hz (牵引频率, 故障排查)'' AFTER `current_weight`',
  'SELECT 1');
PREPARE p FROM @s; EXECUTE p; DEALLOCATE PREPARE p;

-- 下牵引 Hz
SET @c := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=@db AND table_name='mes_weight_sample' AND column_name='lower_hz');
SET @s := IF(@c=0,
  'ALTER TABLE mes_weight_sample ADD COLUMN `lower_hz` DECIMAL(10,3) NULL COMMENT ''下牵引 Hz'' AFTER `upper_hz`',
  'SELECT 1');
PREPARE p FROM @s; EXECUTE p; DEALLOCATE PREPARE p;

-- 机器产量 kg/l
SET @c := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=@db AND table_name='mes_weight_sample' AND column_name='machine_yield');
SET @s := IF(@c=0,
  'ALTER TABLE mes_weight_sample ADD COLUMN `machine_yield` DECIMAL(18,3) NULL COMMENT ''机器产量 kg/l (单位产量)'' AFTER `lower_hz`',
  'SELECT 1');
PREPARE p FROM @s; EXECUTE p; DEALLOCATE PREPARE p;

-- 克重修正 倍率
SET @c := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=@db AND table_name='mes_weight_sample' AND column_name='gauge_fix');
SET @s := IF(@c=0,
  'ALTER TABLE mes_weight_sample ADD COLUMN `gauge_fix` DECIMAL(10,4) NULL COMMENT ''克重修正 倍率 (工艺调参, 1.0=不修正)'' AFTER `machine_yield`',
  'SELECT 1');
PREPARE p FROM @s; EXECUTE p; DEALLOCATE PREPARE p;

-- ========== 顺带: 单位口径纠正 (实际克重单位 g, 之前占位写的 g/m2 不准) ==========
-- mes_weight_channel.unit 默认 'g/m2' → 冠尚达屏「实际克重」单位是 g (每米膜克重), 改占位默认
UPDATE mes_weight_channel SET unit='g'
 WHERE (unit IS NULL OR unit='g/m2') AND deleted=0;

-- ========== 验证 ==========
SELECT '6 列是否都在' AS check_item;
SELECT column_name, column_type, is_nullable, column_comment
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='mes_weight_sample'
  AND column_name IN ('set_length','current_weight','upper_hz','lower_hz','machine_yield','gauge_fix')
ORDER BY ordinal_position;
