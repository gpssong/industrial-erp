package com.industrial.erp.modules.base.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("base_product")
@JsonIgnoreProperties(ignoreUnknown = true)
public class BaseProduct {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String productCode;
    private String productName;
    private Long categoryId;
    private String productType;
    private String spec;
    private String model;
    private String material;
    private BigDecimal thickness;
    private BigDecimal width;
    private BigDecimal density;
    /** 克重 (g/m² 或 g/件) */
    private BigDecimal gramWeight;
    private String colorNo;
    private String batchNo;
    private String barcode;
    private String qrcode;
    private Long mainUnitId;
    private Long minUnitId;
    private BigDecimal purchasePrice;
    private BigDecimal salesPrice;
    private BigDecimal costPrice;
    private BigDecimal taxRate;
    private Integer isWeigh;
    private Integer isBatch;
    private Integer isSn;
    private Integer shelfLifeDays;
    private BigDecimal safetyStock;
    private String imageUrl;
    private Integer status;
    private String remark;
    private Long createBy;
    private LocalDateTime createTime;
    private Long updateBy;
    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted = 0;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public String getProductType() { return productType; }
    public void setProductType(String productType) { this.productType = productType; }
    public String getSpec() { return spec; }
    public void setSpec(String spec) { this.spec = spec; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getMaterial() { return material; }
    public void setMaterial(String material) { this.material = material; }
    public BigDecimal getThickness() { return thickness; }
    public void setThickness(BigDecimal thickness) { this.thickness = thickness; }
    public BigDecimal getWidth() { return width; }
    public void setWidth(BigDecimal width) { this.width = width; }
    public BigDecimal getDensity() { return density; }
    public void setDensity(BigDecimal density) { this.density = density; }
    public BigDecimal getGramWeight() { return gramWeight; }
    public void setGramWeight(BigDecimal gramWeight) { this.gramWeight = gramWeight; }
    public String getColorNo() { return colorNo; }
    public void setColorNo(String colorNo) { this.colorNo = colorNo; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public String getBarcode() { return barcode; }
    public void setBarcode(String barcode) { this.barcode = barcode; }
    public String getQrcode() { return qrcode; }
    public void setQrcode(String qrcode) { this.qrcode = qrcode; }
    public Long getMainUnitId() { return mainUnitId; }
    public void setMainUnitId(Long mainUnitId) { this.mainUnitId = mainUnitId; }
    public Long getMinUnitId() { return minUnitId; }
    public void setMinUnitId(Long minUnitId) { this.minUnitId = minUnitId; }
    public BigDecimal getPurchasePrice() { return purchasePrice; }
    public void setPurchasePrice(BigDecimal purchasePrice) { this.purchasePrice = purchasePrice; }
    public BigDecimal getSalesPrice() { return salesPrice; }
    public void setSalesPrice(BigDecimal salesPrice) { this.salesPrice = salesPrice; }
    public BigDecimal getCostPrice() { return costPrice; }
    public void setCostPrice(BigDecimal costPrice) { this.costPrice = costPrice; }
    public BigDecimal getTaxRate() { return taxRate; }
    public void setTaxRate(BigDecimal taxRate) { this.taxRate = taxRate; }
    public Integer getIsWeigh() { return isWeigh; }
    public void setIsWeigh(Integer isWeigh) { this.isWeigh = isWeigh; }
    public Integer getIsBatch() { return isBatch; }
    public void setIsBatch(Integer isBatch) { this.isBatch = isBatch; }
    public Integer getIsSn() { return isSn; }
    public void setIsSn(Integer isSn) { this.isSn = isSn; }
    public Integer getShelfLifeDays() { return shelfLifeDays; }
    public void setShelfLifeDays(Integer shelfLifeDays) { this.shelfLifeDays = shelfLifeDays; }
    public BigDecimal getSafetyStock() { return safetyStock; }
    public void setSafetyStock(BigDecimal safetyStock) { this.safetyStock = safetyStock; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public Long getCreateBy() { return createBy; }
    public void setCreateBy(Long createBy) { this.createBy = createBy; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public Long getUpdateBy() { return updateBy; }
    public void setUpdateBy(Long updateBy) { this.updateBy = updateBy; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
}
