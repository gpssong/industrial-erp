-- =====================================================================
-- 五十-补、v1.1.79 — 工作流模块: 上传字段 (应急预案/安全检查)
-- =====================================================================
-- 背景:
--   1. 应急预案要能保存应急联络电话 + 演练照片 (drill photo) + 演练附件 (drill script PDF)
--   2. 安全检查要能保存整改现场照片 (attachment) + 隐患照片 (per-row risk_photo)
--   3. 文件落点: ERP_UPLOAD_PATH 已有 /opt/industrial-erp/upload, SysUploadController + WebMvcConfig /upload/** 已就绪
--   4. 设计: attachment 字段 = 相对路径字符串 (逗号分隔多 URL), 与 BaseProduct.imageUrl 风格一致
--   5. 极简: 沿用 sys_role_menu 探测 + NOT EXISTS 模式, 无 DDL 破坏性变更
--
-- 决策: 所有照片 / 附件仅作为 head/detail 表的可选 VARCHAR 字段,
--       不另建 attachment 附件表 (目前不需要附件下载审计日志, 见 R19 后续)
--
-- 用法 (双站 home + 飞牛各跑一次, 必须 utf8mb4):
--   mysql -uroot -perp_root_pwd --default-character-set=utf8mb4 \
--     industrial_erp < sql/50b_v179_workflow_uploads.sql
-- =====================================================================

USE `industrial_erp`;

-- ---------------------------------------------------------------------
-- 1) 应急预案 head: 加应急联络电话 + 演练照片 (多图) + 演练附件 (单文件)
-- ---------------------------------------------------------------------
-- ALTER 在重复跑时用 IF NOT EXISTS 守护 (MySQL 8 不支持列级 IF NOT EXISTS,
--   用 information_schema 探测判断)
SET @stmt := IF(
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'wf_emergency_plan'
     AND column_name = 'contact_phone') = 0,
  'ALTER TABLE wf_emergency_plan ADD COLUMN contact_phone VARCHAR(64) DEFAULT NULL COMMENT ''应急联络电话'' AFTER owner',
  'SELECT 1'
);
PREPARE ps FROM @stmt; EXECUTE ps; DEALLOCATE PREPARE ps;

SET @stmt := IF(
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'wf_emergency_plan'
     AND column_name = 'drill_photos') = 0,
  'ALTER TABLE wf_emergency_plan ADD COLUMN drill_photos TEXT DEFAULT NULL COMMENT ''演练照片 (逗号分隔 URL) 多个 /upload/yyyyMM/dd/xxx.jpg'' AFTER next_drill_date',
  'SELECT 1'
);
PREPARE ps FROM @stmt; EXECUTE ps; DEALLOCATE PREPARE ps;

SET @stmt := IF(
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'wf_emergency_plan'
     AND column_name = 'drill_attachment') = 0,
  'ALTER TABLE wf_emergency_plan ADD COLUMN drill_attachment VARCHAR(500) DEFAULT NULL COMMENT ''演练脚本/附件 URL (单文件: PDF/DOC)''',
  ''
);
PREPARE ps FROM @stmt; EXECUTE ps; DEALLOCATE PREPARE ps;

-- ---------------------------------------------------------------------
-- 2) 应急预案 detail: 加演练步骤照片 (每一步可上传照片)
-- ---------------------------------------------------------------------
SET @stmt := IF(
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'wf_emergency_detail'
     AND column_name = 'step_photo') = 0,
  'ALTER TABLE wf_emergency_detail ADD COLUMN step_photo VARCHAR(500) DEFAULT NULL COMMENT ''该处置步骤的演练照片 URL''',
  ''
);
PREPARE ps FROM @stmt; EXECUTE ps; DEALLOCATE PREPARE ps;

-- ---------------------------------------------------------------------
-- 3) 安全检查 head: 加整改后照片 (attachment, 多个逗号分隔)
-- ---------------------------------------------------------------------
SET @stmt := IF(
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'wf_safety_record'
     AND column_name = 'attachment') = 0,
  'ALTER TABLE wf_safety_record ADD COLUMN attachment TEXT DEFAULT NULL COMMENT ''整改后照片/附件 (逗号分隔 URL) 多个 /upload/yyyyMM/dd/xxx.jpg''',
  'SELECT 1'
);
PREPARE ps FROM @stmt; EXECUTE ps; DEALLOCATE PREPARE ps;

-- ---------------------------------------------------------------------
-- 4) 安全检查 detail: 加隐患照片 (per-row)
-- ---------------------------------------------------------------------
SET @stmt := IF(
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'wf_safety_detail'
     AND column_name = 'risk_photo') = 0,
  'ALTER TABLE wf_safety_detail ADD COLUMN risk_photo VARCHAR(500) DEFAULT NULL COMMENT ''该行隐患现场照片 URL''',
  ''
);
PREPARE ps FROM @stmt; EXECUTE ps; DEALLOCATE PREPARE ps;

-- =====================================================================
-- 5) 校验
-- =====================================================================
-- DESCRIBE wf_emergency_plan;
-- DESCRIBE wf_emergency_detail;
-- DESCRIBE wf_safety_record;
-- DESCRIBE wf_safety_detail;

-- =====================================================================
-- 回滚 (慎用):
-- ALTER TABLE wf_emergency_plan DROP COLUMN contact_phone, drill_photos, drill_attachment;
-- ALTER TABLE wf_emergency_detail DROP COLUMN step_photo;
-- ALTER TABLE wf_safety_record DROP COLUMN attachment;
-- ALTER TABLE wf_safety_detail DROP COLUMN risk_photo;
-- (代码侧同步删除 WfEmergencyPlan/Detail/Safety 加字段 + PC 视图前端上传 UI)
-- =====================================================================
