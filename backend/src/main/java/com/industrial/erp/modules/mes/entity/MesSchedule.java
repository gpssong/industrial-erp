package com.industrial.erp.modules.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * v1.1.99 C4 — 生产排程实例 (工单 × 设备 × 工序 × 计划时段).
 *
 * <p>把「某工单某道工序派到某设备某时段」显式建模 (此前只有 base_equipment_process 设备↔工序
 * M2M 主数据, 无排程实例). 支撑车间派工甘特 + 冲突检测 + OEE 归集.
 */
@TableName("mes_schedule")
public class MesSchedule {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 关联生产工单 prd_order.id */
    private Long prdOrderId;
    /** 工单号 (冗余快照, 免 JOIN) */
    private String prdOrderNo;
    /** 设备 base_equipment.id */
    private Long equipmentId;
    /** 设备编码 (冗余) */
    private String equipmentCode;
    /** 设备名称 (冗余) */
    private String equipmentName;
    /** 工序号 */
    private Integer processNo;
    /** 工序名 */
    private String processName;
    /** 计划开工时间 */
    private LocalDateTime planStart;
    /** 计划完工时间 */
    private LocalDateTime planEnd;
    /** 实际开工 (报工首次记) */
    private LocalDateTime actualStart;
    /** 实际完工 */
    private LocalDateTime actualEnd;
    /** PLANNED / IN_PROGRESS / DONE / CANCELLED */
    private String status;
    /** OEE 百分比 (可用时间×稼动率×良品率) */
    private BigDecimal oee;
    private String remark;
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

    /** 关联工单的商品名称 (list page 注入, 不入库) — 前端显示「工单号 · 品名」用 */
    @TableField(exist = false)
    private String productName;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPrdOrderId() { return prdOrderId; }
    public void setPrdOrderId(Long prdOrderId) { this.prdOrderId = prdOrderId; }
    public String getPrdOrderNo() { return prdOrderNo; }
    public void setPrdOrderNo(String prdOrderNo) { this.prdOrderNo = prdOrderNo; }
    public Long getEquipmentId() { return equipmentId; }
    public void setEquipmentId(Long equipmentId) { this.equipmentId = equipmentId; }
    public String getEquipmentCode() { return equipmentCode; }
    public void setEquipmentCode(String equipmentCode) { this.equipmentCode = equipmentCode; }
    public String getEquipmentName() { return equipmentName; }
    public void setEquipmentName(String equipmentName) { this.equipmentName = equipmentName; }
    public Integer getProcessNo() { return processNo; }
    public void setProcessNo(Integer processNo) { this.processNo = processNo; }
    public String getProcessName() { return processName; }
    public void setProcessName(String processName) { this.processName = processName; }
    public LocalDateTime getPlanStart() { return planStart; }
    public void setPlanStart(LocalDateTime planStart) { this.planStart = planStart; }
    public LocalDateTime getPlanEnd() { return planEnd; }
    public void setPlanEnd(LocalDateTime planEnd) { this.planEnd = planEnd; }
    public LocalDateTime getActualStart() { return actualStart; }
    public void setActualStart(LocalDateTime actualStart) { this.actualStart = actualStart; }
    public LocalDateTime getActualEnd() { return actualEnd; }
    public void setActualEnd(LocalDateTime actualEnd) { this.actualEnd = actualEnd; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public BigDecimal getOee() { return oee; }
    public void setOee(BigDecimal oee) { this.oee = oee; }
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
    /** 关联工单的商品名称 (list page 注入) */
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
}
