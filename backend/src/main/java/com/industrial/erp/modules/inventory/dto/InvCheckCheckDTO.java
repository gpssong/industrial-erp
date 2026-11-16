package com.industrial.erp.modules.inventory.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 库存盘点审核时指定「每条盘亏明细从哪个批次出、各出多少」。
 * <p>不传该 body (App / 旧客户端) 时, 后端按该商品在当前仓库的实际批次库存自动拆分扣减。
 * <p>batchNo 空串 = 无批次 (对应 inv_stock.batch_no IS NULL 那行).
 */
public class InvCheckCheckDTO {

    private List<Line> details;

    public static class Line {
        /** inv_check_detail.id */
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
