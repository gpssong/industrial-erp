-- =====================================================================
-- v1.1.96 — 设备维保 3 张表 (em 模块) + 责任人字段
--   1. base_equipment 加 owner 字段 (视图「责任人」列, 自由文本, 兼容 null)
--   2. em_maintain_plan   维保计划 (按设备+周期生成的下次保养计划)
--   3. em_maintain_order  维保工单 (具体执行单, 来源=计划 或 临时)
--   4. em_maintain_record 维保记录 (工单完成后的归档, 强绑照片+结果)
--   5. sys_menu 加 perm 节点 (plan/order/record 各 4 步)
--   6. sys_role_menu 给 6 内置角色授权
-- =====================================================================
-- 用法 (飞牛 canary 先行):
--   docker cp sql/102_v196_em_maintain.sql <host>/tmp/ &&
--   docker exec -i erp-mysql-failover mysql -uroot -perp_root_pwd \
--     --default-character-set=utf8mb4 industrial_erp < /tmp/102_v196_em_maintain.sql
-- =====================================================================

USE `industrial_erp`;

-- ------------------------------------------------------------------
-- 1. base_equipment 加 owner 字段 (兼容老数据 NULL)
-- ------------------------------------------------------------------
SET @col_cnt := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'base_equipment'
    AND COLUMN_NAME = 'owner'
);
SET @sql_add := IF(@col_cnt = 0,
  'ALTER TABLE `base_equipment`
   ADD COLUMN `owner` VARCHAR(64) DEFAULT NULL COMMENT ''设备责任人(自由文本, 视图列需要)'' AFTER `workshop`',
  'SELECT 1');
PREPARE s FROM @sql_add; EXECUTE s; DEALLOCATE PREPARE s;

-- ------------------------------------------------------------------
-- 2. 维保计划 em_maintain_plan
--    周期 (CYCLE_DAILY=1 / WEEKLY=7 / MONTHLY=30 / QUARTERLY=90 / YEARLY=365)
--    状态: DRAFT(草稿) / ACTIVE(已激活) / DISABLED(已停用)
--    next_run_date: 下次应执行日期 (用于看板「待执行/已逾期」)
-- ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `em_maintain_plan` (
  `id`                BIGINT        NOT NULL AUTO_INCREMENT,
  `plan_no`           VARCHAR(32)   NOT NULL COMMENT '计划编号 MPyyyymmddNNNN',
  `plan_name`         VARCHAR(128)  NOT NULL COMMENT '计划名(如: 2#吹膜机 月度滤网保养)',
  `equipment_id`      BIGINT        NOT NULL COMMENT 'base_equipment.id',
  `equipment_code`    VARCHAR(32)   DEFAULT NULL COMMENT '冗余, 列表不 JOIN',
  `equipment_name`    VARCHAR(64)   DEFAULT NULL,
  `maint_content`     VARCHAR(512)  NOT NULL COMMENT '维保内容描述',
  `cycle_type`        VARCHAR(16)   NOT NULL DEFAULT 'MONTHLY' COMMENT 'DAILY/WEEKLY/MONTHLY/QUARTERLY/YEARLY/CUSTOM',
  `cycle_days`        INT           NOT NULL DEFAULT 30 COMMENT '实际周期天数',
  `owner`             VARCHAR(64)   DEFAULT NULL COMMENT '负责人',
  `last_run_date`     DATE          DEFAULT NULL,
  `next_run_date`     DATE          DEFAULT NULL,
  `status`            VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'DRAFT/ACTIVE/DISABLED',
  `remark`            VARCHAR(255)  DEFAULT NULL,
  `version`           INT           NOT NULL DEFAULT 0,
  `create_by`         BIGINT        DEFAULT NULL,
  `create_time`       DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`         BIGINT        DEFAULT NULL,
  `update_time`       DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`           TINYINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_em_plan_no` (`plan_no`, `deleted`),
  KEY `idx_em_plan_eq` (`equipment_id`, `status`, `deleted`),
  KEY `idx_em_plan_next` (`next_run_date`, `status`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备维保计划';

-- ------------------------------------------------------------------
-- 3. 维保工单 em_maintain_order
--    来源: PLAN (从计划生成) / MANUAL (临时新增)
--    状态: DRAFT / DISPATCHED(已派工) / PROCESSING(处理中) / COMPLETED(已完成) / CANCELLED
--    start_time / complete_time: 处理起止 (前端 hero/详情用)
-- ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `em_maintain_order` (
  `id`                BIGINT        NOT NULL AUTO_INCREMENT,
  `order_no`          VARCHAR(32)   NOT NULL COMMENT '工单号 MOyyyymmddNNNN',
  `plan_id`           BIGINT        DEFAULT NULL COMMENT 'em_maintain_plan.id (MANUAL 为 NULL)',
  `source_type`       VARCHAR(16)   NOT NULL DEFAULT 'PLAN' COMMENT 'PLAN/MANUAL',
  `equipment_id`      BIGINT        NOT NULL,
  `equipment_code`    VARCHAR(32)   DEFAULT NULL,
  `equipment_name`    VARCHAR(64)   DEFAULT NULL,
  `maint_content`     VARCHAR(512)  NOT NULL,
  `plan_date`         DATE          NOT NULL COMMENT '计划执行日',
  `operator`          VARCHAR(64)   DEFAULT NULL COMMENT '维保人员',
  `start_time`        DATETIME      DEFAULT NULL,
  `complete_time`     DATETIME      DEFAULT NULL,
  `description`       TEXT          DEFAULT NULL COMMENT '处理说明/故障原因',
  `result`            VARCHAR(255)  DEFAULT NULL COMMENT '处理结果(完成后填写)',
  `photo_urls`        JSON          DEFAULT NULL COMMENT '现场照片 JSON 数组',
  `status`            VARCHAR(16)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/DISPATCHED/PROCESSING/COMPLETED/CANCELLED',
  `remark`            VARCHAR(255)  DEFAULT NULL,
  `version`           INT           NOT NULL DEFAULT 0,
  `create_by`         BIGINT        DEFAULT NULL,
  `create_time`       DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`         BIGINT        DEFAULT NULL,
  `update_time`       DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`           TINYINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_em_order_no` (`order_no`, `deleted`),
  KEY `idx_em_order_eq` (`equipment_id`, `status`, `deleted`),
  KEY `idx_em_order_plan` (`plan_id`, `deleted`),
  KEY `idx_em_order_date` (`plan_date`, `status`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备维保工单';

-- ------------------------------------------------------------------
-- 4. 维保记录 em_maintain_record (工单完成后归档, 不可物理删)
--    与工单 1:1 (同一个 order_id 唯一); 视图「维保记录 Tab」= 该表
-- ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `em_maintain_record` (
  `id`                BIGINT        NOT NULL AUTO_INCREMENT,
  `record_no`         VARCHAR(32)   NOT NULL COMMENT '记录号 MRyyyymmddNNNN',
  `order_id`          BIGINT        NOT NULL COMMENT 'em_maintain_order.id',
  `order_no`          VARCHAR(32)   DEFAULT NULL,
  `plan_id`           BIGINT        DEFAULT NULL,
  `equipment_id`      BIGINT        NOT NULL,
  `equipment_code`    VARCHAR(32)   DEFAULT NULL,
  `equipment_name`    VARCHAR(64)   DEFAULT NULL,
  `maint_content`     VARCHAR(512)  NOT NULL,
  `operator`          VARCHAR(64)   DEFAULT NULL,
  `start_time`        DATETIME      DEFAULT NULL,
  `complete_time`     DATETIME      DEFAULT NULL,
  `result`            TEXT          DEFAULT NULL COMMENT '处理结果',
  `photo_urls`        JSON          DEFAULT NULL,
  `reviewer`          VARCHAR(64)   DEFAULT NULL COMMENT '审核人 (完成时记)',
  `review_time`       DATETIME      DEFAULT NULL,
  `version`           INT           NOT NULL DEFAULT 0,
  `create_by`         BIGINT        DEFAULT NULL,
  `create_time`       DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`         BIGINT        DEFAULT NULL,
  `update_time`       DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`           TINYINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_em_record_no` (`record_no`, `deleted`),
  UNIQUE KEY `uniq_em_record_order` (`order_id`, `deleted`),
  KEY `idx_em_record_eq` (`equipment_id`, `complete_time`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备维保记录(已完成工单归档)';

-- ------------------------------------------------------------------
-- 5. 既有 base_equipment 加索引 (按 owner 查, 给视图用)
-- ------------------------------------------------------------------
SET @idx_cnt := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'base_equipment' AND INDEX_NAME = 'idx_base_eq_owner'
);
SET @sql_idx := IF(@idx_cnt = 0,
  'CREATE INDEX `idx_base_eq_owner` ON `base_equipment` (`owner`, `deleted`)',
  'SELECT 1');
PREPARE s FROM @sql_idx; EXECUTE s; DEALLOCATE PREPARE s;

-- ------------------------------------------------------------------
-- 6. 权限 F 载体: plan / order / record 各 4 步
--    4 步 = list / add / edit / delete (record 无 edit, 只有 list)
--    一共 11 个 perm (4 + 4 + 3)
-- ------------------------------------------------------------------
INSERT IGNORE INTO `sys_menu`(`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`perms`,`icon`,`sort_no`,`is_visible`,`status`) VALUES
-- 维保计划 4
(0,'维保计划查询','F','',NULL,'equipment:maintain-plan:list',NULL,4401,0,1),
(0,'维保计划新增','F','',NULL,'equipment:maintain-plan:add',NULL,4402,0,1),
(0,'维保计划编辑','F','',NULL,'equipment:maintain-plan:edit',NULL,4403,0,1),
(0,'维保计划删除','F','',NULL,'equipment:maintain-plan:delete',NULL,4404,0,1),
-- 维保工单 4
(0,'维保工单查询','F','',NULL,'equipment:maintain-order:list',NULL,4411,0,1),
(0,'维保工单新增','F','',NULL,'equipment:maintain-order:add',NULL,4412,0,1),
(0,'维保工单编辑','F','',NULL,'equipment:maintain-order:edit',NULL,4413,0,1),
(0,'维保工单完成','F','',NULL,'equipment:maintain-order:complete',NULL,4414,0,1),
-- 维保记录 3
(0,'维保记录查询','F','',NULL,'equipment:maintain-record:list',NULL,4421,0,1),
(0,'维保记录审核','F','',NULL,'equipment:maintain-record:check',NULL,4422,0,1),
(0,'维保记录导出','F','',NULL,'equipment:maintain-record:export',NULL,4423,0,1);

-- ------------------------------------------------------------------
-- 7. 授权: SUPER_ADMIN + WAREHOUSE_MGR + PRODUCTION_MGR
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
       ''equipment:maintain-plan:list'', ''equipment:maintain-plan:add'',
       ''equipment:maintain-plan:edit'', ''equipment:maintain-plan:delete'',
       ''equipment:maintain-order:list'', ''equipment:maintain-order:add'',
       ''equipment:maintain-order:edit'', ''equipment:maintain-order:complete'',
       ''equipment:maintain-record:list'', ''equipment:maintain-record:check'',
       ''equipment:maintain-record:export''
     )
     AND r.role_code IN (''SUPER_ADMIN'', ''WAREHOUSE_MGR'', ''PRODUCTION_MGR'')
     AND r.deleted = 0',
  'SELECT 1');
PREPARE s FROM @sql_grant; EXECUTE s; DEALLOCATE PREPARE s;

-- ------------------------------------------------------------------
-- 8. 校验
-- ------------------------------------------------------------------
SELECT 'em_maintain_plan'        AS tbl, COUNT(*) AS cnt FROM em_maintain_plan;
SELECT 'em_maintain_order'       AS tbl, COUNT(*) AS cnt FROM em_maintain_order;
SELECT 'em_maintain_record'      AS tbl, COUNT(*) AS cnt FROM em_maintain_record;
SELECT 'base_equipment.owner col' AS col_check, COUNT(*) AS cnt
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'base_equipment' AND COLUMN_NAME = 'owner';

SELECT 'em maintain perms' AS check_item, COUNT(*) AS cnt
FROM sys_menu WHERE perms LIKE 'equipment:maintain-%';

SELECT 'em maintain perm granted' AS check_item, COUNT(*) AS cnt
FROM sys_role_menu rm
JOIN sys_menu m ON m.id = rm.menu_id
WHERE m.perms LIKE 'equipment:maintain-%';
