package com.industrial.erp.modules.sales.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 销售出库审核时指定「每行明细出哪个批次、各出多少」(v1.1.152+).
 * <p>不传该 body (App / 旧客户端) 时, 后端沿用原逻辑: 按明细 batch_no 勾选顺序,
 * 依各批当前库存自动拆分整行数量. 传了则严格按照分配执行, 不自动补差.
 * <p>batchNo 空串 = 无批次 (对应 inv_stock.batch_no IS NULL 那行).
 */
public class SalDeliveryCheckDTO {

    private List<Line> details;

    public static class Line {
        /** sal_delivery_detail.id */
        private Long detailId;
        private List<BatchQty> allocations;

        public Long getDetailId() { return detailId; }
        public void setDetailId(Long detailId) { this.detailId = detailId; }
        public List<BatchQty> getAllocations() { return allocations; }
        public void setAllocations(List<BatchQty> allocations) { this.allocations = allocations; }
    }

    public static class BatchQty {
        private String batchNo;
        private BigDecimal qty;

        public String getBatchNo() { return batchNo; }
        public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
        public BigDecimal getQty() { return qty; }
        public void setQty(BigDecimal qty) { this.qty = qty; }
    }

    public List<Line> getDetails() { return details; }
    public void setDetails(List<Line> details) { this.details = details; }
}
