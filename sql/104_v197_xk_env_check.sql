-- =====================================================================
-- v1.1.97 — XK 生产环境检查 (xk_env_check) 模块
--   1. xk_env_check 表 (车间/类型/主题/内容/计划时间/检查人 + 整改字段)
--   2. sys_menu 加 5 perm (list/add/edit/check/export)
--   3. sys_role_menu 给 6 内置角色授权
-- =====================================================================
-- 用法 (飞牛 canary 先行):
--   docker cp sql/104_v197_xk_env_check.sql <host>/tmp/ &&
--   docker exec erp-mysql-failover sh -c \
--     "exec mysql -uroot -perp_root_pwd --default-character-set=utf8mb4 industrial_erp < /tmp/104.sql"
-- =====================================================================

USE `industrial_erp`;

-- ------------------------------------------------------------------
-- 1. xk_env_check 表 (生产环境安全/卫生检查)
--    状态: DRAFT(草稿) / CHECKED(已整改完成)
--    env_type: TEMP_HUMIDITY(温湿度) / CLEANLINESS(洁净度) / FIRE_SAFETY(消防安全)
--              EMERGENCY_LIGHT(应急照明) / SAFETY_DOOR(安全门) / POWER(供电)
--              COMPRESSED_AIR(压缩空气)
--    level (整改等级): LOW / MEDIUM / HIGH (HIGH 强制整改)
--    status 联动: 草稿 -> check(level/owner/deadline) -> CHECKED
--    photo_urls: JSON 数组 (整改前 + 整改后)
-- ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `xk_env_check` (
  `id`                BIGINT        NOT NULL AUTO_INCREMENT,
  `check_no`          VARCHAR(32)   NOT NULL COMMENT '检查单号 ECyyyymmddNNNN',
  `workshop`          VARCHAR(64)   NOT NULL COMMENT '车间/区域',
  `env_type`          VARCHAR(32)   NOT NULL COMMENT 'TEMP_HUMIDITY/CLEANLINESS/FIRE_SAFETY/EMERGENCY_LIGHT/SAFETY_DOOR/POWER/COMPRESSED_AIR',
  `check_title`       VARCHAR(128)  NOT NULL COMMENT '检查主题',
  `check_content`     TEXT          DEFAULT NULL COMMENT '检查内容/描述',
  `planned_at`        DATETIME      DEFAULT NULL COMMENT '计划检查时间',
  `inspector`         VARCHAR(64)   DEFAULT NULL COMMENT '检查人 (冗余)',
  `level`             VARCHAR(16)   DEFAULT NULL COMMENT '整改等级 LOW/MEDIUM/HIGH (NULL=无问题)',
  `rectify_owner`     VARCHAR(64)   DEFAULT NULL COMMENT '整改负责人',
  `rectify_deadline`  DATE          DEFAULT NULL COMMENT '整改期限',
  `rectify_status`    VARCHAR(16)   DEFAULT 'NONE' COMMENT 'NONE/PENDING/IN_PROGRESS/DONE',
  `rectify_desc`      TEXT          DEFAULT NULL COMMENT '整改说明',
  `before_photos`     JSON          DEFAULT NULL COMMENT '整改前照片 (JSON 数组)',
  `after_photos`      JSON          DEFAULT NULL COMMENT '整改后照片 (JSON 数组)',
  `status`            VARCHAR(16)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/CHECKED',
  `remark`            VARCHAR(255)  DEFAULT NULL,
  `version`           INT           NOT NULL DEFAULT 0,
  `create_by`         BIGINT        DEFAULT NULL,
  `create_time`       DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`         BIGINT        DEFAULT NULL,
  `update_time`       DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`           TINYINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_xk_env_check_no` (`check_no`, `deleted`),
  KEY `idx_xk_env_workshop` (`workshop`, `status`, `deleted`),
  KEY `idx_xk_env_type`     (`env_type`, `status`, `deleted`),
  KEY `idx_xk_env_planned`  (`planned_at`, `status`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='XK 生产环境安全/卫生检查';

-- ------------------------------------------------------------------
-- 2. 权限 F 载体: 5 个 perm (list/add/edit/check/export)
--    sort_no 4801-4805 (与 em 4401-4423 + qa 4601-4613 错开)
-- ------------------------------------------------------------------
INSERT IGNORE INTO `sys_menu`(`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`perms`,`icon`,`sort_no`,`is_visible`,`status`) VALUES
(0,'XK环境检查查询','F','',NULL,'xk:env:list',NULL,4801,0,1),
(0,'XK环境检查新增','F','',NULL,'xk:env:add',NULL,4802,0,1),
(0,'XK环境检查编辑','F','',NULL,'xk:env:edit',NULL,4803,0,1),
(0,'XK环境检查审核','F','',NULL,'xk:env:check',NULL,4804,0,1),
(0,'XK环境检查导出','F','',NULL,'xk:env:export',NULL,4805,0,1);

-- ------------------------------------------------------------------
-- 3. 授权: 6 业务角色 (PC only, XK 不上 App)
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
       ''xk:env:list'', ''xk:env:add'', ''xk:env:edit'', ''xk:env:check'', ''xk:env:export''
     )
     AND r.role_code IN (
       ''SUPER_ADMIN'', ''PURCHASE_MGR'', ''SALES_MGR'',
       ''WAREHOUSE_MGR'', ''PRODUCTION_MGR'', ''FINANCE''
     )
     AND r.deleted = 0',
  'SELECT 1');
PREPARE s FROM @sql_grant; EXECUTE s; DEALLOCATE PREPARE s;

-- ------------------------------------------------------------------
-- 4. 校验
-- ------------------------------------------------------------------
SELECT 'xk_env_check'           AS tbl, COUNT(*) AS cnt FROM xk_env_check;
SELECT 'xk:env:* perm nodes'    AS perm_check, COUNT(*) AS cnt
FROM sys_menu WHERE perms LIKE 'xk:env:%';
SELECT 'xk:env:* grants'        AS grant_check, COUNT(*) AS cnt
FROM sys_role_menu rm JOIN sys_menu m ON m.id = rm.menu_id
WHERE m.perms LIKE 'xk:env:%';
