<CB>销售退货单</CB><BR>
单号: ${bill.billNo!''}<BR>
日期: ${bill.billDate!''}<BR>
客户: ${bill.customerName!''}
<#if bill.customerPhone?has_content>
<br>电话: ${bill.customerPhone!''}
</#if>
<BR>
仓库: ${bill.warehouseName!''}
<BR>
<BR>
<#if (details?size > 0)>
<C>退货明细</C><BR>
<#list details as d>
${d.productName!''}  数量: ${d.qty!'0'}  单价: ${d.price!''}  金额: ${d.amount!''}<BR>
</#list>
</#if>
<BR>
合计: ¥${bill.totalAmount!'0'}
<#if bill.remark?has_content>
<BR>
备注: ${bill.remark!''}
</#if>
<BR>
退货凭证