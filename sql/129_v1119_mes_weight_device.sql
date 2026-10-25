-- v1.1.119 — 吹膜机米克重控制器数据采集 (ESP32 RS485 嗅探/采集 → ERP 实时曲线)
-- 3 新表: mes_weight_device (采集器注册) + mes_weight_channel (通道/寄存器表)
--          + mes_weight_sample (采样流水)
-- 背景: 米重只显示在车间触摸屏, 办公室看不到 → 无法质量追溯 / 工艺分析
--      ESP32 旁听控制器 485 总线, HTTP POST 到 /device/weight/report
-- 全幂等: CREATE TABLE IF NOT EXISTS + NOT EXISTS perm 探测 + NOT EXISTS 授权守门
-- ⚠️ 两处必须遵守的坑 (照 sql/113 + sql/122 修正后的写法):
--    1. DDL 绝对不要 AUTO_INCREMENT — 全局 mybatis-plus id-type: ASSIGN_ID 雪花 ID
--    2. sys_menu.parent_id 必须动态解析 — home 站「生产管理」id=7, 飞牛站 id=702,
--       硬编码 702 会在 home 站把菜单挂到错误目录 (sql/122 已踩过此坑)
-- 部署: docker exec -i erp-mysql[-failover] mysql industrial_erp -uroot -p<pwd> \
--       --default-character-set=utf8mb4 < 129_v1119_mes_weight_device.sql
--
-- ⚠️ 编号说明 (v1.1.124): 本脚本原名 `128_v1119_mes_weight_device.sql`, 与
--    `128_v1119_bom_ratio_semantics.sql` (BOM 配比正名 + 单位回填) **编号撞车**,
--    已改为 129。**执行顺序**: 先 128 (BOM), 后 129 (本脚本)。
--    ⚠️ sql/128 是幂等脚本但含状态位 UPDATE (is_main 位切换), 按 R24 必须连跑
--    3 次验证 —— 编号唯一是跑对脚本的前提, 别再重号。

USE industrial_erp;

-- ========== 1. mes_weight_device 采集器注册表 (ESP32 ↔ ERP 对应关系) ==========
CREATE TABLE IF NOT EXISTS `mes_weight_device` (
  `id` BIGINT NOT NULL COMMENT '雪花 ID',
  `device_code` VARCHAR(32) NOT NULL COMMENT '设备编码 (ESP32 上报标识, 唯一)',
  `device_name` VARCHAR(128) NULL COMMENT '设备名称',
  `equipment_id` BIGINT NULL COMMENT '关联设备 base_equipment.id (吹膜机)',
  `equipment_code` VARCHAR(64) NULL COMMENT '设备编码 (冗余快照)',
  `equipment_name` VARCHAR(128) NULL COMMENT '设备名称 (冗余快照)',
  `workshop` VARCHAR(64) NULL COMMENT '车间',
  `channel_count` INT NOT NULL DEFAULT 1 COMMENT '称重通道数 (1/2/3)',
  `baud_rate` INT NOT NULL DEFAULT 9600 COMMENT '485 波特率 (协议反推后回填)',
  `slave_addr` INT NULL COMMENT 'Modbus 从站地址 (协议反推后回填)',
  `protocol` VARCHAR(32) NULL DEFAULT 'MODBUS_RTU' COMMENT 'MODBUS_RTU / MODBUS_ASCII / VENDOR',
  `report_interval_ms` INT NOT NULL DEFAULT 5000 COMMENT '上报间隔毫秒 (勿低于 5000)',
  `fw_version` VARCHAR(32) NULL COMMENT '固件版本',
  `online_status` VARCHAR(16) NULL DEFAULT 'OFFLINE' COMMENT 'ONLINE / OFFLINE',
  `last_report_time` DATETIME NULL COMMENT '最后上报时间 (超 2 个周期判 OFFLINE)',
  `last_report_ip` VARCHAR(64) NULL COMMENT '最后上报来源 IP',
  `device_key_masked` VARCHAR(32) NULL COMMENT '设备密钥掩码 (后6位, 绝不存明文)',
  `remark` VARCHAR(512) NULL,
  `create_by` BIGINT NULL, `create_time` DATETIME NULL,
  `update_by` BIGINT NULL, `update_time` DATETIME NULL,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mwd_code` (`device_code`),
  KEY `idx_mwd_equipment` (`equipment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MES 米克重采集器注册表 (ESP32 ↔ ERP)';

-- ========== 2. mes_weight_channel 通道配置 (寄存器表 + 设定值 + 公差) ==========
-- 协议反推完成后回填 reg_start: 哪个寄存器是米重, scale: 缩放系数
CREATE TABLE IF NOT EXISTS `mes_weight_channel` (
  `id` BIGINT NOT NULL COMMENT '雪花 ID',
  `device_id` BIGINT NOT NULL COMMENT '采集器 mes_weight_device.id',
  `channel_no` INT NOT NULL COMMENT '通道号 1/2/3',
  `channel_name` VARCHAR(64) NULL COMMENT '通道名 (称重1/2/3)',
  `reg_start` INT NULL COMMENT '米重寄存器起始地址 (十六进制十进制均可, 如 0)',
  `reg_type` INT NULL DEFAULT 4 COMMENT 'Modbus 功能码 3=保持寄存器 4=输入寄存器',
  `scale` DECIMAL(18,6) NULL DEFAULT 1.000000 COMMENT '缩放系数: 实际值=寄存器值×scale',
  `unit` VARCHAR(16) NULL DEFAULT 'g' COMMENT '单位 g / kg / g/m2',
  `set_weight` DECIMAL(18,3) NULL COMMENT '设定米重 (工艺目标值)',
  `upper_tol` DECIMAL(18,3) NULL COMMENT '上公差 (设定值+此值=上限)',
  `lower_tol` DECIMAL(18,3) NULL COMMENT '下公差 (设定值-此值=下限)',
  `sort_no` INT NULL DEFAULT 1 COMMENT '排序号',
  `remark` VARCHAR(512) NULL,
  `create_by` BIGINT NULL, `create_time` DATETIME NULL,
  `update_by` BIGINT NULL, `update_time` DATETIME NULL,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_mwc_device` (`device_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MES 米克重通道配置 (寄存器表+设定值+公差)';

-- ========== 3. mes_weight_sample 采样流水 (时序表, 曲线数据源) ==========
CREATE TABLE IF NOT EXISTS `mes_weight_sample` (
  `id` BIGINT NOT NULL COMMENT '雪花 ID',
  `device_id` BIGINT NOT NULL COMMENT '采集器 mes_weight_device.id',
  `device_code` VARCHAR(32) NULL COMMENT '设备编码 (冗余快照)',
  `equipment_id` BIGINT NULL COMMENT '设备 base_equipment.id',
  `equipment_code` VARCHAR(64) NULL COMMENT '设备编码 (冗余快照)',
  `prd_order_id` BIGINT NULL COMMENT '关联生产工单 (可空, 由前端指定或自动匹配)',
  `prd_order_no` VARCHAR(64) NULL COMMENT '工单号 (冗余快照)',
  `channel_no` INT NOT NULL DEFAULT 1 COMMENT '通道号',
  `set_weight` DECIMAL(18,3) NULL COMMENT '设定米重 (冗余快照, 便于历史回溯)',
  `actual_weight` DECIMAL(18,3) NULL COMMENT '实测米重',
  `deviation` DECIMAL(18,3) NULL COMMENT '偏差 = 实测 - 设定',
  `deviation_pct` DECIMAL(10,3) NULL COMMENT '偏差百分比',
  `is_over` TINYINT NOT NULL DEFAULT 0 COMMENT '是否超公差 1=是',
  `line_speed` DECIMAL(10,2) NULL COMMENT '线速度 m/min',
  `output_kg` DECIMAL(14,3) NULL COMMENT '累计产量 kg',
  `roll_length` DECIMAL(14,2) NULL COMMENT '累计长度 m',
  `collect_time` DATETIME NOT NULL COMMENT '采样时间 (设备侧时钟)',
  `create_by` BIGINT NULL, `create_time` DATETIME NULL,
  `update_by` BIGINT NULL, `update_time` DATETIME NULL,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_mws_dev_time` (`device_id`, `collect_time`),
  KEY `idx_mws_eq_time` (`equipment_id`, `collect_time`),
  KEY `idx_mws_ch_time` (`channel_no`, `collect_time`),
  KEY `idx_mws_order` (`prd_order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MES 米克重采样流水 (实时曲线数据源)';

-- ========== 4. sys_menu perm 节点 (F 型, parent 动态取「生产管理」实际 id) ==========
-- 注意: home 站「生产管理」id=7, 飞牛站 id=702 —— 必须动态解析
INSERT INTO sys_menu (parent_id, menu_name, menu_type, path, component, perms, icon, sort_no, is_visible, status)
SELECT
  IFNULL((SELECT id FROM sys_menu WHERE menu_name = '生产管理' AND deleted = 0 LIMIT 1), 0),
  '米重采集查看', 'F', '', '', 'mes:weight:list', '', 4401, 0, 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'mes:weight:list' AND deleted = 0);

INSERT INTO sys_menu (parent_id, menu_name, menu_type, path, component, perms, icon, sort_no, is_visible, status)
SELECT
  IFNULL((SELECT id FROM sys_menu WHERE menu_name = '生产管理' AND deleted = 0 LIMIT 1), 0),
  '米重采集维护', 'F', '', '', 'mes:weight:edit', '', 4402, 0, 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'mes:weight:edit' AND deleted = 0);

-- ========== 5. M 型侧边栏菜单节点 (吹膜米重采集) ==========
INSERT INTO sys_menu (menu_type, menu_name, parent_id, path, component, perms, icon, sort_no, is_visible, status)
SELECT
  'M', '吹膜米重采集',
  IFNULL((SELECT id FROM sys_menu WHERE menu_name = '生产管理' AND deleted = 0 LIMIT 1), 0),
  '/production/weight', 'production/Weight', '', 'Odometer', 4410, 1, 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/production/weight' AND deleted = 0);

-- 反向挂 list perm 到 M 节点 (后端按 perms 匹配, 前端 router meta.perm 对齐)
UPDATE sys_menu SET perms = 'mes:weight:list'
WHERE path = '/production/weight' AND deleted = 0 AND (perms IS NULL OR perms = '');

-- ========== 6. 授权 — 2 角色 × 米重 perm × client_type (PC/APP/BOTH 三份) ==========
-- 米重是生产数据, 只给 SUPER_ADMIN + PRODUCTION_MGR, 不给采购/销售/财务
SET @safe_guard = 1;

INSERT INTO sys_role_menu (role_id, menu_id, client_type)
SELECT r.id, m.id, 'PC'
FROM sys_role r
CROSS JOIN sys_menu m
WHERE m.perms IN ('mes:weight:list', 'mes:weight:edit')
  AND m.deleted = 0
  AND r.role_code IN ('SUPER_ADMIN', 'PRODUCTION_MGR')
  AND r.deleted = 0 AND @safe_guard = 1
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm
    WHERE rm.role_id = r.id AND rm.menu_id = m.id AND rm.client_type = 'PC'
  );

INSERT INTO sys_role_menu (role_id, menu_id, client_type)
SELECT r.id, m.id, 'APP'
FROM sys_role r
CROSS JOIN sys_menu m
WHERE m.perms IN ('mes:weight:list', 'mes:weight:edit')
  AND m.deleted = 0
  AND r.role_code IN ('SUPER_ADMIN', 'PRODUCTION_MGR')
  AND r.deleted = 0 AND @safe_guard = 1
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm
    WHERE rm.role_id = r.id AND rm.menu_id = m.id AND rm.client_type = 'APP'
  );

-- ========== 7. 验证 ==========
SELECT '建表检查' AS check_item;
SELECT table_name FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN ('mes_weight_device', 'mes_weight_channel', 'mes_weight_sample');

SELECT '菜单/perm 节点' AS check_item;
SELECT id, parent_id, menu_type, menu_name, path, perms, status
FROM sys_menu WHERE perms LIKE 'mes:weight:%' OR path = '/production/weight';

SELECT '角色授权数 (期望: 2 角色 × 2 perm × 2 端 = 8, 加 M 节点 4)' AS check_item;
SELECT r.role_code, rm.client_type, COUNT(*) AS cnt
FROM sys_role_menu rm
JOIN sys_role r ON r.id = rm.role_id
JOIN sys_menu m ON m.id = rm.menu_id
WHERE m.perms LIKE 'mes:weight:%' OR m.path = '/production/weight'
GROUP BY r.role_code, rm.client_type
ORDER BY r.role_code;