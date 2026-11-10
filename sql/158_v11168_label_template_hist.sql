-- =====================================================================
-- v1.1.168 — 标签模板历史快照表 base_label_template_hist
--   规格: docs/35_标签图形化编辑设计.md; 计划: docs/36_标签图形化编辑实施计划_P1.md Task 6
--
-- 这张表只回答一个问题:「这一次覆盖，把什么改掉了」。
--   所以列 = 纸头 + 元素 + 操作者 + 时间，**没有** template_code / template_name /
--   is_default / status / remark —— 那几项改了不算「标签内容变了」，回滚它们会把
--   默认位、启停状态和编码一起拽回旧值（计划全局约束 12）。
--
-- ⚠ 只在 .150 主库跑！本项目 binlog_format=ROW，.32 是下游：
--   这些 DDL/INSERT 一旦先在 canary 跑过，灌主库后复制回 .32 必撞 1062 → Slave_SQL_Running: No
--   （sql/142/143/152 真实翻车三次）。canary 只做只读演练。
--
-- 为什么这份**不含 USE / SET NAMES**：执行客户端已经连在主库上（见下方第一行的
--   --default-character-set=utf8mb4），再写一遍 USE 只是把库名钉死在这份文件里，
--   还会让「同一份文件全文也能被集成测试原样执行」这件事做不到
--   （backend/src/test/java/com/industrial/erp/modules/base/label/LabelSnapshotIT.java）。
--
-- 幂等：CREATE TABLE IF NOT EXISTS，可重复执行。无外键（计划全局约束 13）——
--   主表行软删后历史还要留够 20 版可回滚，外键只会把「先删史再删主行」的顺序问题
--   变成 1451 错误。
-- =====================================================================

-- 执行客户端必须使用 --default-character-set=utf8mb4；仅在主库执行迁移。
CREATE TABLE IF NOT EXISTS base_label_template_hist (
  id BIGINT NOT NULL,
  template_id BIGINT NOT NULL,
  seq INT NOT NULL,
  biz_type VARCHAR(32) NOT NULL,
  width_mm INT NOT NULL,
  height_mm INT NOT NULL,
  gap_mm INT NOT NULL,
  dpi INT NOT NULL,
  direction VARCHAR(16) NOT NULL,
  copies INT NOT NULL,
  elements LONGTEXT NOT NULL,
  create_by BIGINT NOT NULL,
  create_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  KEY idx_template_seq (template_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
