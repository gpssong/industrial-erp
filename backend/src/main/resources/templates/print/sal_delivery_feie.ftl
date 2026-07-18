<CB>销售出库单</CB><BR>
单号: ${bill.billNo!''}<BR>
日期: ${bill.billDate!''}<BR>
客户: ${bill.customerName!''}
<#if bill.customerPhone?has_content>
<br>电话: ${bill.customerPhone!''}
</#if>
<BR>
仓库: ${bill.warehouseName!''}
<#if bill.salesUser?has_content>
<br>业务员: ${bill.salesUser!''}
</#if>
<BR>
<BR>
<#if (details?size > 0)>
<C>商品明细</C><BR>
<#list details as d>
${d.productName!''}  数量: ${d.qty!'0'}  单价: ${d.price!''}  金额: ${d.amount!''}<BR>
</#list>
</#if>
<BR>
合计: ¥${bill.totalAmount!'0'}
<#if bill.totalAmountTax?has_content && bill.totalAmountTax != bill.totalAmount>
<br>含税: ¥${bill.totalAmountTax!''}
</#if>
<#if bill.remark?has_content>
<BR>
备注: ${bill.remark!''}
</#if>
<BR>
请当面验收