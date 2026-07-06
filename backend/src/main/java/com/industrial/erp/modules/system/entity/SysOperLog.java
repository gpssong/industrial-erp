package com.industrial.erp.modules.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("sys_oper_log")
public class SysOperLog {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String module;
    private String businessType;
    private String method;
    private String requestUrl;
    private String requestMethod;
    private String requestParam;
    private String responseData;
    private String ipAddress;
    private Long userId;
    private String username;
    private Long costTime;
    private Integer status;
    private String errorMsg;
    /** 删除前的完整对象 JSON 快照 (仅 businessType=DELETE 时填充) */
    private String snapshotJson;
    private LocalDateTime operTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getModule() { return module; }
    public void setModule(String module) { this.module = module; }
    public String getBusinessType() { return businessType; }
    public void setBusinessType(String businessType) { this.businessType = businessType; }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public String getRequestUrl() { return requestUrl; }
    public void setRequestUrl(String requestUrl) { this.requestUrl = requestUrl; }
    public String getRequestMethod() { return requestMethod; }
    public void setRequestMethod(String requestMethod) { this.requestMethod = requestMethod; }
    public String getRequestParam() { return requestParam; }
    public void setRequestParam(String requestParam) { this.requestParam = requestParam; }
    public String getResponseData() { return responseData; }
    public void setResponseData(String responseData) { this.responseData = responseData; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public Long getCostTime() { return costTime; }
    public void setCostTime(Long costTime) { this.costTime = costTime; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }
    public String getSnapshotJson() { return snapshotJson; }
    public void setSnapshotJson(String snapshotJson) { this.snapshotJson = snapshotJson; }
    public LocalDateTime getOperTime() { return operTime; }
    public void setOperTime(LocalDateTime operTime) { this.operTime = operTime; }
}
