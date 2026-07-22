package com.industrial.erp.modules.inventory.vo;

import java.math.BigDecimal;

/**
 * 仓库商品账面快照 (v1.0.8+)
 * <p>用于 App 进入盘点前预加载该仓库所有商品的账面库存, 离线录入实盘.
 * <p>避免 App 端在扫码前看不到账面数, 提高现场录入体验.
 */
public class WarehouseStockSnapshotVO {
    private Long productId;
    private String productCode;
    private String productName;
    private String spec;
    private String barcode;
    private String unitName;
    private BigDecimal bookQty;

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public String getSpec() { return spec; }
    public void setSpec(String spec) { this.spec = spec; }
    public String getBarcode() { return barcode; }
    public void setBarcode(String barcode) { this.barcode = barcode; }
    public String getUnitName() { return unitName; }
    public void setUnitName(String unitName) { this.unitName = unitName; }
    public BigDecimal getBookQty() { return bookQty; }
    public void setBookQty(BigDecimal bookQty) { this.bookQty = bookQty; }
}
