-- =============================================================================
-- 90_drift_fill_columns.sql  (v1.1.104 P1-D 巡检配套 — 补灌漂移缺列)
--
-- 背景: 双站 (home 192.168.0.150 + 飞牛 192.168.0.32) 各跑一份 industrial_erp,
--       列结构靠手动 docker cp + SOURCE 同步。漏灌一列 → home 某功能 500 / 飞牛正常。
--       scripts/sql-drift-check.sh 巡检发现 live 缺以下列, 逐个补回:
--         model 列 ×5  : sal_delivery_detail / sal_return_detail /
--                        pur_receipt_detail / pur_return_detail / prd_order   (源 sql/17)
--         fin_invoice_apply.invoice_status / .uninvoiced_amount            (源 sql/07 / sql/24)
--         base_equipment.photo_url                                        (源 sql/107, 仅 home 缺)
--
-- 本文件全部用 information_schema 探测 + PREPARE/EXECUTE 幂等写法:
--       列已存在 → 走 'SELECT 1' 空语句; 缺 → 才 ALTER ADD COLUMN。
--       重跑 0 副作用, 可安全反复 SOURCE。
--
-- 执行 (飞牛 canary 先, 验证后 home):
--   飞牛: sudo docker cp 90_drift_fill_columns.sql erp-mysql-failover:/tmp/ &&
--         sudo docker exec erp-mysql-failover mysql -uroot -perp_root_pwd industrial_erp \
--           --default-character-set=utf8mb4 < /tmp/90_drift_fill_columns.sql
--   home: sudo docker cp ... erp-mysql:/tmp/ && sudo docker exec erp-mysql mysql ... < /tmp/...
--   或 (有本机 mysql 客户端且能直连 3306):  mysql -h<ip> -uroot -perp_root_pwd industrial_erp \
--           --default-character-set=utf8mb4 < 90_drift_fill_columns.sql
-- =============================================================================
SET NAMES utf8mb4;
USE `industrial_erp`;

-- 幂等补一列的通用套路: 探测不存在才 ALTER
-- --- model 列 ×5 (源 sql/17, VARCHAR(128) AFTER spec) ---
SET @t:='sal_delivery_detail'; SET @c:='model';
SET @s:=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=@t AND column_name=@c)=0,
  CONCAT('ALTER TABLE `',@t,'` ADD COLUMN `model` VARCHAR(128) DEFAULT NULL COMMENT ''型号'' AFTER `spec`'),
  'SELECT 1');
PREPARE p FROM @s; EXECUTE p; DEALLOCATE PREPARE p;

SET @t:='sal_return_detail';
SET @s:=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=@t AND column_name='model')=0,
  CONCAT('ALTER TABLE `',@t,'` ADD COLUMN `model` VARCHAR(128) DEFAULT NULL COMMENT ''型号'' AFTER `spec`'),
  'SELECT 1');
PREPARE p FROM @s; EXECUTE p; DEALLOCATE PREPARE p;

SET @t:='pur_receipt_detail';
SET @s:=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=@t AND column_name='model')=0,
  CONCAT('ALTER TABLE `',@t,'` ADD COLUMN `model` VARCHAR(128) DEFAULT NULL COMMENT ''型号'' AFTER `spec`'),
  'SELECT 1');
PREPARE p FROM @s; EXECUTE p; DEALLOCATE PREPARE p;

SET @t:='pur_return_detail';
SET @s:=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=@t AND column_name='model')=0,
  CONCAT('ALTER TABLE `',@t,'` ADD COLUMN `model` VARCHAR(128) DEFAULT NULL COMMENT ''型号'' AFTER `spec`'),
  'SELECT 1');
PREPARE p FROM @s; EXECUTE p; DEALLOCATE PREPARE p;

SET @t:='prd_order';
SET @s:=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=@t AND column_name='model')=0,
  CONCAT('ALTER TABLE `',@t,'` ADD COLUMN `model` VARCHAR(128) DEFAULT NULL COMMENT ''型号'' AFTER `spec`'),
  'SELECT 1');
PREPARE p FROM @s; EXECUTE p; DEALLOCATE PREPARE p;

-- --- fin_invoice_apply.invoice_status / uninvoiced_amount (源 sql/07 / sql/24) ---
SET @s:=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='fin_invoice_apply' AND column_name='invoice_status')=0,
  'ALTER TABLE `fin_invoice_apply` ADD COLUMN `invoice_status` VARCHAR(32) DEFAULT ''UNINVOICED'' COMMENT ''UNINVOICED/PARTIAL_INVOICED/FULL_INVOICED''',
  'SELECT 1');
PREPARE p FROM @s; EXECUTE p; DEALLOCATE PREPARE p;

SET @s:=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='fin_invoice_apply' AND column_name='uninvoiced_amount')=0,
  'ALTER TABLE `fin_invoice_apply` ADD COLUMN `uninvoiced_amount` DECIMAL(18,4) DEFAULT 0 COMMENT ''未开票金额''',
  'SELECT 1');
PREPARE p FROM @s; EXECUTE p; DEALLOCATE PREPARE p;

-- --- base_equipment.photo_url (源 sql/107, 仅 home 缺; 飞牛已有则跳过) ---
SET @s:=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='base_equipment' AND column_name='photo_url')=0,
  'ALTER TABLE `base_equipment` ADD COLUMN `photo_url` VARCHAR(512) DEFAULT NULL COMMENT ''设备照片 URL''',
  'SELECT 1');
PREPARE p FROM @s; EXECUTE p; DEALLOCATE PREPARE p;

-- --- 补灌结果自检 (应全部 =1) ---
SELECT 'sal_delivery_detail.model'       AS col, (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='sal_delivery_detail' AND column_name='model') AS present
UNION ALL SELECT 'sal_return_detail.model',    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='sal_return_detail' AND column_name='model')
UNION ALL SELECT 'pur_receipt_detail.model',   (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pur_receipt_detail' AND column_name='model')
UNION ALL SELECT 'pur_return_detail.model',    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pur_return_detail' AND column_name='model')
UNION ALL SELECT 'prd_order.model',            (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='prd_order' AND column_name='model')
UNION ALL SELECT 'fin_invoice_apply.invoice_status',   (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='fin_invoice_apply' AND column_name='invoice_status')
UNION ALL SELECT 'fin_invoice_apply.uninvoiced_amount',(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='fin_invoice_apply' AND column_name='uninvoiced_amount')
UNION ALL SELECT 'base_equipment.photo_url',   (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='base_equipment' AND column_name='photo_url');
