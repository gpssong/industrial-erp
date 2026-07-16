-- =====================================================================
--  工业 ERP - 打印模板添加"型号"(model)字段
--  编号 17: 紧接 16 之后
--  涉及: 销售出库/退货明细、采购入库/退货明细、生产加工单主表
-- =====================================================================
SET NAMES utf8mb4;
USE `industrial_erp`;

-- 1. 销售出库明细表
ALTER TABLE `sal_delivery_detail` ADD COLUMN `model` VARCHAR(128) DEFAULT NULL COMMENT '型号' AFTER `spec`;

-- 2. 销售退货明细表
ALTER TABLE `sal_return_detail` ADD COLUMN `model` VARCHAR(128) DEFAULT NULL COMMENT '型号' AFTER `spec`;

-- 3. 采购入库明细表
ALTER TABLE `pur_receipt_detail` ADD COLUMN `model` VARCHAR(128) DEFAULT NULL COMMENT '型号' AFTER `spec`;

-- 4. 采购退货明细表
ALTER TABLE `pur_return_detail` ADD COLUMN `model` VARCHAR(128) DEFAULT NULL COMMENT '型号' AFTER `spec`;

-- 5. 生产加工单主表
ALTER TABLE `prd_order` ADD COLUMN `model` VARCHAR(128) DEFAULT NULL COMMENT '型号' AFTER `spec`;
