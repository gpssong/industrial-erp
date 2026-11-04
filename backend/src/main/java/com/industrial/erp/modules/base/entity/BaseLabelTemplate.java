package com.industrial.erp.modules.base.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * v1.1.167 标签模板 (docs/32 §2.2)。
 *
 * <p>只存**结构化元素**，不存原始指令文本 —— 这是「以后换 CPCL/别家机器不用重写」的机制保证。
 * 元素坐标单位是**点**，{@code dpi} 决定点数：dots = floor(mm × trueDpi / 25.4)，203dpi 按 8 点/毫米。
 */
@TableName("base_label_template")
public class BaseLabelTemplate {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String templateCode;
    private String templateName;
    /** FG_ROLL / RAW_PALLET / CARTON / CERT */
    private String bizType;
    private Integer widthMm;
    private Integer heightMm;
    private Integer gapMm;
    private Integer dpi;
    /** NORMAL / REVERSE */
    private String direction;
    private Integer copies;
    /** 结构化元素数组 JSON，见 docs/32 §3 */
    private String elements;
    private Integer isDefault;
    private Integer status;
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
    public String getTemplateCode() { return templateCode; }
    public void setTemplateCode(String templateCode) { this.templateCode = templateCode; }
    public String getTemplateName() { return templateName; }
    public void setTemplateName(String templateName) { this.templateName = templateName; }
    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public Integer getWidthMm() { return widthMm; }
    public void setWidthMm(Integer widthMm) { this.widthMm = widthMm; }
    public Integer getHeightMm() { return heightMm; }
    public void setHeightMm(Integer heightMm) { this.heightMm = heightMm; }
    public Integer getGapMm() { return gapMm; }
    public void setGapMm(Integer gapMm) { this.gapMm = gapMm; }
    public Integer getDpi() { return dpi; }
    public void setDpi(Integer dpi) { this.dpi = dpi; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public Integer getCopies() { return copies; }
    public void setCopies(Integer copies) { this.copies = copies; }
    public String getElements() { return elements; }
    public void setElements(String elements) { this.elements = elements; }
    public Integer getIsDefault() { return isDefault; }
    public void setIsDefault(Integer isDefault) { this.isDefault = isDefault; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
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
