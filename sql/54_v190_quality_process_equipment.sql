-- =====================================================================
-- 五十四、v1.1.90 — MES 第二阶段: 质量 + 工艺 + 设备
--   3 个子模块 + 6 张新表 + 5 处既有表加列 + 21 条 perm
--
-- 背景 (用户目标):
--   v1.1.80 已打开「车间黑箱」(工单+报工+批次),
--   本期继续打开:
--     1) 质量检验 (IQC/FAI/PAT/OQC/OQC_RETURN) + 缺陷明细 + 处置联动
--        mes_batch.status (PASS 放行 / FAIL 冻结 / FAIL_REJECT 报废)
--     2) 工艺参数 + SOP (key-value + 公差 + Markdown 文档),
--        支持开单 snapshot prd_process.process_param_version
--     3) 设备主数据 + 设备-工序 M2M, 维保记录 device_id FK
--        (兼容 wf_maintain_record.device_name 自由文本, 老数据 NULL)
--
--   用户 3 决策 (AskUserQuestion 拍板):
--     Q1) Perm 粒度: 合一+类型后缀 (quality:inspection:add/list/check + :list/check:iqc/fai/pat/oqc)
--     Q2) 检验触发: 自动 (pur_receipt.check → IQC, prd_finished_in.check → OQC,
--                     sal_return.check → OQC_RETURN, FAI/PAT 手工 App 按钮)
--                     QMS_AUTO_INSPECTION 系统配置 (默认 ON) 控制, 失败 try/catch 包住
--     Q3) 设备模块: 新建 modules/em (对齐 v1.1.79 modules/workflow 模式)
--
-- ⚠️ MySQL 8 容器默认 latin1, 必须:
--   docker exec -i erp-mysql[-failover] mysql industrial_erp -uroot -perp_root_pwd \
--     --default-character-set=utf8mb4 < 54_v190_quality_process_equipment.sql
-- =====================================================================

USE `industrial_erp`;

-- ------------------------------------------------------------------
-- 1. 质量检验主表 (4 类 + 客诉联动合一)
--    IQC     来料检验 (pur_receipt.check 自动触发)
--    FAI     首件检验 (App/PC 手工)
--    PAT     巡检     (App/PC 手工)
--    OQC     成品检验 (prd_finished_in.check 自动触发)
--    OQC_RETURN 客诉退品 (sal_return.check 自动触发)
--    result: PASS 放行 / FAIL 冻结批 / HOLD 待定 / FAIL_REJECT 报废 / PASS_DISPOSITION 让步接收
-- ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `mes_quality_inspection` (
  `id`               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '雪花ID',
  `bill_no`          VARCHAR(32)   NOT NULL COMMENT '检验单号 QIyyyymmddNNNN',
  `bill_date`        DATE          NOT NULL COMMENT '检验日期',
  `inspection_type`  VARCHAR(16)   NOT NULL COMMENT 'IQC/FAI/PAT/OQC/OQC_RETURN',
  `source_bill_type` VARCHAR(32)   DEFAULT NULL COMMENT 'PUR_RECEIPT/PRD_PROCESS/SAL_RETURN 等',
  `source_bill_id`   BIGINT        DEFAULT NULL COMMENT '源单据ID',
  `source_bill_no`   VARCHAR(32)   DEFAULT NULL COMMENT '源单据号',
  `product_id`       BIGINT        DEFAULT NULL,
  `product_code`     VARCHAR(64)   DEFAULT NULL,
  `product_name`     VARCHAR(128)  DEFAULT NULL,
  `spec`             VARCHAR(128)  DEFAULT NULL,
  `model`            VARCHAR(64)   DEFAULT NULL,
  `batch_no`         VARCHAR(64)   DEFAULT NULL COMMENT '关联批次(mes_batch.batch_no)',
  `supplier_id`      BIGINT        DEFAULT NULL,
  `customer_id`      BIGINT        DEFAULT NULL,
  `workshop`         VARCHAR(64)   DEFAULT NULL,
  `sample_qty`       DECIMAL(18,4) DEFAULT NULL COMMENT '抽样数量',
  `qualified_qty`    DECIMAL(18,4) DEFAULT NULL,
  `unqualified_qty`  DECIMAL(18,4) DEFAULT NULL,
  `result`           VARCHAR(24)   NOT NULL DEFAULT 'HOLD' COMMENT 'PASS/FAIL/HOLD/PASS_DISPOSITION/FAIL_REJECT',
  `disposition`      VARCHAR(24)   DEFAULT NULL COMMENT 'NONE/FROZEN/SCRAP/REWORK/SORT/CONCESSION',
  `disposition_reason` VARCHAR(255) DEFAULT NULL,
  `inspector`        BIGINT        DEFAULT NULL COMMENT '检验人 user_id',
  `inspector_name`   VARCHAR(64)   DEFAULT NULL COMMENT '冗余, 列表不 JOIN',
  `check_time`       DATETIME      DEFAULT NULL COMMENT '审核时间',
  `photo_urls`       JSON          DEFAULT NULL COMMENT '现场照片 JSON 数组',
  `remark`           VARCHAR(255)  DEFAULT NULL,
  `status`           VARCHAR(16)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/CHECKED',
  `version`          INT           NOT NULL DEFAULT 0,
  `create_by`        BIGINT        DEFAULT NULL,
  `create_time`      DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`        BIGINT        DEFAULT NULL,
  `update_time`      DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`          TINYINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_qms_bill_no` (`bill_no`, `deleted`),
  KEY `idx_qms_insp_type` (`inspection_type`, `deleted`),
  KEY `idx_qms_insp_source` (`source_bill_type`, `source_bill_id`, `deleted`),
  KEY `idx_qms_insp_batch` (`batch_no`),
  KEY `idx_qms_insp_product` (`product_id`, `deleted`),
  KEY `idx_qms_insp_status` (`status`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='质量检验主表(IQC/FAI/PAT/OQC/OQC_RETURN)';

-- ------------------------------------------------------------------
-- 2. 缺陷明细 (一张检验单 N 行缺陷)
-- ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `mes_quality_defect` (
  `id`             BIGINT        NOT NULL AUTO_INCREMENT,
  `inspection_id`  BIGINT        NOT NULL COMMENT 'mes_quality_inspection.id',
  `line_no`        INT           NOT NULL DEFAULT 1,
  `defect_type`    VARCHAR(32)   DEFAULT NULL COMMENT '外观/尺寸/性能/印刷/...',
  `severity`       VARCHAR(16)   DEFAULT 'MINOR' COMMENT 'MINOR/MAJOR/CRITICAL',
  `qty`            DECIMAL(18,4) DEFAULT NULL,
  `location`       VARCHAR(64)   DEFAULT NULL COMMENT '缺陷位置',
  `photo_url`      VARCHAR(512)  DEFAULT NULL,
  `disposal`       VARCHAR(16)   DEFAULT NULL COMMENT 'REWORK/SCRAP/CONCESSION/SORT',
  `remark`         VARCHAR(255)  DEFAULT NULL,
  `version`        INT           NOT NULL DEFAULT 0,
  `create_by`      BIGINT        DEFAULT NULL,
  `create_time`    DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`      BIGINT        DEFAULT NULL,
  `update_time`    DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`        TINYINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_qms_defect_insp` (`inspection_id`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='质量缺陷明细';

-- ------------------------------------------------------------------
-- 3. 工艺参数 (按 mes_process_def 维度, key-value + 公差)
-- ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `mes_process_param` (
  `id`             BIGINT        NOT NULL AUTO_INCREMENT,
  `process_def_id` BIGINT        NOT NULL COMMENT 'mes_process_def.id',
  `param_key`      VARCHAR(64)   NOT NULL COMMENT '吹膜温度/牵引比/膜厚/收卷张力/...',
  `param_value`    VARCHAR(64)   DEFAULT NULL,
  `unit`           VARCHAR(16)   DEFAULT NULL,
  `upper_limit`    DECIMAL(18,4) DEFAULT NULL,
  `lower_limit`    DECIMAL(18,4) DEFAULT NULL,
  `sort_no`        INT           DEFAULT 0,
  `is_critical`    TINYINT       NOT NULL DEFAULT 0 COMMENT '关键参数(超限自动 NG)',
  `remark`         VARCHAR(255)  DEFAULT NULL,
  `version`        INT           NOT NULL DEFAULT 0,
  `create_by`      BIGINT        DEFAULT NULL,
  `create_time`    DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`      BIGINT        DEFAULT NULL,
  `update_time`    DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`        TINYINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_mes_param_pdef` (`process_def_id`, `sort_no`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工艺参数(mes_process_param)';

-- ------------------------------------------------------------------
-- 4. 作业指导书 (SOP/工艺卡)
--    version: 每次 activate 自动递增; 同 process_def 下 1 行 status=ACTIVE, 其余 DEPRECATED/DRAFT
-- ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `mes_process_sop` (
  `id`             BIGINT        NOT NULL AUTO_INCREMENT,
  `process_def_id` BIGINT        NOT NULL,
  `version`        VARCHAR(32)   NOT NULL DEFAULT '1.0' COMMENT '版本号',
  `doc_url`        VARCHAR(512)  DEFAULT NULL COMMENT 'PDF/图片附件',
  `content`        LONGTEXT      DEFAULT NULL COMMENT 'Markdown 富文本(直接编辑)',
  `effective_date` DATE          DEFAULT NULL,
  `status`         VARCHAR(16)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/ACTIVE/DEPRECATED',
  `version_lock`   INT           NOT NULL DEFAULT 0,
  `create_by`      BIGINT        DEFAULT NULL,
  `create_time`    DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`      BIGINT        DEFAULT NULL,
  `update_time`    DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`        TINYINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_mes_sop_pdef` (`process_def_id`, `status`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='作业指导书(mes_process_sop)';

-- ------------------------------------------------------------------
-- 5. 设备主数据
--    equipment_type 枚举: FILM_BLOWING(吹膜)/SIDE_SEAL(边封)/BAG_MAKING(制袋)/
--                         PRINTING(印刷)/LAMINATING(复合)/SLITTING(分切)/...
-- ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `base_equipment` (
  `id`                BIGINT        NOT NULL AUTO_INCREMENT,
  `equipment_code`    VARCHAR(32)   NOT NULL COMMENT '设备编号 EQ001...',
  `equipment_name`    VARCHAR(64)   NOT NULL,
  `equipment_type`    VARCHAR(32)   NOT NULL COMMENT '吹膜/制袋/印刷/复合/分切/...',
  `model`             VARCHAR(64)   DEFAULT NULL,
  `manufacturer`      VARCHAR(64)   DEFAULT NULL,
  `install_date`      DATE          DEFAULT NULL,
  `location`          VARCHAR(64)   DEFAULT NULL,
  `workshop`          VARCHAR(64)   DEFAULT NULL,
  `status`            VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/MAINT/IDLE/SCRAP',
  `qrcode`            VARCHAR(255)  DEFAULT NULL,
  `last_maint_date`   DATE          DEFAULT NULL,
  `next_maint_date`   DATE          DEFAULT NULL,
  `remark`            VARCHAR(255)  DEFAULT NULL,
  `version`           INT           NOT NULL DEFAULT 0,
  `create_by`         BIGINT        DEFAULT NULL,
  `create_time`       DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`         BIGINT        DEFAULT NULL,
  `update_time`       DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`           TINYINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_base_eq_code` (`equipment_code`, `deleted`),
  KEY `idx_base_eq_type` (`equipment_type`, `status`, `deleted`),
  KEY `idx_base_eq_workshop` (`workshop`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备主数据(base_equipment)';

-- ------------------------------------------------------------------
-- 6. 设备-工序 M2M (1 台机能做多道工序, 1 道工序可由多台机做)
-- ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `base_equipment_process` (
  `id`             BIGINT        NOT NULL AUTO_INCREMENT,
  `equipment_id`   BIGINT        NOT NULL COMMENT 'base_equipment.id',
  `process_name`   VARCHAR(64)   NOT NULL COMMENT 'mes_process_def.process_name',
  `sort_no`        INT           DEFAULT 0,
  `remark`         VARCHAR(255)  DEFAULT NULL,
  `version`        INT           NOT NULL DEFAULT 0,
  `create_by`      BIGINT        DEFAULT NULL,
  `create_time`    DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`      BIGINT        DEFAULT NULL,
  `update_time`    DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`        TINYINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_base_eqproc_eq` (`equipment_id`, `deleted`),
  KEY `idx_base_eqproc_proc` (`process_name`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备可执行工序(M2M)';

-- ------------------------------------------------------------------
-- 7. 既有表加列: mes_process_def + version/effective_date/is_critical
--    information_schema.COLUMNS 探测 3 列同时存在才 skip (幂等)
-- ------------------------------------------------------------------
SET @col_cnt := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mes_process_def'
    AND COLUMN_NAME IN ('version', 'effective_date', 'is_critical')
);
SET @sql_add_pdef := IF(@col_cnt < 3,
  'ALTER TABLE `mes_process_def`
   ADD COLUMN `version` VARCHAR(32) NOT NULL DEFAULT ''1.0'' COMMENT ''工艺版本'' AFTER `is_default`,
   ADD COLUMN `effective_date` DATE DEFAULT NULL COMMENT ''生效日期'' AFTER `version`,
   ADD COLUMN `is_critical` TINYINT NOT NULL DEFAULT 0 COMMENT ''是否关键工序(超限自动 NG)'' AFTER `effective_date`',
  'SELECT 1');
PREPARE s FROM @sql_add_pdef; EXECUTE s; DEALLOCATE PREPARE s;

-- ------------------------------------------------------------------
-- 8. 既有表加列: prd_process + process_param_version (开单 snapshot)
-- ------------------------------------------------------------------
SET @col_cnt := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'prd_process'
    AND COLUMN_NAME = 'process_param_version'
);
SET @sql_add_prc := IF(@col_cnt = 0,
  'ALTER TABLE `prd_process`
   ADD COLUMN `process_param_version` VARCHAR(32) DEFAULT NULL COMMENT ''开单时使用的工艺参数版本(snapshot)'' AFTER `from_def_id`',
  'SELECT 1');
PREPARE s FROM @sql_add_prc; EXECUTE s; DEALLOCATE PREPARE s;

-- prd_process 加索引 (按 version 查追溯)
SET @idx_cnt := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'prd_process' AND INDEX_NAME = 'idx_prd_process_param_ver'
);
SET @sql_prc_idx := IF(@idx_cnt = 0,
  'CREATE INDEX `idx_prd_process_param_ver` ON `prd_process` (`process_param_version`)',
  'SELECT 1');
PREPARE s FROM @sql_prc_idx; EXECUTE s; DEALLOCATE PREPARE s;

-- ------------------------------------------------------------------
-- 9. 既有表加列: mes_batch_link + inspection_id (disposition 追溯)
-- ------------------------------------------------------------------
SET @col_cnt := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mes_batch_link'
    AND COLUMN_NAME = 'inspection_id'
);
SET @sql_add_link := IF(@col_cnt = 0,
  'ALTER TABLE `mes_batch_link`
   ADD COLUMN `inspection_id` BIGINT DEFAULT NULL COMMENT ''关联 mes_quality_inspection.id(锁定/放行)'' AFTER `customer_id`',
  'SELECT 1');
PREPARE s FROM @sql_add_link; EXECUTE s; DEALLOCATE PREPARE s;

SET @idx_cnt := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mes_batch_link' AND INDEX_NAME = 'idx_mes_link_insp'
);
SET @sql_link_idx := IF(@idx_cnt = 0,
  'CREATE INDEX `idx_mes_link_insp` ON `mes_batch_link` (`inspection_id`)',
  'SELECT 1');
PREPARE s FROM @sql_link_idx; EXECUTE s; DEALLOCATE PREPARE s;

-- ------------------------------------------------------------------
-- 10. 既有表加列: mes_batch + hold_reason + frozen_inspection_id
-- ------------------------------------------------------------------
SET @col_cnt := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mes_batch'
    AND COLUMN_NAME IN ('hold_reason', 'frozen_inspection_id')
);
SET @sql_add_batch := IF(@col_cnt < 2,
  'ALTER TABLE `mes_batch`
   ADD COLUMN `hold_reason` VARCHAR(255) DEFAULT NULL AFTER `status`,
   ADD COLUMN `frozen_inspection_id` BIGINT DEFAULT NULL AFTER `hold_reason`',
  'SELECT 1');
PREPARE s FROM @sql_add_batch; EXECUTE s; DEALLOCATE PREPARE s;

-- ------------------------------------------------------------------
-- 11. 既有表加列: wf_maintain_record + device_id (兼容老数据 NULL)
-- ------------------------------------------------------------------
SET @col_cnt := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wf_maintain_record'
    AND COLUMN_NAME = 'device_id'
);
SET @sql_add_wfr := IF(@col_cnt = 0,
  'ALTER TABLE `wf_maintain_record`
   ADD COLUMN `device_id` BIGINT DEFAULT NULL COMMENT ''关联 base_equipment.id'' AFTER `device_name`',
  'SELECT 1');
PREPARE s FROM @sql_add_wfr; EXECUTE s; DEALLOCATE PREPARE s;

-- device_id 索引 (按设备过滤维保记录)
SET @idx_cnt := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wf_maintain_record' AND INDEX_NAME = 'idx_wf_maint_device'
);
SET @sql_wfr_idx := IF(@idx_cnt = 0,
  'CREATE INDEX `idx_wf_maint_device` ON `wf_maintain_record` (`device_id`)',
  'SELECT 1');
PREPARE s FROM @sql_wfr_idx; EXECUTE s; DEALLOCATE PREPARE s;

-- =====================================================================
-- 12. 权限 F 载体 (parent_id=0, is_visible=0)
--     21 条 perm:
--       Quality 11: 3 基础 + 8 类型后缀
--       Process 8:  param CRUD 4 + sop CRUD 4
--       Equipment 7: master CRUD 4 + maintain 3
-- =====================================================================
INSERT IGNORE INTO `sys_menu`(`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`perms`,`icon`,`sort_no`,`is_visible`,`status`) VALUES
-- Quality 基础 3
(0,'质量检验-新增','F','',NULL,'quality:inspection:add',NULL,4101,0,1),
(0,'质量检验查询','F','',NULL,'quality:inspection:list',NULL,4102,0,1),
(0,'质量检验审核','F','',NULL,'quality:inspection:check',NULL,4103,0,1),
-- Quality 类型后缀 (list*4 + check*4 = 8)
(0,'IQC 来料检验查询','F','',NULL,'quality:inspection:list:iqc',NULL,4110,0,1),
(0,'IQC 来料检验审核','F','',NULL,'quality:inspection:check:iqc',NULL,4111,0,1),
(0,'FAI 首件检验查询','F','',NULL,'quality:inspection:list:fai',NULL,4120,0,1),
(0,'FAI 首件检验审核','F','',NULL,'quality:inspection:check:fai',NULL,4121,0,1),
(0,'PAT 巡检查询','F','',NULL,'quality:inspection:list:pat',NULL,4130,0,1),
(0,'PAT 巡检审核','F','',NULL,'quality:inspection:check:pat',NULL,4131,0,1),
(0,'OQC 成品检验查询','F','',NULL,'quality:inspection:list:oqc',NULL,4140,0,1),
(0,'OQC 成品检验审核','F','',NULL,'quality:inspection:check:oqc',NULL,4141,0,1),
-- Process 工艺参数 4
(0,'工艺参数-新增','F','',NULL,'pdm:param:add',NULL,4201,0,1),
(0,'工艺参数查询','F','',NULL,'pdm:param:list',NULL,4202,0,1),
(0,'工艺参数编辑','F','',NULL,'pdm:param:edit',NULL,4203,0,1),
(0,'工艺参数删除','F','',NULL,'pdm:param:delete',NULL,4204,0,1),
-- Process SOP 4
(0,'SOP-新增','F','',NULL,'pdm:sop:add',NULL,4211,0,1),
(0,'SOP 查询','F','',NULL,'pdm:sop:list',NULL,4212,0,1),
(0,'SOP 编辑','F','',NULL,'pdm:sop:edit',NULL,4213,0,1),
(0,'SOP 删除','F','',NULL,'pdm:sop:delete',NULL,4214,0,1),
-- Equipment 设备主数据 4
(0,'设备主数据-查询','F','',NULL,'equipment:master:list',NULL,4301,0,1),
(0,'设备主数据-新增','F','',NULL,'equipment:master:add',NULL,4302,0,1),
(0,'设备主数据-编辑','F','',NULL,'equipment:master:edit',NULL,4303,0,1),
(0,'设备主数据-删除','F','',NULL,'equipment:master:delete',NULL,4304,0,1),
-- Equipment 维保 3
(0,'设备维保-新增','F','',NULL,'equipment:maintain:add',NULL,4311,0,1),
(0,'设备维保查询','F','',NULL,'equipment:maintain:list',NULL,4312,0,1),
(0,'设备维保审核','F','',NULL,'equipment:maintain:check',NULL,4313,0,1);

-- =====================================================================
-- 13. 授权策略 — 6 内置角色 × 21 perm × client_type='PC'
--     Quality (基础 3 + 4 类型后缀)  → SUPER_ADMIN + WAREHOUSE_MGR + PRODUCTION_MGR
--     Process (param/sop 8 全)      → SUPER_ADMIN + PRODUCTION_MGR
--     Equipment master (4)          → SUPER_ADMIN + WAREHOUSE_MGR + PRODUCTION_MGR
--     Equipment maintain (3)        → SUPER_ADMIN + WAREHOUSE_MGR + PRODUCTION_MGR
--     (类型后缀授权: IQC → + WAREHOUSE_MGR + PROD_MGR; FAI/PAT/OQC → + PROD_MGR)
-- =====================================================================
SET @has_ct := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_role_menu' AND COLUMN_NAME = 'client_type'
);

-- 13.1 Quality 基础 3 → SUPER_ADMIN + WAREHOUSE_MGR + PRODUCTION_MGR
SET @sql_qms_basic := IF(@has_ct > 0,
  'INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
   SELECT r.id, m.id, ''PC''
   FROM sys_role r CROSS JOIN sys_menu m
   WHERE m.perms IN (''quality:inspection:add'', ''quality:inspection:list'', ''quality:inspection:check'')
     AND r.role_code IN (''SUPER_ADMIN'', ''WAREHOUSE_MGR'', ''PRODUCTION_MGR'')
     AND r.deleted = 0',
  'SELECT 1');
PREPARE s FROM @sql_qms_basic; EXECUTE s; DEALLOCATE PREPARE s;

-- 13.2 IQC 类型后缀 2 → SUPER_ADMIN + WAREHOUSE_MGR + PRODUCTION_MGR (仓管主要管 IQC)
SET @sql_qms_iqc := IF(@has_ct > 0,
  'INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
   SELECT r.id, m.id, ''PC''
   FROM sys_role r CROSS JOIN sys_menu m
   WHERE m.perms IN (''quality:inspection:list:iqc'', ''quality:inspection:check:iqc'')
     AND r.role_code IN (''SUPER_ADMIN'', ''WAREHOUSE_MGR'', ''PRODUCTION_MGR'')
     AND r.deleted = 0',
  'SELECT 1');
PREPARE s FROM @sql_qms_iqc; EXECUTE s; DEALLOCATE PREPARE s;

-- 13.3 FAI/PAT/OQC 类型后缀 6 → SUPER_ADMIN + PRODUCTION_MGR (生产管)
SET @sql_qms_prod := IF(@has_ct > 0,
  'INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
   SELECT r.id, m.id, ''PC''
   FROM sys_role r CROSS JOIN sys_menu m
   WHERE m.perms IN (
       ''quality:inspection:list:fai'', ''quality:inspection:check:fai'',
       ''quality:inspection:list:pat'', ''quality:inspection:check:pat'',
       ''quality:inspection:list:oqc'', ''quality:inspection:check:oqc''
     )
     AND r.role_code IN (''SUPER_ADMIN'', ''PRODUCTION_MGR'')
     AND r.deleted = 0',
  'SELECT 1');
PREPARE s FROM @sql_qms_prod; EXECUTE s; DEALLOCATE PREPARE s;

-- 13.4 Process 工艺参数 + SOP 8 → SUPER_ADMIN + PRODUCTION_MGR
SET @sql_pdm := IF(@has_ct > 0,
  'INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
   SELECT r.id, m.id, ''PC''
   FROM sys_role r CROSS JOIN sys_menu m
   WHERE m.perms IN (
       ''pdm:param:add'', ''pdm:param:list'', ''pdm:param:edit'', ''pdm:param:delete'',
       ''pdm:sop:add'',   ''pdm:sop:list'',   ''pdm:sop:edit'',   ''pdm:sop:delete''
     )
     AND r.role_code IN (''SUPER_ADMIN'', ''PRODUCTION_MGR'')
     AND r.deleted = 0',
  'SELECT 1');
PREPARE s FROM @sql_pdm; EXECUTE s; DEALLOCATE PREPARE s;

-- 13.5 Equipment master 4 → SUPER_ADMIN + WAREHOUSE_MGR + PRODUCTION_MGR
SET @sql_eq_master := IF(@has_ct > 0,
  'INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
   SELECT r.id, m.id, ''PC''
   FROM sys_role r CROSS JOIN sys_menu m
   WHERE m.perms IN (''equipment:master:list'', ''equipment:master:add'',
                     ''equipment:master:edit'', ''equipment:master:delete'')
     AND r.role_code IN (''SUPER_ADMIN'', ''WAREHOUSE_MGR'', ''PRODUCTION_MGR'')
     AND r.deleted = 0',
  'SELECT 1');
PREPARE s FROM @sql_eq_master; EXECUTE s; DEALLOCATE PREPARE s;

-- 13.6 Equipment maintain 3 → SUPER_ADMIN + WAREHOUSE_MGR + PRODUCTION_MGR
SET @sql_eq_maint := IF(@has_ct > 0,
  'INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
   SELECT r.id, m.id, ''PC''
   FROM sys_role r CROSS JOIN sys_menu m
   WHERE m.perms IN (''equipment:maintain:add'', ''equipment:maintain:list'', ''equipment:maintain:check'')
     AND r.role_code IN (''SUPER_ADMIN'', ''WAREHOUSE_MGR'', ''PRODUCTION_MGR'')
     AND r.deleted = 0',
  'SELECT 1');
PREPARE s FROM @sql_eq_maint; EXECUTE s; DEALLOCATE PREPARE s;

-- =====================================================================
-- 14. APP_MODULE_ENABLED 加 3 模块 key (qms/pdm/em)
--     UPDATE sys_config.config_value SET = JSON_SET(config_value, '$.qms', ...)
--     幂等靠 JSON 路径不存在则不动 + 重复跑无害
-- =====================================================================
SET @row_exists := (
  SELECT COUNT(*) FROM sys_config WHERE config_key = 'APP_MODULE_ENABLED'
);
SET @sql_appmod := IF(@row_exists > 0,
  'UPDATE sys_config
   SET config_value = JSON_SET(
       config_value,
       ''$.qms'', JSON_OBJECT(''enabled'', true, ''overrides'', JSON_OBJECT()),
       ''$.pdm'', JSON_OBJECT(''enabled'', true, ''overrides'', JSON_OBJECT()),
       ''$.em'',  JSON_OBJECT(''enabled'', true, ''overrides'', JSON_OBJECT())
     ),
     update_by = 1, update_time = NOW()
   WHERE config_key = ''APP_MODULE_ENABLED''',
  'SELECT 1');
PREPARE s FROM @sql_appmod; EXECUTE s; DEALLOCATE PREPARE s;

-- =====================================================================
-- 15. 校验 (分行 SELECT, 不 UNION, 避免老 MySQL 1064)
-- =====================================================================
SELECT 'mes_quality_inspection'  AS tbl, COUNT(*) AS cnt FROM mes_quality_inspection;
SELECT 'mes_quality_defect'      AS tbl, COUNT(*) AS cnt FROM mes_quality_defect;
SELECT 'mes_process_param'       AS tbl, COUNT(*) AS cnt FROM mes_process_param;
SELECT 'mes_process_sop'         AS tbl, COUNT(*) AS cnt FROM mes_process_sop;
SELECT 'base_equipment'          AS tbl, COUNT(*) AS cnt FROM base_equipment;
SELECT 'base_equipment_process'  AS tbl, COUNT(*) AS cnt FROM base_equipment_process;

SELECT 'mes_process_def.version col'  AS col_check, COUNT(*) AS cnt
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mes_process_def' AND COLUMN_NAME = 'version';

SELECT 'prd_process.process_param_version col' AS col_check, COUNT(*) AS cnt
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'prd_process' AND COLUMN_NAME = 'process_param_version';

SELECT 'mes_batch_link.inspection_id col' AS col_check, COUNT(*) AS cnt
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mes_batch_link' AND COLUMN_NAME = 'inspection_id';

SELECT 'mes_batch.hold_reason col' AS col_check, COUNT(*) AS cnt
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mes_batch' AND COLUMN_NAME = 'hold_reason';

SELECT 'wf_maintain_record.device_id col' AS col_check, COUNT(*) AS cnt
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wf_maintain_record' AND COLUMN_NAME = 'device_id';

SELECT 'qms+process+equipment perms in sys_menu' AS check_item, COUNT(*) AS cnt
FROM sys_menu
WHERE perms LIKE 'quality:inspection%' OR perms LIKE 'pdm:%' OR perms LIKE 'equipment:%';

SELECT 'perms granted to roles' AS check_item, COUNT(*) AS cnt
FROM sys_role_menu rm
JOIN sys_menu m ON m.id = rm.menu_id
WHERE m.perms LIKE 'quality:inspection%' OR m.perms LIKE 'pdm:%' OR m.perms LIKE 'equipment:%';

SELECT 'APP_MODULE_ENABLED modules' AS check_item, JSON_KEYS(config_value) AS modules
FROM sys_config
WHERE config_key = 'APP_MODULE_ENABLED';
