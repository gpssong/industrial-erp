<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<title>采购退货单</title>
<style>
  body{font-family:"SimHei","Microsoft YaHei";font-size:11px;width:76mm;margin:0 auto;padding:2mm;}
  h1{text-align:center;font-size:14px;margin:4px 0 6px 0;border-bottom:1px solid #000;padding-bottom:2px;}
  .row{display:flex;justify-content:space-between;font-size:10px;line-height:1.6;}
  table{width:100%;border-collapse:collapse;margin:4px 0;}
  th{background:#f0f0f0;font-size:10px;text-align:left;padding:2px;border-bottom:1px solid #000;}
  td{font-size:10px;padding:2px 1px;border-bottom:1px dashed #999;}
  .total{text-align:right;font-weight:bold;font-size:11px;margin-top:4px;}
  .hr{border-top:1px dashed #000;margin:4px 0;}
  .sign{text-align:right;margin-top:6px;font-size:10px;}
  .right{text-align:right;}
</style>
</head>
<body>
<h1>采购退货单</h1>
<div class="row"><span>单号: ${(bill.billNo)!"无"}</span></div>
<div class="row"><span>日期: ${(bill.billDate)!"-"}</span><span>仓库: ${(bill.warehouseName)!(bill.warehouseId)!"-"}</span></div>
<div class="row"><span>供应商: ${(bill.supplierName)!"-"}</span></div>
<#if (bill.sourceReceiptId)??><div class="row"><span>来源入库单ID: ${(bill.sourceReceiptId)?c}</span></div></#if>
<table>
  <thead>
    <tr>
      <th>序号</th><th>商品</th><th>规格</th>
      <th class="right">数量</th><th class="right">单价</th><th class="right">金额</th>
      <#if taxSeparation>
        <th class="right">税率</th><th class="right">税额</th>
      </#if>
    </tr>
  </thead>
  <tbody>
  <#list (details)![] as d>
    <tr>
      <td>${(d.lineNo)!"0"}</td>
      <td>${(d.productName)!"-"}</td>
      <td>${(d.spec)!"-"}</td>
      <td class="right">${((d.qty)!0)?string["0.####"]}</td>
      <td class="right">${((d.price)!0)?string["0.####"]}</td>
      <td class="right">${((d.amount)!0)?string["0.####"]}</td>
      <#if taxSeparation>
        <td class="right">${((d.taxRate)!0)?string["0.##"]}%</td>
        <td class="right">${((d.taxAmount)!0)?string["0.####"]}</td>
      </#if>
    </tr>
  </#list>
  </tbody>
</table>
<div class="hr"></div>
<div class="row"><span>合计数量:</span><span>${((bill.totalQty)!0)?string["0.####"]}</span></div>
<#if taxSeparation>
  <div class="row"><span>不含税:</span><span>${((bill.totalAmount)!0)?string["0.####"]}</span></div>
  <div class="row"><span>税额:</span><span>${((bill.taxAmount)!0)?string["0.####"]}</span></div>
  <div class="total">退货金额: ¥${((bill.totalAmountTax)!0)?string["0.####"]}</div>
<#else>
  <div class="total">退货金额: ¥${((bill.totalAmountTax)!0)?string["0.####"]}</div>
</#if>
<div class="sign">仓管签字:_______________</div>
<div class="sign" style="margin-top:8px;">${.now?string["yyyy-MM-dd HH:mm"]} 打印</div>
</body>
</html>
