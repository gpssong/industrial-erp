package com.industrial.erp.modules.inventory.vo;

import java.math.BigDecimal;

/**
 * App 端盘点提交返回 VO (v1.0.8+)
 * <p>告知 App 用户生成的盘点单号 + 差异汇总, 便于现场反馈"已提交, 待 PC 审核".
 */
public class AppCheckSubmitVO {
    private Long id;
    private String billNo;
    private String billStatus;
    private BigDecimal totalDiffQty;
    private BigDecimal totalDiffAmount;
    private Integer itemCount;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getBillNo() { return billNo; }
    public void setBillNo(String billNo) { this.billNo = billNo; }
    public String getBillStatus() { return billStatus; }
    public void setBillStatus(String billStatus) { this.billStatus = billStatus; }
    public BigDecimal getTotalDiffQty() { return totalDiffQty; }
    public void setTotalDiffQty(BigDecimal totalDiffQty) { this.totalDiffQty = totalDiffQty; }
    public BigDecimal getTotalDiffAmount() { return totalDiffAmount; }
    public void setTotalDiffAmount(BigDecimal totalDiffAmount) { this.totalDiffAmount = totalDiffAmount; }
    public Integer getItemCount() { return itemCount; }
    public void setItemCount(Integer itemCount) { this.itemCount = itemCount; }
}
