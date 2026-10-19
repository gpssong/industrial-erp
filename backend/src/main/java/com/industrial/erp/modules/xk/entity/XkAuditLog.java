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
 * v1.1.91 XK 合规中心 — 审计流水 (XK16-204 R2/R3/R4/R5/R6).
 *
 * <p>跟 {@code sys_oper_log} 互补: 本表专门给 XK 合规中心展示, 携带
 * SHA256 (R2 防篡改) + OSS 路径 (R6 图片/附件对象存储) + 2 年到期日 (R4 数据留存)
 * + 服务器时间戳 (R5)。提交关键动作时由 {@code XkAuditService.writeAudit}
 * <b>同步直写</b> (McpAuditService 教训, 不走 @Async @EventListener 防高频漏写)。
 */
@TableName("xk_audit_log")
public class XkAuditLog {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 审计流水号 XAyyyymmddNNNNN */
    private String logNo;
    /** INBOUND/PRODUCE/MAINTAIN/SAFETY/TRACE/EDIT/PDF/UPLOAD/VOID */
    private String bizType;
    /** 关联业务单号 (RK/WO/WB/AJ/FG 等) */
    private String bizBillNo;
    /** PDF/EDIT/UPLOAD/VOID */
    private String action;
    /** 变更描述 */
    private String changeDesc;
    private Long operator;
    private String operatorName;
    /** 角色名 (仓库员/质量 QA/管理员) */
    private String operatorRole;
    /** PDF 文件 SHA256 (R2 防篡改) */
    private String sha256;
    /** 对象存储路径 (R6) */
    private String ossPath;
    /** PDF/附件大小 KB */
    private Integer sizeKb;
    /** 2 年到期日 (R4 数据留存, XK_AUDIT_RETENTION_YEARS 配置) */
    private LocalDate expireAt;
    /** 服务器时间戳 (R5) */
    private LocalDateTime operTime;
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
    public String getLogNo() { return logNo; }
    public void setLogNo(String logNo) { this.logNo = logNo; }
    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public String getBizBillNo() { return bizBillNo; }
    public void setBizBillNo(String bizBillNo) { this.bizBillNo = bizBillNo; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getChangeDesc() { return changeDesc; }
    public void setChangeDesc(String changeDesc) { this.changeDesc = changeDesc; }
    public Long getOperator() { return operator; }
    public void setOperator(Long operator) { this.operator = operator; }
    public String getOperatorName() { return operatorName; }
    public void setOperatorName(String operatorName) { this.operatorName = operatorName; }
    public String getOperatorRole() { return operatorRole; }
    public void setOperatorRole(String operatorRole) { this.operatorRole = operatorRole; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public String getOssPath() { return ossPath; }
    public void setOssPath(String ossPath) { this.ossPath = ossPath; }
    public Integer getSizeKb() { return sizeKb; }
    public void setSizeKb(Integer sizeKb) { this.sizeKb = sizeKb; }
    public LocalDate getExpireAt() { return expireAt; }
    public void setExpireAt(LocalDate expireAt) { this.expireAt = expireAt; }
    public LocalDateTime getOperTime() { return operTime; }
    public void setOperTime(LocalDateTime operTime) { this.operTime = operTime; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
}
