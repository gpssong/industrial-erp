package com.industrial.erp.modules.qa.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * v1.1.97 QA — 校准记录.
 *
 * <p>对应 Safety.vue Tab 5「校准记录」. 一台校准设备可以有多条校准记录
 * (与 qa_calibration_equipment 1:N). 每次校准后由 QA 主管审核, 完成后
 * 推 equipment.last_cal_date + next_cal_date (按 intvMonths).
 *
 * <p>状态: PENDING(待校准) / PASSED(合格) / FAILED(不合格).
 */
@TableName("qa_calibration_record")
public class QaCalibrationRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 记录号 CRyyyymmddNNNN */
    private String recordNo;
    /** qa_calibration_equipment.id */
    private Long equipmentId;
    /** 冗余 */
    private String equipmentCode;
    private String equipmentName;
    /** 校准日期 */
    private LocalDate calDate;
    /** 有效期至 */
    private LocalDate validUntil;
    /** 校准机构 */
    private String calOrg;
    /** 证书编号 */
    private String certNo;
    /** 证书附件 URL (/api/system/upload/file) */
    private String certFile;
    /** 报告 PDF URL */
    private String reportPdf;
    /** PENDING/PASSED/FAILED */
    private String result = "PENDING";
    /** 偏差/不合格说明 */
    private String deviation;
    /** 审核人 (QA 主管) */
    private String reviewer;
    /** 审核时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime reviewTime;
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
    public String getRecordNo() { return recordNo; }
    public void setRecordNo(String recordNo) { this.recordNo = recordNo; }
    public Long getEquipmentId() { return equipmentId; }
    public void setEquipmentId(Long equipmentId) { this.equipmentId = equipmentId; }
    public String getEquipmentCode() { return equipmentCode; }
    public void setEquipmentCode(String equipmentCode) { this.equipmentCode = equipmentCode; }
    public String getEquipmentName() { return equipmentName; }
    public void setEquipmentName(String equipmentName) { this.equipmentName = equipmentName; }
    public LocalDate getCalDate() { return calDate; }
    public void setCalDate(LocalDate calDate) { this.calDate = calDate; }
    public LocalDate getValidUntil() { return validUntil; }
    public void setValidUntil(LocalDate validUntil) { this.validUntil = validUntil; }
    public String getCalOrg() { return calOrg; }
    public void setCalOrg(String calOrg) { this.calOrg = calOrg; }
    public String getCertNo() { return certNo; }
    public void setCertNo(String certNo) { this.certNo = certNo; }
    public String getCertFile() { return certFile; }
    public void setCertFile(String certFile) { this.certFile = certFile; }
    public String getReportPdf() { return reportPdf; }
    public void setReportPdf(String reportPdf) { this.reportPdf = reportPdf; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getDeviation() { return deviation; }
    public void setDeviation(String deviation) { this.deviation = deviation; }
    public String getReviewer() { return reviewer; }
    public void setReviewer(String reviewer) { this.reviewer = reviewer; }
    public LocalDateTime getReviewTime() { return reviewTime; }
    public void setReviewTime(LocalDateTime reviewTime) { this.reviewTime = reviewTime; }
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
