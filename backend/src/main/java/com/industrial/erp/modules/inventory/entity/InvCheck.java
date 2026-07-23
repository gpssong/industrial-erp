package com.industrial.erp.modules.inventory.entity;
import com.industrial.erp.modules.inventory.entity.InvCheckDetail;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@TableName("inv_check")
public class InvCheck {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String billNo;
    private LocalDate billDate;
    private Long warehouseId;
    private String warehouseName;
    private String checkType;
    private BigDecimal totalDiffQty;
    private BigDecimal totalDiffAmount;
    private String billStatus;
    private String remark;
    private Long createBy;
    private LocalDateTime createTime;
    private Long updateBy;
    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted = 0;

    // P0 fix: 必须用 @TableField(exist=false) 标记为非表字段, 否则 BaseMapper.insert 会把整个 List
    // 当作单字段尝试 set 到 SQL 参数里, 报 "Type handler was null on parameter mapping for
    // property 'details'" 错.
    @TableField(exist = false)
    private List<InvCheckDetail> details;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getBillNo() { return billNo; }
    public void setBillNo(String billNo) { this.billNo = billNo; }
    public LocalDate getBillDate() { return billDate; }
    public void setBillDate(LocalDate billDate) { this.billDate = billDate; }
    public Long getWarehouseId() { return warehouseId; }
    public void setWarehouseId(Long warehouseId) { this.warehouseId = warehouseId; }
    public String getWarehouseName() { return warehouseName; }
    public void setWarehouseName(String warehouseName) { this.warehouseName = warehouseName; }
    public String getCheckType() { return checkType; }
    public void setCheckType(String checkType) { this.checkType = checkType; }
    public BigDecimal getTotalDiffQty() { return totalDiffQty; }
    public void setTotalDiffQty(BigDecimal totalDiffQty) { this.totalDiffQty = totalDiffQty; }
    public BigDecimal getTotalDiffAmount() { return totalDiffAmount; }
    public void setTotalDiffAmount(BigDecimal totalDiffAmount) { this.totalDiffAmount = totalDiffAmount; }
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
    public List<InvCheckDetail> getDetails() { return details; }
    public void setDetails(List<InvCheckDetail> details) { this.details = details; }
}
