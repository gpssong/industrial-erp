package com.industrial.erp.modules.production.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@TableName("prd_order")
public class PrdOrder {
    // ===== 商品规格属性 (transient, JOIN 注入) =====
    // v1.1.116 必须显式 @JsonProperty("pXxx"), 不能靠 getPThickness() 的推导。
    // Jackson legacy 命名规则: getPThickness() 去 get 得 "PThickness", 因为首字母 P 后跟大写 T,
    // 被当成 "P + Thickness" 驼峰切分 → 序列化成 "pthickness" (小写 p), 而前端读的是 "pThickness" → undefined。
    // 无前缀的 getThickness() 正常输出 "thickness", 所以同一份 JSON 里两套 key 都在, 前端读的 pXxx 恒为空。
    // 影响: 成品规格属性 7 字段 + 食品合规 5 字段全部显示 "—" (数据在库里, 只是 key 对不上)。
    @TableField(exist = false)
    @JsonProperty("pThickness")
    private transient BigDecimal pThickness;
    @TableField(exist = false)
    @JsonProperty("pWidth")
    private transient BigDecimal pWidth;
    @TableField(exist = false)
    @JsonProperty("pDensity")
    private transient BigDecimal pDensity;
    @TableField(exist = false)
    @JsonProperty("pGramWeight")
    private transient BigDecimal pGramWeight;
    @TableField(exist = false)
    @JsonProperty("pMaterial")
    private transient String pMaterial;
    @TableField(exist = false)
    @JsonProperty("pColorNo")
    private transient String pColorNo;
    // ===== v1.1.100 食品合规 (transient, 从 base_product JOIN 注入, 食品级成品回显) =====
    @TableField(exist = false)
    @JsonProperty("pFoodExecStd")
    private transient String pFoodExecStd;
    @TableField(exist = false)
    @JsonProperty("pFoodFilingNo")
    private transient String pFoodFilingNo;
    @TableField(exist = false)
    @JsonProperty("pFoodUseDesc")
    private transient String pFoodUseDesc;
    @TableField(exist = false)
    @JsonProperty("pFoodMigrationLimit")
    private transient java.math.BigDecimal pFoodMigrationLimit;
    @TableField(exist = false)
    @JsonProperty("pIsFoodGrade")
    private transient Integer pIsFoodGrade;
    @TableField(exist = false)
    private transient String bomRemark;

    /** v1.1.133 总重量 (kg) = 计划数量(件) × 成品克重(g/件) ÷ 1000, 展示口径保留 3 位小数 */
    @TableField(exist = false)
    private transient BigDecimal totalWeightKg;

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
    /** 原料批号 (v1.1.99 新增, 食品级成品 XK16-204 必填) */
    private String batchNo;
    private String leader;
    private LocalDate startDate;
    private LocalDate endDate;
    private BigDecimal costAmount;
    private String billStatus;
    private String sourceBillType;
    private Long sourceBillId;
    private String sourceBillNo;
    private String remark;
    /** v1.1.102 XK — 开工操作人 (sys_user.id, 吹膜工在 App 端点「开工」时写入) */
    private Long releaseBy;
    /** v1.1.102 XK — 开工操作人姓名 (sys_user.username 快照, 展示用) */
    private String releaseName;
    @TableField(fill = FieldFill.INSERT)

    private Long createBy;

    @TableField(fill = FieldFill.INSERT)

    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)

    private Long updateBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)

    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted = 0;

    /** 操作员姓名 (list page 注入, 不入库) */
    @TableField(exist = false)
    private transient String createByName;

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
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
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
    public Long getReleaseBy() { return releaseBy; }
    public void setReleaseBy(Long releaseBy) { this.releaseBy = releaseBy; }
    public String getReleaseName() { return releaseName; }
    public void setReleaseName(String releaseName) { this.releaseName = releaseName; }
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
    /** 色号, 从 base_product.colorNo JOIN 注入 (打印模板用) */
    public String getPColorNo() { return pColorNo; }
    public void setPColorNo(String pColorNo) { this.pColorNo = pColorNo; }
    public String getPFoodExecStd() { return pFoodExecStd; }
    public void setPFoodExecStd(String pFoodExecStd) { this.pFoodExecStd = pFoodExecStd; }
    public String getPFoodFilingNo() { return pFoodFilingNo; }
    public void setPFoodFilingNo(String pFoodFilingNo) { this.pFoodFilingNo = pFoodFilingNo; }
    public String getPFoodUseDesc() { return pFoodUseDesc; }
    public void setPFoodUseDesc(String pFoodUseDesc) { this.pFoodUseDesc = pFoodUseDesc; }
    public java.math.BigDecimal getPFoodMigrationLimit() { return pFoodMigrationLimit; }
    public void setPFoodMigrationLimit(java.math.BigDecimal pFoodMigrationLimit) { this.pFoodMigrationLimit = pFoodMigrationLimit; }
    public Integer getPIsFoodGrade() { return pIsFoodGrade; }
    public void setPIsFoodGrade(Integer pIsFoodGrade) { this.pIsFoodGrade = pIsFoodGrade; }
    /** 模板字段: colorNo = 色号 */
    public String getColorNo() { return pColorNo; }
    public void setColorNo(String colorNo) { this.pColorNo = colorNo; }
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
    public BigDecimal getTotalWeightKg() { return totalWeightKg; }
    public void setTotalWeightKg(BigDecimal totalWeightKg) { this.totalWeightKg = totalWeightKg; }
    public List<PrdRequisitionDetail> getRequisitionDetails() { return requisitionDetails; }
    public void setRequisitionDetails(List<PrdRequisitionDetail> requisitionDetails) { this.requisitionDetails = requisitionDetails; }

    /** 操作员姓名 (list page 注入) */
    public String getCreateByName() { return createByName; }
    public void setCreateByName(String createByName) { this.createByName = createByName; }
}
