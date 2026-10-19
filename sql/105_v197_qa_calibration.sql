-- =====================================================================
-- v1.1.97 — QA 检验设备校准 (qa_calibration_equipment + qa_calibration_record)
--   1. qa_calibration_equipment 表 (设备主数据 + 校准周期 + 状态)
--   2. qa_calibration_record    表 (校准历史, 与 equipment 1:N)
--   3. sys_menu 加 11 perm
--   4. sys_role_menu 给 6 业务角色授权
-- =====================================================================
-- 用法 (飞牛 canary 先行):
--   docker cp sql/105_v197_qa_calibration.sql <host>/tmp/ &&
--   docker exec erp-mysql-failover sh -c \
--     "exec mysql -uroot -perp_root_pwd --default-character-set=utf8mb4 industrial_erp < /tmp/105.sql"
-- =====================================================================

USE `industrial_erp`;

-- ------------------------------------------------------------------
-- 1. qa_calibration_equipment (校准设备主数据)
--    status: VALID(有效) / EXPIRING_SOON(30天内到期) / EXPIRED(已到期)
--    status 派生: next_cal_date vs today (与 frontend computed 一致)
--    intv_months: 校准周期 (月), 12=年度, 6=半年, 3=季度
-- ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `qa_calibration_equipment` (
  `id`                BIGINT        NOT NULL AUTO_INCREMENT,
  `code`              VARCHAR(32)   NOT NULL COMMENT '设备编号 CEyyyymmddNNNN',
  `name`              VARCHAR(128)  NOT NULL COMMENT '设备名称 (如: 数显测厚仪)',
  `spec`              VARCHAR(128)  DEFAULT NULL COMMENT '规格型号',
  `manufacturer`      VARCHAR(64)   DEFAULT NULL COMMENT '厂家',
  `location`          VARCHAR(64)   DEFAULT NULL COMMENT '存放位置',
  `workshop`          VARCHAR(64)   DEFAULT NULL COMMENT '所属车间',
  `measure_range`     VARCHAR(128)  DEFAULT NULL COMMENT '量程/精度',
  `intv_months`       INT           NOT NULL DEFAULT 12 COMMENT '校准周期(月)',
  `last_cal_date`     DATE          DEFAULT NULL,
  `next_cal_date`     DATE          DEFAULT NULL,
  `last_org`          VARCHAR(128)  DEFAULT NULL COMMENT '最近校准机构',
  `last_cert_no`      VARCHAR(64)   DEFAULT NULL COMMENT '最近证书号',
  `status`            VARCHAR(16)   NOT NULL DEFAULT 'VALID' COMMENT 'VALID/EXPIRING_SOON/EXPIRED/SCRAP',
  `remark`            VARCHAR(255)  DEFAULT NULL,
  `version`           INT           NOT NULL DEFAULT 0,
  `create_by`         BIGINT        DEFAULT NULL,
  `create_time`       DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`         BIGINT        DEFAULT NULL,
  `update_time`       DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`           TINYINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_qa_cal_eq_code` (`code`, `deleted`),
  KEY `idx_qa_cal_eq_status` (`status`, `next_cal_date`, `deleted`),
  KEY `idx_qa_cal_eq_workshop` (`workshop`, `status`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='校准设备主数据';

-- ------------------------------------------------------------------
-- 2. qa_calibration_record (校准历史, 与 equipment 1:N)
--    cert_file / report_pdf 走 /api/system/upload/file 存 URL
--    status: PENDING (待审核) / PASSED (校准合格) / FAILED (校准不合格)
--    review 字段: 校准结果复核 (QA 主管审核)
-- ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `qa_calibration_record` (
  `id`                BIGINT        NOT NULL AUTO_INCREMENT,
  `record_no`         VARCHAR(32)   NOT NULL COMMENT '记录号 CRyyyymmddNNNN',
  `equipment_id`      BIGINT        NOT NULL COMMENT 'qa_calibration_equipment.id',
  `equipment_code`    VARCHAR(32)   DEFAULT NULL,
  `equipment_name`    VARCHAR(64)   DEFAULT NULL,
  `cal_date`          DATE          NOT NULL COMMENT '校准日期',
  `valid_until`       DATE          DEFAULT NULL COMMENT '有效期至',
  `cal_org`           VARCHAR(128)  DEFAULT NULL COMMENT '校准机构',
  `cert_no`           VARCHAR(64)   DEFAULT NULL COMMENT '证书编号',
  `cert_file`         VARCHAR(255)  DEFAULT NULL COMMENT '证书附件 URL',
  `report_pdf`        VARCHAR(255)  DEFAULT NULL COMMENT '报告 PDF URL',
  `result`            VARCHAR(24)   DEFAULT 'PENDING' COMMENT 'PENDING/PASSED/FAILED',
  `deviation`         VARCHAR(255)  DEFAULT NULL COMMENT '偏差/不合格说明',
  `reviewer`          VARCHAR(64)   DEFAULT NULL COMMENT '审核人 (QA 主管)',
  `review_time`       DATETIME      DEFAULT NULL,
  `remark`            VARCHAR(255)  DEFAULT NULL,
  `version`           INT           NOT NULL DEFAULT 0,
  `create_by`         BIGINT        DEFAULT NULL,
  `create_time`       DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`         BIGINT        DEFAULT NULL,
  `update_time`       DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`           TINYINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_qa_cal_rec_no` (`record_no`, `deleted`),
  KEY `idx_qa_cal_rec_eq` (`equipment_id`, `cal_date`, `deleted`),
  KEY `idx_qa_cal_rec_result` (`result`, `cal_date`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='校准记录';

-- ------------------------------------------------------------------
-- 3. 权限 F 载体: 11 perm
--    equipment×4 (list/add/edit/delete) + record×4 (list/add/edit/delete) +
--    + 3 综合 (校准计划 list / 审核 check / 导出 export)
--    sort_no 4601-4613 (与 em 4401-4423 错开, 与 xk:env 4801-4805 错开)
-- ------------------------------------------------------------------
INSERT IGNORE INTO `sys_menu`(`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`perms`,`icon`,`sort_no`,`is_visible`,`status`) VALUES
-- 校准设备 4
(0,'校准设备查询','F','',NULL,'qa:calibration-equipment:list',NULL,4601,0,1),
(0,'校准设备新增','F','',NULL,'qa:calibration-equipment:add',NULL,4602,0,1),
(0,'校准设备编辑','F','',NULL,'qa:calibration-equipment:edit',NULL,4603,0,1),
(0,'校准设备删除','F','',NULL,'qa:calibration-equipment:delete',NULL,4604,0,1),
-- 校准记录 4
(0,'校准记录查询','F','',NULL,'qa:calibration-record:list',NULL,4611,0,1),
(0,'校准记录新增','F','',NULL,'qa:calibration-record:add',NULL,4612,0,1),
(0,'校准记录编辑','F','',NULL,'qa:calibration-record:edit',NULL,4613,0,1),
(0,'校准记录删除','F','',NULL,'qa:calibration-record:delete',NULL,4614,0,1),
-- 校准综合 3
(0,'校准计划查询','F','',NULL,'qa:calibration:list',NULL,4621,0,1),
(0,'校准记录审核','F','',NULL,'qa:calibration:check',NULL,4622,0,1),
(0,'校准记录导出','F','',NULL,'qa:calibration:export',NULL,4623,0,1);

-- ------------------------------------------------------------------
-- 4. 授权: 6 业务角色 (PC only, QA 不上 App)
-- ------------------------------------------------------------------
SET @has_ct := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_role_menu' AND COLUMN_NAME = 'client_type'
);
SET @sql_grant := IF(@has_ct > 0,
  'INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
   SELECT r.id, m.id, ''PC''
   FROM sys_role r CROSS JOIN sys_menu m
   WHERE m.perms IN (
       ''qa:calibration-equipment:list'', ''qa:calibration-equipment:add'',
       ''qa:calibration-equipment:edit'', ''qa:calibration-equipment:delete'',
       ''qa:calibration-record:list'', ''qa:calibration-record:add'',
       ''qa:calibration-record:edit'', ''qa:calibration-record:delete'',
       ''qa:calibration:list'', ''qa:calibration:check'', ''qa:calibration:export''
     )
     AND r.role_code IN (
       ''SUPER_ADMIN'', ''PURCHASE_MGR'', ''SALES_MGR'',
       ''WAREHOUSE_MGR'', ''PRODUCTION_MGR'', ''FINANCE''
     )
     AND r.deleted = 0',
  'SELECT 1');
PREPARE s FROM @sql_grant; EXECUTE s; DEALLOCATE PREPARE s;

-- ------------------------------------------------------------------
-- 5. 校验
-- ------------------------------------------------------------------
SELECT 'qa_calibration_equipment' AS tbl, COUNT(*) AS cnt FROM qa_calibration_equipment;
SELECT 'qa_calibration_record'    AS tbl, COUNT(*) AS cnt FROM qa_calibration_record;
SELECT 'qa:calibration* perm'     AS perm_check, COUNT(*) AS cnt
FROM sys_menu WHERE perms LIKE 'qa:calibration%';
SELECT 'qa:calibration* grants'   AS grant_check, COUNT(*) AS cnt
FROM sys_role_menu rm JOIN sys_menu m ON m.id = rm.menu_id
WHERE m.perms LIKE 'qa:calibration%';
