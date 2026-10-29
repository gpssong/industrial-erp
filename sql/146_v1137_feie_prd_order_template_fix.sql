-- v1.1.137 飞鹅生产单打印 DB 模板修复 (sys_feie_print_template, biz_type=PRD_ORDER, id=2078644534343434242)
--
-- 根因: DB 行覆盖了 classpath prd_order_feie.ftl, 但正文用的是 Java 字段名 (pWidth/pThickness/
--       pDensity/pGramWeight) 和不存在的变量 (d.qty), 导致规格/领料行全空.
--       FreeMarker 的 JavaBean introspection 只走 getter (getWidth/getThickness/... 别名 getter
--       由 PrdOrder 提供, 对应 transient 字段), 模板必须用 order.width / order.thickness /
--       order.density / order.gramWeight / order.totalWeightKg.
--       同时 PrdOrderBillLoader 之前没有 setTotalWeightKg (只有 PrdOrderService.page/detail 设),
--       模板里 ${order.totalWeightKg} 也渲染不出 —— BillLoader 现按同一算法补齐.
--
-- 幂等: 备份 1 行; 更新按 id + 业务条件精确命中 (内容固定, 重复执行结果一致).
-- 只在 .150 主库执行 (复制链自动到 .32 / 阿里云).
use industrial_erp;

create table if not exists sys_feie_print_template_bak_v1137 like sys_feie_print_template;
insert into sys_feie_print_template_bak_v1137
select * from sys_feie_print_template
 where id=2078644534343434242 and not exists (select 1 from (select 1 from sys_feie_print_template_bak_v1137 where id=2078644534343434242) x);

update sys_feie_print_template
set content = concat(
  '<CB>生产单</CB><BR>\n',
  '单号：${order.billNo!''''}<BR>\n',
  '日期：${order.billDate!''''}<BR>\n',
  '产品：${order.productName!''''}<BR>\n',
  '编号：${order.productCode!''''}<BR>\n',
  '<#if order.model?has_content>型号：${order.model}<BR></#if>\n',
  '<#if order.width != null>宽度：${order.width}mm<BR></#if>\n',
  '<#if order.thickness != null>长度：${order.thickness}mm<BR></#if>\n',
  '<#if order.density != null>厚度：${order.density}mm<BR></#if>\n',
  '<#if order.gramWeight != null>克重：${order.gramWeight}g/个<BR></#if>\n',
  '<#if order.colorNo?has_content>颜色：${order.colorNo}<BR></#if>\n',
  '数量：${order.planQty!0}<BR>\n',
  '<#if order.bomName?has_content>配方：${order.bomName}<BR></#if>\n',
  '<#if order.spec?has_content>规格：${order.spec}<BR></#if>\n',
  '<#if order.totalWeightKg != null>使用原料：${order.totalWeightKg}kg<BR></#if>\n',
  '<#if order.remark?has_content><BR>备注：${order.remark}<BR></#if>\n',
  '<BR>\n',
  '<QR>${order.productCode!''''}</QR>\n',
  '<BR><BR><BR><BR><BR>'
)
where id = 2078644534343434242
  and biz_type = 'PRD_ORDER'
  and is_default = 1
  and deleted = 0
  and content not like '<#if order.width%';

select id, char_length(content) as new_len, update_time from sys_feie_print_template where id = 2078644534343434242;
