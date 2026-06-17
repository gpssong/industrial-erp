package com.industrial.erp.modules.inventory.entity;
import com.industrial.erp.modules.inventory.entity.InvTransferDetail;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@TableName("inv_transfer")
public class InvTransfer {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String billNo;
    private LocalDate billDate;
    private Long outWarehouseId;
    private Long inWarehouseId;
    private BigDecimal totalQty;
    private String billStatus;
    private String remark;
    private Long createBy;
    private LocalDateTime createTime;
    private Long updateBy;
    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted;

    private List<InvTransferDetail> details;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getBillNo() { return billNo; }
    public void setBillNo(String billNo) { this.billNo = billNo; }
    public LocalDate getBillDate() { return billDate; }
    public void setBillDate(LocalDate billDate) { this.billDate = billDate; }
    public Long getOutWarehouseId() { return outWarehouseId; }
    public void setOutWarehouseId(Long outWarehouseId) { this.outWarehouseId = outWarehouseId; }
    public Long getInWarehouseId() { return inWarehouseId; }
    public void setInWarehouseId(Long inWarehouseId) { this.inWarehouseId = inWarehouseId; }
    public BigDecimal getTotalQty() { return totalQty; }
    public void setTotalQty(BigDecimal totalQty) { this.totalQty = totalQty; }
    public String getBillStatus() { return billStatus; }
    public void setBillStatus(String billStatus) { this.billStatus = billStatus; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public Long getCreateBy() { return createBy; }
    public void setCreateBy(Long createBy) { this.createBy = createBy; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public Long getUpdateBy() { return updateBy; }
    public void setUpdateBy(Long updateBy) { this.updateBy = updateBy; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
    public List<InvTransferDetail> getDetails() { return details; }
    public void setDetails(List<InvTransferDetail> details) { this.details = details; }
}
