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
 * v1.1.96 EM — 设备维保计划.
 *
 * <p>每条计划 = 某台设备 + 某种维保内容 + 周期 (天). 系统按 {@code next_run_date}
 * 推算下次执行日; 工单可由计划生成, 也可临时新增 (MANUAL).
 *
 * <p>状态机:
 * <ul>
 *   <li>DRAFT — 草稿, 未生效</li>
 *   <li>ACTIVE — 激活, 按周期自动推算 next_run_date</li>
 *   <li>DISABLED — 停用 (设备报废或临时禁用)</li>
 * </ul>
 */
@TableName("em_maintain_plan")
public class EmMaintainPlan {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 计划编号 MPyyyymmddNNNN */
    private String planNo;
    /** 计划名 */
    private String planName;
    /** base_equipment.id */
    private Long equipmentId;
    /** 冗余 */
    private String equipmentCode;
    private String equipmentName;
    /** 维保内容 */
    private String maintContent;
    /** DAILY/WEEKLY/MONTHLY/QUARTERLY/YEARLY/CUSTOM */
    private String cycleType;
    /** 实际周期天数 (cycle_type=CUSTOM 时用户填) */
    private Integer cycleDays;
    /** 负责人 (自由文本) */
    private String owner;
    private LocalDate lastRunDate;
    private LocalDate nextRunDate;
    /** DRAFT/ACTIVE/DISABLED */
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
    public String getPlanNo() { return planNo; }
    public void setPlanNo(String planNo) { this.planNo = planNo; }
    public String getPlanName() { return planName; }
    public void setPlanName(String planName) { this.planName = planName; }
    public Long getEquipmentId() { return equipmentId; }
    public void setEquipmentId(Long equipmentId) { this.equipmentId = equipmentId; }
    public String getEquipmentCode() { return equipmentCode; }
    public void setEquipmentCode(String equipmentCode) { this.equipmentCode = equipmentCode; }
    public String getEquipmentName() { return equipmentName; }
    public void setEquipmentName(String equipmentName) { this.equipmentName = equipmentName; }
    public String getMaintContent() { return maintContent; }
    public void setMaintContent(String maintContent) { this.maintContent = maintContent; }
    public String getCycleType() { return cycleType; }
    public void setCycleType(String cycleType) { this.cycleType = cycleType; }
    public Integer getCycleDays() { return cycleDays; }
    public void setCycleDays(Integer cycleDays) { this.cycleDays = cycleDays; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public LocalDate getLastRunDate() { return lastRunDate; }
    public void setLastRunDate(LocalDate lastRunDate) { this.lastRunDate = lastRunDate; }
    public LocalDate getNextRunDate() { return nextRunDate; }
    public void setNextRunDate(LocalDate nextRunDate) { this.nextRunDate = nextRunDate; }
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
