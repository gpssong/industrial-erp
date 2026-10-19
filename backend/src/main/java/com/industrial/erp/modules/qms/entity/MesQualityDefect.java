package com.industrial.erp.modules.qms.entity;

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
 * v1.1.90 QMS — 质量检验单明细 (不合格缺陷行, PASS 不必登记).
 *
 * <p>挂在 {@code mes_quality_inspection.id} 下, 行号 {@code line_no} 1..N.
 * 一期没有头行关联表 (与 prd_process 不同), 缺陷只属于检验单, 不参与库存账.
 */
@TableName("mes_quality_defect")
public class MesQualityDefect {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 所属检验单 id (mes_quality_inspection.id) */
    private Long inspectionId;
    /** 行号 (1..N, 同一 inspection_id 下有序) */
    private Integer lineNo;
    /** 外观/尺寸/性能/印刷/... (字典化由前端维护) */
    private String defectType;
    /** MINOR / MAJOR / CRITICAL */
    private String severity;
    /** 不合格数量 */
    private BigDecimal qty;
    /** 缺陷位置 (如: 左上角 5cm 处 / 第 3 模腔) */
    private String location;
    /** 缺陷照片 URL (单图) */
    private String photoUrl;
    /** REWORK / SCRAP / CONCESSION / SORT */
    private String disposal;
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
    public Long getInspectionId() { return inspectionId; }
    public void setInspectionId(Long inspectionId) { this.inspectionId = inspectionId; }
    public Integer getLineNo() { return lineNo; }
    public void setLineNo(Integer lineNo) { this.lineNo = lineNo; }
    public String getDefectType() { return defectType; }
    public void setDefectType(String defectType) { this.defectType = defectType; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public BigDecimal getQty() { return qty; }
    public void setQty(BigDecimal qty) { this.qty = qty; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getPhotoUrl() { return photoUrl; }
    public void setPhotoUrl(String photoUrl) { this.photoUrl = photoUrl; }
    public String getDisposal() { return disposal; }
    public void setDisposal(String disposal) { this.disposal = disposal; }
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
