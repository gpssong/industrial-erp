-- =====================================================================
-- 21. 飞鹅云打印日志表
--   用于: 记录每次飞鹅云打印的请求/响应/耗时/状态, 供运营回查与失败重试
-- =====================================================================

DROP TABLE IF EXISTS `sys_feie_print_log`;
CREATE TABLE `sys_feie_print_log` (
  `id`              BIGINT         NOT NULL AUTO_INCREMENT,
  `biz_type`        VARCHAR(32)    NOT NULL COMMENT '单据类型: PRD_ORDER/SAL_DELIVERY/SAL_RETURN/PUR_RECEIPT/PUR_RETURN/INV_CHECK',
  `bill_id`         BIGINT         NOT NULL COMMENT '单据ID',
  `bill_no`         VARCHAR(64)    DEFAULT NULL COMMENT '单据号 (冗余便于查)',
  `config_id`       BIGINT         DEFAULT NULL COMMENT '打印机配置ID (sys_feie_printer_config.id)',
  `device_sn`       VARCHAR(128)   DEFAULT NULL COMMENT '目标设备SN',
  `content_hash`    CHAR(32)       DEFAULT NULL COMMENT '渲染内容 MD5 (幂等键)',
  `status`          TINYINT        NOT NULL DEFAULT 0 COMMENT '0=失败 1=已下发 2=已打印',
  `resp_code`       INT            DEFAULT NULL COMMENT '飞鹅返回 code',
  `resp_msg`        VARCHAR(500)   DEFAULT NULL COMMENT '飞鹅返回 msg',
  `cost_ms`         INT            DEFAULT NULL COMMENT '请求耗时 (ms)',
  `user_id`         BIGINT         DEFAULT NULL COMMENT '操作人ID',
  `user_name`       VARCHAR(64)    DEFAULT NULL COMMENT '操作人 (冗余)',
  `client_ip`       VARCHAR(64)    DEFAULT NULL COMMENT '客户端 IP',
  `create_time`     DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_biz_bill` (`biz_type`, `bill_id`),
  KEY `idx_status_time` (`status`, `create_time`),
  KEY `idx_user_time` (`user_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='飞鹅云打印日志';

-- 21.2 飞鹅打印日志查询菜单 (父菜单: 飞鹅打印机 id 见 20_add_feie_printer.sql)
SET @feie_menu_id := (SELECT id FROM sys_menu WHERE perms = 'system:feie:list' AND deleted = 0 LIMIT 1);

INSERT INTO `sys_menu` (`menu_name`, `parent_id`, `sort_no`, `path`, `component`, `menu_type`, `is_visible`, `status`, `perms`, `icon`, `create_by`, `create_time`)
SELECT '飞鹅打印日志', @feie_menu_id, 6, '', '', 'C', 0, 0, 'system:feie:log', '#', NULL, NOW()
WHERE @feie_menu_id IS NOT NULL;

-- 21.3 日志菜单分配给管理员
INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT r.id, m.id FROM sys_role r, sys_menu m
WHERE r.deleted = 0 AND m.deleted = 0
  AND r.role_code IN ('admin', 'manager')
  AND m.perms = 'system:feie:log';