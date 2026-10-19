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
 * v1.1.90 EM — 设备主数据 (Equipment Master).
 *
 * <p>一张设备 = 工厂里一台机器 (吹膜机/制袋机/印刷机/复合机/分切机...). 与
 * {@link MesProcessDef} 工序定义是多对多关系 ({@code base_equipment_process}) —
 * 一台设备可承担多道工序 (例如分切机可分切也可做简易切袋), 一个工序可由多台设备完成。
 *
 * <p>字段说明:
 * <ul>
 *   <li>{@code equipmentCode} 设备编码 (人工维护, 唯一)</li>
 *   <li>{@code equipmentType} 设备类型枚举字符串
 *       ({@code Constants.EQUIPMENT_TYPE_FILM_BLOWING / SIDE_SEAL / BAG_MAKING /
 *       PRINTING / LAMINATING / SLITTING})</li>
 *   <li>{@code status} ACTIVE / MAINT / IDLE / SCRAP (维护中/闲置/报废)</li>
 *   <li>{@code installDate / lastMaintDate / nextMaintDate} 安装/上次保养/下次保养日期,
 *       用于维保看板的到期预警</li>
 * </ul>
 *
 * <p>审计: 沿用 v1.1.52 全 entity {@code @TableField(fill = FieldFill.INSERT/INSERT_UPDATE)}
 * 范式, create_by/create_time 由 MyBatis-Plus 自动填充当前登录用户。
 */
@TableName("base_equipment")
public class BaseEquipment {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 设备编码 (人工维护, NOT NULL) */
    private String equipmentCode;
    /** 设备名称 (NOT NULL) */
    private String equipmentName;
    /** 设备类型枚举字符串 (Constants.EQUIPMENT_TYPE_*) */
    private String equipmentType;
    /** 规格型号 */
    private String model;
    /** 厂家 */
    private String manufacturer;
    /** 安装日期 */
    private LocalDate installDate;
    /** 安装位置 */
    private String location;
    /** 车间 */
    private String workshop;
    /** ACTIVE / MAINT / IDLE / SCRAP */
    private String status;
    /** 设备二维码图片 URL (打印标贴用) */
    private String qrcode;
    /** 设备照片 URL (新增设备时上传, 设备档案卡片显示) */
    private String photoUrl;
    /** 上次保养日期 */
    private LocalDate lastMaintDate;
    /** 下次保养日期 (维保看板到期预警) */
    private LocalDate nextMaintDate;
    /** 设备责任人 (自由文本, 视图「责任人」列需要). v1.1.96 hotfix 加字段 */
    private String owner;
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
    public String getEquipmentCode() { return equipmentCode; }
    public void setEquipmentCode(String equipmentCode) { this.equipmentCode = equipmentCode; }
    public String getEquipmentName() { return equipmentName; }
    public void setEquipmentName(String equipmentName) { this.equipmentName = equipmentName; }
    public String getEquipmentType() { return equipmentType; }
    public void setEquipmentType(String equipmentType) { this.equipmentType = equipmentType; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getManufacturer() { return manufacturer; }
    public void setManufacturer(String manufacturer) { this.manufacturer = manufacturer; }
    public LocalDate getInstallDate() { return installDate; }
    public void setInstallDate(LocalDate installDate) { this.installDate = installDate; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getWorkshop() { return workshop; }
    public void setWorkshop(String workshop) { this.workshop = workshop; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getQrcode() { return qrcode; }
    public void setQrcode(String qrcode) { this.qrcode = qrcode; }
    public String getPhotoUrl() { return photoUrl; }
    public void setPhotoUrl(String photoUrl) { this.photoUrl = photoUrl; }
    public LocalDate getLastMaintDate() { return lastMaintDate; }
    public void setLastMaintDate(LocalDate lastMaintDate) { this.lastMaintDate = lastMaintDate; }
    public LocalDate getNextMaintDate() { return nextMaintDate; }
    public void setNextMaintDate(LocalDate nextMaintDate) { this.nextMaintDate = nextMaintDate; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
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