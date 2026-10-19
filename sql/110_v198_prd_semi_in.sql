-- =====================================================================
-- v1.1.98: 半成品入库 (prd_semi_in) 全套 — 打通 原料批 → 半成品卷批 → 成品批 追溯链
--
-- 背景 (飞牛 v1.1.95 审计 P0 断链): 半成品入库整段缺失, mes_batch SEMI 一期建表二期写,
-- 导致 MES 追溯链在"半成品"处断. 本 SQL 建 prd_semi_in 表 + prd_order 半成品/成品数量列
-- + production:semi-in:* 5 perm + 6 角色 PC 授权.
--
-- 幂等: information_schema 探测 + CREATE TABLE IF NOT EXISTS + ALTER NOT EXISTS + INSERT IGNORE/NOT EXISTS.
-- =====================================================================

-- 1) 半成品入库主表 (对齐 prd_finished_in)
CREATE TABLE IF NOT EXISTS `prd_semi_in` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT,
  `bill_no`       VARCHAR(32)   NOT NULL,
  `bill_date`     DATE          NOT NULL,
  `prd_order_id`  BIGINT        DEFAULT NULL,
  `prd_order_no`  VARCHAR(32)   DEFAULT NULL,
  `product_id`    BIGINT        NOT NULL,
  `product_code`  VARCHAR(64)   DEFAULT NULL,
  `product_name`  VARCHAR(128)  DEFAULT NULL,
  `spec`          VARCHAR(128)  DEFAULT NULL,
  `unit_id`       BIGINT        DEFAULT NULL,
  `unit_name`     VARCHAR(32)   DEFAULT NULL,
  `qty`           DECIMAL(18,4) NOT NULL DEFAULT 0,
  `price`         DECIMAL(18,4) DEFAULT 0 COMMENT '入库成本(归集)',
  `amount`        DECIMAL(18,4) DEFAULT 0,
  `warehouse_id`  BIGINT        NOT NULL,
  `location_id`   BIGINT        DEFAULT NULL,
  `batch_no`      VARCHAR(64)   DEFAULT NULL,
  `bill_status`   VARCHAR(32)   DEFAULT 'DRAFT',
  -- XK 门禁快照列 (仿 pur_receipt v1.1.95): 审核时把关联合格 FAI 检验单判定快照写回, 供前端展示 + 审计留痕
  `inspection_report_no` VARCHAR(64) DEFAULT NULL COMMENT '关联 FAI 检验单号',
  `inspection_result`    VARCHAR(32)  DEFAULT NULL COMMENT 'FAI 判定结果 (PASS/PASS_DISPOSITION)',
  `sample_qty`           DECIMAL(18,4) DEFAULT NULL COMMENT 'FAI 抽样数',
  `inspector_name`       VARCHAR(64)  DEFAULT NULL COMMENT 'FAI 检验人',
  `remark`        VARCHAR(500)  DEFAULT NULL,
  `create_by`     BIGINT        DEFAULT NULL,
  `create_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`     BIGINT        DEFAULT NULL,
  `update_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT       DEFAULT 0,
  `tenant_id`     BIGINT        DEFAULT 1,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_prd_psi_bill_no` (`bill_no`, `deleted`),
  KEY `idx_prd_psi_order` (`prd_order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='半成品入库单';

-- 2) prd_order 加 半成品/成品 累计数量列 (information_schema 探测幂等, 重复执行安全)
SET @has_semi := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='prd_order' AND COLUMN_NAME='prd_semi_qty');
SET @sql := IF(@has_semi=0,
  'ALTER TABLE prd_order ADD COLUMN prd_semi_qty DECIMAL(18,4) NULL DEFAULT 0 COMMENT ''累计半成品入库数量'' AFTER actual_qty',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_finish := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='prd_order' AND COLUMN_NAME='prd_finish_qty');
SET @sql := IF(@has_finish=0,
  'ALTER TABLE prd_order ADD COLUMN prd_finish_qty DECIMAL(18,4) NULL DEFAULT 0 COMMENT ''累计成品入库数量'' AFTER prd_semi_qty',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3) 5 个 production:semi-in perm 节点
--    1 M 菜单 (路径 + 反向挂 list perm, 仿 sql/56 成品入库模式)
--    4 F perm 载体 (add/check/delete/uncheck), parent_id=0, is_visible=0, 信息守门 NOT EXISTS
SET @prod_parent = (SELECT id FROM sys_menu WHERE menu_name='生产管理' AND deleted=0 LIMIT 1);

INSERT INTO sys_menu (menu_type, menu_name, parent_id, path, component, perms, icon, sort_no, is_visible, status)
SELECT 'M', '半成品入库', @prod_parent, '/production/semi-in', 'production/SemiFinishedIn', '', 'Box', 3040, 1, 1
FROM DUAL WHERE @prod_parent IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE path='/production/semi-in' AND deleted=0);

-- M 菜单反向挂 list perm (让 el-tree 当可授权叶子)
UPDATE sys_menu SET perms='production:semi-in:list'
WHERE path='/production/semi-in' AND deleted=0 AND (perms IS NULL OR perms='');

-- F perm 载体 (add/check/delete/uncheck + list 兜底, 全部 NOT EXISTS 守门, 仿 R10: 先查重不靠 INSERT IGNORE 兜底)
INSERT INTO sys_menu (menu_type, menu_name, parent_id, path, component, perms, icon, sort_no, is_visible, status)
SELECT 'F', '半成品入库查询', 0, '', NULL, 'production:semi-in:list', NULL, 3041, 0, 1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='production:semi-in:list' AND deleted=0);

INSERT INTO sys_menu (menu_type, menu_name, parent_id, path, component, perms, icon, sort_no, is_visible, status)
SELECT 'F', '半成品入库新增', 0, '', NULL, 'production:semi-in:add', NULL, 3042, 0, 1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='production:semi-in:add' AND deleted=0);

INSERT INTO sys_menu (menu_type, menu_name, parent_id, path, component, perms, icon, sort_no, is_visible, status)
SELECT 'F', '半成品入库审核', 0, '', NULL, 'production:semi-in:check', NULL, 3043, 0, 1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='production:semi-in:check' AND deleted=0);

INSERT INTO sys_menu (menu_type, menu_name, parent_id, path, component, perms, icon, sort_no, is_visible, status)
SELECT 'F', '半成品入库删除', 0, '', NULL, 'production:semi-in:delete', NULL, 3044, 0, 1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='production:semi-in:delete' AND deleted=0);

INSERT INTO sys_menu (menu_type, menu_name, parent_id, path, component, perms, icon, sort_no, is_visible, status)
SELECT 'F', '半成品入库反审核', 0, '', NULL, 'production:semi-in:uncheck', NULL, 3045, 0, 1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='production:semi-in:uncheck' AND deleted=0);

-- 4) 6 内置角色 × PC 授权 (CROSS JOIN 派生 + 子查询反查 menu_id, 避开 @var NULL 隐式短路, 仿 sql/56)
SET @safe_guard = 1;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
SELECT r.id, m.id, 'PC'
FROM sys_role r
CROSS JOIN (SELECT id FROM sys_menu WHERE path='/production/semi-in' AND deleted=0 LIMIT 1) m
WHERE r.role_code IN ('SUPER_ADMIN','PURCHASE_MGR','SALES_MGR','WAREHOUSE_MGR','PRODUCTION_MGR','FINANCE')
  AND r.deleted=0 AND @safe_guard=1;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
SELECT r.id, m.id, 'PC'
FROM sys_role r
CROSS JOIN (SELECT id FROM sys_menu WHERE perms='production:semi-in:add' AND deleted=0 LIMIT 1) m
WHERE r.role_code IN ('SUPER_ADMIN','PURCHASE_MGR','SALES_MGR','WAREHOUSE_MGR','PRODUCTION_MGR','FINANCE')
  AND r.deleted=0 AND @safe_guard=1;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
SELECT r.id, m.id, 'PC'
FROM sys_role r
CROSS JOIN (SELECT id FROM sys_menu WHERE perms='production:semi-in:check' AND deleted=0 LIMIT 1) m
WHERE r.role_code IN ('SUPER_ADMIN','PURCHASE_MGR','SALES_MGR','WAREHOUSE_MGR','PRODUCTION_MGR','FINANCE')
  AND r.deleted=0 AND @safe_guard=1;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
SELECT r.id, m.id, 'PC'
FROM sys_role r
CROSS JOIN (SELECT id FROM sys_menu WHERE perms='production:semi-in:delete' AND deleted=0 LIMIT 1) m
WHERE r.role_code IN ('SUPER_ADMIN','PURCHASE_MGR','SALES_MGR','WAREHOUSE_MGR','PRODUCTION_MGR','FINANCE')
  AND r.deleted=0 AND @safe_guard=1;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
SELECT r.id, m.id, 'PC'
FROM sys_role r
CROSS JOIN (SELECT id FROM sys_menu WHERE perms='production:semi-in:uncheck' AND deleted=0 LIMIT 1) m
WHERE r.role_code IN ('SUPER_ADMIN','PURCHASE_MGR','SALES_MGR','WAREHOUSE_MGR','PRODUCTION_MGR','FINANCE')
  AND r.deleted=0 AND @safe_guard=1;
