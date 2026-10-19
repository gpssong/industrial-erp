package com.industrial.erp.modules.xk.entity;

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
 * v1.1.97 XK — 生产环境安全/卫生检查.
 *
 * <p>对应 Safety.vue Tab 3「生产环境检查」. 一条记录 = 某车间 + 某检查类型
 * (温湿度/洁净度/消防/应急照明/安全门/供电/压缩空气) + 一次检查任务. 完成后填
 * 整改等级 (LOW/MEDIUM/HIGH) + 负责人 + 期限 + 整改前/后照片.
 *
 * <p>状态机:
 * <ul>
 *   <li>DRAFT — 已记录待办, 未整改完成</li>
 *   <li>CHECKED — 已完成, 整改归档 (含 LOW=无整改 / MEDIUM/HIGH=已整改)</li>
 * </ul>
 *
 * <p>数据流: 新建 (DRAFT) -> /check 提交整改 (level/owner/deadline + photos) -> CHECKED.
 * 完成后同步直写 xk_audit_log (CLAUDE.md v1.1.91 沿用, 非 @Async).
 */
@TableName("xk_env_check")
public class XkEnvCheck {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 检查单号 ECyyyymmddNNNN */
    private String checkNo;
    /** 车间/区域 */
    private String workshop;
    /** TEMP_HUMIDITY / CLEANLINESS / FIRE_SAFETY / EMERGENCY_LIGHT / SAFETY_DOOR / POWER / COMPRESSED_AIR */
    private String envType;
    /** 检查主题 */
    private String checkTitle;
    /** 检查内容描述 */
    private String checkContent;
    /** 计划检查时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime plannedAt;
    /** 检查人 (冗余) */
    private String inspector;
    /** 整改等级 LOW/MEDIUM/HIGH (NULL=无问题) */
    private String level;
    /** 整改负责人 */
    private String rectifyOwner;
    /** 整改期限 */
    private LocalDate rectifyDeadline;
    /** NONE/PENDING/IN_PROGRESS/DONE */
    private String rectifyStatus = "NONE";
    /** 整改说明 */
    private String rectifyDesc;
    /** 整改前照片 (JSON 数组) */
    private String beforePhotos;
    /** 整改后照片 (JSON 数组) */
    private String afterPhotos;
    /** DRAFT/CHECKED */
    private String status = "DRAFT";
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
    public String getCheckNo() { return checkNo; }
    public void setCheckNo(String checkNo) { this.checkNo = checkNo; }
    public String getWorkshop() { return workshop; }
    public void setWorkshop(String workshop) { this.workshop = workshop; }
    public String getEnvType() { return envType; }
    public void setEnvType(String envType) { this.envType = envType; }
    public String getCheckTitle() { return checkTitle; }
    public void setCheckTitle(String checkTitle) { this.checkTitle = checkTitle; }
    public String getCheckContent() { return checkContent; }
    public void setCheckContent(String checkContent) { this.checkContent = checkContent; }
    public LocalDateTime getPlannedAt() { return plannedAt; }
    public void setPlannedAt(LocalDateTime plannedAt) { this.plannedAt = plannedAt; }
    public String getInspector() { return inspector; }
    public void setInspector(String inspector) { this.inspector = inspector; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public String getRectifyOwner() { return rectifyOwner; }
    public void setRectifyOwner(String rectifyOwner) { this.rectifyOwner = rectifyOwner; }
    public LocalDate getRectifyDeadline() { return rectifyDeadline; }
    public void setRectifyDeadline(LocalDate rectifyDeadline) { this.rectifyDeadline = rectifyDeadline; }
    public String getRectifyStatus() { return rectifyStatus; }
    public void setRectifyStatus(String rectifyStatus) { this.rectifyStatus = rectifyStatus; }
    public String getRectifyDesc() { return rectifyDesc; }
    public void setRectifyDesc(String rectifyDesc) { this.rectifyDesc = rectifyDesc; }
    public String getBeforePhotos() { return beforePhotos; }
    public void setBeforePhotos(String beforePhotos) { this.beforePhotos = beforePhotos; }
    public String getAfterPhotos() { return afterPhotos; }
    public void setAfterPhotos(String afterPhotos) { this.afterPhotos = afterPhotos; }
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
