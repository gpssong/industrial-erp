-- ============================================================
-- v1.0.4 schema 漂移修复 + sys_config 新增价税分离开关
-- 1. sys_print_template 添加 template_config 列 (HTML 模式打印配置)
-- 2. sys_config 新增 PRICE_TAX_SEPARATION 配置项 (价税分离开关)
-- ============================================================

SET NAMES utf8mb4;

-- 1. sys_print_template 添加 template_config 列
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE()
                     AND TABLE_NAME = 'sys_print_template'
                     AND COLUMN_NAME = 'template_config');

SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE sys_print_template ADD COLUMN template_config TEXT NULL COMMENT ''HTML 模式打印配置 (JSON 字符串)'' AFTER content',
  'SELECT ''template_config 列已存在, 跳过'' AS msg');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. sys_config 新增 PRICE_TAX_SEPARATION 配置
INSERT INTO sys_config (config_name, config_key, config_value, config_type, remark, create_time, update_time, deleted)
SELECT '价税分离开关', 'PRICE_TAX_SEPARATION', 'false', 1, '是否在采购/销售/生产单据上分离显示金额与税额 (true/false)', NOW(), NOW(), 0
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'PRICE_TAX_SEPARATION' AND deleted = 0);