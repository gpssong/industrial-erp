-- v1.1.99 C4 — 生产排程 + 设备 OEE (MES 完善最后一档, 唯一需 DDL)
-- 2 新表: mes_schedule (工单×设备×工序×计划时段 排程实例) + mes_process_time (工序实际耗时流水, 支撑 OEE)
-- 全幂等: CREATE TABLE IF NOT EXISTS + 列/索引 information_schema 探测 + INSERT IGNORE perm 节点 + CROSS JOIN NOT EXISTS 角色授权
-- 部署: mysql -uroot -p --default-character-set=utf8mb4 industrial_erp < 113_v199_mes_schedule_oee.sql

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
  `oee` DECIMAL(5,2) NULL COMMENT 'OEE 百分比 (空闲时间×稼动率×良品率)',
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

-- ========== 3. sys_menu perm 节点 (F 型, 挂生产管理 目录) ==========
-- 复用 sql/54 的模式: 先探 sys_menu, INSERT IGNORE 建 2 个 perm 载体
INSERT IGNORE INTO `sys_menu`(`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`perms`,`icon`,`sort_no`,`is_visible`,`status`) VALUES
  (702, '生产排程查看', 'F', '', '', 'production:schedule:list', '', 2201, 0, 1),
  (702, '生产排程维护', 'F', '', '', 'production:schedule:edit', '', 2202, 0, 1);

-- ========== 4. 授权 — 6 内置角色 × 排程 perm × client_type='PC' (INSERT IGNORE + NOT EXISTS 双保险) ==========
DELIMITER //
CREATE PROCEDURE IF NOT EXISTS sp_grant_mes_schedule()
BEGIN
  DECLARE has_ct INT DEFAULT 0;
  SELECT COUNT(*) INTO has_ct FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_role_menu' AND COLUMN_NAME = 'client_type';
  IF has_ct = 1 THEN
    INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
    SELECT r.id, m.id, 'PC'
    FROM sys_role r CROSS JOIN sys_menu m
    WHERE m.perms IN ('production:schedule:list','production:schedule:edit')
      AND m.deleted = 0
      AND r.role_code IN ('SUPER_ADMIN','PURCHASE_MGR','SALES_MGR','WAREHOUSE_MGR','PRODUCTION_MGR','FINANCE')
      AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm
                      WHERE rm.role_id = r.id AND rm.menu_id = m.id AND rm.client_type = 'PC');
  ELSE
    INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
    SELECT r.id, m.id
    FROM sys_role r CROSS JOIN sys_menu m
    WHERE m.perms IN ('production:schedule:list','production:schedule:edit')
      AND m.deleted = 0
      AND r.role_code IN ('SUPER_ADMIN','PRODUCTION_MGR')
      AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = m.id);
  END IF;
END//
CALL sp_grant_mes_schedule()//
DROP PROCEDURE IF EXISTS sp_grant_mes_schedule//
DELIMITER ;
