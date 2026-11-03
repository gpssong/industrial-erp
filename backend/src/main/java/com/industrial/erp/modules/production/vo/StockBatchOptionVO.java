package com.industrial.erp.modules.production.vo;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * v1.1.160 — 库存批次候选 (工单原料行「从库存选批号」下拉用).
 * <p>由 GET /production/order/material-suggest 带出: 该原料当前所有 available_qty &gt; 0 的库存行,
 * 跨仓库列出, 用户选定后前端把 warehouseId + batchNo 落到工单原料行, 开工时回填领料明细,
 * 保证领料审核 outStock 能精确命中 inv_stock 行。
 */
public class StockBatchOptionVO {
    private Long warehouseId;
    private String warehouseName;
    private String batchNo;
    private Long unitId;
    private String unitName;
    private BigDecimal availableQty;
    private LocalDate productionDate;
    private LocalDate expireDate;

    public Long getWarehouseId() { return warehouseId; }
    public void setWarehouseId(Long warehouseId) { this.warehouseId = warehouseId; }
    public String getWarehouseName() { return warehouseName; }
    public void setWarehouseName(String warehouseName) { this.warehouseName = warehouseName; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public Long getUnitId() { return unitId; }
    public void setUnitId(Long unitId) { this.unitId = unitId; }
    public String getUnitName() { return unitName; }
    public void setUnitName(String unitName) { this.unitName = unitName; }
    public BigDecimal getAvailableQty() { return availableQty; }
    public void setAvailableQty(BigDecimal availableQty) { this.availableQty = availableQty; }
    public LocalDate getProductionDate() { return productionDate; }
    public void setProductionDate(LocalDate productionDate) { this.productionDate = productionDate; }
    public LocalDate getExpireDate() { return expireDate; }
    public void setExpireDate(LocalDate expireDate) { this.expireDate = expireDate; }
}
