package com.industrial.erp.modules.em.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * v1.1.96 EM — 设备维保工单.
 *
 * <p>状态机: DRAFT → DISPATCHED → PROCESSING → COMPLETED (或 CANCELLED).
 * 由计划生成时 {@code source_type=PLAN, plan_id!=null}; 临时工单 source_type=MANUAL.
 */
@TableName("em_maintain_order")
public class EmMaintainOrder {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 工单号 MOyyyymmddNNNN */
    private String orderNo;
    /** em_maintain_plan.id (MANUAL 时为 null) */
    private Long planId;
    /** PLAN / MANUAL */
    private String sourceType;
    private Long equipmentId;
    private String equipmentCode;
    private String equipmentName;
    private String maintContent;
    private LocalDate planDate;
    /** 维保人员 (自由文本) */
    private String operator;
    private LocalDateTime startTime;
    private LocalDateTime completeTime;
    /** 处理说明/故障原因 */
    private String description;
    /** 处理结果 (完成后填写) */
    private String result;
    /** 现场照片 JSON 数组 */
    private String photoUrls;
    /** DRAFT/DISPATCHED/PROCESSING/COMPLETED/CANCELLED */
    private String status;
    private String remark;
    @Version
    private Integer version;
    @TableField(fill = FieldFill.INSERT) private Long createBy;
    @TableField(fill = FieldFill.INSERT) private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE) private Long updateBy;
    @TableField(fill = FieldFill.INSERT_UPDATE) private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted = 0;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public Long getPlanId() { return planId; }
    public void setPlanId(Long planId) { this.planId = planId; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public Long getEquipmentId() { return equipmentId; }
    public void setEquipmentId(Long equipmentId) { this.equipmentId = equipmentId; }
    public String getEquipmentCode() { return equipmentCode; }
    public void setEquipmentCode(String equipmentCode) { this.equipmentCode = equipmentCode; }
    public String getEquipmentName() { return equipmentName; }
    public void setEquipmentName(String equipmentName) { this.equipmentName = equipmentName; }
    public String getMaintContent() { return maintContent; }
    public void setMaintContent(String maintContent) { this.maintContent = maintContent; }
    public LocalDate getPlanDate() { return planDate; }
    public void setPlanDate(LocalDate planDate) { this.planDate = planDate; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
    public LocalDateTime getCompleteTime() { return completeTime; }
    public void setCompleteTime(LocalDateTime completeTime) { this.completeTime = completeTime; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getPhotoUrls() { return photoUrls; }
    public void setPhotoUrls(String photoUrls) { this.photoUrls = photoUrls; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
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
}
