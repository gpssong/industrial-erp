package com.industrial.erp.modules.sales.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("sal_return_detail")
public class SalReturnDetail {
    @TableField(exist = false)
    private transient BigDecimal pThickness;
    @TableField(exist = false)
    private transient BigDecimal pWidth;
    @TableField(exist = false)
    private transient BigDecimal pDensity;
    @TableField(exist = false)
    private transient BigDecimal pGramWeight;
    @TableField(exist = false)
    private transient String pMaterial;
    @TableField(exist = false)
    private transient String pModel;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long returnId;
    private Integer lineNo;
    private Long productId;
    private String productCode;
    private String productName;
    private String spec;
    private Long unitId;
    private String unitName;
    private BigDecimal qty;
    private BigDecimal price;
    private BigDecimal amount;
    private BigDecimal taxRate;
    private BigDecimal taxAmount;
    private BigDecimal amountTax;
    private String batchNo;
    private String remark;
    private Long createBy;
    private LocalDateTime createTime;
    private Long updateBy;
    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted = 0;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getReturnId() { return returnId; }
    public void setReturnId(Long returnId) { this.returnId = returnId; }
    public Integer getLineNo() { return lineNo; }
    public void setLineNo(Integer lineNo) { this.lineNo = lineNo; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public String getSpec() { return spec; }
    public void setSpec(String spec) { this.spec = spec; }
    public Long getUnitId() { return unitId; }
    public void setUnitId(Long unitId) { this.unitId = unitId; }
    public String getUnitName() { return unitName; }
    public void setUnitName(String unitName) { this.unitName = unitName; }
    public BigDecimal getQty() { return qty; }
    public void setQty(BigDecimal qty) { this.qty = qty; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public BigDecimal getTaxRate() { return taxRate; }
    public void setTaxRate(BigDecimal taxRate) { this.taxRate = taxRate; }
    public BigDecimal getTaxAmount() { return taxAmount; }
    public void setTaxAmount(BigDecimal taxAmount) { this.taxAmount = taxAmount; }
    public BigDecimal getAmountTax() { return amountTax; }
    public void setAmountTax(BigDecimal amountTax) { this.amountTax = amountTax; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
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
    public BigDecimal getPThickness() { return pThickness; }
    public void setPThickness(BigDecimal pThickness) { this.pThickness = pThickness; }
    public BigDecimal getPWidth() { return pWidth; }
    public void setPWidth(BigDecimal pWidth) { this.pWidth = pWidth; }
    public BigDecimal getPDensity() { return pDensity; }
    public void setPDensity(BigDecimal pDensity) { this.pDensity = pDensity; }
    public BigDecimal getPGramWeight() { return pGramWeight; }
    public void setPGramWeight(BigDecimal pGramWeight) { this.pGramWeight = pGramWeight; }
    public String getPMaterial() { return pMaterial; }
    public void setPMaterial(String pMaterial) { this.pMaterial = pMaterial; }
    public String getPModel() { return pModel; }
    public void setPModel(String pModel) { this.pModel = pModel; }
    public BigDecimal getThickness() { return pThickness; }
    public void setThickness(BigDecimal thickness) { this.pThickness = thickness; }
    public BigDecimal getWidth() { return pWidth; }
    public void setWidth(BigDecimal width) { this.pWidth = width; }
    public BigDecimal getDensity() { return pDensity; }
    public void setDensity(BigDecimal density) { this.pDensity = density; }
    public BigDecimal getGramWeight() { return pGramWeight; }
    public void setGramWeight(BigDecimal gramWeight) { this.pGramWeight = gramWeight; }
    public String getMaterial() { return pMaterial; }
    public void setMaterial(String material) { this.pMaterial = material; }
    /** 模板字段: model = 型号 */
    public String getModel() { return pModel; }
    public void setModel(String model) { this.pModel = model; }
}
