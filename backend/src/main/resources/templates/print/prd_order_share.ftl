<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8"/>
<title>生产加工单 ${order.billNo!''}</title>
</head>
<body style="font-family:'WenQuanYi Micro Hei';font-size:10pt;color:#000;">
<table width="100%" cellpadding="0" cellspacing="0" border="0" style="margin-bottom:8pt;">
  <tr>
    <td width="60%" style="font-size:18pt;font-weight:bold;">生产加工单</td>
    <td width="40%" align="right" style="color:#666;font-size:9pt;">${order.billDate!''}</td>
  </tr>
  <tr>
    <td colspan="2" style="font-size:13pt;font-weight:bold;color:#1e6091;padding-top:2pt;">${order.billNo!''}</td>
  </tr>
</table>

<table width="100%" cellpadding="6" cellspacing="0" border="1" style="border-collapse:collapse;border-color:#bbb;margin-bottom:10pt;">
  <tr style="background:#f0f2f5;">
    <th colspan="4" align="left" style="font-size:11pt;font-weight:bold;">成品信息</th>
  </tr>
  <tr>
    <td width="15%" style="background:#fafafa;color:#666;">成品</td>
    <td width="35%">${order.productName!''}</td>
    <td width="15%" style="background:#fafafa;color:#666;">编码</td>
    <td width="35%">${order.productCode!''}</td>
  </tr>
  <tr>
    <td style="background:#fafafa;color:#666;">规格</td>
    <td>${order.spec!''}</td>
    <td style="background:#fafafa;color:#666;">型号</td>
    <td>${order.model!''}</td>
  </tr>
  <tr>
    <td style="background:#fafafa;color:#666;">配方 (BOM)</td>
    <td colspan="3">${order.bomName!''} <span style="color:#999;font-size:9pt;">${order.bomCode!' '}</span></td>
  </tr>
  <tr>
    <td style="background:#fafafa;color:#666;">色号</td>
    <td>${order.colorNo!''}</td>
    <td style="background:#fafafa;color:#666;">材质</td>
    <td>${order.material!''}</td>
  </tr>
  <tr>
    <td style="background:#fafafa;color:#666;">长度</td>
    <td>${order.thickness!''} mm</td>
    <td style="background:#fafafa;color:#666;">宽度</td>
    <td>${order.width!''} mm</td>
  </tr>
  <tr>
    <td style="background:#fafafa;color:#666;">厚度</td>
    <td>${order.density!''}</td>
    <td style="background:#fafafa;color:#666;">克重</td>
    <td>${order.gramWeight!''} g</td>
  </tr>
  <tr>
    <td style="background:#fafafa;color:#666;">计划数量</td>
    <td><b>${order.planQty!0}</b> ${order.unitName!''}</td>
    <td style="background:#fafafa;color:#666;">计划损耗</td>
    <td>${order.lossRate!0} %</td>
  </tr>
</table>

<table width="100%" cellpadding="6" cellspacing="0" border="1" style="border-collapse:collapse;border-color:#bbb;margin-bottom:10pt;">
  <tr style="background:#f0f2f5;">
    <th colspan="4" align="left" style="font-size:11pt;font-weight:bold;">生产安排</th>
  </tr>
  <tr>
    <td width="15%" style="background:#fafafa;color:#666;">车间</td>
    <td width="35%">${order.workshop!''}</td>
    <td width="15%" style="background:#fafafa;color:#666;">负责人</td>
    <td width="35%">${order.leader!''}</td>
  </tr>
  <tr>
    <td style="background:#fafafa;color:#666;">开工日期</td>
    <td>${order.startDate!''}</td>
    <td style="background:#fafafa;color:#666;">完工日期</td>
    <td>${order.endDate!''}</td>
  </tr>
</table>

<#if (order.actualQty?? && order.actualQty != 0) || (order.goodQty?? && order.goodQty != 0) || (order.lossQty?? && order.lossQty != 0)>
<table width="100%" cellpadding="6" cellspacing="0" border="1" style="border-collapse:collapse;border-color:#bbb;margin-bottom:10pt;">
  <tr style="background:#f0f2f5;">
    <th colspan="4" align="left" style="font-size:11pt;font-weight:bold;">实际数据</th>
  </tr>
  <tr>
    <td width="15%" style="background:#fafafa;color:#666;">实际数量</td>
    <td width="35%">${order.actualQty!0}</td>
    <td width="15%" style="background:#fafafa;color:#666;">良品数</td>
    <td width="35%">${order.goodQty!0}</td>
  </tr>
  <tr>
    <td style="background:#fafafa;color:#666;">损耗数</td>
    <td>${order.lossQty!0}</td>
    <td style="background:#fafafa;color:#666;">实际损耗率</td>
    <td>${order.lossRate!0} %</td>
  </tr>
</table>
</#if>

<#if requisitionDetails?? && requisitionDetails?size != 0>
<table width="100%" cellpadding="6" cellspacing="0" border="1" style="border-collapse:collapse;border-color:#bbb;margin-bottom:10pt;">
  <tr style="background:#f0f2f5;">
    <th colspan="5" align="left" style="font-size:11pt;font-weight:bold;">领料明细</th>
  </tr>
  <tr style="background:#fafafa;">
    <th width="8%" align="center">行号</th>
    <th width="14%" align="left">物料编码</th>
    <th align="left">物料名称</th>
    <th width="14%" align="right">数量</th>
    <th width="10%" align="center">单位</th>
  </tr>
  <#list requisitionDetails as d>
  <tr>
    <td align="center">${d.lineNo!0}</td>
    <td>${d.productCode!''}</td>
    <td>${d.productName!''}</td>
    <td align="right">${d.qty!0}</td>
    <td align="center">${d.unitName!''}</td>
  </tr>
  </#list>
</table>
</#if>

<#if order.remark?? && order.remark != ''>
<table width="100%" cellpadding="6" cellspacing="0" border="1" style="border-collapse:collapse;border-color:#bbb;margin-bottom:10pt;">
  <tr style="background:#f0f2f5;">
    <th align="left" style="font-size:11pt;font-weight:bold;">备注</th>
  </tr>
  <tr>
    <td>${order.remark!''}</td>
  </tr>
</table>
</#if>

<table width="100%" cellpadding="0" cellspacing="0" border="0" style="margin-top:14pt;border-top:1pt solid #ccc;">
  <tr>
    <td style="color:#999;font-size:8pt;padding-top:4pt;">单据状态: ${order.billStatus!'DRAFT'}  ·  生成时间: ${.now?string('yyyy-MM-dd HH:mm')}</td>
  </tr>
</table>

</body>
</html>
