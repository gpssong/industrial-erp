-- =====================================================================
-- 十、初始化数据 (超级管理员 / 角色 / 菜单 / 字典 / 单位 / 价格等级)
-- =====================================================================
USE `industrial_erp`;

-- 10.1  超级管理员账号  密码: admin123 (BCrypt 加密)
INSERT INTO `sys_user`(`id`,`username`,`password`,`nickname`,`real_name`,`is_admin`,`status`) VALUES
(1,'admin','$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2','超级管理员','系统管理员',1,1);

-- 10.2  部门
INSERT INTO `sys_dept`(`id`,`parent_id`,`dept_code`,`dept_name`,`sort_no`) VALUES
(1,0,'ROOT','总公司',0),
(2,1,'BM_PURCHASE','采购部',1),
(3,1,'BM_SALES','销售部',2),
(4,1,'BM_WAREHOUSE','仓储部',3),
(5,1,'BM_PRODUCTION','生产部',4),
(6,1,'BM_FINANCE','财务部',5);

-- 10.3  角色
INSERT INTO `sys_role`(`id`,`role_code`,`role_name`,`data_scope`) VALUES
(1,'SUPER_ADMIN','超级管理员',1),
(2,'PURCHASE_MGR','采购经理',2),
(3,'SALES_MGR','销售经理',2),
(4,'WAREHOUSE_MGR','仓库主管',2),
(5,'PRODUCTION_MGR','生产主管',2),
(6,'FINANCE','财务',4);

-- 10.4  用户-角色
INSERT INTO `sys_user_role`(`user_id`,`role_id`) VALUES(1,1);

-- 10.5  计量单位
INSERT INTO `base_unit`(`unit_code`,`unit_name`) VALUES
('JUAN','卷'),('MI','米'),('KG','公斤'),('ZHANG','张'),('JIAN','件'),('QIANKE','千克'),
('TAI','吨'),('GE','个'),('PING','瓶'),('HE','盒'),('M2','平方米'),('M3','立方米');

-- 10.6  价格等级
INSERT INTO `base_price_level`(`level_code`,`level_name`,`discount_rate`) VALUES
('RETAIL','零售价',1.0000),
('WHOLESALE','批发价',0.9000),
('VIP','大客户价',0.8500),
('DISTRIBUTOR','经销商价',0.7500);

-- 10.7  仓库
INSERT INTO `base_warehouse`(`id`,`warehouse_code`,`warehouse_name`,`warehouse_type`,`is_default`) VALUES
(1,'WH001','原料一库','RAW',0),
(2,'WH002','成品一库','FG',1),
(3,'WH003','半成品库','SEMI',0),
(4,'WH004','辅料库','RAW',0);

-- 10.8  库区
INSERT INTO `base_warehouse_area`(`warehouse_id`,`area_code`,`area_name`,`sort_no`) VALUES
(1,'A01','A区货架',1),
(1,'A02','A区地堆',2),
(2,'B01','B区货架',1),
(2,'B02','B区地堆',2),
(3,'C01','C区',1);

-- 10.9  库位 (示例)
INSERT INTO `base_warehouse_location`(`warehouse_id`,`area_id`,`location_code`,`location_name`) VALUES
(1,1,'A01-01','A01-01'),(1,1,'A01-02','A01-02'),(1,1,'A01-03','A01-03'),
(2,4,'B02-01','B02-01'),(2,4,'B02-02','B02-02'),(2,4,'B02-03','B02-03');

-- 10.10 商品分类
INSERT INTO `base_product_category`(`id`,`parent_id`,`category_code`,`category_name`,`category_type`) VALUES
(1,0,'CAT_FILM','薄膜','FG'),
(2,0,'CAT_PLASTIC','塑料制品','FG'),
(3,0,'CAT_HARDWARE','五金','FG'),
(4,0,'CAT_RAW','原材料','RAW'),
(5,4,'CAT_RAW_PE','PE原料','RAW'),
(6,4,'CAT_RAW_PET','PET原料','RAW'),
(7,1,'CAT_FILM_BOPP','BOPP薄膜','FG'),
(8,1,'CAT_FILM_CPP','CPP薄膜','FG'),
(9,1,'CAT_FILM_PE','PE保护膜','FG');

-- 10.11 字典类型
INSERT INTO `sys_dict_type`(`dict_name`,`dict_type`) VALUES
('商品类型','product_type'),
('单据状态','bill_status'),
('收款方式','pay_type'),
('订单类型','order_type'),
('价格等级','price_level'),
('应收应付类型','arap_type'),
('计量单位','unit_type');

-- 10.12 字典数据
INSERT INTO `sys_dict_data`(`dict_type`,`dict_label`,`dict_value`,`sort_no`) VALUES
('product_type','商品','GOODS',1),('product_type','原材料','RAW',2),('product_type','半成品','SEMI',3),('product_type','成品','FG',4),('product_type','服务','SERVICE',5),
('bill_status','草稿','DRAFT',1),('bill_status','已审核','CHECKED',2),('bill_status','已完成','FINISHED',3),('bill_status','已关闭','CLOSED',4),('bill_status','已取消','CANCELLED',5),
('pay_type','现金','CASH',1),('pay_type','银行转账','BANK',2),('pay_type','微信','WECHAT',3),('pay_type','支付宝','ALIPAY',4),('pay_type','预付款','PREPAY',5),
('order_type','正常','NORMAL',1),('order_type','加急','URGENT',2),('order_type','合同订单','CONTRACT',3),
('price_level','零售价','RETAIL',1),('price_level','批发价','WHOLESALE',2),('price_level','大客户价','VIP',3),('price_level','经销商价','DISTRIBUTOR',4),
('arap_type','应收','AR',1),('arap_type','应付','AP',2);

-- 10.13 系统配置
INSERT INTO `sys_config`(`config_name`,`config_key`,`config_value`,`config_type`,`remark`) VALUES
('系统名称','sys.name','华鑫工业ERP','1',NULL),
('公司名称','sys.company','华鑫薄膜科技有限公司','1',NULL),
('默认税率','sys.tax.rate','13','2','默认13%'),
('信用超额是否允许开单','sys.credit.overdraft','false','2','false 禁止 / true 允许'),
('是否允许负库存出库','sys.stock.allow.negative','false','2','严格禁止'),
('成本计算方式','sys.cost.method','MOVING_AVG','2','MOVING_AVG=移动加权平均'),
('打印份数','sys.print.copies','1','2',NULL);

-- 10.14 客户 / 供应商
INSERT INTO `base_customer`(`customer_code`,`customer_name`,`contact_person`,`phone`,`price_level`,`credit_limit`) VALUES
('C001','深圳市光明包装有限公司','张总','13800001111','VIP',500000),
('C002','东莞长安塑胶厂','李经理','13800002222','WHOLESALE',200000),
('C003','广州白云印刷有限公司','王主任','13800003333','RETAIL',50000);

INSERT INTO `base_supplier`(`supplier_code`,`supplier_name`,`contact_person`,`phone`) VALUES
('S001','中石化PE原料事业部','赵经理','13900001111'),
('S002','浙江恒逸PET切片','钱总','13900002222'),
('S003','深圳宝安分切外协厂','孙厂长','13900003333');

-- 10.15 商品 (含工业特性)
INSERT INTO `base_product`
(`product_code`,`product_name`,`category_id`,`product_type`,`spec`,`model`,`material`,`thickness`,`width`,`density`,`color_no`,`main_unit_id`,`tax_rate`,`is_weigh`,`is_batch`,`safety_stock`,`sales_price`,`wholesale_price`,`vip_price`,`cost_price`)
VALUES
('P-FM-BOPP-001','BOPP薄膜 20um×1000mm','7','FG','20um×1000mm×4000m','BOPP-20-1000','BOPP',20.0000,1000.0000,0.910000,'透明',1,13.00,1,1,1000.0000,28.0000,25.0000,22.0000,0),
('P-FM-CPP-001','CPP薄膜 25um×800mm','8','FG','25um×800mm×3000m','CPP-25-800','CPP',25.0000,800.0000,0.900000,'透明',1,13.00,1,1,800.0000,32.0000,28.0000,25.0000,0),
('P-FM-PE-001','PE保护膜 30um×1200mm','9','FG','30um×1200mm×5000m','PE-30-1200','PE',30.0000,1200.0000,0.920000,'蓝色',1,13.00,1,1,500.0000,18.0000,15.0000,12.0000,0),
('P-RW-PE-001','PE原料颗粒 工业级','5','RAW','颗粒 25kg/袋','PE-IND-25','PE',NULL,NULL,0.920000,'白色',3,13.00,1,1,5000.0000,0,0,0,0),
('P-RW-PET-001','PET切片 工业级','6','RAW','颗粒 25kg/袋','PET-IND-25','PET',NULL,NULL,1.380000,'透明',3,13.00,1,1,5000.0000,0,0,0,0);

-- 10.16 商品多单位 (1卷 = 4000米 = ? 公斤)
INSERT INTO `base_product_unit`(`product_id`,`unit_id`,`unit_name`,`is_main`,`conversion_rate`,`sales_price`,`wholesale_price`,`vip_price`)
SELECT id, 1, '卷', 1, 1, sales_price, wholesale_price, vip_price FROM `base_product` WHERE product_code='P-FM-BOPP-001';
INSERT INTO `base_product_unit`(`product_id`,`unit_id`,`unit_name`,`is_main`,`conversion_rate`,`sales_price`,`wholesale_price`,`vip_price`)
SELECT id, 2, '米', 0, 0.007, sales_price/4000, wholesale_price/4000, vip_price/4000 FROM `base_product` WHERE product_code='P-FM-BOPP-001';
INSERT INTO `base_product_unit`(`product_id`,`unit_id`,`unit_name`,`is_main`,`conversion_rate`,`sales_price`,`wholesale_price`,`vip_price`)
SELECT id, 3, '公斤', 0, 0.0027, sales_price*0.27, wholesale_price*0.27, vip_price*0.27 FROM `base_product` WHERE product_code='P-FM-BOPP-001';

-- 10.17 打印模板 (Freemarker)
INSERT INTO `sys_print_template`(`template_code`,`template_name`,`template_type`,`paper_width`,`paper_height`,`content`,`is_default`) VALUES
('SAL_DELIVERY','销售出库单(80mm)','paper_80',80,200,
'<!DOCTYPE html><html><head><meta charset="utf-8"><title>销售出库单</title><style>body{font-family:SimHei;font-size:11px;width:76mm;margin:0 auto;}h1{text-align:center;font-size:14px;margin:4px 0;}table{width:100%;border-collapse:collapse;}th,td{border-bottom:1px dashed #000;padding:2px 4px;font-size:10px;}.total{text-align:right;font-weight:bold;}</style></head><body>
<h1>销售出库单</h1>
<div>单号: ${bill.bill_no}</div>
<div>日期: ${bill.bill_date}</div>
<div>客户: ${bill.customer_name}</div>
<table><tr><th>商品</th><th>规格</th><th>数量</th><th>单价</th><th>金额</th></tr>
<#list details as d><tr><td>${d.product_name}</td><td>${d.spec!}</td><td>${d.qty}</td><td>${d.price}</td><td>${d.amount}</td></tr></#list>
</table>
<div class="total">合计金额: ${bill.total_amount}</div>
<div class="total">税额: ${bill.tax_amount}</div>
<div class="total">价税合计: ${bill.total_amount_tax}</div>
<div>地址: ${bill.address!}</div>
<div>电话: ${bill.phone!}</div>
<div style="text-align:right;margin-top:8px;">客户签名:____________</div>
</body></html>',1),

('PUR_RECEIPT','采购入库单(80mm)','paper_80',80,200,
'<!DOCTYPE html><html><head><meta charset="utf-8"><title>采购入库单</title><style>body{font-family:SimHei;font-size:11px;width:76mm;margin:0 auto;}h1{text-align:center;font-size:14px;margin:4px 0;}table{width:100%;border-collapse:collapse;}th,td{border-bottom:1px dashed #000;padding:2px 4px;font-size:10px;}.total{text-align:right;font-weight:bold;}</style></head><body>
<h1>采购入库单</h1>
<div>单号: ${bill.bill_no}</div>
<div>日期: ${bill.bill_date}</div>
<div>供应商: ${bill.supplier_name}</div>
<table><tr><th>商品</th><th>数量</th><th>单价</th><th>金额</th></tr>
<#list details as d><tr><td>${d.product_name}</td><td>${d.qty}</td><td>${d.price}</td><td>${d.amount}</td></tr></#list>
</table>
<div class="total">合计: ${bill.total_amount_tax}</div>
</body></html>',1);

