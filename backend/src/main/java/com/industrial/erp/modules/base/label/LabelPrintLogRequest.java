package com.industrial.erp.modules.base.label;

/**
 * POST /base/label/print/log 的请求体（App 蓝牙 TSPL 打完之后回报日志，成功与失败都要报）。
 *
 * <p>**`copies` 用原始类型 `int`**：App 不传就是 0，不能是 `Integer` —— 否则 Controller 拆箱 NPE，
 * 最该落库的那条失败记录反而丢了（Controller 里 `copies <= 0` 一律按 1 落）。
 * 其余引用类型可空：`templateId` 只是日志上的两列快照（DDL 允许 NULL），取不到就留空 (A10-R3)。
 */
public class LabelPrintLogRequest {
    private String bizType;
    private Long bizId;
    private String bizNo;
    private Long templateId;
    private String printerName;
    private String printerMac;
    private int copies;
    private String content;
    /** SUCCESS / FAIL（大小写不敏感，其余值一律按 SUCCESS 落）。 */
    private String status;
    private String errorMsg;

    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public Long getBizId() { return bizId; }
    public void setBizId(Long bizId) { this.bizId = bizId; }
    public String getBizNo() { return bizNo; }
    public void setBizNo(String bizNo) { this.bizNo = bizNo; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public String getPrinterName() { return printerName; }
    public void setPrinterName(String printerName) { this.printerName = printerName; }
    public String getPrinterMac() { return printerMac; }
    public void setPrinterMac(String printerMac) { this.printerMac = printerMac; }
    public int getCopies() { return copies; }
    public void setCopies(int copies) { this.copies = copies; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }
}
