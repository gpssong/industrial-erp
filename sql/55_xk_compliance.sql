-- =====================================================================
-- 五十五、v1.1.91 — XK 合规中心 (食品接触塑料吹膜厂 — XK16-204 审核)
--   1 个新 module (modules/xk) + 2 张新表 + 2 个 sys_config 键 + 4 F 型 perm
--
-- 背景:
--   XK16-204 食品相关产品工业生产许可证 对「食品接触塑料」生产企业的 5 条硬要求:
--     R1 受控记录: 所有单据 (原料入库/生产/维保/卫生/追溯) 有记录载体
--     R2 禁止物理删除: 已发布单据只能作废, 不允许 DELETE FROM
--     R3 审计留痕: 关键动作 (PDF/修改/上传附件/作废) 全程留痕
--     R4 数据留存 >= 2 年: 到期前不可被覆盖/清除
--     R5 服务器时间戳: 时间源走系统服务器, 不接受本地时钟
--     R6 图片对象存储: 现场照片走 OSS, 不进 DB 二进制
--
--   本次 XK 合规中心 7 页 (原料入库/批次溯源/设备维保/卫生检查/单据打印/
--   审计归档/用户角色) 前端 UI 已在 v1.1.90 交付, 全部走假数据。本 SQL +
--   modules/xk 后端把假数据切到真数据。
--
-- 用户 3 决策 (AskUserQuestion 拍板):
--   Q1) 架构: 方式 A 复用 — 基础数据走 mes/workflow/em/purchase 既有 module,
--        XK 只加 2 新表 (xk_safety_check + xk_audit_log) + 1 新 module (modules/xk)
--   Q2) 部署范围: 飞牛 canary 先行 (home 等飞牛稳定 24h 后再同步)
--   Q3) 照片上传: 复用 /api/system/upload/file (v1.1.79 已有), 不新建 XK 专用端点
--
-- ⚠️ MySQL 8 容器默认 latin1, 必须:
--   docker exec -i erp-mysql[-failover] mysql industrial_erp -uroot -perp_root_pwd \
--     --default-character-set=utf8mb4 < 55_xk_compliance.sql
-- =====================================================================

USE `industrial_erp`;

-- ------------------------------------------------------------------
-- 1. XK 卫生检查单 (独立于 wf_safety_record, XK 合规专用)
--    5 项核查快照 + 整改前/后照片 (JSON 6 张) + 整改闭环
-- ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `xk_safety_check` (
  `id`                  BIGINT        NOT NULL AUTO_INCREMENT,
  `check_no`            VARCHAR(32)   NOT NULL COMMENT '卫生检查单号 AJyyyymmddNNNN',
  `check_date`          DATE          NOT NULL,
  `area`                VARCHAR(64)   DEFAULT NULL COMMENT '吹膜洁净区/制袋区/分切区/印刷区',
  `inspector`           BIGINT        DEFAULT NULL,
  `inspector_name`      VARCHAR(64)   DEFAULT NULL,
  `result`              VARCHAR(16)   NOT NULL DEFAULT 'PASS' COMMENT 'PASS/FAIL',
  `item_snapshot`       JSON          DEFAULT NULL COMMENT '5 项核查快照 [{no, text, status, problem}]',
  `reform_owner`        VARCHAR(64)   DEFAULT NULL COMMENT '整改负责人',
  `reform_deadline`     DATE          DEFAULT NULL COMMENT '整改期限',
  `reform_photos_before` JSON         DEFAULT NULL COMMENT '整改前照片 JSON 数组 (最多 6 张 URL)',
  `reform_photos_after`  JSON         DEFAULT NULL COMMENT '整改后照片 JSON 数组 (最多 6 张 URL)',
  `remark`              VARCHAR(255)  DEFAULT NULL,
  `bill_status`         VARCHAR(16)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/CHECKED/VOID',
  `version`             INT           NOT NULL DEFAULT 0,
  `create_by`           BIGINT        DEFAULT NULL,
  `create_time`         DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`           BIGINT        DEFAULT NULL,
  `update_time`         DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`             TINYINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_xk_safety_check_no` (`check_no`, `deleted`),
  KEY `idx_xk_safety_status` (`bill_status`, `deleted`),
  KEY `idx_xk_safety_date`   (`check_date`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='XK 合规卫生检查单 (XK16-204 R1/R3)';

-- ------------------------------------------------------------------
-- 2. XK 合规审计流水
--    跟 sys_oper_log 互补: 这个专门给 XK 合规中心展示 (含 SHA256 + OSS 路径 + 2 年到期)
--    9 类 biz_type + 4 类 action (PDF/EDIT/UPLOAD/VOID)
-- ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `xk_audit_log` (
  `id`               BIGINT        NOT NULL AUTO_INCREMENT,
  `log_no`           VARCHAR(32)   NOT NULL COMMENT '审计流水号 XAyyyymmddNNNNN',
  `biz_type`         VARCHAR(16)   NOT NULL COMMENT 'INBOUND/PRODUCE/MAINTAIN/SAFETY/TRACE/EDIT/PDF/UPLOAD/VOID',
  `biz_bill_no`      VARCHAR(64)   DEFAULT NULL COMMENT '关联业务单号 (RK/WO/WB/AJ/FG 等)',
  `action`           VARCHAR(16)   NOT NULL COMMENT 'PDF/EDIT/UPLOAD/VOID',
  `change_desc`      VARCHAR(512)  DEFAULT NULL COMMENT '变更描述 (PDF 生成/字段修改/附件上传/作废原因)',
  `operator`         BIGINT        DEFAULT NULL COMMENT 'sys_user.id',
  `operator_name`    VARCHAR(64)   DEFAULT NULL COMMENT '冗余, 列表不 JOIN',
  `operator_role`    VARCHAR(64)   DEFAULT NULL COMMENT '角色名 (仓库员/质量 QA/管理员)',
  `sha256`           VARCHAR(64)   DEFAULT NULL COMMENT 'PDF 文件 SHA256 (XK16-204 R2 防篡改)',
  `oss_path`         VARCHAR(512)  DEFAULT NULL COMMENT '对象存储路径 (XK16-204 R6 图片/附件)',
  `size_kb`          INT           DEFAULT NULL COMMENT 'PDF/附件大小 KB',
  `expire_at`        DATE          DEFAULT NULL COMMENT '2 年到期日 (XK16-204 R4 数据留存)',
  `oper_time`        DATETIME      NOT NULL COMMENT '服务器时间戳 (XK16-204 R5)',
  `version`          INT           NOT NULL DEFAULT 0,
  `create_by`        BIGINT        DEFAULT NULL,
  `create_time`      DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`        BIGINT        DEFAULT NULL,
  `update_time`      DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`          TINYINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_xk_audit_log_no` (`log_no`, `deleted`),
  KEY `idx_xk_audit_biz`     (`biz_type`, `deleted`),
  KEY `idx_xk_audit_bill`    (`biz_bill_no`),
  KEY `idx_xk_audit_time`    (`oper_time`),
  KEY `idx_xk_audit_expire`  (`expire_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='XK 合规审计流水 (XK16-204 R2/R3/R4/R5/R6)';

-- ------------------------------------------------------------------
-- 3. sys_config 加 2 条 (INSERT IGNORE + UPDATE 幂等)
--    DASHBOARD_CONFIG: XK 段 6 项开关 JSON (齿轮按钮 + 系统设置 → 工作台配置 tab)
--    XK_AUDIT_RETENTION_YEARS: XK16-204 数据留存年限 (默认 2 年, 可配置)
-- ------------------------------------------------------------------
INSERT IGNORE INTO sys_config (id, config_name, config_key, config_value, config_type, remark)
VALUES
  (0, 'XK 工作台配置', 'DASHBOARD_CONFIG',
   '{"compliance":true,"kpi":true,"productionBoard":true,"todos":true,"quickLinks":true,"kpiStats":false}',
   1, 'XK 合规中心 6 段开关 (前端 dashboard 齿轮 + 系统设置 → 工作台配置)'),
  (0, 'XK 审计留存年限', 'XK_AUDIT_RETENTION_YEARS', '2',
   1, 'XK16-204 要求 >= 2 年');

-- id=0 走 ASSIGN_ID 自动填, 上面的 0 是占位 (MyBatis-Plus ASSIGN_ID 会覆盖)

-- =====================================================================
-- 4. 权限 F 载体 (parent_id=0, is_visible=0)
--     2 条 perm: xk:audit:list + xk:audit:archive
--     (其余 5 页 material/trace/maintenance/safety/print 全复用已有 perm, 不再 seed)
-- =====================================================================
INSERT IGNORE INTO `sys_menu`(`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`perms`,`icon`,`sort_no`,`is_visible`,`status`) VALUES
(0,'XK 审计日志查询','F','',NULL,'xk:audit:list',    NULL,4401,0,1),
(0,'XK 记录归档查询','F','',NULL,'xk:audit:archive', NULL,4402,0,1);

-- =====================================================================
-- 5. 授权策略 — 6 内置角色 × 2 perm × client_type='PC'
--     xk:audit:* 授 SUPER_ADMIN + WAREHOUSE_MGR + PRODUCTION_MGR
--     (仓管 + 生产管 + 超管, 其他角色 admin 兜底)
-- =====================================================================
SET @has_ct := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_role_menu' AND COLUMN_NAME = 'client_type'
);

SET @sql_xk_grant := IF(@has_ct > 0,
  'INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
   SELECT r.id, m.id, ''PC''
   FROM sys_role r CROSS JOIN sys_menu m
   WHERE m.perms IN (''xk:audit:list'', ''xk:audit:archive'')
     AND r.role_code IN (''SUPER_ADMIN'', ''WAREHOUSE_MGR'', ''PRODUCTION_MGR'')
     AND r.deleted = 0',
  'SELECT 1');
PREPARE s FROM @sql_xk_grant; EXECUTE s; DEALLOCATE PREPARE s;

-- =====================================================================
-- 6. 校验 (分行 SELECT, 不 UNION, 避免老 MySQL 1064)
-- =====================================================================
SELECT 'xk_safety_check' AS tbl, COUNT(*) AS cnt FROM xk_safety_check;
SELECT 'xk_audit_log'    AS tbl, COUNT(*) AS cnt FROM xk_audit_log;

SELECT 'sys_config.DASHBOARD_CONFIG' AS check_item, config_value FROM sys_config
WHERE config_key = 'DASHBOARD_CONFIG';
SELECT 'sys_config.XK_AUDIT_RETENTION_YEARS' AS check_item, config_value FROM sys_config
WHERE config_key = 'XK_AUDIT_RETENTION_YEARS';

SELECT 'xk:audit perms in sys_menu' AS check_item, COUNT(*) AS cnt
FROM sys_menu WHERE perms LIKE 'xk:audit:%';

SELECT 'xk:audit perms granted to roles' AS check_item, COUNT(*) AS cnt
FROM sys_role_menu rm
JOIN sys_menu m ON m.id = rm.menu_id
WHERE m.perms LIKE 'xk:audit:%';
