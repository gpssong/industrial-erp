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
 * v1.1.99 C4 — 工序实际耗时流水 (OEE 数据源).
 *
 * <p>每次报工/设备记录一段实际占用时长 + 停机时长/故障原因, 供 OEE
 * (可用时间 × 稼动率 × 良品率) 与设备稼动报表聚合.
 */
@TableName("mes_process_time")
public class MesProcessTime {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 工序实例 prd_process.id */
    private Long prdProcessId;
    /** 关联工单 */
    private Long prdOrderId;
    /** 工单号 (冗余) */
    private String prdOrderNo;
    /** 设备 base_equipment.id */
    private Long equipmentId;
    /** 设备编码 (冗余) */
    private String equipmentCode;
    /** 工序号 */
    private Integer processNo;
    /** 工序名 */
    private String processName;
    /** 本次开始 */
    private LocalDateTime startTime;
    /** 本次结束 */
    private LocalDateTime endTime;
    /** 停机时长 (分钟) */
    private BigDecimal downtime;
    /** 停机/故障原因 */
    private String faultReason;
    @TableField(fill = FieldFill.INSERT)
    private Long createBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableLogic
    private Integer deleted = 0;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPrdProcessId() { return prdProcessId; }
    public void setPrdProcessId(Long prdProcessId) { this.prdProcessId = prdProcessId; }
    public Long getPrdOrderId() { return prdOrderId; }
    public void setPrdOrderId(Long prdOrderId) { this.prdOrderId = prdOrderId; }
    public String getPrdOrderNo() { return prdOrderNo; }
    public void setPrdOrderNo(String prdOrderNo) { this.prdOrderNo = prdOrderNo; }
    public Long getEquipmentId() { return equipmentId; }
    public void setEquipmentId(Long equipmentId) { this.equipmentId = equipmentId; }
    public String getEquipmentCode() { return equipmentCode; }
    public void setEquipmentCode(String equipmentCode) { this.equipmentCode = equipmentCode; }
    public Integer getProcessNo() { return processNo; }
    public void setProcessNo(Integer processNo) { this.processNo = processNo; }
    public String getProcessName() { return processName; }
    public void setProcessName(String processName) { this.processName = processName; }
    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
    public BigDecimal getDowntime() { return downtime; }
    public void setDowntime(BigDecimal downtime) { this.downtime = downtime; }
    public String getFaultReason() { return faultReason; }
    public void setFaultReason(String faultReason) { this.faultReason = faultReason; }
    public Long getCreateBy() { return createBy; }
    public void setCreateBy(Long createBy) { this.createBy = createBy; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
}
