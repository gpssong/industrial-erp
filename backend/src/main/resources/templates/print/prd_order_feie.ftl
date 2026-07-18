<CB>生产加工单</CB><BR>
单号: ${order.billNo!''}<BR>
日期: ${order.billDate!''}<BR>
<BR>
产品: ${order.productName!''} (${order.productCode!''})<BR>
规格: ${order.spec!''}<BR>
<#if order.model?has_content>
型号: ${order.model!''}<BR>
</#if>
<#if order.bomName?has_content>
配方: ${order.bomName!''}<BR>
</#if>
<#if order.thickness??>
尺寸: ${order.thickness!'0'}×${order.width!'0'}×${order.density!'0'}<BR>
</#if>
<#if order.material?has_content>
材质: ${order.material!''}<BR>
</#if>
<#if order.gramWeight??>
克重: ${order.gramWeight!'0'}g/㎡<BR>
</#if>
<BR>
计划: <B>${order.planQty!'0'}</B> ${order.unitName!''}<BR>
良品: ${order.goodQty!'0'}<BR>
损耗: ${order.lossQty!'0'} (${order.lossRate!'0'}%)<BR>
<BR>
车间: ${order.workshop!''}<BR>
负责人: ${order.leader!''}<BR>
日期: ${order.startDate!'—'} ~ ${order.endDate!'—'}<BR>
<#if order.remark?has_content>
<BR>
备注: ${order.remark!''}<BR>
</#if>
<QR>${order.billNo!''}</QR><BR>
<CUT>