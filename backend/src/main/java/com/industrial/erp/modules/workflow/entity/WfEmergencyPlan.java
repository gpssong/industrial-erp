package com.industrial.erp.modules.workflow.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** v1.1.79 工作流: 应急预案 (head), 关联租客 */
@TableName("wf_emergency_plan")
public class WfEmergencyPlan {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String planNo;
    private Long tenantId;
    private String tenantName;
    private String planType;
    private String scenario;
    private String owner;
    private String contactPhone;
    private LocalDate drillDate;
    private LocalDate nextDrillDate;
    private String drillPhotos;     // 逗号分隔的 /upload/yyyyMM/dd/xxx.jpg (多图)
    private String drillAttachment; // 单文件 URL (演练脚本 PDF/DOC)
    private String billStatus;
    private String remark;
    @TableField(fill = FieldFill.INSERT)
    private Long createBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    @Version
    private Integer version;
    @TableLogic
    private Integer deleted = 0;

    @TableField(exist = false)
    private List<WfEmergencyDetail> details;

    @TableField(exist = false)
    private transient String createByName;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getPlanNo() { return planNo; }
    public void setPlanNo(String planNo) { this.planNo = planNo; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getTenantName() { return tenantName; }
    public void setTenantName(String tenantName) { this.tenantName = tenantName; }
    public String getPlanType() { return planType; }
    public void setPlanType(String planType) { this.planType = planType; }
    public String getScenario() { return scenario; }
    public void setScenario(String scenario) { this.scenario = scenario; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }
    public LocalDate getDrillDate() { return drillDate; }
    public void setDrillDate(LocalDate drillDate) { this.drillDate = drillDate; }
    public LocalDate getNextDrillDate() { return nextDrillDate; }
    public void setNextDrillDate(LocalDate nextDrillDate) { this.nextDrillDate = nextDrillDate; }
    public String getDrillPhotos() { return drillPhotos; }
    public void setDrillPhotos(String drillPhotos) { this.drillPhotos = drillPhotos; }
    public String getDrillAttachment() { return drillAttachment; }
    public void setDrillAttachment(String drillAttachment) { this.drillAttachment = drillAttachment; }
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
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
    public List<WfEmergencyDetail> getDetails() { return details; }
    public void setDetails(List<WfEmergencyDetail> details) { this.details = details; }
    public String getCreateByName() { return createByName; }
    public void setCreateByName(String createByName) { this.createByName = createByName; }
}
