-- v1.1.132 XK16-204 — 开工确认台账 (生产环境确认 + 投料原料批号 + 批号照片)
-- 背景: 食品接触材料生产许可要求「生产过程记录」可追溯到具体工单 —— 何时开工/谁开工/
--       开工前生产环境是否合格/投入哪批原料. 原先 App 开工只调 /production/order/{id}/release,
--       既不校验生产环境, 也不记录投料批号与凭证照片, 审计时无法自证.
-- 注意: 本文件含中文, 灌库必须 --default-character-set=utf8mb4, 否则注释与 COMMENT 乱码.
-- 幂等: 可重复执行.

CREATE TABLE IF NOT EXISTS `mes_work_start_confirm` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT,
  `confirm_no`      VARCHAR(32)  NOT NULL COMMENT '开工确认单号 WS+yyyyMMdd+序号',
  `prd_order_id`    BIGINT       NOT NULL COMMENT 'prd_order.id',
  `prd_order_no`    VARCHAR(32)  DEFAULT NULL COMMENT '冗余生产单号 (列表不 JOIN)',
  `stage`           VARCHAR(16)  NOT NULL COMMENT 'BLOW 吹膜开工 / BAG 制袋开工',
  `workshop`        VARCHAR(64)  DEFAULT NULL COMMENT '吹膜车间/制袋车间 (自动生成的环境检查记录归属车间)',
  `prd_process_id`  BIGINT       DEFAULT NULL COMMENT 'BAG 开工对应的 prd_process.id',
  `env_qualified`   TINYINT      NOT NULL DEFAULT 0 COMMENT '生产环境是否确认合格 (0 = 不合格, 禁止开工)',
  `env_check_ids`   VARCHAR(128) DEFAULT NULL COMMENT '自动生成的 2 份 xk_env_check id, 逗号分隔',
  `raw_batch_nos`   VARCHAR(512) DEFAULT NULL COMMENT '投料原料批号, 逗号分隔 (吹膜开工必填)',
  `photo_urls`      TEXT         DEFAULT NULL COMMENT '原料批号照片 URL (JSON 数组字符串)',
  `requisition_id`  BIGINT       DEFAULT NULL COMMENT '制袋开工自动领用半成品的领料单 id',
  `requisition_no`  VARCHAR(32)  DEFAULT NULL COMMENT '领料单号',
  `confirm_by`      BIGINT       DEFAULT NULL COMMENT '确认人 sys_user.id',
  `confirm_name`    VARCHAR(64)  DEFAULT NULL COMMENT '确认人姓名 (冗余)',
  `confirm_time`    DATETIME     NOT NULL COMMENT '确认时间',
  `remark`          VARCHAR(512) DEFAULT NULL,
  `create_by`       BIGINT       DEFAULT NULL,
  `create_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `update_by`       BIGINT       DEFAULT NULL,
  `update_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`         TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_ws_confirm_no` (`confirm_no`, `deleted`),
  KEY `idx_ws_order_stage` (`prd_order_id`, `stage`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='XK 开工确认台账 (生产环境 + 原料批号 + 批号照片)';
