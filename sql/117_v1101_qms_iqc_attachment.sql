-- v1.1.101 — IQC 附件/照片登记表
-- 业务附件 = 供应商报告/合同/发票 (PDF); 照片 = 标签/包装/抽样 (JPG/PNG)
-- 与 sys_upload 1:1 关联, 但 business_type='QMS_IQC_*' 与 IQC 业务子类型绑定
-- 一期全幂等: CREATE TABLE IF NOT EXISTS + 触发器 DROP IF EXISTS
-- 部署: mysql -uroot -p --default-character-set=utf8mb4 industrial_erp < 117_v1101_qms_iqc_attachment.sql

CREATE TABLE IF NOT EXISTS `qms_iqc_attachment` (
  `id`              BIGINT       NOT NULL                COMMENT '雪花 ID',
  `iqc_id`          BIGINT       NOT NULL                COMMENT '所属检验单 id',
  `upload_id`       BIGINT       NOT NULL                COMMENT 'sys_upload.id (实际文件)',
  `attach_type`     VARCHAR(32)  NOT NULL                COMMENT '类型 REPORT/CONTRACT/INVOICE/LABEL_PHOTO/PACK_PHOTO/SAMPLE_PHOTO/OTHER',
  `attach_name`     VARCHAR(255) NOT NULL                COMMENT '显示名 (用户录入/系统取 file_name)',
  `file_url`        VARCHAR(512) NOT NULL                COMMENT '冗余: sys_upload.file_url (列表不 JOIN)',
  `file_size_kb`    INT                   DEFAULT NULL   COMMENT '冗余: 文件大小 KB',
  `verified`        TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已核验 (0=未核验 1=已核验, App 端 UI 标记)',
  `verified_by`     BIGINT                 DEFAULT NULL   COMMENT '核验人 (sys_user.id)',
  `verified_time`   DATETIME               DEFAULT NULL   COMMENT '核验时间',
  `remark`          VARCHAR(255)           DEFAULT NULL,
  `version`         INT         NOT NULL DEFAULT 0        COMMENT '@Version 乐观锁',
  `create_by`       BIGINT                 DEFAULT NULL,
  `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_by`       BIGINT                 DEFAULT NULL,
  `update_time`     DATETIME               DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`         TINYINT     NOT NULL DEFAULT 0        COMMENT '@TableLogic',
  PRIMARY KEY (`id`),
  KEY `idx_iqc` (`iqc_id`, `attach_type`, `deleted`),
  KEY `idx_upload` (`upload_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='IQC 附件/照片 (v1.1.101, 与 sys_upload 1:1 关联, 强约束: 提交后不可脱离批次单独删除)';

-- 触发器: 防止"已审核 IQC 附件被单独删除"破坏 XK 审计链
DROP TRIGGER IF EXISTS `trg_qms_iqc_attachment_protect`;
DELIMITER $$
CREATE TRIGGER `trg_qms_iqc_attachment_protect`
BEFORE UPDATE ON `qms_iqc_attachment`
FOR EACH ROW
BEGIN
  IF NEW.deleted = 1 THEN
    DECLARE v_status VARCHAR(32);
    SELECT bill_status INTO v_status FROM mes_quality_inspection
      WHERE id = OLD.iqc_id AND deleted = 0;
    IF v_status = 'CHECKED' THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'XK 合规: 已审核的 IQC 检验单附件不可单独删除, 请反审核 IQC 单据后操作';
    END IF;
  END IF;
END $$
DELIMITER ;
