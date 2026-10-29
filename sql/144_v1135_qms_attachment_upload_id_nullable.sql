-- =============================================================================
-- v1.1.135 — qms_iqc_attachment.upload_id 改为可空
-- 背景: sql/117 建表时 upload_id 是 `BIGINT NOT NULL` 且无默认值, 注释写"sys_upload.id";
--       但本项目上传接口 SysUploadController 并不落库 sys_upload (直接写文件 + 返 url),
--       QmsIqcAttachmentService.bind() 因此一直 setUploadId(null)。
--       MyBatis-Plus 的 insert 会把 NULL 显式写进 INSERT 列表,
--       MySQL 严格模式下 "Field 'upload_id' doesn't have a default value" 直接 500。
--       → 自 v1.1.101 起, App 端"上传照片/附件 → bind"从来没有成功过;
--         v1.1.135 App 成品检要求签核前至少 1 张现场照片, 这条链路第一次被真正踩到。
--
-- 方案: 列改 NULL DEFAULT NULL (语义与代码一致: 无 sys_upload 记录时留空)。
--       不回填、不删索引 (idx_upload 对 NULL 仍可用)。
--
-- 幂等: information_schema 守门, 已可空则跳过, 可重复执行。
-- 执行后自检: 应返回 IS_NULLABLE=YES / COLUMN_DEFAULT=NULL
--   SELECT IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT FROM information_schema.COLUMNS
--   WHERE TABLE_SCHEMA='industrial_erp' AND TABLE_NAME='qms_iqc_attachment' AND COLUMN_NAME='upload_id';
-- =============================================================================
SET NAMES utf8mb4;
USE industrial_erp;

DROP PROCEDURE IF EXISTS _qms_attachment_upload_id_nullable;
DELIMITER //
CREATE PROCEDURE _qms_attachment_upload_id_nullable()
BEGIN
  DECLARE is_null VARCHAR(3) DEFAULT 'YES';
  SELECT IS_NULLABLE INTO is_null FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qms_iqc_attachment' AND COLUMN_NAME = 'upload_id';
  IF is_null = 'NO' THEN
    ALTER TABLE `qms_iqc_attachment`
      MODIFY COLUMN `upload_id` BIGINT NULL DEFAULT NULL
      COMMENT 'sys_upload.id (实际文件); 本项目上传接口不落 sys_upload, 允许为 NULL';
    SELECT 'v1.1.135: qms_iqc_attachment.upload_id 已改为可空' AS result;
  ELSE
    SELECT 'v1.1.135: qms_iqc_attachment.upload_id 已可空, 跳过' AS result;
  END IF;
END //
DELIMITER ;
CALL _qms_attachment_upload_id_nullable();
DROP PROCEDURE _qms_attachment_upload_id_nullable;
