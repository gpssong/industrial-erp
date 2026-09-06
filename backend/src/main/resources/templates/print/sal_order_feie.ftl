<CB>销售订单</CB><BR>
单号: ${bill.billNo!''}<BR>
日期: ${bill.billDate!''}<BR>
客户: ${bill.customerName!''}
<#if bill.phone?has_content>
<br>电话: ${bill.phone!''}
</#if>
<BR>
交货日期: ${bill.deliveryDate!''}
<#if bill.payType?has_content>
<br>付款方式: ${bill.payType!''}
</#if>
<BR>
<BR>
<#if (details?size > 0)>
<C>商品明细</C><BR>
<#list details as d>
${d.productName!''}  数量: ${d.qty!'0'}  单价(含税): ${d.price!''}  金额: ${d.amount!''}
<#if d.spec?has_content>
  规格: ${d.spec!''}
</#if>
<#if d.batchNo?has_content>
  批次: ${d.batchNo!''}
</#if>
<BR>
</#list>
</#if>
<BR>
合计: 数量 ${bill.totalQty!'0'}  金额: ¥${bill.totalAmount!'0'}
<#if bill.remark?has_content>
<BR>
备注: ${bill.remark!''}
</#if>
<BR>
请当面验收
