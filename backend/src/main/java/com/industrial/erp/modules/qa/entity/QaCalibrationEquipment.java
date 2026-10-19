package com.industrial.erp.modules.qa.entity;

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
 * v1.1.97 QA — 校准设备主数据.
 *
 * <p>对应 Safety.vue Tab 5「检验设备校准」左侧设备台账. 一台校准设备
 * (数显测厚仪/电子拉力试验机/熔体流动速率仪等) + 校准周期 + 状态.
 *
 * <p>status 派生: VALID(有效) / EXPIRING_SOON(30天内到期) / EXPIRED(已到期) / SCRAP.
 * 状态机: 新建 VALID -> 新增校准记录 (qa_calibration_record) 推 next_cal_date.
 */
@TableName("qa_calibration_equipment")
public class QaCalibrationEquipment {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 设备编号 CEyyyymmddNNNN */
    private String code;
    /** 设备名称 (如: 数显测厚仪) */
    private String name;
    /** 规格型号 */
    private String spec;
    /** 厂家 */
    private String manufacturer;
    /** 存放位置 */
    private String location;
    /** 所属车间 */
    private String workshop;
    /** 量程/精度 */
    private String measureRange;
    /** 校准周期 (月) */
    private Integer intvMonths;
    private LocalDate lastCalDate;
    private LocalDate nextCalDate;
    /** 最近校准机构 */
    private String lastOrg;
    /** 最近证书号 */
    private String lastCertNo;
    /** VALID/EXPIRING_SOON/EXPIRED/SCRAP */
    private String status = "VALID";
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
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSpec() { return spec; }
    public void setSpec(String spec) { this.spec = spec; }
    public String getManufacturer() { return manufacturer; }
    public void setManufacturer(String manufacturer) { this.manufacturer = manufacturer; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getWorkshop() { return workshop; }
    public void setWorkshop(String workshop) { this.workshop = workshop; }
    public String getMeasureRange() { return measureRange; }
    public void setMeasureRange(String measureRange) { this.measureRange = measureRange; }
    public Integer getIntvMonths() { return intvMonths; }
    public void setIntvMonths(Integer intvMonths) { this.intvMonths = intvMonths; }
    public LocalDate getLastCalDate() { return lastCalDate; }
    public void setLastCalDate(LocalDate lastCalDate) { this.lastCalDate = lastCalDate; }
    public LocalDate getNextCalDate() { return nextCalDate; }
    public void setNextCalDate(LocalDate nextCalDate) { this.nextCalDate = nextCalDate; }
    public String getLastOrg() { return lastOrg; }
    public void setLastOrg(String lastOrg) { this.lastOrg = lastOrg; }
    public String getLastCertNo() { return lastCertNo; }
    public void setLastCertNo(String lastCertNo) { this.lastCertNo = lastCertNo; }
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
