package com.industrial.erp.modules.pdm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * v1.1.90 PDM — 工艺参数 (工序的「温度/压力/速度/时间」等关键控制点).
 *
 * <p>挂在 {@code mes_process_def} 下: 一个工序可配 N 个参数 (温度上限/下限, 速度设定值等),
 * 报工时录入实测值比对上下限做 NG 判断 (留作 v1.1.91 强制执行, 当前仅录入存储).
 * 关键参数 {@link #isCritical}=1 在报工页 UI 高亮红框提醒必填.
 */
@TableName("mes_process_param")
public class MesProcessParam {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属工序定义 id (mes_process_def.id) */
    private Long processDefId;

    /** 参数名 (温度/压力/速度/时间) */
    private String paramKey;

    /** 参数值 (字符串, 兼容数值/文本) */
    private String paramValue;

    /** 单位 (℃/MPa/m/min/s/...) */
    private String unit;

    /** 上限 (工艺要求 ≤ 此值, 留空=不限) */
    private BigDecimal upperLimit;

    /** 下限 (工艺要求 ≥ 此值, 留空=不限) */
    private BigDecimal lowerLimit;

    /** 排序号 (UI 显示顺序) */
    private Integer sortNo;

    /** 是否关键参数 (1=关键, 报工页 UI 高亮, v1.1.91 强制) */
    private Integer isCritical;

    /** 备注 */
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

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProcessDefId() { return processDefId; }
    public void setProcessDefId(Long processDefId) { this.processDefId = processDefId; }
    public String getParamKey() { return paramKey; }
    public void setParamKey(String paramKey) { this.paramKey = paramKey; }
    public String getParamValue() { return paramValue; }
    public void setParamValue(String paramValue) { this.paramValue = paramValue; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public BigDecimal getUpperLimit() { return upperLimit; }
    public void setUpperLimit(BigDecimal upperLimit) { this.upperLimit = upperLimit; }
    public BigDecimal getLowerLimit() { return lowerLimit; }
    public void setLowerLimit(BigDecimal lowerLimit) { this.lowerLimit = lowerLimit; }
    public Integer getSortNo() { return sortNo; }
    public void setSortNo(Integer sortNo) { this.sortNo = sortNo; }
    public Integer getIsCritical() { return isCritical; }
    public void setIsCritical(Integer isCritical) { this.isCritical = isCritical; }
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
}
