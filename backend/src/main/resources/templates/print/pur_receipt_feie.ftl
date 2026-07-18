<CB>采购入库单</CB><BR>
单号: ${bill.billNo!''}<BR>
日期: ${bill.billDate!''}<BR>
供应商: ${bill.supplierName!''}
<#if bill.supplierPhone?has_content>
<br>电话: ${bill.supplierPhone!''}
</#if>
<BR>
仓库: ${bill.warehouseName!''}
<#if bill.purchaseUser?has_content>
<br>采购员: ${bill.purchaseUser!''}
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
<#if bill.remark?has_content>
<BR>
备注: ${bill.remark!''}
</#if>
<BR>
仓库签收