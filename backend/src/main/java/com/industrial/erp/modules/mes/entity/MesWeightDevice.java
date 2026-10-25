package com.industrial.erp.modules.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * v1.1.119 MES — 米克重采集器注册 (ESP32 ↔ ERP 对应关系).
 *
 * <p>一台吹膜机对应一个采集器。协议反推完成后回填 {@link #baudRate} /
 * {@link #slaveAddr} / {@link #protocol} 三个字段 —— 这三个值决定了 ESP32
 * 固件怎么问、米克重控制器怎么答, 抓包阶段确定后一次性配好即可。
 *
 * <p>设备密钥只存掩码 ({@link #deviceKeyMasked} 后 6 位), 明文走环境变量
 * {@code ERP_DEVICE_KEY} 注入, 绝不落库。
 */
@TableName("mes_weight_device")
public class MesWeightDevice {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 设备编码 (ESP32 上报标识, 唯一) */
    private String deviceCode;

    /** 设备名称 */
    private String deviceName;

    /** 关联设备 base_equipment.id (吹膜机) */
    private Long equipmentId;

    /** 设备编码 (冗余快照) */
    private String equipmentCode;

    /** 设备名称 (冗余快照) */
    private String equipmentName;

    /** 车间 */
    private String workshop;

    /** 称重通道数 (1/2/3) */
    private Integer channelCount;

    /** 485 波特率 (协议反推后回填) */
    private Integer baudRate;

    /** Modbus 从站地址 (协议反推后回填) */
    private Integer slaveAddr;

    /** MODBUS_RTU / MODBUS_ASCII / VENDOR */
    private String protocol;

    /** 上报间隔毫秒 (勿低于 5000) */
    private Integer reportIntervalMs;

    /** 固件版本 */
    private String fwVersion;

    /** ONLINE / OFFLINE */
    private String onlineStatus;

    /** 最后上报时间 (超 2 个周期判 OFFLINE) */
    private LocalDateTime lastReportTime;

    /** 最后上报来源 IP */
    private String lastReportIp;

    /** 设备密钥掩码 (后6位, 绝不存明文) */
    private String deviceKeyMasked;

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
    public String getDeviceCode() { return deviceCode; }
    public void setDeviceCode(String deviceCode) { this.deviceCode = deviceCode; }
    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }
    public Long getEquipmentId() { return equipmentId; }
    public void setEquipmentId(Long equipmentId) { this.equipmentId = equipmentId; }
    public String getEquipmentCode() { return equipmentCode; }
    public void setEquipmentCode(String equipmentCode) { this.equipmentCode = equipmentCode; }
    public String getEquipmentName() { return equipmentName; }
    public void setEquipmentName(String equipmentName) { this.equipmentName = equipmentName; }
    public String getWorkshop() { return workshop; }
    public void setWorkshop(String workshop) { this.workshop = workshop; }
    public Integer getChannelCount() { return channelCount; }
    public void setChannelCount(Integer channelCount) { this.channelCount = channelCount; }
    public Integer getBaudRate() { return baudRate; }
    public void setBaudRate(Integer baudRate) { this.baudRate = baudRate; }
    public Integer getSlaveAddr() { return slaveAddr; }
    public void setSlaveAddr(Integer slaveAddr) { this.slaveAddr = slaveAddr; }
    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }
    public Integer getReportIntervalMs() { return reportIntervalMs; }
    public void setReportIntervalMs(Integer reportIntervalMs) { this.reportIntervalMs = reportIntervalMs; }
    public String getFwVersion() { return fwVersion; }
    public void setFwVersion(String fwVersion) { this.fwVersion = fwVersion; }
    public String getOnlineStatus() { return onlineStatus; }
    public void setOnlineStatus(String onlineStatus) { this.onlineStatus = onlineStatus; }
    public LocalDateTime getLastReportTime() { return lastReportTime; }
    public void setLastReportTime(LocalDateTime lastReportTime) { this.lastReportTime = lastReportTime; }
    public String getLastReportIp() { return lastReportIp; }
    public void setLastReportIp(String lastReportIp) { this.lastReportIp = lastReportIp; }
    public String getDeviceKeyMasked() { return deviceKeyMasked; }
    public void setDeviceKeyMasked(String deviceKeyMasked) { this.deviceKeyMasked = deviceKeyMasked; }
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