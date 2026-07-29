-- =============================================================================
-- 24_add_invoice_tables.sql
-- v1.1.10+: 发票管理 (AR + AP 双向)
-- 背景: 公司有"开票客户按发票汇款 / 不开票客户按出库单收款"两类业务,
--       现有 fin_arap 只跟踪出库单维度的金额, 没有发票概念.
--       需要新增发票主表 + 发票-AR/AP 关联表, 让 AR/AP 单能跟踪
--       开票状态 / 未开票金额 / 已开票金额 / 最近开票日.
--
-- 修复范围:
--   1. fin_arap 加 4 字段 (invoiced_amount / uninvoiced_amount / invoice_status / last_invoice_date)
--   2. fin_cash_flow 加 invoice_id (按发票回款的关联字段)
--   3. CREATE fin_invoice (发票主表, AR/AP 共用, invoice_type 区分)
--   4. CREATE fin_invoice_apply (发票-AR/AP 关联明细, 跨单合并开票)
--   5. UPDATE fin_arap 历史回填 (老数据默认未开票)
--   6. sys_menu 加"发票管理"菜单 + 4 个按钮权限
--   7. sys_role_menu 给超管授权 (其他角色按需手动授权)
--
-- ⚠️ 重要: 用十六进制字面量 (X'...' / 0x...) 传中文, 避免 mysql 客户端
--   在 ssh/管道传输 stdin 时把 UTF-8 字节当 Latin-1 重新编码, 造成双重
--   编码乱码.
--   推荐执行方式:
--     sudo docker exec erp-mysql mysql -uroot -perp_root_pwd industrial_erp \
--       --default-character-set=utf8mb4 < 24_add_invoice_tables.sql
-- =============================================================================

-- 1. fin_arap 加开票字段
ALTER TABLE fin_arap
  ADD COLUMN invoiced_amount   DECIMAL(18,4) DEFAULT 0    COMMENT '已开票金额',
  ADD COLUMN uninvoiced_amount DECIMAL(18,4) DEFAULT 0    COMMENT '未开票金额',
  ADD COLUMN invoice_status    VARCHAR(32)   DEFAULT 'UNINVOICED' COMMENT 'UNINVOICED/PARTIAL_INVOICED/FULL_INVOICED',
  ADD COLUMN last_invoice_date DATE          DEFAULT NULL COMMENT '最近开票日期';

-- 2. fin_cash_flow 加 invoice_id (按发票回款关联)
ALTER TABLE fin_cash_flow
  ADD COLUMN invoice_id BIGINT DEFAULT NULL COMMENT '关联发票 ID (NULL=按单核销, 非NULL=按发票核销)',
  ADD KEY idx_fin_cf_invoice (invoice_id);

-- 3. 发票主表 (AR/AP 共用, invoice_type 区分)
DROP TABLE IF EXISTS `fin_invoice`;
CREATE TABLE `fin_invoice` (
  `id`                BIGINT        NOT NULL AUTO_INCREMENT,
  `bill_no`           VARCHAR(32)   NOT NULL             COMMENT '内部单号 INV...',
  `external_no`       VARCHAR(64)   DEFAULT NULL         COMMENT '外部票号 (真实发票号)',
  `invoice_type`      VARCHAR(16)   NOT NULL             COMMENT 'AR_SALE=销项 / AP_PURCHASE=进项',
  `partner_type`      VARCHAR(16)   NOT NULL             COMMENT 'CUSTOMER / SUPPLIER',
  `partner_id`        BIGINT        NOT NULL             COMMENT '客户/供应商 ID',
  `partner_name`      VARCHAR(128)  DEFAULT NULL,
  `partner_tax_no`    VARCHAR(64)   DEFAULT NULL         COMMENT '税号',
  `bill_date`         DATE          NOT NULL             COMMENT '发票日期',
  `total_amount`      DECIMAL(18,4) NOT NULL DEFAULT 0   COMMENT '发票总金额 (含税)',
  `tax_amount`        DECIMAL(18,4) DEFAULT 0            COMMENT '税额',
  `collected_amount`  DECIMAL(18,4) NOT NULL DEFAULT 0   COMMENT '已收/付金额',
  `balance`           DECIMAL(18,4) NOT NULL DEFAULT 0   COMMENT '未收/付',
  `invoice_status`    VARCHAR(32)   DEFAULT 'DRAFT'      COMMENT 'DRAFT/ISSUED/PARTIAL/PAID/VOID',
  `due_date`          DATE          DEFAULT NULL         COMMENT '到期日',
  `title`             VARCHAR(128)  DEFAULT NULL         COMMENT '发票抬头',
  `remark`            VARCHAR(500)  DEFAULT NULL,
  `create_by`         BIGINT        DEFAULT NULL,
  `create_time`       DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_by`         BIGINT        DEFAULT NULL,
  `update_time`       DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`           TINYINT       DEFAULT 0,
  `tenant_id`         BIGINT        DEFAULT 1,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_fin_invoice_bill_no` (`bill_no`, `deleted`),
  KEY `idx_fin_invoice_partner` (`partner_type`, `partner_id`),
  KEY `idx_fin_invoice_type` (`invoice_type`),
  KEY `idx_fin_invoice_date` (`bill_date`),
  KEY `idx_fin_invoice_status` (`invoice_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发票主表 (AR/AP 共用)';

-- 4. 发票-AR/AP 关联明细表 (一张发票可对应多个 AR/AP 单, 支持跨单合并开票)
DROP TABLE IF EXISTS `fin_invoice_apply`;
CREATE TABLE `fin_invoice_apply` (
  `id`              BIGINT        NOT NULL AUTO_INCREMENT,
  `invoice_id`      BIGINT        NOT NULL                COMMENT '发票 ID',
  `arap_id`         BIGINT        NOT NULL                COMMENT '关联 AR/AP 单 ID (fin_arap.id)',
  `source_bill_type` VARCHAR(32)  DEFAULT NULL            COMMENT 'PUR_RECEIPT/SAL_DELIVERY/...',
  `source_bill_id`  BIGINT        DEFAULT NULL,
  `source_bill_no`  VARCHAR(32)   DEFAULT NULL,
  `apply_amount`    DECIMAL(18,4) NOT NULL                COMMENT '本次开票金额',
  `remark`          VARCHAR(255)  DEFAULT NULL,
  `create_by`       BIGINT        DEFAULT NULL,
  `create_time`     DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `deleted`         TINYINT       DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_fin_apply_invoice` (`invoice_id`),
  KEY `idx_fin_apply_arap` (`arap_id`),
  KEY `idx_fin_apply_source` (`source_bill_type`, `source_bill_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发票关联明细 (跨单合并开票)';

-- 5. 历史数据回填: 老 AR/AP 单默认未开票
UPDATE fin_arap
SET invoiced_amount   = 0,
    uninvoiced_amount = amount,
    invoice_status    = 'UNINVOICED'
WHERE deleted = 0;

-- 6. 菜单: 在"财务往来"下加"发票管理"
-- 6.1 找父菜单 id (path='/finance/arap' 的 '财务往来')
SET @finance_parent_id = (SELECT id FROM sys_menu WHERE path = '/finance/arap' AND menu_type = 'M' AND deleted = 0 LIMIT 1);

-- 6.2 加菜单: "发票管理" (M, /finance/invoice)
-- "发票管理" UTF-8: E5 8F 91 E7 A5 A8 E7 AE A1 E7 90 86
INSERT INTO sys_menu (parent_id, menu_name, menu_type, path, perms, is_visible, status, sort_no, deleted)
SELECT @finance_parent_id, _utf8mb4 X'E58F91E7A5A8E7AEA1E79086', 'M', '/finance/invoice', 'finance:invoice:list', 1, 1, 50, 0
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/finance/invoice' AND deleted = 0)
  AND @finance_parent_id IS NOT NULL;

SET @invoice_menu_id = (SELECT id FROM sys_menu WHERE path = '/finance/invoice' AND deleted = 0 LIMIT 1);

-- 6.3 加 4 个按钮权限点
-- "申请开票" UTF-8: E7 94 B3 E8 AF B7 E5 BC 80 E7 A5 A8
INSERT INTO sys_menu (parent_id, menu_name, menu_type, path, perms, is_visible, status, sort_no, deleted)
SELECT @invoice_menu_id, _utf8mb4 X'E794B3E8AFB7E5BC80E7A5A8', 'B', '/finance/invoice/create', 'finance:invoice:add', 0, 1, 1, 0
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/finance/invoice/create' AND deleted = 0)
  AND @invoice_menu_id IS NOT NULL;

-- "作废发票" UTF-8: E4 BD 9C E5 BA 9F E5 8F 91 E7 A5 A8
INSERT INTO sys_menu (parent_id, menu_name, menu_type, path, perms, is_visible, status, sort_no, deleted)
SELECT @invoice_menu_id, _utf8mb4 X'E4BD9CE5BA9FE58F91E7A5A8', 'B', '/finance/invoice/void', 'finance:invoice:void', 0, 1, 2, 0
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/finance/invoice/void' AND deleted = 0)
  AND @invoice_menu_id IS NOT NULL;

-- "按发票收款" UTF-8: E6 8C 89 E5 8F 91 E7 A5 A8 E6 94 B6 E6 AC BE
INSERT INTO sys_menu (parent_id, menu_name, menu_type, path, perms, is_visible, status, sort_no, deleted)
SELECT @invoice_menu_id, _utf8mb4 X'E68C89E58F91E7A5A8E694B6E6ACBE', 'B', '/finance/arap/cash-by-invoice', 'finance:writeoff:by-invoice', 0, 1, 3, 0
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/finance/arap/cash-by-invoice' AND deleted = 0)
  AND @invoice_menu_id IS NOT NULL;

-- "未开票列表" UTF-8: E6 9C AA E5 BC 80 E7 A5 A8 E5 88 97 E8 A1 A8
INSERT INTO sys_menu (parent_id, menu_name, menu_type, path, perms, is_visible, status, sort_no, deleted)
SELECT @invoice_menu_id, _utf8mb4 X'E69CAAE5BC80E7A5A8E58897E8A1A8', 'B', '/finance/invoice/uninvoiced', 'finance:invoice:uninvoiced', 0, 1, 4, 0
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/finance/invoice/uninvoiced' AND deleted = 0)
  AND @invoice_menu_id IS NOT NULL;

-- 7. 角色授权: 给超管 (role_id=1) 全部授权
INSERT INTO sys_role_menu (role_id, menu_id, client_type)
SELECT 1, id, 'BOTH'
FROM sys_menu
WHERE deleted = 0
  AND (perms LIKE 'finance:invoice:%' OR perms = 'finance:writeoff:by-invoice');

-- 给"财务"角色 (假设 name='财务' 的角色) 也授权
INSERT INTO sys_role_menu (role_id, menu_id, client_type)
SELECT r.id, m.id, 'BOTH'
FROM sys_role r
INNER JOIN sys_menu m ON m.deleted = 0
    AND (m.perms LIKE 'finance:invoice:%' OR m.perms = 'finance:writeoff:by-invoice')
WHERE r.deleted = 0 AND r.role_name = '财务'
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu srm
    WHERE srm.role_id = r.id AND srm.menu_id = m.id AND srm.client_type = 'BOTH'
  );

-- 8. 验证
SELECT 'fin_arap 新字段' AS info;
SELECT COUNT(*) AS column_count FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'fin_arap'
  AND column_name IN ('invoiced_amount','uninvoiced_amount','invoice_status','last_invoice_date');

SELECT 'fin_cash_flow 新字段' AS info;
SELECT COUNT(*) AS column_count FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'fin_cash_flow'
  AND column_name = 'invoice_id';

SELECT 'fin_invoice' AS info;
SELECT COUNT(*) AS row_count FROM fin_invoice;

SELECT 'fin_invoice_apply' AS info;
SELECT COUNT(*) AS row_count FROM fin_invoice_apply;

SELECT '新菜单' AS info;
SELECT id, parent_id, menu_name, path, perms FROM sys_menu
WHERE deleted = 0 AND (perms LIKE 'finance:invoice:%' OR perms = 'finance:writeoff:by-invoice')
ORDER BY id;

SELECT 'AR 单回填' AS info;
SELECT COUNT(*) AS total,
       SUM(CASE WHEN invoice_status='UNINVOICED' THEN 1 ELSE 0 END) AS uninvoiced,
       SUM(CASE WHEN invoice_status='PARTIAL_INVOICED' THEN 1 ELSE 0 END) AS partial,
       SUM(CASE WHEN invoice_status='FULL_INVOICED' THEN 1 ELSE 0 END) AS full
FROM fin_arap WHERE deleted=0;