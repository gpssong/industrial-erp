package com.industrial.erp.modules.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 登录日志 (append-only, 不软删)
 * 表结构见 sql/01_schema_system.sql:169-181
 */
@TableName("sys_login_log")
public class SysLoginLog {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String username;
    private String ipAddress;
    private String browser;
    private String os;
    /** 1=成功 0=失败 */
    private Integer status;
    private String msg;
    private LocalDateTime loginTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public String getBrowser() { return browser; }
    public void setBrowser(String browser) { this.browser = browser; }
    public String getOs() { return os; }
    public void setOs(String os) { this.os = os; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getMsg() { return msg; }
    public void setMsg(String msg) { this.msg = msg; }
    public LocalDateTime getLoginTime() { return loginTime; }
    public void setLoginTime(LocalDateTime loginTime) { this.loginTime = loginTime; }
}
