package com.industrial.erp.modules.base.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * v1.1.167 标签打印日志 (docs/32 §2.2, XK 检查时自证打过什么、谁打的)。
 *
 * <p><b>{@code createBy} 就是打印人</b>，靠 {@code MybatisPlusConfig.metaObjectHandler()}
 * 从当前登录用户回填，不要手工 set。成功与失败都要落一行。
 */
@TableName("base_label_print_log")
public class BaseLabelPrintLog {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** FG_ROLL / RAW_PALLET / CARTON / CERT */
    private String bizType;
    /** 单据 id */
    private Long bizId;
    /** 单据号 (快照) */
    private String bizNo;
    private Long templateId;
    /** 模板码 (快照, 模板改了日志仍可对上) */
    private String templateCode;
    /** BT_TSPL 手机蓝牙 / FEIE 飞鹅云 / EXPORT 导出文本 */
    private String channel;
    /** 蓝牙通道: 手机上报的设备名 */
    private String printerName;
    /** 蓝牙通道: MAC (绑定不落库, 只能从日志事后推) */
    private String printerMac;
    private Integer copies;
    /** 渲染结果快照 (TSPL 原文或飞鹅指令) */
    private String content;
    /** SHA-256 hex, 查重/幂等 (同 sys_feie_print_log 口径) */
    private String contentHash;
    /** SUCCESS / FAIL */
    private String status;
    /** 失败原因 (渲染异常/蓝牙发送失败都要落) */
    private String errorMsg;
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
    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public Long getBizId() { return bizId; }
    public void setBizId(Long bizId) { this.bizId = bizId; }
    public String getBizNo() { return bizNo; }
    public void setBizNo(String bizNo) { this.bizNo = bizNo; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public String getTemplateCode() { return templateCode; }
    public void setTemplateCode(String templateCode) { this.templateCode = templateCode; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getPrinterName() { return printerName; }
    public void setPrinterName(String printerName) { this.printerName = printerName; }
    public String getPrinterMac() { return printerMac; }
    public void setPrinterMac(String printerMac) { this.printerMac = printerMac; }
    public Integer getCopies() { return copies; }
    public void setCopies(Integer copies) { this.copies = copies; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }
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
