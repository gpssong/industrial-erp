-- sql/152  v1.1.162 飞鹅「生产单」默认模板内容修复 (sys_feie_print_template, biz_type=PRD_ORDER, id=2078644534343434242)
--
-- 现象: PC/App 生产单飞鹅预览与打印报
--       「自定义模板渲染失败: Syntax error in template "custom" in line 22, column 45: Encountered "''"」
-- 根因: 2026-11-03 14:14:09 在 PC「系统设置 > 飞鹅打印模板」里手工改过这一行, 当时后端保存没有语法校验,
--       坏内容直接落库, 只在预览/渲染时才炸。三处语法错:
--         L21  <#list bill.details as d>      -> 根变量错: PrdOrder 没有 getDetails(), bill.details 永远不存在, 应为 details
--         L22  ${d.qty!0''}                  -> 默认值写法非法: 0 后面不能再跟 ''  , 应为 ${d.qty!0}
--         L22 之后缺 </#list>                -> 标签不闭合
--       顺带: L22 明细行没有 <BR>, 就算语法过也是一行糊在一起。
-- 同批代码修复(v1.1.162, 已三站上线): SysFeiePrintTemplateService 保存前用 FreeMarker 试解析,
--       ParseException 回 400「模板语法有误: 第 N 行 第 M 列 — 该行内容: ...」;
--       PrdOrderBillLoader 同时注入 order 与 bill 两个别名; PC 编辑器提示改为 details / ${order.xxx}.
--
-- 幂等: UPDATE 命中条件带 CHAR_LENGTH(content)=925 (坏内容的字节长度), 修复后 (927) 再跑就是 0 行。
-- ⚠️ 只在 .150 主库执行, 复制链自动到 .32 / 阿里云; **禁止在 .32 试跑** ——
--    备份表沿用源行主键, canary 本地写过的行灌主库会撞 1062 停复制 (见 erp-deploy §五)。
--    .32 的该行此前已在本地直接改好 (md5 与本次目标一致), 主库这条 UPDATE 复制过去覆盖成同一内容, 收敛一致。
-- 回滚 (需要时手工执行, 注意 update_by 是 bigint 不要塞字符串):
--   update sys_feie_print_template t
--     join sys_feie_print_template_bak_v1162 b on b.id = t.id
--      set t.content = b.content, t.update_time = NOW()
--    where t.id = 2078644534343434242;

use industrial_erp;

create table if not exists sys_feie_print_template_bak_v1162 like sys_feie_print_template;
insert into sys_feie_print_template_bak_v1162
select * from sys_feie_print_template
 where id=2078644534343434242 and not exists (select 1 from (select 1 from sys_feie_print_template_bak_v1162 where id=2078644534343434242) x);

-- 修复前 md5 应为 c2788ea3a784b41f68a73f1da2dc4958 / len 925
select '修复前' as step, char_length(content) as len, md5(content) as md5, update_time
  from sys_feie_print_template where id=2078644534343434242;

update sys_feie_print_template
   set content = '<CB>生产单</CB><BR>
单号：${order.billNo!''''}<BR>
日期：${order.billDate!''''}<BR>
产品：${order.productName!''''}<BR>
编号：${order.productCode!''''}<BR>
<#if order.model?has_content>型号：${order.model}<BR></#if>
<#if order.width != null>宽度：${order.width}mm<BR></#if>
<#if order.thickness != null>长度：${order.thickness}mm<BR></#if>
<#if order.density != null>厚度：${order.density}mm<BR></#if>
<#if order.gramWeight != null>克重：${order.gramWeight}g/个<BR></#if>
<#if order.colorNo?has_content>颜色：${order.colorNo}<BR></#if>
数量：${order.planQty!0}<BR>
<#if order.bomName?has_content>配方：${order.bomName}<BR></#if>
<#if order.spec?has_content>规格：${order.spec}<BR></#if>
<#if order.totalWeightKg != null>使用原料：${order.totalWeightKg}kg<BR></#if>
<#if order.remark?has_content><BR>
<BR>备注：${order.remark}<BR></#if>
<BR>
<QR>${order.productCode!''''}</QR>
<BR><BR><BR><BR><BR>
<#list details as d>
${d.lineNo!''''}｜${d.productName!''''}｜${d.qty!0}｜${d.batchNo!''''}
</#list>',
       update_time = NOW()
 where id=2078644534343434242
   and char_length(content) = 925;

-- 修复后 md5 应为 e56b87a8d7a7494012832e0d3e0cc185 / len 927
select '修复后' as step, char_length(content) as len, md5(content) as md5, update_time
  from sys_feie_print_template where id=2078644534343434242;
