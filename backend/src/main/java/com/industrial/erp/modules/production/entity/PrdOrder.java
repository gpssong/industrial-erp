package com.industrial.erp.modules.production.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@TableName("prd_order")
public class PrdOrder {
    // ===== 商品规格属性 (transient, JOIN 注入) =====
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
    private transient String bomRemark;

    /** 领料明细 (打印用, JOIN prd_requisition + prd_requisition_detail 注入, 按 prd_order_id 汇总) */
    @TableField(exist = false)
    private List<PrdRequisitionDetail> requisitionDetails;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String billNo;
    private LocalDate billDate;
    private Long bomId;
    private String bomNo;
    /** BOM 编码/名称, 从 prd_bom 表 JOIN 获取(打印模板用) */
    @TableField(exist = false)
    private String bomCode;
    @TableField(exist = false)
    private String bomName;
    private Long productId;
    private String productCode;
    private String productName;
    private String spec;
    /** 成品型号, 从 base_product 同步到生产单但不持久化到 prd_order 表 */
    @TableField(exist = false)
    private String model;
    private Long unitId;
    private String unitName;
    private BigDecimal planQty;
    private BigDecimal actualQty;
    private BigDecimal goodQty;
    private BigDecimal lossQty;
    private BigDecimal lossRate;
    private String workshop;
    private Long workshopId;
    private Long warehouseId;
    private String leader;
    private LocalDate startDate;
    private LocalDate endDate;
    private BigDecimal costAmount;
    private String billStatus;
    private String sourceBillType;
    private Long sourceBillId;
    private String sourceBillNo;
    private String remark;
    private Long createBy;
    private LocalDateTime createTime;
    private Long updateBy;
    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted = 0;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getBillNo() { return billNo; }
    public void setBillNo(String billNo) { this.billNo = billNo; }
    public LocalDate getBillDate() { return billDate; }
    public void setBillDate(LocalDate billDate) { this.billDate = billDate; }
    public Long getBomId() { return bomId; }
    public void setBomId(Long bomId) { this.bomId = bomId; }
    public String getBomNo() { return bomNo; }
    public void setBomNo(String bomNo) { this.bomNo = bomNo; }
    public String getBomCode() { return bomCode; }
    public void setBomCode(String bomCode) { this.bomCode = bomCode; }
    public String getBomName() { return bomName; }
    public void setBomName(String bomName) { this.bomName = bomName; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public String getSpec() { return spec; }
    public void setSpec(String spec) { this.spec = spec; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Long getUnitId() { return unitId; }
    public void setUnitId(Long unitId) { this.unitId = unitId; }
    public String getUnitName() { return unitName; }
    public void setUnitName(String unitName) { this.unitName = unitName; }
    public BigDecimal getPlanQty() { return planQty; }
    public void setPlanQty(BigDecimal planQty) { this.planQty = planQty; }
    public BigDecimal getActualQty() { return actualQty; }
    public void setActualQty(BigDecimal actualQty) { this.actualQty = actualQty; }
    public BigDecimal getGoodQty() { return goodQty; }
    public void setGoodQty(BigDecimal goodQty) { this.goodQty = goodQty; }
    public BigDecimal getLossQty() { return lossQty; }
    public void setLossQty(BigDecimal lossQty) { this.lossQty = lossQty; }
    public BigDecimal getLossRate() { return lossRate; }
    public void setLossRate(BigDecimal lossRate) { this.lossRate = lossRate; }
    public String getWorkshop() { return workshop; }
    public void setWorkshop(String workshop) { this.workshop = workshop; }
    public Long getWorkshopId() { return workshopId; }
    public void setWorkshopId(Long workshopId) { this.workshopId = workshopId; }
    public Long getWarehouseId() { return warehouseId; }
    public void setWarehouseId(Long warehouseId) { this.warehouseId = warehouseId; }
    public String getLeader() { return leader; }
    public void setLeader(String leader) { this.leader = leader; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    public BigDecimal getCostAmount() { return costAmount; }
    public void setCostAmount(BigDecimal costAmount) { this.costAmount = costAmount; }
    public String getBillStatus() { return billStatus; }
    public void setBillStatus(String billStatus) { this.billStatus = billStatus; }
    public String getSourceBillType() { return sourceBillType; }
    public void setSourceBillType(String sourceBillType) { this.sourceBillType = sourceBillType; }
    public Long getSourceBillId() { return sourceBillId; }
    public void setSourceBillId(Long sourceBillId) { this.sourceBillId = sourceBillId; }
    public String getSourceBillNo() { return sourceBillNo; }
    public void setSourceBillNo(String sourceBillNo) { this.sourceBillNo = sourceBillNo; }
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

    // transient 字段 getter/setter
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
    /** 模板字段: thickness = 长度 */
    public BigDecimal getThickness() { return pThickness; }
    public void setThickness(BigDecimal thickness) { this.pThickness = thickness; }
    public BigDecimal getWidth() { return pWidth; }
    public void setWidth(BigDecimal width) { this.pWidth = width; }
    /** 模板字段: density = 厚度 */
    public BigDecimal getDensity() { return pDensity; }
    public void setDensity(BigDecimal density) { this.pDensity = density; }
    /** 模板字段: gramWeight = 克重 */
    public BigDecimal getGramWeight() { return pGramWeight; }
    public void setGramWeight(BigDecimal gramWeight) { this.pGramWeight = gramWeight; }
    public String getMaterial() { return pMaterial; }
    public void setMaterial(String material) { this.pMaterial = material; }
    public String getBomRemark() { return bomRemark; }
    public void setBomRemark(String bomRemark) { this.bomRemark = bomRemark; }
    public List<PrdRequisitionDetail> getRequisitionDetails() { return requisitionDetails; }
    public void setRequisitionDetails(List<PrdRequisitionDetail> requisitionDetails) { this.requisitionDetails = requisitionDetails; }
}
