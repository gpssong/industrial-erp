-- =====================================================================
-- 五十、v1.1.79 — 工作流模块 (设备维保记录 / 安全检查记录 / 应急预案 / 租客主数据)
-- =====================================================================
-- 背景: 用户要新增「工作流」模块, 三类记录 + 租客主数据:
--   1. 设备维保记录  wf_maintain_record(+detail) — 某设备某次维保 (谁/何时/内容/结果/下次到期)
--   2. 安全检查记录  wf_safety_record(+detail)    — 某次安全检查 (检查项/结果/隐患/整改责任人)
--   3. 应急预案      wf_emergency_plan(+detail)    — 按租客组织的预案 (类型/场景/步骤/负责人/演练)
--   4. 租客主数据    base_tenant                    — 名称/联系人/区域/设备清单
-- 深度 = 记录台账 (简单 CRUD + 审核/反审核 + 删除), 不做审批流转引擎; 仅 PC 端.
-- 深度/数据源/端 决策见 v1.1.79 计划 (用户拍板).
--
-- 约定 (与 01-08 基线 + 20/48 增量一致):
--   - CREATE TABLE IF NOT EXISTS (幂等, 不 DROP); utf8mb4; 审计字段 + deleted + tenant_id
--   - 主键 id 省略, 走雪花/AUTO_INCREMENT (MyBatis-Plus @TableId ASSIGN_ID)
--   - sys_menu: M 型目录 + C 型页面 (perms 非空) + B 型按钮; NOT EXISTS 幂等
--   - sys_role_menu: 6 内置角色 × PC; information_schema 探测 client_type 列, 老库退化 2 列
--   - sql/47 教训: 可授权菜单节点 perms 必须非空
--
-- 用法 (双站 home + 飞牛各跑一次, 必须 utf8mb4):
--   mysql -uroot -perp_root_pwd --default-character-set=utf8mb4 \
--     industrial_erp < sql/50_v179_workflow_module.sql
--
-- 幂等: 建表 IF NOT EXISTS; sys_menu NOT EXISTS; sys_role_menu INSERT IGNORE. 可重复跑.
-- =====================================================================

USE `industrial_erp`;

-- ---------------------------------------------------------------------
-- 1) 租客主数据 base_tenant
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `base_tenant` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键 (雪花)',
  `tenant_code`   VARCHAR(32)  DEFAULT NULL COMMENT '租客编号',
  `tenant_name`   VARCHAR(128) NOT NULL COMMENT '租客名称',
  `contact_name`  VARCHAR(64)  DEFAULT NULL COMMENT '联系人',
  `contact_phone` VARCHAR(32)  DEFAULT NULL COMMENT '联系电话',
  `area`          VARCHAR(255) DEFAULT NULL COMMENT '区域/位置',
  `device_list`   VARCHAR(500) DEFAULT NULL COMMENT '设备清单 (逗号分隔)',
  `remark`        VARCHAR(500) DEFAULT NULL COMMENT '备注',
  `status`        TINYINT      DEFAULT 1 COMMENT '状态 1=启用 0=停用',
  `create_by`     BIGINT       DEFAULT NULL,
  `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `update_by`     BIGINT       DEFAULT NULL,
  `update_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT      DEFAULT 0,
  `tenant_id`     BIGINT       DEFAULT 1,
  `version`       INT          DEFAULT 0 COMMENT '乐观锁 (MyBatis-Plus @Version, 防止并发更新丢失)',
  PRIMARY KEY (`id`),
  KEY `idx_base_tenant_name` (`tenant_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租客主数据';

-- ---------------------------------------------------------------------
-- 2) 设备维保记录 (head + detail)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `wf_maintain_record` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键 (雪花)',
  `record_no`     VARCHAR(32)  NOT NULL COMMENT '记录编号: WM+yyyyMMdd+0001',
  `device_name`   VARCHAR(128) NOT NULL COMMENT '设备名称',
  `maint_date`    DATE         NOT NULL COMMENT '维保日期',
  `maint_type`    VARCHAR(32)  DEFAULT NULL COMMENT '维保类型 (日常保养/大修/定期...)',
  `operator`      VARCHAR(64)  DEFAULT NULL COMMENT '执行人',
  `result`        VARCHAR(255) DEFAULT NULL COMMENT '维保结果',
  `next_due_date` DATE         DEFAULT NULL COMMENT '下次到期日',
  `bill_status`   VARCHAR(32)  DEFAULT 'DRAFT' COMMENT '状态 DRAFT/CHECKED',
  `remark`        VARCHAR(500) DEFAULT NULL,
  `create_by`     BIGINT       DEFAULT NULL,
  `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `update_by`     BIGINT       DEFAULT NULL,
  `update_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT      DEFAULT 0,
  `tenant_id`     BIGINT       DEFAULT 1,
  `version`       INT          DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_wf_maintain_record_no` (`record_no`, `deleted`),
  KEY `idx_wf_maintain_device` (`device_name`),
  KEY `idx_wf_maintain_due` (`next_due_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备维保记录';

CREATE TABLE IF NOT EXISTS `wf_maintain_detail` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键 (雪花)',
  `record_id`   BIGINT       NOT NULL COMMENT '外键 wf_maintain_record.id',
  `item`        VARCHAR(128) DEFAULT NULL COMMENT '维保项目',
  `content`     VARCHAR(500) DEFAULT NULL COMMENT '维保内容',
  `result`      VARCHAR(255) DEFAULT NULL COMMENT '结果/状态',
  `remark`      VARCHAR(500) DEFAULT NULL,
  `create_by`   BIGINT       DEFAULT NULL,
  `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `update_by`   BIGINT       DEFAULT NULL,
  `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`     TINYINT      DEFAULT 0,
  `tenant_id`   BIGINT       DEFAULT 1,
  PRIMARY KEY (`id`),
  KEY `idx_wf_maintain_detail_record` (`record_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备维保记录明细';

-- ---------------------------------------------------------------------
-- 3) 安全检查记录 (head + detail)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `wf_safety_record` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键 (雪花)',
  `record_no`     VARCHAR(32)  NOT NULL COMMENT '记录编号: SIN+yyyyMMdd+0001',
  `check_date`    DATE         NOT NULL COMMENT '检查日期',
  `check_type`    VARCHAR(32)  DEFAULT NULL COMMENT '检查类型 (日常/专项/节前...)',
  `site`          VARCHAR(128) DEFAULT NULL COMMENT '检查地点',
  `checker`       VARCHAR(64)  DEFAULT NULL COMMENT '检查人',
  `risk_count`    INT          DEFAULT 0 COMMENT '隐患数量',
  `next_due_date` DATE         DEFAULT NULL COMMENT '下次到期日',
  `bill_status`   VARCHAR(32)  DEFAULT 'DRAFT' COMMENT '状态 DRAFT/CHECKED',
  `remark`        VARCHAR(500) DEFAULT NULL,
  `create_by`     BIGINT       DEFAULT NULL,
  `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `update_by`     BIGINT       DEFAULT NULL,
  `update_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT      DEFAULT 0,
  `tenant_id`     BIGINT       DEFAULT 1,
  `version`       INT          DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_wf_safety_record_no` (`record_no`, `deleted`),
  KEY `idx_wf_safety_due` (`next_due_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='安全检查记录';

CREATE TABLE IF NOT EXISTS `wf_safety_detail` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键 (雪花)',
  `record_id`      BIGINT       NOT NULL COMMENT '外键 wf_safety_record.id',
  `check_item`     VARCHAR(128) DEFAULT NULL COMMENT '检查项',
  `result`         VARCHAR(255) DEFAULT NULL COMMENT '检查结果 (合格/不合格/...)',
  `risk_desc`      VARCHAR(500) DEFAULT NULL COMMENT '隐患描述',
  `handler`        VARCHAR(64)  DEFAULT NULL COMMENT '整改责任人',
  `fix_deadline`   DATE         DEFAULT NULL COMMENT '整改期限',
  `remark`         VARCHAR(500) DEFAULT NULL,
  `create_by`      BIGINT       DEFAULT NULL,
  `create_time`    DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `update_by`      BIGINT       DEFAULT NULL,
  `update_time`    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`        TINYINT      DEFAULT 0,
  `tenant_id`      BIGINT       DEFAULT 1,
  PRIMARY KEY (`id`),
  KEY `idx_wf_safety_detail_record` (`record_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='安全检查记录明细';

-- ---------------------------------------------------------------------
-- 4) 应急预案 (head + detail), 关联租客
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `wf_emergency_plan` (
  `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键 (雪花)',
  `plan_no`           VARCHAR(32)  NOT NULL COMMENT '预案编号: EP+yyyyMMdd+0001',
  `tenant_id`         BIGINT       DEFAULT NULL COMMENT '外键 base_tenant.id',
  `tenant_name`       VARCHAR(128) DEFAULT NULL COMMENT '租客名称 (去规范, 打印/列表)',
  `plan_type`         VARCHAR(32)  DEFAULT NULL COMMENT '预案类型 (消防/触电/泄漏/...)',
  `scenario`          VARCHAR(255) DEFAULT NULL COMMENT '适用场景',
  `owner`             VARCHAR(64)  DEFAULT NULL COMMENT '预案负责人',
  `drill_date`        DATE         DEFAULT NULL COMMENT '最近演练日期',
  `next_drill_date`   DATE         DEFAULT NULL COMMENT '下次演练日期',
  `bill_status`       VARCHAR(32)  DEFAULT 'DRAFT' COMMENT '状态 DRAFT/CHECKED',
  `remark`            VARCHAR(500) DEFAULT NULL,
  `create_by`         BIGINT       DEFAULT NULL,
  `create_time`       DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `update_by`         BIGINT       DEFAULT NULL,
  `update_time`       DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`           TINYINT      DEFAULT 0,
  `tenant_id2`        BIGINT       DEFAULT 1,
  `version`          INT          DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_wf_emergency_plan_no` (`plan_no`, `deleted`),
  KEY `idx_wf_emergency_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应急预案';

CREATE TABLE IF NOT EXISTS `wf_emergency_detail` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键 (雪花)',
  `plan_id`     BIGINT       NOT NULL COMMENT '外键 wf_emergency_plan.id',
  `step_no`     INT          DEFAULT NULL COMMENT '步骤序号',
  `step_desc`   VARCHAR(500) DEFAULT NULL COMMENT '处置步骤',
  `remark`      VARCHAR(500) DEFAULT NULL,
  `create_by`   BIGINT       DEFAULT NULL,
  `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `update_by`   BIGINT       DEFAULT NULL,
  `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`     TINYINT      DEFAULT 0,
  `tenant_id`   BIGINT       DEFAULT 1,
  PRIMARY KEY (`id`),
  KEY `idx_wf_emergency_detail_plan` (`plan_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应急预案明细';

-- =====================================================================
-- 5) sys_menu: 「工作流」目录 + 4 个 C 型页面 + B 型按钮
--    每个可授权节点 perms 非空 (sql/47 教训). 目录 M 也带 perms.
-- =====================================================================
-- 目录: 工作流 (M, 顶层 parent_id=0, perms 用设备维保 list 作为可授权叶子载体)
INSERT INTO `sys_menu`
  (`parent_id`, `menu_name`, `menu_type`, `path`, `component`, `perms`, `icon`, `sort_no`, `is_visible`, `status`)
SELECT 0, '工作流', 'M', '/workflow', NULL, 'work:maintain:list', 'Operation', 20, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `path` = '/workflow' AND `deleted` = 0);

SET @wf_id := (SELECT `id` FROM `sys_menu` WHERE `path` = '/workflow' AND `deleted` = 0 LIMIT 1);

-- 4 个 C 型页面节点 (各带真实 perms)
INSERT INTO `sys_menu`
  (`parent_id`, `menu_name`, `menu_type`, `path`, `component`, `perms`, `icon`, `sort_no`, `is_visible`, `status`)
SELECT @wf_id, '租客管理', 'C', '/workflow/tenant', 'workflow/Tenant.vue', 'work:tenant:list', 'User', 1, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `path` = '/workflow/tenant' AND `deleted` = 0);

INSERT INTO `sys_menu`
  (`parent_id`, `menu_name`, `menu_type`, `path`, `component`, `perms`, `icon`, `sort_no`, `is_visible`, `status`)
SELECT @wf_id, '设备维保记录', 'C', '/workflow/maintain', 'workflow/Maintain.vue', 'work:maintain:list', 'Tools', 2, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `path` = '/workflow/maintain' AND `deleted` = 0);

INSERT INTO `sys_menu`
  (`parent_id`, `menu_name`, `menu_type`, `path`, `component`, `perms`, `icon`, `sort_no`, `is_visible`, `status`)
SELECT @wf_id, '安全检查记录', 'C', '/workflow/safety', 'workflow/Safety.vue', 'work:safety:list', 'Document', 3, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `path` = '/workflow/safety' AND `deleted` = 0);

INSERT INTO `sys_menu`
  (`parent_id`, `menu_name`, `menu_type`, `path`, `component`, `perms`, `icon`, `sort_no`, `is_visible`, `status`)
SELECT @wf_id, '应急预案', 'C', '/workflow/emergency', 'workflow/Emergency.vue', 'work:emergency:list', 'Warning', 4, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `path` = '/workflow/emergency' AND `deleted` = 0);

SET @tenant_menu_id := (SELECT `id` FROM `sys_menu` WHERE `path` = '/workflow/tenant' AND `deleted` = 0 LIMIT 1);
SET @maintain_menu_id := (SELECT `id` FROM `sys_menu` WHERE `path` = '/workflow/maintain' AND `deleted` = 0 LIMIT 1);
SET @safety_menu_id := (SELECT `id` FROM `sys_menu` WHERE `path` = '/workflow/safety' AND `deleted` = 0 LIMIT 1);
SET @emergency_menu_id := (SELECT `id` FROM `sys_menu` WHERE `path` = '/workflow/emergency' AND `deleted` = 0 LIMIT 1);

-- B 型按钮权限 (挂在对应页面 C 节点下, is_visible=0)
-- 租客: add/edit/delete
INSERT INTO `sys_menu` (`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`perms`,`icon`,`sort_no`,`is_visible`,`status`)
SELECT @tenant_menu_id, '租客新增', 'B', '', '', 'work:tenant:add', '', 1, 0, 1
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `perms` = 'work:tenant:add' AND `deleted` = 0);
INSERT INTO `sys_menu` (`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`perms`,`icon`,`sort_no`,`is_visible`,`status`)
SELECT @tenant_menu_id, '租客编辑', 'B', '', '', 'work:tenant:edit', '', 2, 0, 1
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `perms` = 'work:tenant:edit' AND `deleted` = 0);
INSERT INTO `sys_menu` (`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`perms`,`icon`,`sort_no`,`is_visible`,`status`)
SELECT @tenant_menu_id, '租客删除', 'B', '', '', 'work:tenant:delete', '', 3, 0, 1
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `perms` = 'work:tenant:delete' AND `deleted` = 0);
-- 设备维保: add/edit/delete/check/uncheck
INSERT INTO `sys_menu` (`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`perms`,`icon`,`sort_no`,`is_visible`,`status`)
SELECT @maintain_menu_id, '维保记录新增', 'B', '', '', 'work:maintain:add', '', 1, 0, 1
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `perms` = 'work:maintain:add' AND `deleted` = 0);
INSERT INTO `sys_menu` (`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`perms`,`icon`,`sort_no`,`is_visible`,`status`)
SELECT @maintain_menu_id, '维保记录编辑', 'B', '', '', 'work:maintain:edit', '', 2, 0, 1
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `perms` = 'work:maintain:edit' AND `deleted` = 0);
INSERT INTO `sys_menu` (`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`perms`,`icon`,`sort_no`,`is_visible`,`status`)
SELECT @maintain_menu_id, '维保记录删除', 'B', '', '', 'work:maintain:delete', '', 3, 0, 1
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `perms` = 'work:maintain:delete' AND `deleted` = 0);
INSERT INTO `sys_menu` (`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`perms`,`icon`,`sort_no`,`is_visible`,`status`)
SELECT @maintain_menu_id, '维保记录审核', 'B', '', '', 'work:maintain:check', '', 4, 0, 1
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `perms` = 'work:maintain:check' AND `deleted` = 0);
INSERT INTO `sys_menu` (`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`perms`,`icon`,`sort_no`,`is_visible`,`status`)
SELECT @maintain_menu_id, '维保记录反审核', 'B', '', '', 'work:maintain:uncheck', '', 5, 0, 1
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `perms` = 'work:maintain:uncheck' AND `deleted` = 0);
-- 安全检查: add/edit/delete/check/uncheck
INSERT INTO `sys_menu` (`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`perms`,`icon`,`sort_no`,`is_visible`,`status`)
SELECT @safety_menu_id, '安全检查新增', 'B', '', '', 'work:safety:add', '', 1, 0, 1
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `perms` = 'work:safety:add' AND `deleted` = 0);
INSERT INTO `sys_menu` (`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`perms`,`icon`,`sort_no`,`is_visible`,`status`)
SELECT @safety_menu_id, '安全检查编辑', 'B', '', '', 'work:safety:edit', '', 2, 0, 1
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `perms` = 'work:safety:edit' AND `deleted` = 0);
INSERT INTO `sys_menu` (`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`perms`,`icon`,`sort_no`,`is_visible`,`status`)
SELECT @safety_menu_id, '安全检查删除', 'B', '', '', 'work:safety:delete', '', 3, 0, 1
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `perms` = 'work:safety:delete' AND `deleted` = 0);
INSERT INTO `sys_menu` (`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`perms`,`icon`,`sort_no`,`is_visible`,`status`)
SELECT @safety_menu_id, '安全检查审核', 'B', '', '', 'work:safety:check', '', 4, 0, 1
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `perms` = 'work:safety:check' AND `deleted` = 0);
INSERT INTO `sys_menu` (`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`perms`,`icon`,`sort_no`,`is_visible`,`status`)
SELECT @safety_menu_id, '安全检查反审核', 'B', '', '', 'work:safety:uncheck', '', 5, 0, 1
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `perms` = 'work:safety:uncheck' AND `deleted` = 0);
-- 应急预案: add/edit/delete/check/uncheck
INSERT INTO `sys_menu` (`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`perms`,`icon`,`sort_no`,`is_visible`,`status`)
SELECT @emergency_menu_id, '应急预案新增', 'B', '', '', 'work:emergency:add', '', 1, 0, 1
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `perms` = 'work:emergency:add' AND `deleted` = 0);
INSERT INTO `sys_menu` (`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`perms`,`icon`,`sort_no`,`is_visible`,`status`)
SELECT @emergency_menu_id, '应急预案编辑', 'B', '', '', 'work:emergency:edit', '', 2, 0, 1
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `perms` = 'work:emergency:edit' AND `deleted` = 0);
INSERT INTO `sys_menu` (`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`perms`,`icon`,`sort_no`,`is_visible`,`status`)
SELECT @emergency_menu_id, '应急预案删除', 'B', '', '', 'work:emergency:delete', '', 3, 0, 1
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `perms` = 'work:emergency:delete' AND `deleted` = 0);
INSERT INTO `sys_menu` (`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`perms`,`icon`,`sort_no`,`is_visible`,`status`)
SELECT @emergency_menu_id, '应急预案审核', 'B', '', '', 'work:emergency:check', '', 4, 0, 1
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `perms` = 'work:emergency:check' AND `deleted` = 0);
INSERT INTO `sys_menu` (`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`perms`,`icon`,`sort_no`,`is_visible`,`status`)
SELECT @emergency_menu_id, '应急预案反审核', 'B', '', '', 'work:emergency:uncheck', '', 5, 0, 1
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `perms` = 'work:emergency:uncheck' AND `deleted` = 0);

-- =====================================================================
-- 6) 角色授权: 6 内置角色 × PC (client_type 探测, 老库退化 2 列)
-- =====================================================================
SET @has_ct := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'sys_role_menu'
    AND column_name = 'client_type'
);

-- 目录 + 4 C 页面 + 所有 B 按钮, 一次性授权 (给工作流整套)
SET @grant_ids := (
  SELECT GROUP_CONCAT(`id`) FROM `sys_menu`
  WHERE (`path` IN ('/workflow','/workflow/tenant','/workflow/maintain','/workflow/safety','/workflow/emergency')
     OR `perms` LIKE 'work:%')
    AND `deleted` = 0
);

SET @stmt := IF(
  @has_ct > 0,
  CONCAT('INSERT IGNORE INTO sys_role_menu(role_id, menu_id, client_type) '
        'SELECT r.id, m.id, "PC" FROM sys_role r '
        'CROSS JOIN sys_menu m WHERE m.id IN (', @grant_ids, ') '
        'AND r.role_code IN ("SUPER_ADMIN","PURCHASE_MGR","SALES_MGR","WAREHOUSE_MGR","PRODUCTION_MGR","FINANCE") AND r.deleted = 0'),
  CONCAT('INSERT IGNORE INTO sys_role_menu(role_id, menu_id) '
        'SELECT r.id, m.id FROM sys_role r '
        'CROSS JOIN sys_menu m WHERE m.id IN (', @grant_ids, ') '
        'AND r.role_code IN ("SUPER_ADMIN","PURCHASE_MGR","SALES_MGR","WAREHOUSE_MGR","PRODUCTION_MGR","FINANCE") AND r.deleted = 0')
);
PREPARE ps FROM @stmt;
EXECUTE ps;
DEALLOCATE PREPARE ps;

-- =====================================================================
-- 7) 校验
-- =====================================================================
-- SELECT m.id, m.menu_name, m.menu_type, m.perms FROM sys_menu m WHERE m.perms LIKE 'work:%' AND m.deleted = 0;
-- SELECT rm.role_id, m.perms, rm.client_type FROM sys_role_menu rm JOIN sys_menu m ON m.id = rm.menu_id
--   WHERE m.perms LIKE 'work:%' AND m.deleted = 0 LIMIT 50;

-- =====================================================================
-- 回滚 (慎用 — 会移除菜单授权 + 建表; 代码侧需同步删除 workflow 模块):
-- DELETE FROM sys_role_menu WHERE menu_id IN (SELECT id FROM sys_menu WHERE (perms LIKE 'work:%' OR path LIKE '/workflow%') AND deleted=0);
-- DELETE FROM sys_menu WHERE perms LIKE 'work:%' OR path LIKE '/workflow%';
-- DROP TABLE IF EXISTS wf_maintain_detail, wf_maintain_record, wf_safety_detail, wf_safety_record, wf_emergency_detail, wf_emergency_plan, base_tenant;
-- =====================================================================
