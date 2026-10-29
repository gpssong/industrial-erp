-- v1.1.130 P0: XK 合格供应商 — 暂停/恢复台账 (XK16-204 采购控制 + 记录留存)
-- 背景: 原先暂停/恢复只改 base_supplier.status, 无原因/时间/操作人/证据, 审计无法自证.
-- 幂等: 可重复执行.
CREATE TABLE IF NOT EXISTS `xk_supplier_status_log` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT,
  `supplier_id`    BIGINT       NOT NULL COMMENT 'base_supplier.id',
  `supplier_code`  VARCHAR(64)  DEFAULT NULL COMMENT '冗余, 列表不 JOIN',
  `supplier_name`  VARCHAR(200) DEFAULT NULL COMMENT '冗余, 列表不 JOIN',
  `action`         VARCHAR(16)  NOT NULL COMMENT 'SUSPEND/RESUME',
  `reason_type`    VARCHAR(32)  DEFAULT NULL COMMENT '资质过期/检测不合格/交付异常/现场审核不通过/其他',
  `reason`         VARCHAR(500) NOT NULL COMMENT '暂停或恢复原因 (必填)',
  `effective_date` DATE         DEFAULT NULL COMMENT '生效日期',
  `evidence_url`   VARCHAR(512) DEFAULT NULL COMMENT '整改/恢复评审证据附件 URL',
  `operator`       BIGINT       DEFAULT NULL COMMENT 'sys_user.id',
  `operator_name`  VARCHAR(64)  DEFAULT NULL COMMENT '操作人姓名',
  `oper_time`      DATETIME     NOT NULL COMMENT '服务器时间戳',
  `version`        INT          NOT NULL DEFAULT 0,
  `create_by`      BIGINT       DEFAULT NULL,
  `create_time`    DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `update_by`      BIGINT       DEFAULT NULL,
  `update_time`    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`        TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_xk_supplier_status_supplier` (`supplier_id`),
  KEY `idx_xk_supplier_status_action_time` (`action`, `oper_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='XK 合格供应商暂停/恢复台账';
