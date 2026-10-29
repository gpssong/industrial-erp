-- v1.1.130: mes_process_sop 补 remark 列
-- 背景: MesProcessSop 实体有 remark 字段, PC 工艺 SOP 页面 (process/Sop.vue) 也有「备注」输入框
--       (sopForm.remark), 但 sql/54 建表时漏了这一列 → MP 插入报 Unknown column 'remark',
--       工艺 SOP 整块保存 500 (该表至今 0 行, 说明这条路径从未跑通).
-- 修法: 幂等补列 (存在则跳过), 与实体字段对齐。
-- 执行库: .150 主库 (industrial_erp), 由实时同步带到 .32 与阿里云只读副本。

SET @db = DATABASE();

-- 1) mes_process_sop.remark
SET @ddl = (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'mes_process_sop' AND COLUMN_NAME = 'remark') > 0,
  'SELECT 1',
  'ALTER TABLE `mes_process_sop` ADD COLUMN `remark` VARCHAR(255) DEFAULT NULL COMMENT ''备注'' AFTER `content`'
));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2) 校验
SELECT 'mes_process_sop.remark' AS col, COUNT(*) AS exists_flag
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'mes_process_sop' AND COLUMN_NAME = 'remark';
