package com.industrial.erp.modules.xk.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * v1.1.189 — 批次台账视图 (mes_batch + 品名/供应商/单位名).
 *
 * <p>{@code mes_batch} 只有 product_id / supplier_id, 现场看到的是一串 id,
 * 不知道这批料是什么牌子 ⇒ 批次台账补上名字 (RAW 走 base_product,
 * FG 的 product_id 为空 ⇒ 按来源工单 prd_order 回查)。
 */
public class XkBatchView {
    private String batchNo;
    private String batchType;
    private Long productId;
    private String productName;
    private Long supplierId;
    private String supplierName;
    private String receiptBillNo;
    private String sourcePrdOrderNo;
    private String processName;
    private String rollNo;
    private String status;
    private String holdReason;
    private BigDecimal qty;
    private Long unitId;
    private String unitName;
    private LocalDate productionDate;
    private LocalDateTime createTime;
    private String remark;

    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public String getBatchType() { return batchType; }
    public void setBatchType(String batchType) { this.batchType = batchType; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public Long getSupplierId() { return supplierId; }
    public void setSupplierId(Long supplierId) { this.supplierId = supplierId; }
    public String getSupplierName() { return supplierName; }
    public void setSupplierName(String supplierName) { this.supplierName = supplierName; }
    public String getReceiptBillNo() { return receiptBillNo; }
    public void setReceiptBillNo(String receiptBillNo) { this.receiptBillNo = receiptBillNo; }
    public String getSourcePrdOrderNo() { return sourcePrdOrderNo; }
    public void setSourcePrdOrderNo(String sourcePrdOrderNo) { this.sourcePrdOrderNo = sourcePrdOrderNo; }
    public String getProcessName() { return processName; }
    public void setProcessName(String processName) { this.processName = processName; }
    public String getRollNo() { return rollNo; }
    public void setRollNo(String rollNo) { this.rollNo = rollNo; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getHoldReason() { return holdReason; }
    public void setHoldReason(String holdReason) { this.holdReason = holdReason; }
    public BigDecimal getQty() { return qty; }
    public void setQty(BigDecimal qty) { this.qty = qty; }
    public Long getUnitId() { return unitId; }
    public void setUnitId(Long unitId) { this.unitId = unitId; }
    public String getUnitName() { return unitName; }
    public void setUnitName(String unitName) { this.unitName = unitName; }
    public LocalDate getProductionDate() { return productionDate; }
    public void setProductionDate(LocalDate productionDate) { this.productionDate = productionDate; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
