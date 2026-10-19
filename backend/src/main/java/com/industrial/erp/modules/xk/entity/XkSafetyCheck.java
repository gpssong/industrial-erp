package com.industrial.erp.modules.xk.entity;

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
 * v1.1.91 XK 合规中心 — 卫生检查单 (XK16-204 R1/R3 受控记录 + 审计留痕).
 *
 * <p>独立于 {@code wf_safety_record} (工作流类), 本表是 XK 合规专用:
 * 5 项核查快照 (item_snapshot JSON) + 整改前/后照片 (JSON 6 张, 走 R6 OSS 不存 DB 二进制)
 * + 整改闭环 (owner/deadline)。提交时同步写 {@code xk_audit_log} (module=XK合规)。
 */
@TableName("xk_safety_check")
public class XkSafetyCheck {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String checkNo;
    private LocalDate checkDate;
    /** 吹膜洁净区/制袋区/分切区/印刷区 */
    private String area;
    private Long inspector;
    private String inspectorName;
    /** PASS / FAIL */
    private String result;
    /** 5 项核查快照 JSON: [{no, text, status(OK/PROBLEM), problem}] */
    private String itemSnapshot;
    /** 整改负责人 */
    private String reformOwner;
    /** 整改期限 */
    private LocalDate reformDeadline;
    /** 整改前照片 JSON 数组 (最多 6 张 OSS URL, R6) */
    private String reformPhotosBefore;
    /** 整改后照片 JSON 数组 (最多 6 张 OSS URL, R6) */
    private String reformPhotosAfter;
    private String remark;
    /** DRAFT / CHECKED / VOID (R2: 只能作废, 不允许 DELETE) */
    private String billStatus;
    @TableField(fill = FieldFill.INSERT)
    private Long createBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    @Version
    private Integer version;
    @TableLogic
    private Integer deleted = 0;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCheckNo() { return checkNo; }
    public void setCheckNo(String checkNo) { this.checkNo = checkNo; }
    public LocalDate getCheckDate() { return checkDate; }
    public void setCheckDate(LocalDate checkDate) { this.checkDate = checkDate; }
    public String getArea() { return area; }
    public void setArea(String area) { this.area = area; }
    public Long getInspector() { return inspector; }
    public void setInspector(Long inspector) { this.inspector = inspector; }
    public String getInspectorName() { return inspectorName; }
    public void setInspectorName(String inspectorName) { this.inspectorName = inspectorName; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getItemSnapshot() { return itemSnapshot; }
    public void setItemSnapshot(String itemSnapshot) { this.itemSnapshot = itemSnapshot; }
    public String getReformOwner() { return reformOwner; }
    public void setReformOwner(String reformOwner) { this.reformOwner = reformOwner; }
    public LocalDate getReformDeadline() { return reformDeadline; }
    public void setReformDeadline(LocalDate reformDeadline) { this.reformDeadline = reformDeadline; }
    public String getReformPhotosBefore() { return reformPhotosBefore; }
    public void setReformPhotosBefore(String reformPhotosBefore) { this.reformPhotosBefore = reformPhotosBefore; }
    public String getReformPhotosAfter() { return reformPhotosAfter; }
    public void setReformPhotosAfter(String reformPhotosAfter) { this.reformPhotosAfter = reformPhotosAfter; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public String getBillStatus() { return billStatus; }
    public void setBillStatus(String billStatus) { this.billStatus = billStatus; }
    public Long getCreateBy() { return createBy; }
    public void setCreateBy(Long createBy) { this.createBy = createBy; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public Long getUpdateBy() { return updateBy; }
    public void setUpdateBy(Long updateBy) { this.updateBy = updateBy; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
}
