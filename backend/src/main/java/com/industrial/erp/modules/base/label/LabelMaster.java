package com.industrial.erp.modules.base.label;

/** 标签主数据 (docs/32 §2.1)。空值一律用空串表示，不要让渲染器处理 null。 */
public class LabelMaster {
    private String producerName = "";
    private String producerAddress = "";
    private String xkNo = "";
    private String execStandard = "";
    private String storageCondition = "";
    private String inspectorCode = "";

    public String getProducerName() { return producerName; }
    public void setProducerName(String v) { this.producerName = v == null ? "" : v; }
    public String getProducerAddress() { return producerAddress; }
    public void setProducerAddress(String v) { this.producerAddress = v == null ? "" : v; }
    public String getXkNo() { return xkNo; }
    public void setXkNo(String v) { this.xkNo = v == null ? "" : v; }
    public String getExecStandard() { return execStandard; }
    public void setExecStandard(String v) { this.execStandard = v == null ? "" : v; }
    public String getStorageCondition() { return storageCondition; }
    public void setStorageCondition(String v) { this.storageCondition = v == null ? "" : v; }
    public String getInspectorCode() { return inspectorCode; }
    public void setInspectorCode(String v) { this.inspectorCode = v == null ? "" : v; }
}
