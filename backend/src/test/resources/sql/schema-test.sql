# v1.1.49: Testcontainers MySQL 集成测试 schema fixture
# 抽取生产环境 sql/02_schema_base.sql + sql/05_schema_inventory.sql 的关键表
# 跟生产保持一致, 避免测试通过但生产挂掉 (v1.1.32/45/47 反复修 only_full_group_by 的根源)

DROP TABLE IF EXISTS inv_stock;
DROP TABLE IF EXISTS base_product;
DROP TABLE IF EXISTS base_warehouse;

CREATE TABLE base_product (
  id              BIGINT        NOT NULL AUTO_INCREMENT,
  product_code    VARCHAR(64)   NOT NULL,
  product_name    VARCHAR(128)  NOT NULL,
  spec            VARCHAR(128)  DEFAULT NULL,
  safety_stock    DECIMAL(18,4) DEFAULT 0 COMMENT '安全库存',
  status          TINYINT       DEFAULT 1,
  deleted         TINYINT       DEFAULT 0,
  create_time     DATETIME      DEFAULT CURRENT_TIMESTAMP,
  update_time     DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE base_warehouse (
  id              BIGINT        NOT NULL AUTO_INCREMENT,
  warehouse_code  VARCHAR(64)   NOT NULL,
  warehouse_name  VARCHAR(64)   NOT NULL,
  status          TINYINT       DEFAULT 1,
  deleted         TINYINT       DEFAULT 0,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE inv_stock (
  id              BIGINT        NOT NULL AUTO_INCREMENT,
  warehouse_id    BIGINT        NOT NULL,
  warehouse_name  VARCHAR(64)   DEFAULT NULL,
  product_id      BIGINT        NOT NULL,
  product_code    VARCHAR(64)   DEFAULT NULL,
  product_name    VARCHAR(128)  DEFAULT NULL,
  spec            VARCHAR(128)  DEFAULT NULL,
  qty             DECIMAL(18,4) NOT NULL DEFAULT 0,
  available_qty   DECIMAL(18,4) NOT NULL DEFAULT 0,
  safety_stock    DECIMAL(18,4) DEFAULT 0,
  deleted         TINYINT       DEFAULT 0,
  create_time     DATETIME      DEFAULT CURRENT_TIMESTAMP,
  update_time     DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_inv_stock_product (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
