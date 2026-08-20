package com.industrial.erp.modules.finance.vo;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 已开发票列表 VO (v1.1.19+)
 *
 * <p>展示 fin_invoice + 关联的 fin_invoice_apply + fin_arap 信息
 */
public class FinInvoiceIssuedVO {
    private Long id;
    private String billNo;
    private String externalNo;
    /** AR_SALE / AP_PURCHASE */
    private String invoiceType;
    /** CUSTOMER / SUPPLIER */
    private String partnerType;
    private Long partnerId;
    private String partnerName;
    private LocalDate billDate;
    private BigDecimal totalAmount;
    private BigDecimal collectedAmount;
    private BigDecimal balance;
    /** DRAFT/ISSUED/PARTIAL/PAID/VOID */
    private String invoiceStatus;
    /** 关联的源单号 (如 CKP202608190002) */
    private String sourceBillNo;
    /** 本次开票金额 */
    private BigDecimal applyAmount;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getBillNo() { return billNo; }
    public void setBillNo(String billNo) { this.billNo = billNo; }
    public String getExternalNo() { return externalNo; }
    public void setExternalNo(String externalNo) { this.externalNo = externalNo; }
    public String getInvoiceType() { return invoiceType; }
    public void setInvoiceType(String invoiceType) { this.invoiceType = invoiceType; }
    public String getPartnerType() { return partnerType; }
    public void setPartnerType(String partnerType) { this.partnerType = partnerType; }
    public Long getPartnerId() { return partnerId; }
    public void setPartnerId(Long partnerId) { this.partnerId = partnerId; }
    public String getPartnerName() { return partnerName; }
    public void setPartnerName(String partnerName) { this.partnerName = partnerName; }
    public LocalDate getBillDate() { return billDate; }
    public void setBillDate(LocalDate billDate) { this.billDate = billDate; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public BigDecimal getCollectedAmount() { return collectedAmount; }
    public void setCollectedAmount(BigDecimal collectedAmount) { this.collectedAmount = collectedAmount; }
    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }
    public String getInvoiceStatus() { return invoiceStatus; }
    public void setInvoiceStatus(String invoiceStatus) { this.invoiceStatus = invoiceStatus; }
    public String getSourceBillNo() { return sourceBillNo; }
    public void setSourceBillNo(String sourceBillNo) { this.sourceBillNo = sourceBillNo; }
    public BigDecimal getApplyAmount() { return applyAmount; }
    public void setApplyAmount(BigDecimal applyAmount) { this.applyAmount = applyAmount; }
}
