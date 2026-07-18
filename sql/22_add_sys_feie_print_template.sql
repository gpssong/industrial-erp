-- =====================================================================
-- 22. 飞鹅云打印模板表 (用户可编辑的飞鹅标签模板)
--   用于: 用户在 PC 端编辑器中编辑飞鹅标签内容, 保存到 DB
--   渲染: FeiePrintService.print() 优先使用此表的 is_default=1 模板
-- =====================================================================

DROP TABLE IF EXISTS `sys_feie_print_template`;
CREATE TABLE `sys_feie_print_template` (
  `id`                  BIGINT       NOT NULL AUTO_INCREMENT          COMMENT '模板ID',
  `name`                VARCHAR(64)  NOT NULL                          COMMENT '模板名称 (如 58mm车间模板)',
  `biz_type`            VARCHAR(32)  NOT NULL                          COMMENT '单据类型: PRD_ORDER/SAL_DELIVERY/SAL_RETURN/PUR_RECEIPT/PUR_RETURN/INV_CHECK',
  `printer_config_id`   BIGINT       NOT NULL                          COMMENT '打印机配置ID (sys_feie_printer_config.id)',
  `content`             LONGTEXT     NOT NULL                          COMMENT '飞鹅标签模板: <CB>标题</CB><BR>${order.billNo!\'\'}<BR>...',
  `paper_width`         INT          DEFAULT 58                         COMMENT '纸张宽度 mm (58/80/110)',
  `status`              TINYINT      DEFAULT 1                         COMMENT '0=停用 1=启用',
  `is_default`          TINYINT      DEFAULT 0                         COMMENT '同一 (biz_type, printer_config_id) 仅 1 个 is_default=1',
  `remark`              VARCHAR(255) DEFAULT NULL                      COMMENT '备注',
  `create_by`           BIGINT       DEFAULT NULL,
  `create_time`         DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `update_by`           BIGINT       DEFAULT NULL,
  `update_time`         DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`             TINYINT      DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_default_per_printer` (`biz_type`, `printer_config_id`, `is_default`, `deleted`),
  KEY `idx_biz_printer` (`biz_type`, `printer_config_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='飞鹅云打印模板 (飞鹅标签语言)';

-- 22.2 菜单: 飞鹅打印模板 (父菜单: 飞鹅打印机)
SET @feie_menu_id := (SELECT id FROM sys_menu WHERE perms = 'system:feie:list' AND deleted = 0 LIMIT 1);

INSERT INTO `sys_menu` (`menu_name`, `parent_id`, `sort_no`, `path`, `component`, `menu_type`, `is_visible`, `status`, `perms`, `icon`, `create_by`, `create_time`)
SELECT '飞鹅打印模板', @feie_menu_id, 7, 'feie-template', NULL, 'M', 0, 0, 'system:feie:template', '#', NULL, NOW()
WHERE @feie_menu_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'system:feie:template' AND deleted = 0);

-- 22.3 模板菜单分配给 SUPER_ADMIN / admin 角色
INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT r.id, m.id FROM sys_role r, sys_menu m
WHERE r.deleted = 0 AND m.deleted = 0
  AND r.role_code IN ('SUPER_ADMIN', 'admin', 'manager')
  AND m.perms = 'system:feie:template';
