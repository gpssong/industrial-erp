-- v1.1.102 home 热修: 补齐 C4 生产排程/OEE (sql/113 在 home 整套未灌)
-- 现象: home.93gushi.com 生产排程页 GET /api/mes/schedule/page → 403
-- 根因: MesController.schedulePage() 门禁 production:schedule:list,
--       但 home sys_menu 无该 perm 节点 + 无 6 角色授权 + mes_schedule/mes_process_time 表缺失.
-- 基于 sql/113_v199_mes_schedule_oee.sql, 修正:
--   1) perm 节点 parent_id 动态取「生产管理」实际 id (home=7, 飞牛=702, 硬编码 702 在 home 挂错目录)
--   2) 授权走 has_client_type 探测 (home sys_role_menu 三列 PK client_type 默认 PC)
-- 全幂等: CREATE TABLE IF NOT EXISTS + INSERT IGNORE + NOT EXISTS 守门.
USE industrial_erp;

-- ========== 1. mes_schedule 生产排程实例 ==========
CREATE TABLE IF NOT EXISTS `mes_schedule` (
  `id` BIGINT NOT NULL COMMENT '雪花 ID',
  `prd_order_id` BIGINT NULL COMMENT '关联生产工单 prd_order.id',
  `prd_order_no` VARCHAR(64) NULL COMMENT '工单号 (冗余快照)',
  `equipment_id` BIGINT NULL COMMENT '设备 base_equipment.id',
  `equipment_code` VARCHAR(64) NULL COMMENT '设备编码 (冗余)',
  `equipment_name` VARCHAR(128) NULL COMMENT '设备名称 (冗余)',
  `process_no` INT NULL COMMENT '工序号',
  `process_name` VARCHAR(128) NULL COMMENT '工序名',
  `plan_start` DATETIME NULL COMMENT '计划开工时间',
  `plan_end` DATETIME NULL COMMENT '计划完工时间',
  `actual_start` DATETIME NULL COMMENT '实际开工 (报工首次记)',
  `actual_end` DATETIME NULL COMMENT '实际完工',
  `status` VARCHAR(32) NULL DEFAULT 'PLANNED' COMMENT 'PLANNED/IN_PROGRESS/DONE/CANCELLED',
  `oee` DECIMAL(5,2) NULL COMMENT 'OEE 百分比',
  `remark` VARCHAR(512) NULL,
  `create_by` BIGINT NULL, `create_time` DATETIME NULL,
  `update_by` BIGINT NULL, `update_time` DATETIME NULL,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_ms_order` (`prd_order_id`),
  KEY `idx_ms_equipment` (`equipment_id`),
  KEY `idx_ms_plan` (`plan_start`, `plan_end`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MES 生产排程实例 (工单×设备×工序×时段)';

-- ========== 2. mes_process_time 工序实际耗时流水 (OEE 数据源) ==========
CREATE TABLE IF NOT EXISTS `mes_process_time` (
  `id` BIGINT NOT NULL COMMENT '雪花 ID',
  `prd_process_id` BIGINT NULL COMMENT '工序实例 prd_process.id',
  `prd_order_id` BIGINT NULL COMMENT '关联工单',
  `prd_order_no` VARCHAR(64) NULL COMMENT '工单号 (冗余)',
  `equipment_id` BIGINT NULL COMMENT '设备 base_equipment.id',
  `equipment_code` VARCHAR(64) NULL COMMENT '设备编码 (冗余)',
  `process_no` INT NULL COMMENT '工序号',
  `process_name` VARCHAR(128) NULL COMMENT '工序名',
  `start_time` DATETIME NULL COMMENT '本次开始',
  `end_time` DATETIME NULL COMMENT '本次结束',
  `downtime` DECIMAL(10,2) NULL COMMENT '停机时长 (分钟)',
  `fault_reason` VARCHAR(256) NULL COMMENT '停机/故障原因',
  `create_by` BIGINT NULL, `create_time` DATETIME NULL,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_mpt_process` (`prd_process_id`),
  KEY `idx_mpt_equipment` (`equipment_id`),
  KEY `idx_mpt_order` (`prd_order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MES 工序实际耗时流水 (OEE 数据源)';

-- ========== 3. sys_menu perm 节点 (F 型, parent 动态取「生产管理」实际 id, home=7/飞牛=702) ==========
INSERT INTO sys_menu (parent_id, menu_name, menu_type, path, component, perms, icon, sort_no, is_visible, status)
SELECT
  IFNULL((SELECT id FROM sys_menu WHERE menu_name = '生产管理' AND deleted = 0 LIMIT 1), 0),
  '生产排程查看', 'F', '', '', 'production:schedule:list', '', 2201, 0, 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'production:schedule:list' AND deleted = 0);

INSERT INTO sys_menu (parent_id, menu_name, menu_type, path, component, perms, icon, sort_no, is_visible, status)
SELECT
  IFNULL((SELECT id FROM sys_menu WHERE menu_name = '生产管理' AND deleted = 0 LIMIT 1), 0),
  '生产排程维护', 'F', '', '', 'production:schedule:edit', '', 2202, 0, 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'production:schedule:edit' AND deleted = 0);

-- ========== 4. 授权 — 6 内置角色 × 排程 perm × client_type='PC' ==========
INSERT INTO sys_role_menu (role_id, menu_id, client_type)
SELECT r.id, m.id, 'PC'
FROM sys_role r
CROSS JOIN sys_menu m
WHERE m.perms IN ('production:schedule:list', 'production:schedule:edit')
  AND m.deleted = 0
  AND r.role_code IN ('SUPER_ADMIN','PURCHASE_MGR','SALES_MGR','WAREHOUSE_MGR','PRODUCTION_MGR','FINANCE')
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm
    WHERE rm.role_id = r.id AND rm.menu_id = m.id AND rm.client_type = 'PC'
  );

-- ========== 5. 验证 ==========
SELECT m.id, m.perms, m.menu_name, m.menu_type, m.status
FROM sys_menu m WHERE m.perms LIKE 'production:schedule:%' AND m.deleted = 0;
SELECT r.role_code, rm.client_type, COUNT(*) AS cnt
FROM sys_role_menu rm
JOIN sys_role r ON r.id = rm.role_id
JOIN sys_menu m ON m.id = rm.menu_id
WHERE m.perms LIKE 'production:schedule:%'
GROUP BY r.role_code, rm.client_type
ORDER BY r.role_code;
SELECT table_name FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name IN ('mes_schedule','mes_process_time');
