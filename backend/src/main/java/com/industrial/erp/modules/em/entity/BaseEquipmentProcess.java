package com.industrial.erp.modules.em.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import java.time.LocalDateTime;

/**
 * v1.1.90 EM — 设备-工序 M2M 关系表 ({@code base_equipment_process}).
 *
 * <p>一台设备 ({@link BaseEquipment}) 可承担多道工序 (例如分切机可分切也可做简易切袋),
 * 一个工序可由多台设备完成。{@code process_name} 直接存工序名字符串 (而非工序定义 id),
 * 与 {@code MesProcessDef.process_name} 对齐 — 这样设备主数据可以独立于工序定义存在,
 * 不需要先建工序再挂设备。
 *
 * <p>{@code sortNo} 控制同一设备下工序的展示顺序 (绑定的工序列表展示)。
 */
@TableName("base_equipment_process")
public class BaseEquipmentProcess {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 设备 id (NOT NULL) */
    private Long equipmentId;
    /** 工序名 (与 MesProcessDef.process_name 对齐) */
    private String processName;
    /** 同一设备下工序展示顺序 */
    private Integer sortNo;
    private String remark;
    @Version
    private Integer version;
    @TableField(fill = FieldFill.INSERT)
    private Long createBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted = 0;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getEquipmentId() { return equipmentId; }
    public void setEquipmentId(Long equipmentId) { this.equipmentId = equipmentId; }
    public String getProcessName() { return processName; }
    public void setProcessName(String processName) { this.processName = processName; }
    public Integer getSortNo() { return sortNo; }
    public void setSortNo(Integer sortNo) { this.sortNo = sortNo; }
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