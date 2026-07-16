package com.industrial.erp.modules.system.dto;

import java.io.Serializable;

/**
 * 打印模板查询条件
 */
public class PrintTemplateQuery implements Serializable {
    private Integer pageNum = 1;
    private Integer pageSize = 20;
    private String name;
    private String bizType;
    private Integer status;

    public Integer getPageNum() { return pageNum; }
    public void setPageNum(Integer pageNum) { this.pageNum = pageNum; }
    public Integer getPageSize() { return pageSize; }
    public void setPageSize(Integer pageSize) { this.pageSize = pageSize; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
}