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
 * v1.1.101 XK — IQC 附件/照片登记表.
 *
 * <p>业务附件 = 供应商报告/采购合同/增值税发票 (PDF/JPG);
 * 照片 = 原料标签/包装/抽样 (JPG/PNG).
 * 与 {@code sys_upload} 1:1 关联, 强约束: 提交后不可脱离批次单独删除
 * (sql/117 trg_qms_iqc_attachment_protect 触发器保护).
 */
@TableName("qms_iqc_attachment")
public class QmsIqcAttachment {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 所属检验单 id (mes_quality_inspection.id) */
    private Long iqcId;
    /** sys_upload.id (实际文件) */
    private Long uploadId;
    /** 类型: REPORT/CONTRACT/INVOICE/LABEL_PHOTO/PACK_PHOTO/SAMPLE_PHOTO/OTHER */
    private String attachType;
    /** 显示名 (用户录入/系统取 file_name) */
    private String attachName;
    /** 冗余: sys_upload.file_url (列表不 JOIN) */
    private String fileUrl;
    /** 冗余: 文件大小 KB */
    private Integer fileSizeKb;
    /** 0=未核验 1=已核验 (App 端 UI 标记) */
    private Integer verified;
    /** 核验人 sys_user.id */
    private Long verifiedBy;
    /** 核验时间 */
    private LocalDateTime verifiedTime;
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
    public Long getIqcId() { return iqcId; }
    public void setIqcId(Long iqcId) { this.iqcId = iqcId; }
    public Long getUploadId() { return uploadId; }
    public void setUploadId(Long uploadId) { this.uploadId = uploadId; }
    public String getAttachType() { return attachType; }
    public void setAttachType(String attachType) { this.attachType = attachType; }
    public String getAttachName() { return attachName; }
    public void setAttachName(String attachName) { this.attachName = attachName; }
    public String getFileUrl() { return fileUrl; }
    public void setFileUrl(String fileUrl) { this.fileUrl = fileUrl; }
    public Integer getFileSizeKb() { return fileSizeKb; }
    public void setFileSizeKb(Integer fileSizeKb) { this.fileSizeKb = fileSizeKb; }
    public Integer getVerified() { return verified; }
    public void setVerified(Integer verified) { this.verified = verified; }
    public Long getVerifiedBy() { return verifiedBy; }
    public void setVerifiedBy(Long verifiedBy) { this.verifiedBy = verifiedBy; }
    public LocalDateTime getVerifiedTime() { return verifiedTime; }
    public void setVerifiedTime(LocalDateTime verifiedTime) { this.verifiedTime = verifiedTime; }
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
