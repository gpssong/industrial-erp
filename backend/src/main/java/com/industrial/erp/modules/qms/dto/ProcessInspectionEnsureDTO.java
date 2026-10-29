package com.industrial.erp.modules.qms.dto;

public class ProcessInspectionEnsureDTO {
    private Long orderId;
    private Long processId;
    private String inspectionType;

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public Long getProcessId() { return processId; }
    public void setProcessId(Long processId) { this.processId = processId; }
    public String getInspectionType() { return inspectionType; }
    public void setInspectionType(String inspectionType) { this.inspectionType = inspectionType; }
}
