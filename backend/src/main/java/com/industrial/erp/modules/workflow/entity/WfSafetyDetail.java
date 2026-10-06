package com.industrial.erp.modules.workflow.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** v1.1.79 工作流: 安全检查记录明细 */
@TableName("wf_safety_detail")
public class WfSafetyDetail {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long recordId;
    private String checkItem;
    private String result;
    private String riskDesc;
    private String handler;
    private LocalDate fixDeadline;
    private String riskPhoto; // 该行隐患现场照片 URL
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
    public Long getRecordId() { return recordId; }
    public void setRecordId(Long recordId) { this.recordId = recordId; }
    public String getCheckItem() { return checkItem; }
    public void setCheckItem(String checkItem) { this.checkItem = checkItem; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getRiskDesc() { return riskDesc; }
    public void setRiskDesc(String riskDesc) { this.riskDesc = riskDesc; }
    public String getHandler() { return handler; }
    public void setHandler(String handler) { this.handler = handler; }
    public LocalDate getFixDeadline() { return fixDeadline; }
    public void setFixDeadline(LocalDate fixDeadline) { this.fixDeadline = fixDeadline; }
    public String getRiskPhoto() { return riskPhoto; }
    public void setRiskPhoto(String riskPhoto) { this.riskPhoto = riskPhoto; }
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
