package com.industrial.erp.modules.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 打印模板 (myprint-design)
 * content 字段为模板 JSON 字符串, 与前端 myprint-design Template.content 格式一致
 */
@TableName("sys_print_template")
public class SysPrintTemplate {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String name;
    /** 业务类型: SAL_DELIVERY / PUR_RECEIPT / PUR_RETURN / SAL_RETURN / PRD_ORDER */
    private String bizType;
    /** 模板 JSON (myprint-design Template.content) */
    private String content;
    private BigDecimal paperWidth;
    private BigDecimal paperHeight;
    /** mm / cm / in / px */
    private String pageUnit;
    /** 0=停用 1=启用 */
    private Integer status;
    /** 同 biz_type 仅 1 个为 1 */
    private Integer isDefault;
    private String remark;
    private Long createBy;
    private LocalDateTime createTime;
    private Long updateBy;
    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted = 0;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public BigDecimal getPaperWidth() { return paperWidth; }
    public void setPaperWidth(BigDecimal paperWidth) { this.paperWidth = paperWidth; }
    public BigDecimal getPaperHeight() { return paperHeight; }
    public void setPaperHeight(BigDecimal paperHeight) { this.paperHeight = paperHeight; }
    public String getPageUnit() { return pageUnit; }
    public void setPageUnit(String pageUnit) { this.pageUnit = pageUnit; }
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