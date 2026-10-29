-- v1.1.130 P1/P2: XK 合格供应商 — 准入评审记录 / 证照与检测报告 / 年度绩效评分
-- 背景: P0 阶段「XK 准入评审 / 证照与检测报告 / 年度绩效评价」三张卡片仍是 base_supplier 的视图过滤,
--       没有独立的评审记录、检测报告附件与评分模型, 无法满足 XK16-204 的「准入评审记录 + 检测报告留存 + 年度绩效分级」。
-- 幂等: 可重复执行 (CREATE TABLE IF NOT EXISTS + 回填 NOT EXISTS)。
-- 执行库: .150 主库 (industrial_erp), 由实时同步带到 .32 与阿里云只读副本。

-- ---------------------------------------------------------------------------
-- 1. 准入评审记录 (初次准入 / 年度复评 / 专项评审)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `xk_supplier_review` (
  `id`               BIGINT       NOT NULL AUTO_INCREMENT,
  `supplier_id`      BIGINT       NOT NULL COMMENT 'base_supplier.id',
  `supplier_code`    VARCHAR(64)  DEFAULT NULL COMMENT '冗余, 列表不 JOIN',
  `supplier_name`    VARCHAR(200) DEFAULT NULL COMMENT '冗余',
  `review_type`      VARCHAR(20)  NOT NULL DEFAULT 'ANNUAL' COMMENT 'INITIAL 初次准入 / ANNUAL 年度复评 / SPECIAL 专项评审',
  `review_date`      DATE         NOT NULL COMMENT '评审日期',
  `reviewer`         VARCHAR(100) DEFAULT NULL COMMENT '评审人 (可多人)',
  `next_review_date` DATE         DEFAULT NULL COMMENT '下次复评日期, 留空按类型推算 (初次/年度 +1 年, 专项 +6 月)',
  `result`           VARCHAR(20)  NOT NULL DEFAULT 'PASS' COMMENT 'PASS 通过 / CONDITIONAL 有条件通过 / FAIL 不通过',
  `conclusion`       VARCHAR(1000) DEFAULT NULL COMMENT '评审结论 / 整改要求',
  `attachment_url`   VARCHAR(512) DEFAULT NULL COMMENT '评审记录扫描件 / 现场照片 URL',
  `operator`         BIGINT       DEFAULT NULL COMMENT 'sys_user.id',
  `operator_name`    VARCHAR(64)  DEFAULT NULL,
  `oper_time`        DATETIME     DEFAULT NULL COMMENT '服务器时间戳',
  `version`          INT          NOT NULL DEFAULT 0,
  `create_by`        BIGINT       DEFAULT NULL,
  `create_time`      DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `update_by`        BIGINT       DEFAULT NULL,
  `update_time`      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`          TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_xk_supplier_review_supplier` (`supplier_id`, `review_date`),
  KEY `idx_xk_supplier_review_next` (`next_review_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='XK 合格供应商准入评审记录';

-- ---------------------------------------------------------------------------
-- 2. 证照与检测报告附件 (生产许可 / GB 4806 检测报告 / 符合性声明 等)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `xk_supplier_cert` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT,
  `supplier_id`   BIGINT       NOT NULL COMMENT 'base_supplier.id',
  `supplier_code` VARCHAR(64)  DEFAULT NULL,
  `supplier_name` VARCHAR(200) DEFAULT NULL,
  `cert_type`     VARCHAR(32)  NOT NULL DEFAULT 'PRODUCTION_LICENSE'
    COMMENT 'PRODUCTION_LICENSE 生产许可 / GB4806_REPORT 食品接触材料检测报告 / CONFORMITY_DECLARATION 符合性声明 / ISO22000 / OTHER',
  `cert_name`     VARCHAR(200) DEFAULT NULL COMMENT '证照名称, 如「全国工业产品生产许可证」',
  `cert_no`       VARCHAR(128) NOT NULL COMMENT '证照/报告编号',
  `issuer`        VARCHAR(200) DEFAULT NULL COMMENT '发证机构 / 检测机构',
  `issue_date`    DATE         DEFAULT NULL COMMENT '发证日期',
  `expire_date`   DATE         DEFAULT NULL COMMENT '有效期至 (检测报告可空 = 单次报告)',
  `file_url`      VARCHAR(512) DEFAULT NULL COMMENT '扫描件 PDF/图片 URL',
  `remark`        VARCHAR(500) DEFAULT NULL COMMENT '检测项目/结论摘要',
  `create_by`     BIGINT       DEFAULT NULL,
  `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `update_by`     BIGINT       DEFAULT NULL,
  `update_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_xk_supplier_cert_supplier` (`supplier_id`),
  KEY `idx_xk_supplier_cert_expire` (`expire_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='XK 供应商证照与检测报告';

-- ---------------------------------------------------------------------------
-- 3. 年度绩效评分 (5 维度加权 → 总分 → A/B/C)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `xk_supplier_perf` (
  `id`               BIGINT       NOT NULL AUTO_INCREMENT,
  `supplier_id`      BIGINT       NOT NULL COMMENT 'base_supplier.id',
  `supplier_code`    VARCHAR(64)  DEFAULT NULL,
  `supplier_name`    VARCHAR(200) DEFAULT NULL,
  `eval_year`        INT          NOT NULL COMMENT '评价年度, 如 2026',
  `quality_score`    DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT '质量 (权重 35)',
  `delivery_score`   DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT '交付 (权重 20)',
  `price_score`      DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT '价格 (权重 15)',
  `service_score`    DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT '服务 (权重 15)',
  `compliance_score` DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT '合规/资质 (权重 15)',
  `total_score`      DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT '加权总分 (后端计算)',
  `grade`            VARCHAR(2)   NOT NULL DEFAULT 'B' COMMENT 'A(>=90) / B(>=75) / C',
  `eval_date`        DATE         NOT NULL COMMENT '评价日期',
  `evaluator`        VARCHAR(100) DEFAULT NULL COMMENT '评价人',
  `next_review_date` DATE         DEFAULT NULL COMMENT '下次复评 (默认评价日 +1 年)',
  `remark`           VARCHAR(1000) DEFAULT NULL COMMENT '评价说明 / 整改要求',
  `create_by`        BIGINT       DEFAULT NULL,
  `create_time`      DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `update_by`        BIGINT       DEFAULT NULL,
  `update_time`      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`          TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_xk_supplier_perf_supplier` (`supplier_id`, `eval_year`),
  KEY `idx_xk_supplier_perf_year` (`eval_year`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='XK 供应商年度绩效评分';

-- ---------------------------------------------------------------------------
-- 4. 回填: 现有 base_supplier.food_cert_no → xk_supplier_cert (生产许可)
--    只补历史数据, 不伪造评审记录/绩效评分 (合规记录不可代填)
-- ---------------------------------------------------------------------------
INSERT INTO `xk_supplier_cert`
  (`supplier_id`, `supplier_code`, `supplier_name`, `cert_type`, `cert_name`, `cert_no`,
   `issue_date`, `expire_date`, `remark`, `create_by`, `create_time`, `update_by`, `update_time`)
SELECT s.`id`, s.`supplier_code`, s.`supplier_name`, 'PRODUCTION_LICENSE',
       CASE s.`food_cert_type`
         WHEN 'FOOD_PRODUCT_LICENSE' THEN '食品相关产品生产许可证'
         WHEN 'FOOD_GRADE_RAW'       THEN '食品级原料证明'
         WHEN 'FOOD_CONTACT'         THEN '食品接触材料符合性证明'
         ELSE '食品接触材料资质证明' END,
       s.`food_cert_no`, NULL, s.`food_cert_exp`, '系统回填 (v1.1.130 由 base_supplier 迁移)',
       s.`create_by`, NOW(), s.`update_by`, NOW()
FROM `base_supplier` s
WHERE s.`deleted` = 0
  AND s.`food_cert_no` IS NOT NULL AND s.`food_cert_no` <> ''
  AND NOT EXISTS (
    SELECT 1 FROM `xk_supplier_cert` c
    WHERE c.`supplier_id` = s.`id` AND c.`cert_no` = s.`food_cert_no` AND c.`deleted` = 0
  );

-- 校验 (人工执行可看):
-- SELECT COUNT(*) AS cert_rows FROM xk_supplier_cert WHERE deleted = 0;
-- SELECT COUNT(*) AS suppliers_with_cert FROM base_supplier WHERE deleted = 0 AND food_cert_no IS NOT NULL AND food_cert_no <> '';
