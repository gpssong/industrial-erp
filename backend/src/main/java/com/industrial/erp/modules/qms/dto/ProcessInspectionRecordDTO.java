package com.industrial.erp.modules.qms.dto;

import com.industrial.erp.modules.qms.entity.QmsIqcItem;

import java.math.BigDecimal;
import java.util.List;

public class ProcessInspectionRecordDTO {
    private BigDecimal sampleQty;
    private BigDecimal qualifiedQty;
    private BigDecimal unqualifiedQty;
    private String batchNo;
    private String photoUrls;
    private List<QmsIqcItem> items;
    private String result;

    public BigDecimal getSampleQty() { return sampleQty; }
    public void setSampleQty(BigDecimal sampleQty) { this.sampleQty = sampleQty; }
    public BigDecimal getQualifiedQty() { return qualifiedQty; }
    public void setQualifiedQty(BigDecimal qualifiedQty) { this.qualifiedQty = qualifiedQty; }
    public BigDecimal getUnqualifiedQty() { return unqualifiedQty; }
    public void setUnqualifiedQty(BigDecimal unqualifiedQty) { this.unqualifiedQty = unqualifiedQty; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public String getPhotoUrls() { return photoUrls; }
    public void setPhotoUrls(String photoUrls) { this.photoUrls = photoUrls; }
    public List<QmsIqcItem> getItems() { return items; }
    public void setItems(List<QmsIqcItem> items) { this.items = items; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
}
