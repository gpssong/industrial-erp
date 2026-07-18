package com.industrial.erp.modules.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 飞鹅云打印模板 (用户可编辑的飞鹅标签模板)
 */
@TableName("sys_feie_print_template")
public class SysFeiePrintTemplate {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;
    private String bizType;
    private Long printerConfigId;
    private String content;
    private Integer paperWidth;
    private Integer status;
    private Integer isDefault;
    private String remark;
    private Long createBy;
    private LocalDateTime createTime;
    private Long updateBy;
    private LocalDateTime updateTime;
    private Integer deleted;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public Long getPrinterConfigId() { return printerConfigId; }
    public void setPrinterConfigId(Long printerConfigId) { this.printerConfigId = printerConfigId; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Integer getPaperWidth() { return paperWidth; }
    public void setPaperWidth(Integer paperWidth) { this.paperWidth = paperWidth; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Integer getIsDefault() { return isDefault; }
    public void setIsDefault(Integer isDefault) { this.isDefault = isDefault; }
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