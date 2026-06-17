<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<title>销售出库单</title>
<style>
  body{font-family: "SimHei","Microsoft YaHei";font-size:11px;width:76mm;margin:0 auto;padding:2mm;}
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
<h1>销售出库单</h1>
<div class="row"><span>单号: ${bill.billNo}</span></div>
<div class="row"><span>日期: ${bill.billDate}</span><span>仓库: ${bill.warehouseId!""}</span></div>
<div class="row"><span>客户: ${bill.customerName!}</span></div>
<#if bill.address??><div class="row"><span>地址: ${bill.address}</span></div></#if>
<#if bill.phone??><div class="row"><span>电话: ${bill.phone}</span></div></#if>
<table>
  <thead><tr><th>序号</th><th>商品</th><th>规格</th><th class="right">数量</th><th class="right">单价</th><th class="right">金额</th></tr></thead>
  <tbody>
  <#list details as d>
    <tr>
      <td>${d.lineNo!0}</td>
      <td>${d.productName!}</td>
      <td>${d.spec!""}</td>
      <td class="right">${d.qty?string["0.####"]}</td>
      <td class="right">${d.price?string["0.####"]}</td>
      <td class="right">${d.amount?string["0.####"]}</td>
    </tr>
  </#list>
  </tbody>
</table>
<div class="hr"></div>
<div class="row"><span>合计数量:</span><span>${bill.totalQty?string["0.####"]}</span></div>
<div class="row"><span>合计金额:</span><span>${bill.totalAmount?string["0.####"]}</span></div>
<div class="row"><span>税额:</span><span>${bill.taxAmount?string["0.####"]}</span></div>
<div class="total">价税合计: ¥${bill.totalAmountTax?string["0.####"]}</div>
<div class="sign">客户签收:_______________</div>
<div class="sign" style="margin-top:8px;">${.now?string["yyyy-MM-dd HH:mm"]} 打印</div>
</body>
</html>
