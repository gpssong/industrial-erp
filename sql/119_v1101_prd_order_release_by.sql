-- v1.1.102 XK 吹膜生产全流程 — 生产工单「开工操作人」留痕
-- 吹膜工在 App 端点「开工」后, 记录是哪个账号开的工 (release_by / release_name).
-- 幂等: information_schema 探测列, 老库/重跑安全.
-- 部署: mysql -uroot -p --default-character-set=utf8mb4 industrial_erp < 119_v1101_prd_order_release_by.sql

-- ========== prd_order 加 2 列: 开工操作人 ==========
SET @dbname = DATABASE();

-- release_by (sys_user.id, 谁点的开工)
SET @col = 'release_by';
SET @sql = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE prd_order ADD COLUMN `release_by` BIGINT DEFAULT NULL COMMENT ''开工操作人 (sys_user.id, v1.1.102 吹膜工留痕)'' AFTER `update_time`',
    'SELECT 1'
  )
  FROM information_schema.columns
  WHERE table_schema = @dbname AND table_name = 'prd_order' AND column_name = @col
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- release_name (冗余账号名, 展示用)
SET @col = 'release_name';
SET @sql = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE prd_order ADD COLUMN `release_name` VARCHAR(64) DEFAULT NULL COMMENT ''开工操作人姓名 (sys_user.username 快照)'' AFTER `release_by`',
    'SELECT 1'
  )
  FROM information_schema.columns
  WHERE table_schema = @dbname AND table_name = 'prd_order' AND column_name = @col
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
