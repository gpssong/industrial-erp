package com.industrial.erp.modules.pdm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * v1.1.90 PDM — 作业指导书 (SOP, Standard Operating Procedure).
 *
 * <p>一个工序可有多版本 SOP (DRAFT/ACTIVE/DEPRECATED), 同一时刻最多 1 个 ACTIVE —
 * 激活时自动把同工序其他 ACTIVE 标 DEPRECATED. {@link #versionLock} 是悲观锁字段
 * (激活 SOP 时手动 SELECT FOR UPDATE / version 比对, <b>不是</b> MyBatis-Plus @Version
 * 乐观锁 — 故意不用, 避免误把 SOP 状态切换当成普通并发更新).
 *
 * <p>{@link #createByName} 是 transient UI 字段, 不入库, 列表展示创建人不 JOIN 即可.
 */
@TableName("mes_process_sop")
public class MesProcessSop {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属工序定义 id (mes_process_def.id) */
    private Long processDefId;

    /** 版本号 (默认 1.0, 每次新建自动 +0.1) */
    private String version;

    /** 文档文件 URL (富文本编辑器附件, 可空 = 仅 text content) */
    private String docUrl;

    /** SOP 主体内容 (LONGTEXT 富文本/Markdown/HTML 自由格式) */
    private String content;

    /** 生效日期 */
    private LocalDate effectiveDate;

    /** 状态: DRAFT / ACTIVE / DEPRECATED (Constants.SOP_*) */
    private String status;

    /** 悲观锁字段 (激活 SOP 互斥, 不用 @Version) */
    private Integer versionLock;

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

    /** 创建人姓名 (transient, UI 直接显示不 JOIN sys_user) */
    @TableField(exist = false)
    private String createByName;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProcessDefId() { return processDefId; }
    public void setProcessDefId(Long processDefId) { this.processDefId = processDefId; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getDocUrl() { return docUrl; }
    public void setDocUrl(String docUrl) { this.docUrl = docUrl; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public LocalDate getEffectiveDate() { return effectiveDate; }
    public void setEffectiveDate(LocalDate effectiveDate) { this.effectiveDate = effectiveDate; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getVersionLock() { return versionLock; }
    public void setVersionLock(Integer versionLock) { this.versionLock = versionLock; }
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
    public String getCreateByName() { return createByName; }
    public void setCreateByName(String createByName) { this.createByName = createByName; }
}
