package com.industrial.erp.modules.inventory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * App 端外勤盘点提交 DTO (v1.0.8+)
 * <p>App 扫码 + 录入实盘数量后, 一次性提交到后端生成 DRAFT 状态盘点单.
 * <p>账面数 (book_qty) 由后端事务内从 inv_stock 拉, 不依赖 App 端.
 */
public class AppCheckSubmitDTO {

    @NotNull(message = "仓库不能为空")
    private Long warehouseId;

    /** 业务日期, 默认当天 */
    private LocalDate billDate;

    @Size(max = 500, message = "备注不能超过 500 字")
    private String remark;

    @NotEmpty(message = "至少录入一个商品")
    @Valid
    private List<Item> items;

    public static class Item {
        @NotNull(message = "商品ID不能为空")
        private Long productId;

        @NotNull(message = "实盘数量不能为空")
        private BigDecimal actualQty;

        @Size(max = 255)
        private String remark;

        public Long getProductId() { return productId; }
        public void setProductId(Long productId) { this.productId = productId; }
        public BigDecimal getActualQty() { return actualQty; }
        public void setActualQty(BigDecimal actualQty) { this.actualQty = actualQty; }
        public String getRemark() { return remark; }
        public void setRemark(String remark) { this.remark = remark; }
    }

    public Long getWarehouseId() { return warehouseId; }
    public void setWarehouseId(Long warehouseId) { this.warehouseId = warehouseId; }
    public LocalDate getBillDate() { return billDate; }
    public void setBillDate(LocalDate billDate) { this.billDate = billDate; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public List<Item> getItems() { return items; }
    public void setItems(List<Item> items) { this.items = items; }
}
