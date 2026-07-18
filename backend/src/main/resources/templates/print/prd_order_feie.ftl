<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<style>
  body {
    margin: 0;
    padding: 4px;
    font-family: "SimSun", "STSong", serif;
    font-size: 11px;
    color: #000;
    width: 56mm;
  }
  .header {
    text-align: center;
    font-size: 16px;
    font-weight: bold;
    margin-bottom: 6px;
    letter-spacing: 2px;
  }
  .sub-header {
    text-align: center;
    font-size: 10px;
    margin-bottom: 8px;
    border-bottom: 1px dashed #000;
    padding-bottom: 4px;
  }
  .info-row {
    display: flex;
    justify-content: space-between;
    margin-bottom: 2px;
    font-size: 11px;
  }
  .info-label {
    font-weight: bold;
  }
  .section-title {
    font-size: 12px;
    font-weight: bold;
    margin-top: 6px;
    margin-bottom: 3px;
    border-bottom: 1px solid #000;
    padding-bottom: 2px;
  }
  table {
    width: 100%;
    border-collapse: collapse;
    font-size: 10px;
    margin-bottom: 4px;
  }
  table th {
    background: #eee;
    padding: 2px 1px;
    text-align: left;
    border: 1px solid #999;
    font-weight: bold;
  }
  table td {
    padding: 2px 1px;
    border: 1px solid #999;
    text-align: center;
  }
  table td.left { text-align: left; }
  .footer {
    text-align: center;
    font-size: 9px;
    margin-top: 8px;
    padding-top: 4px;
    border-top: 1px dashed #000;
  }
  .big-num {
    font-size: 18px;
    font-weight: bold;
  }
</style>
</head>
<body>
  <!-- 标题 -->
  <div class="header">生产加工单</div>
  <div class="sub-header">
    单号: ${order.billNo!''}<br>
    日期: ${order.billDate!' '}
  </div>

  <!-- 基本信息 -->
  <div class="info-row">
    <span class="info-label">产品:</span>
    <span>${order.productName!''} (${order.productCode!''})</span>
  </div>
  <div class="info-row">
    <span class="info-label">规格:</span>
    <span>${order.spec!''}</span>
  </div>
  <#if order.model?has_content>
  <div class="info-row">
    <span class="info-label">型号:</span>
    <span>${order.model!''}</span>
  </div>
  </#if>
  <#if order.bomName?has_content>
  <div class="info-row">
    <span class="info-label">配方:</span>
    <span>${order.bomName!''}</span>
  </div>
  </#if>
  <#if order.thickness??>
  <div class="info-row">
    <span class="info-label">尺寸:</span>
    <span>${order.thickness!'0'}×${order.width!'0'}×${order.density!'0'}</span>
  </div>
  </#if>
  <#if order.material?has_content>
  <div class="info-row">
    <span class="info-label">材质:</span>
    <span>${order.material!''}</span>
  </div>
  </#if>
  <#if order.gramWeight??>
  <div class="info-row">
    <span class="info-label">克重:</span>
    <span>${order.gramWeight!'0'}g/㎡</span>
  </div>
  </#if>

  <!-- 数量信息 -->
  <div class="section-title">生产数量</div>
  <div class="info-row">
    <span class="info-label">计划:</span>
    <span class="big-num">${order.planQty!'0'}</span>
  </div>
  <div class="info-row">
    <span class="info-label">单位:</span>
    <span>${order.unitName!''}</span>
  </div>
  <div class="info-row">
    <span class="info-label">良品:</span>
    <span>${order.goodQty!'0'}</span>
  </div>
  <div class="info-row">
    <span class="info-label">损耗:</span>
    <span>${order.lossQty!'0'} (${order.lossRate!'0'}%)</span>
  </div>

  <!-- 车间信息 -->
  <div class="section-title">生产安排</div>
  <div class="info-row">
    <span class="info-label">车间:</span>
    <span>${order.workshop!''}</span>
  </div>
  <div class="info-row">
    <span class="info-label">负责人:</span>
    <span>${order.leader!''}</span>
  </div>
  <div class="info-row">
    <span class="info-label">日期:</span>
    <span>${order.startDate!'—'} ~ ${order.endDate!'—'}</span>
  </div>

  <!-- 领料明细 -->
  <#if order.requisitionDetails?has_content && order.requisitionDetails?size gt 0>
  <div class="section-title">领料明细</div>
  <table>
    <thead>
      <tr>
        <th style="width:30%">物料</th>
        <th style="width:25%">规格</th>
        <th style="width:20%">数量</th>
        <th style="width:25%">单位</th>
      </tr>
    </thead>
    <tbody>
      <#list order.requisitionDetails as d>
      <tr>
        <td class="left">${d.productName!''}</td>
        <td class="left">${d.productCode!''}</td>
        <td>${d.qty!'0'}</td>
        <td>${d.unitName!''}</td>
      </tr>
      </#list>
    </tbody>
  </table>
  </#if>

  <!-- 备注 -->
  <#if order.remark?has_content>
  <div class="section-title">备注</div>
  <div style="font-size:10px;margin-bottom:4px;">${order.remark!''}</div>
  </#if>

  <!-- 底部 -->
  <div class="footer">
    飞鹅云打印 · ${.now?string("yyyy-MM-dd HH:mm:ss")}<br>
    请核对后开始生产
  </div>
</body>
</html>
