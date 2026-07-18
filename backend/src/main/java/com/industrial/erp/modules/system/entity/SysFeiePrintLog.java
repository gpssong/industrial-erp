package com.industrial.erp.modules.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 飞鹅云打印日志
 *
 * <p>每次 {@code FeiePrintClient} 调用都对应一条记录, 用于:
 * <ul>
 *   <li>失败回查与告警</li>
 *   <li>幂等 (contentHash)</li>
 *   <li>运营统计 (按 user / biz_type / 时间)</li>
 * </ul>
 */
@TableName("sys_feie_print_log")
public class SysFeiePrintLog {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String bizType;
    private Long billId;
    private String billNo;
    private Long configId;
    private String deviceSn;
    private String contentHash;

    /** 0=失败 1=已下发 2=已打印 */
    private Integer status;

    private Integer respCode;
    private String respMsg;
    private Integer costMs;

    private Long userId;
    private String userName;
    private String clientIp;

    private LocalDateTime createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public Long getBillId() { return billId; }
    public void setBillId(Long billId) { this.billId = billId; }
    public String getBillNo() { return billNo; }
    public void setBillNo(String billNo) { this.billNo = billNo; }
    public Long getConfigId() { return configId; }
    public void setConfigId(Long configId) { this.configId = configId; }
    public String getDeviceSn() { return deviceSn; }
    public void setDeviceSn(String deviceSn) { this.deviceSn = deviceSn; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Integer getRespCode() { return respCode; }
    public void setRespCode(Integer respCode) { this.respCode = respCode; }
    public String getRespMsg() { return respMsg; }
    public void setRespMsg(String respMsg) { this.respMsg = respMsg; }
    public Integer getCostMs() { return costMs; }
    public void setCostMs(Integer costMs) { this.costMs = costMs; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }
    public String getClientIp() { return clientIp; }
    public void setClientIp(String clientIp) { this.clientIp = clientIp; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}