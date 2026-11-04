-- =====================================================================
-- v1.1.167 — 标签层：两张新表 + sys_user.inspector_code + LABEL_MASTER 主数据 + 三条默认模板
-- 设计见 docs/32_标签层设计规格.md §2 §3。
-- ⚠ 只在 .150 主库跑！本项目 binlog_format=ROW，.32 是下游：
--   这些 INSERT 一旦先在 canary 跑过，灌主库后复制回 .32 必撞 1062 → Slave_SQL_Running: No
--   （sql/142/143/152 真实翻车三次）。canary 只做只读演练。
-- ⚠ 本文件含中文，必须 --default-character-set=utf8mb4 灌，否则 COMMENT 与模板名乱码。
-- 幂等：CREATE TABLE IF NOT EXISTS + INSERT IGNORE + information_schema 探测，可重复执行。
-- =====================================================================
USE `industrial_erp`;
SET NAMES utf8mb4;

SELECT '--- 1) base_label_template ---' AS step;
CREATE TABLE IF NOT EXISTS `base_label_template` (
  `id`            BIGINT      NOT NULL COMMENT '雪花 id (MyBatis-Plus ASSIGN_ID)',
  `template_code` VARCHAR(64) NOT NULL COMMENT '模板码；**活行内唯一**（软删行不占位，故不建 UNIQUE 索引，由服务层按 deleted=0 校验）',
  `template_name` VARCHAR(128) NOT NULL COMMENT '模板名',
  `biz_type`      VARCHAR(32) NOT NULL COMMENT 'FG_ROLL 成品卷标 / RAW_PALLET 原料垛标 / CARTON 箱标 / CERT 合格证(本期只占枚举)',
  `width_mm`      INT         NOT NULL DEFAULT 80  COMMENT '标签宽 mm',
  `height_mm`     INT         NOT NULL DEFAULT 60  COMMENT '标签高 mm',
  `gap_mm`        INT         NOT NULL DEFAULT 3   COMMENT '标签间隙 mm (TSPL GAP)',
  `dpi`           INT         NOT NULL DEFAULT 203 COMMENT '203dpi ⇒ 80×60mm = 640×480 点；元素坐标一律以点存',
  `direction`     VARCHAR(8)  NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL / REVERSE (TSPL DIRECTION)',
  `copies`        INT         NOT NULL DEFAULT 1   COMMENT '每张单打印份数',
  `elements`      LONGTEXT    NOT NULL COMMENT '结构化元素数组 JSON，见 docs/32 §3',
  `is_default`    TINYINT     NOT NULL DEFAULT 0   COMMENT '同 biz_type 的活行里至多一条为 1',
  `status`        TINYINT     NOT NULL DEFAULT 1   COMMENT '1 启用',
  `remark`        VARCHAR(255) DEFAULT NULL,
  `create_by`     BIGINT      DEFAULT NULL,
  `create_time`   DATETIME    DEFAULT CURRENT_TIMESTAMP,
  `update_by`     BIGINT      DEFAULT NULL,
  `update_time`   DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT     NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_blt_biz` (`biz_type`, `status`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标签模板 (结构化元素, 与打印机厂商无关)';

SELECT '--- 2) base_label_print_log ---' AS step;
CREATE TABLE IF NOT EXISTS `base_label_print_log` (
  `id`            BIGINT      NOT NULL COMMENT '雪花 id',
  `biz_type`      VARCHAR(32) NOT NULL,
  `biz_id`        BIGINT      DEFAULT NULL COMMENT '单据 id',
  `biz_no`        VARCHAR(64) DEFAULT NULL COMMENT '单据号 (快照)',
  `template_id`   BIGINT      DEFAULT NULL,
  `template_code` VARCHAR(64) DEFAULT NULL COMMENT '模板码 (快照, 模板改了日志仍可对上)',
  `channel`       VARCHAR(16) NOT NULL COMMENT 'BT_TSPL 手机蓝牙 / FEIE 飞鹅云 / EXPORT 导出文本',
  `printer_name`  VARCHAR(128) DEFAULT NULL COMMENT '蓝牙通道: 手机上报的设备名',
  `printer_mac`   VARCHAR(32)  DEFAULT NULL COMMENT '蓝牙通道: MAC (绑定不落库, 只能从日志事后推)',
  `copies`        INT          DEFAULT 1,
  `content`       LONGTEXT     DEFAULT NULL COMMENT '渲染结果快照 (TSPL 原文或飞鹅指令)',
  `content_hash`  VARCHAR(64)  DEFAULT NULL COMMENT 'SHA-256 hex, 查重/幂等 (同 sys_feie_print_log 口径)',
  `status`        VARCHAR(16) NOT NULL DEFAULT 'SUCCESS' COMMENT 'SUCCESS / FAIL',
  `error_msg`     VARCHAR(512) DEFAULT NULL COMMENT '失败原因 (渲染异常/蓝牙发送失败都要落)',
  `create_by`     BIGINT      DEFAULT NULL COMMENT '打印人 (MetaObjectHandler 从当前登录用户回填)',
  `create_time`   DATETIME    DEFAULT CURRENT_TIMESTAMP,
  `update_by`     BIGINT      DEFAULT NULL,
  `update_time`   DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT     NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_bpl_biz` (`biz_type`, `biz_id`, `deleted`),
  KEY `idx_bpl_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标签打印日志 (XK 检查时自证打过什么、谁打的)';

SELECT '--- 3) sys_user.inspector_code ---' AS step;
SET @col_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'inspector_code');
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE `sys_user` ADD COLUMN `inspector_code` VARCHAR(32) DEFAULT NULL COMMENT ''v1.1.167 检验员代号 (标签渲染 inspectorCode 字段; 空则渲染空串并在预览标红, 不阻断打印)''',
  'SELECT ''sys_user.inspector_code 已存在, 跳过'' AS msg');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SELECT '--- 4) sys_config: LABEL_MASTER + 品牌 tab 四个新键 ---' AS step;
INSERT IGNORE INTO `sys_config` (`config_name`, `config_key`, `config_value`, `config_type`, `remark`, `create_by`, `update_by`)
VALUES
 ('标签主数据', 'LABEL_MASTER',
  '{"producerName":"舟山市鹏程包装有限公司","producerAddress":"浙江省舟山市舟山高新技术产业园区大成九路32号","xkNo":"浙 XK16-204-01155","execStandard":"Q/ZPC 1-2017","storageCondition":"储存在清洁、干燥、通风、温度适宜的库房内，避免阳光照射，距热源不小于 1.0 m，堆放合理；贮存期为自生产之日起一年"}',
  1, 'XK 证发证日期 2023-03-10, 有效期至 2028-07-14 (到期提醒以后做)', 1, 1),
 ('统一社会信用代码', 'SYS_CREDIT_CODE', '', 1, 'v1.1.167 「品牌与工厂」tab 接真 (docs/32 §6.2)', 1, 1),
 ('工厂编码',        'SYS_FACTORY_CODE', '', 1, 'v1.1.167 「品牌与工厂」tab 接真', 1, 1),
 ('企业英文名称',    'SYS_EN_NAME',      '', 1, 'v1.1.167 「品牌与工厂」tab 接真', 1, 1),
 ('单据页脚',        'SYS_FOOTER',       '', 1, 'v1.1.167 「品牌与工厂」tab 接真', 1, 1);

-- 已有同 key 行时 INSERT IGNORE 不覆盖，但键必须存在才能被 PUT /system/config/value 更新 —— 检查行数应为 5
SELECT config_key, CHAR_LENGTH(config_value) AS len FROM sys_config
 WHERE config_key IN ('LABEL_MASTER','SYS_CREDIT_CODE','SYS_FACTORY_CODE','SYS_EN_NAME','SYS_FOOTER') AND deleted = 0;
SELECT JSON_UNQUOTE(JSON_EXTRACT(config_value,'$.xkNo')) AS xk_no
  FROM sys_config WHERE config_key = 'LABEL_MASTER';   -- 期望: 浙 XK16-204-01155

SELECT '--- 5) 三条默认模板 ---' AS step;
INSERT IGNORE INTO `base_label_template`
 (`id`,`template_code`,`template_name`,`biz_type`,`width_mm`,`height_mm`,`gap_mm`,`dpi`,`direction`,`copies`,`elements`,`is_default`,`status`,`remark`,`create_by`,`update_by`)
VALUES
 (1167000000000000001,'FG_ROLL_DEFAULT','成品卷标 80×60','FG_ROLL',80,60,3,203,'NORMAL',1,
 '[{"type":"text","x":20,"y":20,"text":"品名","size":24,"bold":true},{"type":"text","x":110,"y":20,"field":"productName","size":24,"required":true},
  {"type":"text","x":20,"y":60,"text":"规格","size":24},{"type":"text","x":110,"y":60,"field":"spec","size":24},
  {"type":"text","x":20,"y":100,"text":"数量","size":24},{"type":"text","x":110,"y":100,"field":"qty","size":24},
  {"type":"text","x":20,"y":140,"text":"单位","size":24},{"type":"text","x":110,"y":140,"field":"unitName","size":24},
  {"type":"line","x":20,"y":180,"w":600,"h":2},
  {"type":"text","x":20,"y":200,"text":"批号","size":24},{"type":"text","x":110,"y":200,"field":"batchNo","size":24,"required":true},
  {"type":"text","x":20,"y":240,"text":"生产日期","size":24},{"type":"text","x":160,"y":240,"field":"productionDate","size":24},
  {"type":"text","x":20,"y":280,"text":"工单号","size":24},{"type":"text","x":160,"y":280,"field":"orderNo","size":24},
  {"type":"qrcode","x":480,"y":200,"field":"batchNo","size":7},
  {"type":"barcode","x":20,"y":320,"field":"billNo","size":60},
  {"type":"line","x":20,"y":400,"w":600,"h":2},
  {"type":"multiline","x":20,"y":415,"field":"producerName","size":16,"w":600,"required":true},
  {"type":"text","x":20,"y":455,"field":"xkNo","size":16,"required":true}]',
  1,1,'v1.1.167 种子；批号/生产日期取 mes_batch.production_date (非 bill_date)',1,1),
 (1167000000000000002,'RAW_PALLET_DEFAULT','原料垛标 80×60','RAW_PALLET',80,60,3,203,'NORMAL',1,
 '[{"type":"text","x":20,"y":20,"text":"原料","size":32,"bold":true},{"type":"text","x":20,"y":70,"field":"productName","size":32,"required":true},
  {"type":"line","x":20,"y":120,"w":600,"h":2},
  {"type":"text","x":20,"y":140,"text":"供应商","size":24},{"type":"multiline","x":150,"y":140,"field":"supplierName","size":24,"w":460},
  {"type":"text","x":20,"y":200,"text":"批号","size":24},{"type":"text","x":110,"y":200,"field":"batchNo","size":24,"required":true},
  {"type":"text","x":20,"y":240,"text":"数量","size":24},{"type":"text","x":110,"y":240,"field":"qty","size":24},
  {"type":"text","x":180,"y":240,"field":"unitName","size":24},
  {"type":"text","x":20,"y":280,"text":"报告号","size":24},{"type":"text","x":160,"y":280,"field":"inspectionReportNo","size":24},
  {"type":"barcode","x":20,"y":330,"field":"batchNo","size":60},
  {"type":"text","x":20,"y":430,"field":"producerName","size":16}]',
  1,1,'v1.1.167 种子；按 pur_receipt_detail 行展开，一次打印可能出 N 张',1,1),
 (1167000000000000003,'CARTON_DEFAULT','箱标 80×60','CARTON',80,60,3,203,'NORMAL',1,
 '[{"type":"text","x":20,"y":20,"text":"客户","size":24,"bold":true},{"type":"multiline","x":110,"y":20,"field":"customerName","size":24,"w":500,"required":true},
  {"type":"multiline","x":20,"y":80,"field":"deliveryAddress","size":16,"w":600},
  {"type":"text","x":20,"y":130,"text":"电话","size":16},{"type":"text","x":110,"y":130,"field":"deliveryPhone","size":16},
  {"type":"line","x":20,"y":165,"w":600,"h":2},
  {"type":"text","x":20,"y":180,"field":"productName","size":32,"required":true},
  {"type":"text","x":20,"y":230,"field":"spec","size":24},
  {"type":"text","x":20,"y":270,"text":"数量","size":24},{"type":"text","x":110,"y":270,"field":"qty","size":24,"bold":true},
  {"type":"text","x":180,"y":270,"field":"unitName","size":24},
  {"type":"text","x":20,"y":310,"text":"单号","size":16},{"type":"text","x":110,"y":310,"field":"deliveryBillNo","size":16},
  {"type":"text","x":340,"y":310,"text":"日期","size":16},{"type":"text","x":430,"y":310,"field":"deliveryDate","size":16},
  {"type":"text","x":20,"y":440,"field":"batchNo","size":16,"w":600}]',
  1,1,'v1.1.167 种子；batchNo 是 | 连接的多批次串 (v1.1.152)，放低位小字号',1,1);
