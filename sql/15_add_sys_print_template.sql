-- =====================================================================
--  工业 ERP - 打印模板表 (myprint-design 集成)
--  编号 15: 紧接 14_* 之后, 不修改 01_schema_system.sql baseline
--  设计器: Vue3 + myprint-design v1.0.12 (npm)
--  模板存储: myprint-design 的 Template.content (JSON 字符串)
-- =====================================================================
SET NAMES utf8mb4;
USE `industrial_erp`;

-- ---------------------------------------------------------------------
-- 15.1  打印模板表
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_print_template`;
CREATE TABLE `sys_print_template` (
  `id`           BIGINT        NOT NULL AUTO_INCREMENT             COMMENT '模板ID',
  `name`         VARCHAR(64)   NOT NULL                            COMMENT '模板名称',
  `biz_type`     VARCHAR(32)   NOT NULL                            COMMENT '业务类型: SAL_DELIVERY/PUR_RECEIPT/PUR_RETURN/SAL_RETURN/PRD_ORDER',
  `content`      LONGTEXT      NOT NULL                            COMMENT '模板 JSON (myprint-design Template.content)',
  `paper_width`  DECIMAL(10,2) DEFAULT 210.00                      COMMENT '纸张宽 mm',
  `paper_height` DECIMAL(10,2) DEFAULT 297.00                      COMMENT '纸张高 mm',
  `page_unit`    VARCHAR(8)    DEFAULT 'mm'                        COMMENT 'mm/cm/in/px',
  `status`       TINYINT       DEFAULT 1                           COMMENT '0=停用 1=启用',
  `is_default`   TINYINT       DEFAULT 0                           COMMENT '该 biz_type 默认模板 (同一 biz_type 仅 1 个 is_default=1)',
  `remark`       VARCHAR(255)  DEFAULT NULL                        COMMENT '备注',
  `create_by`    BIGINT        DEFAULT NULL                        COMMENT '创建人',
  `create_time`  DATETIME      DEFAULT CURRENT_TIMESTAMP           COMMENT '创建时间',
  `update_by`    BIGINT        DEFAULT NULL                        COMMENT '更新人',
  `update_time`  DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`      TINYINT       DEFAULT 0                           COMMENT '软删除',
  PRIMARY KEY (`id`),
  KEY `idx_sys_print_template_biz` (`biz_type`, `status`, `deleted`),
  KEY `idx_sys_print_template_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='打印模板 (myprint-design)';