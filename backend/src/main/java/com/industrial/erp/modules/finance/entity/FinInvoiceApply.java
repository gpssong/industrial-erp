package com.industrial.erp.modules.finance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 发票-AR/AP 关联明细表
 *
 * <p>一张发票可对应多个 AR/AP 单 (跨单合并开票)
 * <br>例如: INV001 = SD001 + SD002 合并开票 → fin_invoice_apply 2 行
 */
@TableName("fin_invoice_apply")
public class FinInvoiceApply {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long invoiceId;
    /** 关联的 fin_arap.id */
    private Long arapId;
    private String sourceBillType;
    private Long sourceBillId;
    private String sourceBillNo;
    /** 本次开票金额 (≤ 对应 AR/AP 单的 uninvoiced_amount) */
    private BigDecimal applyAmount;
    private String remark;
    private Long createBy;
    private LocalDateTime createTime;
    @TableLogic
    private Integer deleted = 0;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getInvoiceId() { return invoiceId; }
    public void setInvoiceId(Long invoiceId) { this.invoiceId = invoiceId; }
    public Long getArapId() { return arapId; }
    public void setArapId(Long arapId) { this.arapId = arapId; }
    public String getSourceBillType() { return sourceBillType; }
    public void setSourceBillType(String sourceBillType) { this.sourceBillType = sourceBillType; }
    public Long getSourceBillId() { return sourceBillId; }
    public void setSourceBillId(Long sourceBillId) { this.sourceBillId = sourceBillId; }
    public String getSourceBillNo() { return sourceBillNo; }
    public void setSourceBillNo(String sourceBillNo) { this.sourceBillNo = sourceBillNo; }
    public BigDecimal getApplyAmount() { return applyAmount; }
    public void setApplyAmount(BigDecimal applyAmount) { this.applyAmount = applyAmount; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public Long getCreateBy() { return createBy; }
    public void setCreateBy(Long createBy) { this.createBy = createBy; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
}