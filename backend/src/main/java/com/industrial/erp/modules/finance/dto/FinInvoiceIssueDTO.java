package com.industrial.erp.modules.finance.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 开票 DTO
 *
 * <p>前端表单提交结构:
 * <pre>
 * {
 *   "externalNo": "20260725001",
 *   "invoiceType": "AR_SALE",
 *   "partnerType": "CUSTOMER",
 *   "partnerId": 1,
 *   "billDate": "2026-07-25",
 *   "taxAmount": 1300,
 *   "title": "XX 有限公司",
 *   "remark": "...",
 *   "items": [
 *     {"arapId": 101, "applyAmount": 10000},
 *     {"arapId": 102, "applyAmount": 15000}
 *   ]
 * }
 * </pre>
 */
public class FinInvoiceIssueDTO {
    private String externalNo;
    private String invoiceType;
    private String partnerType;
    private Long partnerId;
    private LocalDate billDate;
    private BigDecimal taxAmount;
    private LocalDate dueDate;
    private String title;
    private String remark;
    private List<InvoiceApplyItem> items;

    public static class InvoiceApplyItem {
        private Long arapId;
        private BigDecimal applyAmount;
        public Long getArapId() { return arapId; }
        public void setArapId(Long arapId) { this.arapId = arapId; }
        public BigDecimal getApplyAmount() { return applyAmount; }
        public void setApplyAmount(BigDecimal applyAmount) { this.applyAmount = applyAmount; }
    }

    public String getExternalNo() { return externalNo; }
    public void setExternalNo(String externalNo) { this.externalNo = externalNo; }
    public String getInvoiceType() { return invoiceType; }
    public void setInvoiceType(String invoiceType) { this.invoiceType = invoiceType; }
    public String getPartnerType() { return partnerType; }
    public void setPartnerType(String partnerType) { this.partnerType = partnerType; }
    public Long getPartnerId() { return partnerId; }
    public void setPartnerId(Long partnerId) { this.partnerId = partnerId; }
    public LocalDate getBillDate() { return billDate; }
    public void setBillDate(LocalDate billDate) { this.billDate = billDate; }
    public BigDecimal getTaxAmount() { return taxAmount; }
    public void setTaxAmount(BigDecimal taxAmount) { this.taxAmount = taxAmount; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public List<InvoiceApplyItem> getItems() { return items; }
    public void setItems(List<InvoiceApplyItem> items) { this.items = items; }
}