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
 * v1.1.119 MES — 米克重通道配置 (寄存器表 + 设定值 + 公差).
 *
 * <p>核心是 {@link #regStart} / {@link #scale} / {@link #regType} 三个字段 ——
 * 协议反推阶段要确定的"哪个寄存器是米重、怎么缩放、用哪个功能码读"。
 * {@link #scale} 的确定方法: 把一卷已知标重的料放上秤, 读寄存器原始值,
 * 两者相除就是缩放系数。没有实测比对不要猜。
 *
 * <p>{@link #setWeight} + {@link #upperTol} / {@link #lowerTol} 组成公差带,
 * 采样入库时算 deviation / is_over, 前端曲线上画基准线和公差带。
 */
@TableName("mes_weight_channel")
public class MesWeightChannel {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 采集器 mes_weight_device.id */
    private Long deviceId;

    /** 通道号 1/2/3 */
    private Integer channelNo;

    /** 通道名 (称重1/2/3) */
    private String channelName;

    /** 米重寄存器起始地址 (协议反推后回填) */
    private Integer regStart;

    /** Modbus 功能码 3=保持寄存器 4=输入寄存器 */
    private Integer regType;

    /** 缩放系数: 实际值 = 寄存器值 × scale */
    private BigDecimal scale;

    /** 单位 g / kg / g/m2 */
    private String unit;

    /** 设定米重 (工艺目标值) */
    private BigDecimal setWeight;

    /** 上公差 (设定值+此值=上限) */
    private BigDecimal upperTol;

    /** 下公差 (设定值-此值=下限) */
    private BigDecimal lowerTol;

    /** 排序号 */
    private Integer sortNo;

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

    @TableLogic
    private Integer deleted = 0;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDeviceId() { return deviceId; }
    public void setDeviceId(Long deviceId) { this.deviceId = deviceId; }
    public Integer getChannelNo() { return channelNo; }
    public void setChannelNo(Integer channelNo) { this.channelNo = channelNo; }
    public String getChannelName() { return channelName; }
    public void setChannelName(String channelName) { this.channelName = channelName; }
    public Integer getRegStart() { return regStart; }
    public void setRegStart(Integer regStart) { this.regStart = regStart; }
    public Integer getRegType() { return regType; }
    public void setRegType(Integer regType) { this.regType = regType; }
    public BigDecimal getScale() { return scale; }
    public void setScale(BigDecimal scale) { this.scale = scale; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public BigDecimal getSetWeight() { return setWeight; }
    public void setSetWeight(BigDecimal setWeight) { this.setWeight = setWeight; }
    public BigDecimal getUpperTol() { return upperTol; }
    public void setUpperTol(BigDecimal upperTol) { this.upperTol = upperTol; }
    public BigDecimal getLowerTol() { return lowerTol; }
    public void setLowerTol(BigDecimal lowerTol) { this.lowerTol = lowerTol; }
    public Integer getSortNo() { return sortNo; }
    public void setSortNo(Integer sortNo) { this.sortNo = sortNo; }
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
}