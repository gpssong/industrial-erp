-- =====================================================================
-- 20. 飞鹅云打印机配置表 + 菜单权限
-- =====================================================================

-- 20.1 飞鹅打印机配置表
DROP TABLE IF EXISTS `sys_feie_printer_config`;
CREATE TABLE `sys_feie_printer_config` (
  `id`              BIGINT         NOT NULL AUTO_INCREMENT,
  `printer_name`    VARCHAR(64)    NOT NULL COMMENT '打印机配置名称',
  `ukey`            VARCHAR(128)   NOT NULL COMMENT '飞鹅 UKey',
  `device_sn`       VARCHAR(128)   DEFAULT NULL COMMENT '设备序列号 (留空则自动发现)',
  `status`          TINYINT        DEFAULT 1 COMMENT '1=启用 0=停用',
  `remark`          VARCHAR(255)   DEFAULT NULL,
  `create_by`       BIGINT         DEFAULT NULL,
  `create_time`     DATETIME       DEFAULT CURRENT_TIMESTAMP,
  `update_by`       BIGINT         DEFAULT NULL,
  `update_time`     DATETIME       DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`         TINYINT        DEFAULT 0,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='飞鹅云打印机配置';

-- 20.2 菜单: 飞鹅打印机配置 (system:feie:*)
-- 父菜单: 系统设置 (id=1, 已在 seed 数据中)
INSERT INTO `sys_menu` (`menu_name`, `parent_id`, `order_num`, `path`, `component`, `is_frame`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `remark`)
VALUES ('飞鹅打印机', 1, 7, 'feie-printer', NULL, 1, 'M', '0', '0', 'system:feie:list', 'Printer', 'admin', NOW(), '飞鹅云打印机配置管理');

SET @feie_menu_id = LAST_INSERT_ID();

INSERT INTO `sys_menu` (`menu_name`, `parent_id`, `order_num`, `path`, `component`, `is_frame`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `remark`)
VALUES ('飞鹅打印机查询', @feie_menu_id, 1, '', '', 1, 'C', '0', '0', 'system:feie:list', '#', 'admin', NOW(), '');

INSERT INTO `sys_menu` (`menu_name`, `parent_id`, `order_num`, `path`, `component`, `is_frame`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `remark`)
VALUES ('飞鹅打印机新增', @feie_menu_id, 2, '', '', 1, 'F', '0', '0', 'system:feie:add', '#', 'admin', NOW(), '');

INSERT INTO `sys_menu` (`menu_name`, `parent_id`, `order_num`, `path`, `component`, `is_frame`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `remark`)
VALUES ('飞鹅打印机编辑', @feie_menu_id, 3, '', '', 1, 'F', '0', '0', 'system:feie:edit', '#', 'admin', NOW(), '');

INSERT INTO `sys_menu` (`menu_name`, `parent_id`, `order_num`, `path`, `component`, `is_frame`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `remark`)
VALUES ('飞鹅打印机删除', @feie_menu_id, 4, '', '', 1, 'F', '0', '0', 'system:feie:delete', '#', 'admin', NOW(), '');

INSERT INTO `sys_menu` (`menu_name`, `parent_id`, `order_num`, `path`, `component`, `is_frame`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `remark`)
VALUES ('飞鹅打印机测试', @feie_menu_id, 5, '', '', 1, 'F', '0', '0', 'system:feie:test', '#', 'admin', NOW(), '');

-- 20.3 生产单飞鹅打印权限
INSERT INTO `sys_menu` (`menu_name`, `parent_id`, `order_num`, `path`, `component`, `is_frame`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `remark`)
VALUES ('飞鹅打印', 0, 0, '', '', 1, 'F', '0', '0', 'production:order:feie-print', '#', 'admin', NOW(), '生产单飞鹅打印权限');

SET @feie_print_perm_id = LAST_INSERT_ID();

-- 20.4 将飞鹅打印权限分配给所有已有角色 (admin/manager/operator/viewer)
INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT r.id, @feie_print_perm_id FROM `sys_role` r WHERE r.`deleted` = 0;

-- 20.5 飞鹅打印机配置权限也分配给管理员角色
INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT r.id, @feie_menu_id FROM `sys_role` r WHERE r.`deleted` = 0 AND r.`role_key` IN ('admin', 'manager');
