package com.industrial.erp.modules.qms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import java.time.LocalDateTime;

/**
 * v1.1.101 XK — IQC 检验项明细 (1 检验单 N 检验项).
 *
 * <p>与 {@code mes_quality_defect} 互补: defect 是不合格缺陷行, item 是必检项目 (PASS 也登记).
 * 挂在 {@code mes_quality_inspection.id} (inspection_type=IQC) 下, 行号 {@code line_no} 1..N.
 * 一期 1 检验单 N 检验项, 不参与库存账.
 */
@TableName("qms_iqc_item")
public class QmsIqcItem {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 所属检验单 (mes_quality_inspection.id, inspection_type=IQC) */
    private Long iqcId;
    /** 行号 1..N */
    private Integer lineNo;
    /** 检验项编码 (PACKAGING/DENSITY/MFR/SENSORY/FOOD_CONTACT_HEAVY_METAL 等) */
    private String itemCode;
    /** 检验项名称 (包装与标签/外观与感官/...) */
    private String itemName;
    /** 要求 */
    private String requirement;
    /** 方法 (目测/感官/熔融指数仪/...) */
    private String method;
    /** 实测值 (2.18/0.918/符合/已核验) */
    private String resultValue;
    /** 实测单位 (g/10min/g/cm³/无) */
    private String unit;
    /** 1=合格 / 0=不合格 */
    private Integer passFlag;
    /** 判定依据 (合格范围文字描述) */
    private String judgmentBasis;
    /** 是否食品级专用检验项 (base_product.is_food_grade=1 自动加载) */
    private Integer isFoodGradeItem;
    /** TEMPLATE=模板自动生成 / MANUAL=手工录入 */
    private String source;
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
    public Long getIqcId() { return iqcId; }
    public void setIqcId(Long iqcId) { this.iqcId = iqcId; }
    public Integer getLineNo() { return lineNo; }
    public void setLineNo(Integer lineNo) { this.lineNo = lineNo; }
    public String getItemCode() { return itemCode; }
    public void setItemCode(String itemCode) { this.itemCode = itemCode; }
    public String getItemName() { return itemName; }
    public void setItemName(String itemName) { this.itemName = itemName; }
    public String getRequirement() { return requirement; }
    public void setRequirement(String requirement) { this.requirement = requirement; }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public String getResultValue() { return resultValue; }
    public void setResultValue(String resultValue) { this.resultValue = resultValue; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public Integer getPassFlag() { return passFlag; }
    public void setPassFlag(Integer passFlag) { this.passFlag = passFlag; }
    public String getJudgmentBasis() { return judgmentBasis; }
    public void setJudgmentBasis(String judgmentBasis) { this.judgmentBasis = judgmentBasis; }
    public Integer getIsFoodGradeItem() { return isFoodGradeItem; }
    public void setIsFoodGradeItem(Integer isFoodGradeItem) { this.isFoodGradeItem = isFoodGradeItem; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
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
