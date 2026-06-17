package com.industrial.erp.modules.inventory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("inv_ledger")
public class InvLedger {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String billType;
    private Long billId;
    private String billNo;
    private Long billDetailId;
    private Integer bizDirection;
    private LocalDate bizDate;
    private Long warehouseId;
    private Long areaId;
    private Long locationId;
    private Long productId;
    private String productCode;
    private String productName;
    private Long unitId;
    private String unitName;
    private String batchNo;
    private BigDecimal qty;
    private BigDecimal price;
    private BigDecimal amount;
    private BigDecimal beforeQty;
    private BigDecimal afterQty;
    private BigDecimal beforeAvgCost;
    private BigDecimal afterAvgCost;
    private String sourceNo;
    private Long supplierId;
    private Long customerId;
    private String remark;
    private Long createBy;
    private LocalDateTime createTime;
    @TableLogic
    private Integer deleted;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getBillType() { return billType; }
    public void setBillType(String billType) { this.billType = billType; }
    public Long getBillId() { return billId; }
    public void setBillId(Long billId) { this.billId = billId; }
    public String getBillNo() { return billNo; }
    public void setBillNo(String billNo) { this.billNo = billNo; }
    public Long getBillDetailId() { return billDetailId; }
    public void setBillDetailId(Long billDetailId) { this.billDetailId = billDetailId; }
    public Integer getBizDirection() { return bizDirection; }
    public void setBizDirection(Integer bizDirection) { this.bizDirection = bizDirection; }
    public LocalDate getBizDate() { return bizDate; }
    public void setBizDate(LocalDate bizDate) { this.bizDate = bizDate; }
    public Long getWarehouseId() { return warehouseId; }
    public void setWarehouseId(Long warehouseId) { this.warehouseId = warehouseId; }
    public Long getAreaId() { return areaId; }
    public void setAreaId(Long areaId) { this.areaId = areaId; }
    public Long getLocationId() { return locationId; }
    public void setLocationId(Long locationId) { this.locationId = locationId; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public Long getUnitId() { return unitId; }
    public void setUnitId(Long unitId) { this.unitId = unitId; }
    public String getUnitName() { return unitName; }
    public void setUnitName(String unitName) { this.unitName = unitName; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public BigDecimal getQty() { return qty; }
    public void setQty(BigDecimal qty) { this.qty = qty; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public BigDecimal getBeforeQty() { return beforeQty; }
    public void setBeforeQty(BigDecimal beforeQty) { this.beforeQty = beforeQty; }
    public BigDecimal getAfterQty() { return afterQty; }
    public void setAfterQty(BigDecimal afterQty) { this.afterQty = afterQty; }
    public BigDecimal getBeforeAvgCost() { return beforeAvgCost; }
    public void setBeforeAvgCost(BigDecimal beforeAvgCost) { this.beforeAvgCost = beforeAvgCost; }
    public BigDecimal getAfterAvgCost() { return afterAvgCost; }
    public void setAfterAvgCost(BigDecimal afterAvgCost) { this.afterAvgCost = afterAvgCost; }
    public String getSourceNo() { return sourceNo; }
    public void setSourceNo(String sourceNo) { this.sourceNo = sourceNo; }
    public Long getSupplierId() { return supplierId; }
    public void setSupplierId(Long supplierId) { this.supplierId = supplierId; }
    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public Long getCreateBy() { return createBy; }
    public void setCreateBy(Long createBy) { this.createBy = createBy; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
}
