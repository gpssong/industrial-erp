-- =====================================================================
-- 五十三、v1.1.80 — MES 第一阶段: 打开「车间黑箱」
--   工单管理 + 扫码报工 + 物料批次绑定
--
-- 背景 (用户目标):
--   ERP 已有订单/BOM/库存/采购, 但车间是「黑箱」: 订单开工后, 做到哪道
--   工序、谁做的、做了多少、这卷膜用了哪批料/流到哪个客户, 系统一无所知.
--   本 SQL 落地 MES 一期数据基础:
--     1) mes_process_def  工序定义 (按产品/BOM 定制工序链)
--     2) prd_process      工序实例 (现有死表启用, 开单展开 N 行, 报工驱动状态)
--     3) mes_batch        批次/卷号主数据 (RAW原料/SEMI半成品卷/FG成品)
--     4) mes_batch_link   批次流向绑定 (料→半成品卷→成品→客户, 递归追溯)
--   权限: 报工 F 载体 perm production:report:add / production:report:list
--   设计原则:
--     - prd_process 是 sql/06 已建的死表 (无 entity/service/UI), 本次启用,
--       不新造工序实例表; 只补 1 列 from_def_id 记录「展开自哪条工序定义」.
--     - 批次 binding 走独立 mes_batch / mes_batch_link, 不改 inv_stock 的
--       batch_no 自由文本模型 (库存账保持现状, 零破坏).
--     - 全部 CREATE TABLE IF NOT EXISTS + information_schema 探测 + INSERT IGNORE
--       幂等, 老库重复执行安全.
--
-- ⚠️ MySQL 8 容器默认 latin1, 必须:
--   docker exec -i erp-mysql mysql industrial_erp -uroot -p$PW \
--     --default-character-set=utf8mb4 < 53_v180_mes_workorder.sql
-- =====================================================================

USE `industrial_erp`;

-- ------------------------------------------------------------------
-- 1. 工序定义 (按产品/BOM 定制工序链)
--    一个产品可配多条工序链 (吹膜→印刷→复合→分切→制袋→包装 等),
--    is_default=1 的链在开生产单 release() 时自动展开成该单工序实例.
-- ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `mes_process_def` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '雪花ID',
  `product_id`    BIGINT        DEFAULT NULL COMMENT '所属成品产品(可空=通用工序)',
  `bom_id`        BIGINT        DEFAULT NULL COMMENT '所属BOM(可空)',
  `process_no`    INT           NOT NULL DEFAULT 0 COMMENT '工序顺序(链内序号)',
  `process_name`  VARCHAR(64)   NOT NULL COMMENT '工序名: 吹膜/印刷/复合/分切/制袋/包装...',
  `workshop`      VARCHAR(64)   DEFAULT NULL COMMENT '车间/工位',
  `is_default`    TINYINT       NOT NULL DEFAULT 0 COMMENT '该产品的默认工序链(开单自动展开)',
  `status`        TINYINT       NOT NULL DEFAULT 1,
  `remark`        VARCHAR(255)  DEFAULT NULL,
  `create_by`     BIGINT        DEFAULT NULL,
  `create_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`     BIGINT        DEFAULT NULL,
  `update_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_mes_pdef_product` (`product_id`, `deleted`),
  KEY `idx_mes_pdef_bom` (`bom_id`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工序定义(按产品/BOM定制)';

-- ------------------------------------------------------------------
-- 2. prd_process 启用: 补 1 列记录「展开自哪条 mes_process_def」
--    (现有死表, 仅加列, 不动原字段)
-- ------------------------------------------------------------------
SET @has_from_def := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'prd_process' AND COLUMN_NAME = 'from_def_id'
);
SET @sql := IF(@has_from_def = 0,
  'ALTER TABLE `prd_process` ADD COLUMN `from_def_id` BIGINT DEFAULT NULL COMMENT ''展开自 mes_process_def.id'' AFTER `process_no`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 给 prd_process 补 status 索引 (报工进度查询按订单+状态过滤)
-- MySQL 8 无 CREATE INDEX IF NOT EXISTS; 用 information_schema 探测, 幂等.
-- status: 0=未开工 1=进行中 2=完成 (见 mes 模块 Constants)
SET @has_proc_idx := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'prd_process' AND INDEX_NAME = 'idx_prd_process_status'
);
SET @sql3 := IF(@has_proc_idx = 0,
  'CREATE INDEX `idx_prd_process_status` ON `prd_process` (`prd_order_id`, `status`, `deleted`)',
  'SELECT 1');
PREPARE stmt3 FROM @sql3; EXECUTE stmt3; DEALLOCATE PREPARE stmt3;

-- ------------------------------------------------------------------
-- 3. 批次/卷号主数据 (RAW原料 / SEMI半成品卷 / FG成品)
--    batch_no 唯一; 原料批次在「领料时」登记 (不碰采购入库, 用户决策),
--    成品批次在「成品入库」登记, 半成品卷一期建表但二期才写.
-- ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `mes_batch` (
  `id`                BIGINT        NOT NULL AUTO_INCREMENT COMMENT '雪花ID',
  `batch_no`          VARCHAR(64)   NOT NULL COMMENT '批次/卷号(唯一标识)',
  `batch_type`        VARCHAR(16)   NOT NULL COMMENT 'RAW原料/SEMI半成品卷/FG成品',
  `product_id`        BIGINT        DEFAULT NULL COMMENT '对应产品(原料产品/半成品/成品)',
  `supplier_id`       BIGINT        DEFAULT NULL COMMENT 'RAW来源供应商',
  `receipt_bill_no`   VARCHAR(32)   DEFAULT NULL COMMENT 'RAW来源采购入库单号(追溯)',
  `source_prd_order_no` VARCHAR(32) DEFAULT NULL COMMENT 'SEMI/FG来源生产单号',
  `process_name`      VARCHAR(64)   DEFAULT NULL COMMENT 'SEMI产生工序(吹膜/分切...)',
  `roll_no`           VARCHAR(64)   DEFAULT NULL COMMENT '膜卷卷号',
  `status`            VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/FROZEN/SCRAP',
  `qty`               DECIMAL(18,4) DEFAULT NULL COMMENT '数量(主单位)',
  `unit_id`           BIGINT        DEFAULT NULL,
  `production_date`   DATE          DEFAULT NULL,
  `remark`            VARCHAR(255)  DEFAULT NULL,
  `create_by`         BIGINT        DEFAULT NULL,
  `create_time`       DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`         BIGINT        DEFAULT NULL,
  `update_time`       DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`           TINYINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_mes_batch_no` (`batch_no`, `deleted`),
  KEY `idx_mes_batch_type` (`batch_type`, `deleted`),
  KEY `idx_mes_batch_product` (`product_id`, `deleted`),
  KEY `idx_mes_batch_roll` (`roll_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='批次/卷号主数据(原料/半成品/成品)';

-- ------------------------------------------------------------------
-- 4. 批次流向绑定 (料→半成品卷→成品→客户, 递归追溯核心表)
--    追溯「成品用了哪批料」: 沿 child_batch_no→parent_batch_no 递归上溯;
--    追溯「这批料流到哪个客户」: 沿 parent→child 下探到 FG 批次 + customer_id.
-- ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `mes_batch_link` (
  `id`               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '雪花ID',
  `child_batch_no`   VARCHAR(64)   DEFAULT NULL COMMENT '下游批次/卷(半成品卷或成品; DISPOSITION 行可空)',
  `parent_batch_no`  VARCHAR(64)   DEFAULT NULL COMMENT '上游批次(原料批或半成品卷; DISPOSITION 行可空)',
  `qty`              DECIMAL(18,4) DEFAULT NULL COMMENT '本次用量(主单位)',
  `prd_order_id`     BIGINT        DEFAULT NULL,
  `prd_process_id`   BIGINT        DEFAULT NULL COMMENT '哪个工序产生此流向(可空)',
  `bill_type`        VARCHAR(16)   DEFAULT NULL COMMENT 'ISSUE领料/CUT切/REWIND复卷/SLIT分切/FG_IN成品入库',
  `bill_id`          BIGINT        DEFAULT NULL COMMENT '关联 prd_requisition / inv_cut_process id',
  `bill_no`          VARCHAR(32)   DEFAULT NULL,
  `customer_id`      BIGINT        DEFAULT NULL COMMENT '成品最终流向客户(追溯出口)',
  `create_by`        BIGINT        DEFAULT NULL,
  `create_time`      DATETIME      DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_mes_link_child` (`child_batch_no`),
  KEY `idx_mes_link_parent` (`parent_batch_no`),
  KEY `idx_mes_link_order` (`prd_order_id`),
  KEY `idx_mes_link_bill` (`bill_type`, `bill_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='批次流向绑定(料→半成品→成品→客户)';

-- ------------------------------------------------------------------
-- 5. 权限 F 载体: 报工 production:report:add / production:report:list
--    挂 parent_id=0 (F 型 perm 载体, 前端 perm-only 兜底入口),
--    默认授给 6 内置角色 PC (报工是生产管理子功能, 生产/仓库角色可用).
--    client_type 列 information_schema 探测 (老库 2 列退化安全).
-- ------------------------------------------------------------------
INSERT IGNORE INTO `sys_menu`(`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`perms`,`icon`,`sort_no`,`is_visible`,`status`) VALUES
(0,'MES报工','F','',NULL,'production:report:list',NULL,3001,0,1),
(0,'MES报工录入','F','',NULL,'production:report:add',NULL,3002,0,1);

-- 授权给 6 内置角色 (PC 端; 有 client_type 列时授 PC, 无则老库退化安全)
SET @has_ct := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_role_menu' AND COLUMN_NAME = 'client_type'
);
SET @sql2 := IF(@has_ct > 0,
  'INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
   SELECT r.id, m.id, ''PC''
   FROM sys_role r
   CROSS JOIN sys_menu m
   WHERE m.perms IN (''production:report:list'', ''production:report:add'')
     AND r.role_code IN (''SUPER_ADMIN'',''PURCHASE_MGR'',''SALES_MGR'',''WAREHOUSE_MGR'',''PRODUCTION_MGR'',''FINANCE'')
     AND r.deleted = 0',
  'SELECT 1');
PREPARE stmt2 FROM @sql2; EXECUTE stmt2; DEALLOCATE PREPARE stmt2;

-- =====================================================================
-- 校验 (分行 SELECT, 避免 UNION 子查询混型在老 MySQL 报 1064)
-- =====================================================================
SELECT 'mes_process_def' AS check_item, COUNT(*) AS cnt FROM mes_process_def;
SELECT 'mes_batch', COUNT(*) FROM mes_batch;
SELECT 'mes_batch_link', COUNT(*) FROM mes_batch_link;
SELECT 'prd_process.from_def_id present', COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='prd_process' AND COLUMN_NAME='from_def_id';
SELECT 'report perms in sys_menu', COUNT(*) FROM sys_menu WHERE perms LIKE 'production:report%';
SELECT 'report role grants', COUNT(*) FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id WHERE m.perms LIKE 'production:report%';
