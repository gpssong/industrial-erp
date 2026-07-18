<CB>库存盘点单</CB><BR>
单号: ${bill.billNo!''}<BR>
日期: ${bill.billDate!''}<BR>
仓库: ${bill.warehouseName!''}
<#if bill.checkType?has_content>
<br>类型: ${bill.checkType!''}
</#if>
<BR>
<BR>
<#if (details?size > 0)>
<C>盘点明细</C><BR>
<#list details as d>
${d.productName!''}  账面: ${d.bookQty!'0'}  实盘: ${d.actualQty!'0'}  差异: ${d.diffQty!'0'}<BR>
</#list>
</#if>
<BR>
差异合计: ¥${bill.totalDiffAmount!'0'}
<#if bill.remark?has_content>
<BR>
备注: ${bill.remark!''}
</#if>
<BR>
盘点凭证