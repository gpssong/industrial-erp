package com.industrial.erp.common;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serializable;
import java.util.List;

@Schema(description = "分页结果")
public class PageResult<T> implements Serializable {
    private long total;
    private long pageNum;
    private long pageSize;
    private long pages;
    private List<T> records;

    public static <T> PageResult<T> of(IPage<T> page) {
        PageResult<T> r = new PageResult<>();
        r.total = page.getTotal();
        r.pageNum = page.getCurrent();
        r.pageSize = page.getSize();
        r.pages = page.getPages();
        r.records = page.getRecords();
        return r;
    }

    public long getTotal() { return total; }
    public void setTotal(long total) { this.total = total; }
    public long getPageNum() { return pageNum; }
    public void setPageNum(long pageNum) { this.pageNum = pageNum; }
    public long getPageSize() { return pageSize; }
    public void setPageSize(long pageSize) { this.pageSize = pageSize; }
    public long getPages() { return pages; }
    public void setPages(long pages) { this.pages = pages; }
    public List<T> getRecords() { return records; }
    public void setRecords(List<T> records) { this.records = records; }
}
