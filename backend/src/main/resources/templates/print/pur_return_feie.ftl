<CB>采购退货单</CB><BR>
单号: ${bill.billNo!''}<BR>
日期: ${bill.billDate!''}<BR>
供应商: ${bill.supplierName!''}
<#if bill.supplierPhone?has_content>
<br>电话: ${bill.supplierPhone!''}
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
退货出库凭证