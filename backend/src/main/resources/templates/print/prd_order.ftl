<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<title>生产加工单</title>
<style>
  body{font-family:"SimHei","Microsoft YaHei";font-size:12px;margin:0 auto;padding:5mm;max-width:210mm;}
  h1{text-align:center;font-size:18px;margin:0 0 6px 0;border-bottom:2px solid #000;padding-bottom:4px;}
  .info-grid{display:grid;grid-template-columns:1fr 1fr;gap:4px 20px;font-size:11px;line-height:1.8;}
  .info-row{display:flex;gap:10px;}
  .info-label{font-weight:bold;color:#333;min-width:70px;}
  .section{margin-top:12px;}
  .section-title{font-size:12px;font-weight:bold;border-bottom:1px solid #333;padding-bottom:2px;margin-bottom:6px;}
  table{width:100%;border-collapse:collapse;font-size:11px;}
  th{background:#f5f5f5;border:1px solid #ccc;padding:4px;text-align:left;}
  td{border:1px solid #ccc;padding:4px;}
  .text-right{text-align:right;}
  .footer{margin-top:20px;display:grid;grid-template-columns:1fr 1fr 1fr;gap:20px;font-size:11px;}
  .sign{text-align:center;margin-top:30px;}
</style>
</head>
<body>
<h1>生产加工单</h1>

<div class="info-grid">
  <div class="info-row"><span class="info-label">单号:</span><span>${(bill.billNo)!"-"}</span></div>
  <div class="info-row"><span class="info-label">日期:</span><span>${(bill.billDate)!"-"}</span></div>
  <div class="info-row"><span class="info-label">BOM编号:</span><span>${(bill.bomNo)!"-"}</span></div>
  <div class="info-row"><span class="info-label">状态:</span><span>${(bill.billStatus)!"-"}</span></div>
  <div class="info-row"><span class="info-label">成品:</span><span>${(bill.productName)!"-"} ${(bill.productCode)!"-"}</span></div>
  <div class="info-row"><span class="info-label">规格:</span><span>${(bill.spec)!"-"}</span></div>
  <div class="info-row"><span class="info-label">长度/宽度/厚度:</span><span>${(bill.thickness)!"-"}/${(bill.width)!"-"}/${(bill.density)!"-"}</span></div>
  <div class="info-row"><span class="info-label">克重/材质:</span><span>${(bill.gramWeight)!"-"}/${(bill.material)!"-"}</span></div>
  <div class="info-row"><span class="info-label">计划数量:</span><span>${((bill.planQty)!0)?string["0.####"]} ${(bill.unitName)!"-"}</span></div>
  <div class="info-row"><span class="info-label">实际数量:</span><span>${((bill.actualQty)!0)?string["0.####"]}</span></div>
  <div class="info-row"><span class="info-label">良品数量:</span><span>${((bill.goodQty)!0)?string["0.####"]}</span></div>
  <div class="info-row"><span class="info-label">损耗数量:</span><span>${((bill.lossQty)!0)?string["0.####"]}</span></div>
  <div class="info-row"><span class="info-label">损耗率:</span><span>${((bill.lossRate)!0)?string["0.##"]}%</span></div>
  <div class="info-row"><span class="info-label">车间:</span><span>${(bill.workshop)!"-"}</span></div>
  <div class="info-row"><span class="info-label">负责人:</span><span>${(bill.leader)!"-"}</span></div>
  <div class="info-row"><span class="info-label">开工日期:</span><span>${(bill.startDate)!"-"}</span></div>
  <div class="info-row"><span class="info-label">完工日期:</span><span>${(bill.endDate)!"-"}</span></div>
</div>

<#if (bill).remark?? && (bill).remark?has_content>
<div class="section">
  <div class="info-row"><span class="info-label">备注:</span><span>${(bill).remark}</span></div>
</div>
</#if>

<div class="footer">
  <div class="sign">制单人:_______________</div>
  <div class="sign">负责人:_______________</div>
  <div class="sign">${.now?string["yyyy-MM-dd HH:mm"]} 打印</div>
</div>
</body>
</html>
