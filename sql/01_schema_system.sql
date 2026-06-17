-- =====================================================================
--  企业级工业进销存 ERP 系统 - 数据库 Schema
--  适配行业: 薄膜 / 塑料 / 五金 / 加工 / 工贸一体
--  数据库 : MySQL 8.0
--  字符集 : utf8mb4 / utf8mb4_unicode_ci
--  设计规范:
--    1. 所有业务表统一审计字段 (create_by / create_time / update_by / update_time)
--    2. 软删除字段 deleted (0=未删 1=已删)
--    3. 单据表统一单据编号 bill_no + 单据状态 bill_status
--    4. 关键金额统一 DECIMAL(18,4) 避免精度丢失
--    5. 数量/重量统一 DECIMAL(18,4)  支持四位小数
--    6. 主键统一 BIGINT AUTO_INCREMENT
--    7. 索引命名: idx_表名_字段名, 唯一索引 uniq_表名_字段名
-- =====================================================================
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;
SET sql_mode = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION';

CREATE DATABASE IF NOT EXISTS `industrial_erp` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `industrial_erp`;

-- =====================================================================
-- 一、系统管理模块 (sys_*)
-- =====================================================================

-- 1.1  部门表
DROP TABLE IF EXISTS `sys_dept`;
CREATE TABLE `sys_dept` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '部门ID',
  `parent_id`   BIGINT       DEFAULT 0         COMMENT '父部门ID, 0=根',
  `dept_code`   VARCHAR(64)  NOT NULL          COMMENT '部门编码',
  `dept_name`   VARCHAR(64)  NOT NULL          COMMENT '部门名称',
  `leader`      VARCHAR(32)  DEFAULT NULL      COMMENT '负责人',
  `phone`       VARCHAR(20)  DEFAULT NULL      COMMENT '联系电话',
  `email`       VARCHAR(64)  DEFAULT NULL      COMMENT '邮箱',
  `sort_no`     INT          DEFAULT 0         COMMENT '显示顺序',
  `status`      TINYINT      DEFAULT 1         COMMENT '状态: 0=停用 1=正常',
  `remark`      VARCHAR(255) DEFAULT NULL      COMMENT '备注',
  `create_by`   BIGINT       DEFAULT NULL      COMMENT '创建人',
  `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`   BIGINT       DEFAULT NULL      COMMENT '更新人',
  `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`     TINYINT      DEFAULT 0         COMMENT '软删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_sys_dept_code` (`dept_code`, `deleted`),
  KEY `idx_sys_dept_parent` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='部门表';

-- 1.2  角色表
DROP TABLE IF EXISTS `sys_role`;
CREATE TABLE `sys_role` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '角色ID',
  `role_code`   VARCHAR(64)  NOT NULL          COMMENT '角色编码',
  `role_name`   VARCHAR(64)  NOT NULL          COMMENT '角色名称',
  `data_scope`  TINYINT      DEFAULT 1         COMMENT '数据范围: 1=全部 2=本部门及下级 3=本部门 4=本人',
  `sort_no`     INT          DEFAULT 0         COMMENT '显示顺序',
  `status`      TINYINT      DEFAULT 1         COMMENT '状态',
  `remark`      VARCHAR(255) DEFAULT NULL      COMMENT '备注',
  `create_by`   BIGINT       DEFAULT NULL,
  `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `update_by`   BIGINT       DEFAULT NULL,
  `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`     TINYINT      DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_sys_role_code` (`role_code`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色表';

-- 1.3  菜单表
DROP TABLE IF EXISTS `sys_menu`;
CREATE TABLE `sys_menu` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '菜单ID',
  `parent_id`   BIGINT       DEFAULT 0         COMMENT '父菜单ID',
  `menu_name`   VARCHAR(64)  NOT NULL          COMMENT '菜单名称',
  `menu_type`   CHAR(1)      NOT NULL          COMMENT '类型: M=目录 C=菜单 F=按钮',
  `path`        VARCHAR(255) DEFAULT NULL      COMMENT '路由路径',
  `component`   VARCHAR(255) DEFAULT NULL      COMMENT '组件路径',
  `perms`       VARCHAR(128) DEFAULT NULL      COMMENT '权限标识, 如 system:user:list',
  `icon`        VARCHAR(64)  DEFAULT NULL      COMMENT '图标',
  `sort_no`     INT          DEFAULT 0         COMMENT '排序',
  `is_visible`  TINYINT      DEFAULT 1         COMMENT '是否显示',
  `status`      TINYINT      DEFAULT 1         COMMENT '状态',
  `create_by`   BIGINT       DEFAULT NULL,
  `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `update_by`   BIGINT       DEFAULT NULL,
  `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`     TINYINT      DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_sys_menu_parent` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜单权限表';

-- 1.4  用户表
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `username`    VARCHAR(64)  NOT NULL          COMMENT '登录账号',
  `password`    VARCHAR(128) NOT NULL          COMMENT '登录密码(BCrypt)',
  `nickname`    VARCHAR(64)  DEFAULT NULL      COMMENT '昵称',
  `real_name`   VARCHAR(64)  DEFAULT NULL      COMMENT '真实姓名',
  `avatar`      VARCHAR(255) DEFAULT NULL      COMMENT '头像URL',
  `phone`       VARCHAR(20)  DEFAULT NULL      COMMENT '手机号',
  `email`       VARCHAR(64)  DEFAULT NULL      COMMENT '邮箱',
  `sex`         TINYINT      DEFAULT 0         COMMENT '性别: 0=未知 1=男 2=女',
  `dept_id`     BIGINT       DEFAULT NULL      COMMENT '部门ID',
  `is_admin`    TINYINT      DEFAULT 0         COMMENT '是否超管: 0=否 1=是',
  `status`      TINYINT      DEFAULT 1         COMMENT '状态: 0=停用 1=正常',
  `last_login_ip`   VARCHAR(64) DEFAULT NULL   COMMENT '最后登录IP',
  `last_login_time` DATETIME     DEFAULT NULL   COMMENT '最后登录时间',
  `create_by`   BIGINT       DEFAULT NULL,
  `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `update_by`   BIGINT       DEFAULT NULL,
  `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`     TINYINT      DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_sys_user_username` (`username`, `deleted`),
  KEY `idx_sys_user_dept` (`dept_id`),
  KEY `idx_sys_user_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 1.5  用户-角色关联
DROP TABLE IF EXISTS `sys_user_role`;
CREATE TABLE `sys_user_role` (
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `role_id` BIGINT NOT NULL COMMENT '角色ID',
  PRIMARY KEY (`user_id`, `role_id`),
  KEY `idx_sys_user_role_role` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户角色关联';

-- 1.6  角色-菜单关联 (含按钮权限)
DROP TABLE IF EXISTS `sys_role_menu`;
CREATE TABLE `sys_role_menu` (
  `role_id` BIGINT NOT NULL COMMENT '角色ID',
  `menu_id` BIGINT NOT NULL COMMENT '菜单ID',
  PRIMARY KEY (`role_id`, `menu_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色菜单(按钮)关联';

-- 1.7  角色-部门关联 (用于数据权限: 本部门及下级)
DROP TABLE IF EXISTS `sys_role_dept`;
CREATE TABLE `sys_role_dept` (
  `role_id` BIGINT NOT NULL COMMENT '角色ID',
  `dept_id` BIGINT NOT NULL COMMENT '部门ID',
  PRIMARY KEY (`role_id`, `dept_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色部门关联(数据权限)';

-- 1.8  操作日志
DROP TABLE IF EXISTS `sys_oper_log`;
CREATE TABLE `sys_oper_log` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT,
  `module`        VARCHAR(64)  DEFAULT NULL  COMMENT '模块名',
  `business_type` VARCHAR(32)  DEFAULT NULL  COMMENT '业务类型: ADD EDIT DELETE QUERY EXPORT',
  `method`        VARCHAR(128) DEFAULT NULL  COMMENT '方法名',
  `request_url`   VARCHAR(255) DEFAULT NULL  COMMENT '请求URL',
  `request_method` VARCHAR(8)  DEFAULT NULL  COMMENT 'GET/POST',
  `request_param` TEXT         DEFAULT NULL  COMMENT '请求参数',
  `response_data` TEXT         DEFAULT NULL  COMMENT '返回结果',
  `ip_address`    VARCHAR(64)  DEFAULT NULL  COMMENT '操作IP',
  `user_id`       BIGINT       DEFAULT NULL  COMMENT '操作人',
  `username`      VARCHAR(64)  DEFAULT NULL,
  `cost_time`     BIGINT       DEFAULT 0     COMMENT '耗时ms',
  `status`        TINYINT      DEFAULT 1     COMMENT '状态: 0=失败 1=成功',
  `error_msg`     TEXT         DEFAULT NULL  COMMENT '错误信息',
  `oper_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  PRIMARY KEY (`id`),
  KEY `idx_sys_oper_user` (`user_id`),
  KEY `idx_sys_oper_time` (`oper_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作日志';

-- 1.9  登录日志
DROP TABLE IF EXISTS `sys_login_log`;
CREATE TABLE `sys_login_log` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `username`   VARCHAR(64)  DEFAULT NULL,
  `ip_address` VARCHAR(64)  DEFAULT NULL,
  `browser`    VARCHAR(64)  DEFAULT NULL,
  `os`         VARCHAR(64)  DEFAULT NULL,
  `status`     TINYINT      DEFAULT 1     COMMENT '1=成功 0=失败',
  `msg`        VARCHAR(255) DEFAULT NULL,
  `login_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_sys_login_user` (`username`),
  KEY `idx_sys_login_time` (`login_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录日志';

-- 1.10 系统配置
DROP TABLE IF EXISTS `sys_config`;
CREATE TABLE `sys_config` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `config_name` VARCHAR(64)  NOT NULL,
  `config_key`  VARCHAR(64)  NOT NULL,
  `config_value` TEXT         DEFAULT NULL,
  `config_type` TINYINT      DEFAULT 1     COMMENT '1=系统 2=业务',
  `remark`      VARCHAR(255) DEFAULT NULL,
  `create_by`   BIGINT       DEFAULT NULL,
  `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `update_by`   BIGINT       DEFAULT NULL,
  `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`     TINYINT      DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_sys_config_key` (`config_key`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统配置';

-- 1.11 打印模板
DROP TABLE IF EXISTS `sys_print_template`;
CREATE TABLE `sys_print_template` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT,
  `template_code` VARCHAR(64)  NOT NULL COMMENT '模板编码: PURCHASE_ORDER/SALES_DELIVERY...',
  `template_name` VARCHAR(64)  NOT NULL,
  `template_type` VARCHAR(32)  DEFAULT NULL COMMENT 'paper_80/needle/a4',
  `paper_width`   INT          DEFAULT 80,
  `paper_height`  INT          DEFAULT 120,
  `content`       MEDIUMTEXT   COMMENT 'Freemarker 模板内容',
  `is_default`    TINYINT      DEFAULT 0,
  `status`        TINYINT      DEFAULT 1,
  `create_by`     BIGINT       DEFAULT NULL,
  `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `update_by`     BIGINT       DEFAULT NULL,
  `update_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT      DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_sys_template_code` (`template_code`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='打印模板';

-- 1.12 数据备份记录
DROP TABLE IF EXISTS `sys_backup_record`;
CREATE TABLE `sys_backup_record` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `backup_name` VARCHAR(128) NOT NULL,
  `file_path`   VARCHAR(255) DEFAULT NULL,
  `file_size`   BIGINT       DEFAULT 0,
  `backup_type` TINYINT      DEFAULT 1 COMMENT '1=自动 2=手动',
  `status`      TINYINT      DEFAULT 1 COMMENT '1=成功 0=失败',
  `remark`      VARCHAR(255) DEFAULT NULL,
  `create_by`   BIGINT       DEFAULT NULL,
  `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_sys_backup_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据备份记录';

-- 1.13 字典类型
DROP TABLE IF EXISTS `sys_dict_type`;
CREATE TABLE `sys_dict_type` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `dict_name`  VARCHAR(64)  NOT NULL,
  `dict_type`  VARCHAR(64)  NOT NULL,
  `status`     TINYINT      DEFAULT 1,
  `remark`     VARCHAR(255) DEFAULT NULL,
  `create_by`  BIGINT       DEFAULT NULL,
  `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `update_by`  BIGINT       DEFAULT NULL,
  `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`    TINYINT      DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_sys_dict_type` (`dict_type`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='字典类型';

-- 1.14 字典数据
DROP TABLE IF EXISTS `sys_dict_data`;
CREATE TABLE `sys_dict_data` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `dict_type`  VARCHAR(64)  NOT NULL,
  `dict_label` VARCHAR(64)  NOT NULL,
  `dict_value` VARCHAR(64)  NOT NULL,
  `css_class`  VARCHAR(32)  DEFAULT NULL,
  `sort_no`    INT          DEFAULT 0,
  `is_default` TINYINT      DEFAULT 0,
  `status`     TINYINT      DEFAULT 1,
  `remark`     VARCHAR(255) DEFAULT NULL,
  `create_by`  BIGINT       DEFAULT NULL,
  `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `update_by`  BIGINT       DEFAULT NULL,
  `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`    TINYINT      DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_sys_dict_data_type` (`dict_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='字典数据';

