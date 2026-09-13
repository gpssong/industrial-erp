-- =====================================================================
-- 31. 按客户切换浏览器打印模板 (v1.1.39)
--   sys_print_template.customer_id: NULL=全局默认, 非NULL=客户专属
--   匹配优先级: 客户专属 > 全局默认 (IS NULL)
-- =====================================================================
SET NAMES utf8mb4;
USE `industrial_erp`;

ALTER TABLE sys_print_template
  ADD COLUMN customer_id BIGINT DEFAULT NULL COMMENT '客户ID (NULL=全局默认, 非NULL=客户专属)' AFTER biz_type,
  ADD INDEX idx_biz_customer (biz_type, customer_id, is_default, status);
