package com.industrial.erp.modules.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * v1.1.119 MES — 米克重采样流水 (实时曲线数据源).
 *
 * <p>时序表, ESP32 每 {@code reportIntervalMs} 上报一条。曲线查询按
 * {@code (device_id, collect_time)} 索引走, 所以 {@link #collectTime} 用设备侧
 * 时钟入库并加索引, 不依赖 create_time。
 *
 * <p>冗余字段 ({@link #deviceCode} / {@link #equipmentCode} / {@link #setWeight})
 * 是有意为之: 采集器和设备的名称可能事后被改, 但历史采样必须保留当时的口径,
 * 否则曲线回看时"设定值对不上"。
 */
@TableName("mes_weight_sample")
public class MesWeightSample {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 采集器 mes_weight_device.id */
    private Long deviceId;

    /** 设备编码 (冗余快照) */
    private String deviceCode;

    /** 设备 base_equipment.id */
    private Long equipmentId;

    /** 设备编码 (冗余快照) */
    private String equipmentCode;

    /** 关联生产工单 (可空) */
    private Long prdOrderId;

    /** 工单号 (冗余快照) */
    private String prdOrderNo;

    /** 通道号 */
    private Integer channelNo;

    /** 设定米重 (冗余快照, 便于历史回溯) */
    private BigDecimal setWeight;

    /** 实测米重 */
    private BigDecimal actualWeight;

    /** 偏差 = 实测 - 设定 */
    private BigDecimal deviation;

    /** 偏差百分比 */
    private BigDecimal deviationPct;

    /** 是否超公差 1=是 */
    private Integer isOver;

    /** 线速度 m/min */
    private BigDecimal lineSpeed;

    /** 累计产量 kg */
    private BigDecimal outputKg;

    /** 累计长度 m */
    private BigDecimal rollLength;

    // ---- v1.1.119b: MCGS 屏全字段上云 (冠尚达高精度米重控制系统) ----
    // 对应屏上 12 个量, 补齐原有 6 列. 全部可空 (旧数据/未反推字段为 NULL, 前端逐格兜底).

    /** 设定长度 mm (屏「设定长度」, 收卷目标) */
    private BigDecimal setLength;

    /** 当前重量 kg (屏「当前重量」, 收卷辊当前累计) */
    private BigDecimal currentWeight;

    /** 上牵引 Hz (屏「上牵引」, 牵引频率, 故障排查) */
    private BigDecimal upperHz;

    /** 下牵引 Hz (屏「下牵引」) */
    private BigDecimal lowerHz;

    /** 机器产量 kg/l (屏「机器产量」, 单位产量) */
    private BigDecimal machineYield;

    /** 克重修正 倍率 (屏「克重修正」, 工艺调参, 1.0=不修正) */
    private BigDecimal gaugeFix;

    /** 采样时间 (设备侧时钟) */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime collectTime;

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
    public Long getDeviceId() { return deviceId; }
    public void setDeviceId(Long deviceId) { this.deviceId = deviceId; }
    public String getDeviceCode() { return deviceCode; }
    public void setDeviceCode(String deviceCode) { this.deviceCode = deviceCode; }
    public Long getEquipmentId() { return equipmentId; }
    public void setEquipmentId(Long equipmentId) { this.equipmentId = equipmentId; }
    public String getEquipmentCode() { return equipmentCode; }
    public void setEquipmentCode(String equipmentCode) { this.equipmentCode = equipmentCode; }
    public Long getPrdOrderId() { return prdOrderId; }
    public void setPrdOrderId(Long prdOrderId) { this.prdOrderId = prdOrderId; }
    public String getPrdOrderNo() { return prdOrderNo; }
    public void setPrdOrderNo(String prdOrderNo) { this.prdOrderNo = prdOrderNo; }
    public Integer getChannelNo() { return channelNo; }
    public void setChannelNo(Integer channelNo) { this.channelNo = channelNo; }
    public BigDecimal getSetWeight() { return setWeight; }
    public void setSetWeight(BigDecimal setWeight) { this.setWeight = setWeight; }
    public BigDecimal getActualWeight() { return actualWeight; }
    public void setActualWeight(BigDecimal actualWeight) { this.actualWeight = actualWeight; }
    public BigDecimal getDeviation() { return deviation; }
    public void setDeviation(BigDecimal deviation) { this.deviation = deviation; }
    public BigDecimal getDeviationPct() { return deviationPct; }
    public void setDeviationPct(BigDecimal deviationPct) { this.deviationPct = deviationPct; }
    public Integer getIsOver() { return isOver; }
    public void setIsOver(Integer isOver) { this.isOver = isOver; }
    public BigDecimal getLineSpeed() { return lineSpeed; }
    public void setLineSpeed(BigDecimal lineSpeed) { this.lineSpeed = lineSpeed; }
    public BigDecimal getOutputKg() { return outputKg; }
    public void setOutputKg(BigDecimal outputKg) { this.outputKg = outputKg; }
    public BigDecimal getRollLength() { return rollLength; }
    public void setRollLength(BigDecimal rollLength) { this.rollLength = rollLength; }
    public BigDecimal getSetLength() { return setLength; }
    public void setSetLength(BigDecimal setLength) { this.setLength = setLength; }
    public BigDecimal getCurrentWeight() { return currentWeight; }
    public void setCurrentWeight(BigDecimal currentWeight) { this.currentWeight = currentWeight; }
    public BigDecimal getUpperHz() { return upperHz; }
    public void setUpperHz(BigDecimal upperHz) { this.upperHz = upperHz; }
    public BigDecimal getLowerHz() { return lowerHz; }
    public void setLowerHz(BigDecimal lowerHz) { this.lowerHz = lowerHz; }
    public BigDecimal getMachineYield() { return machineYield; }
    public void setMachineYield(BigDecimal machineYield) { this.machineYield = machineYield; }
    public BigDecimal getGaugeFix() { return gaugeFix; }
    public void setGaugeFix(BigDecimal gaugeFix) { this.gaugeFix = gaugeFix; }
    public LocalDateTime getCollectTime() { return collectTime; }
    public void setCollectTime(LocalDateTime collectTime) { this.collectTime = collectTime; }
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