package com.industrial.erp.modules.production.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * v1.1.160 — 生产工单原料明细 (BOM 逐行 + 每行出库仓/库存批号).
 * 新建工单时按 BOM 展开成行, 每行选定出库仓库后从 inv_stock 挑批次;
 * 开工 release() 按 productId 把 batch_no/warehouse_id 回填到自动生成的领料明细行.
 */
@TableName("prd_order_material")
public class PrdOrderMaterial {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long prdOrderId;
    private Integer lineNo;
    private Long bomDetailId;
    private String materialType;
    private Long productId;
    private String productCode;
    private String productName;
    private String spec;
    private Long unitId;
    private String unitName;
    private BigDecimal reqQty;
    private Long warehouseId;
    private String batchNo;
    private String remark;

    /** 该行选定批次的可用库存量 (读接口注入, 不入库): 前端展示"库存 X kg"提示是否够领. */
    @TableField(exist = false)
    private transient BigDecimal availableQty;

    /** BOM 配比份数 (仅 material-suggest 建议接口回填, 不入库): 前端展示「配比 X 份 / 损耗 Y%」口径. */
    @TableField(exist = false)
    private transient BigDecimal bomBaseQty;

    /** BOM 该行损耗率 % (仅建议接口回填, 不入库). */
    @TableField(exist = false)
    private transient BigDecimal lossRate;

    /** 该原料当前可用库存批次候选 (仅建议接口回填, 不入库): 跨仓库, available_qty > 0. */
    @TableField(exist = false)
    private transient java.util.List<com.industrial.erp.modules.production.vo.StockBatchOptionVO> batches;

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

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPrdOrderId() { return prdOrderId; }
    public void setPrdOrderId(Long prdOrderId) { this.prdOrderId = prdOrderId; }
    public Integer getLineNo() { return lineNo; }
    public void setLineNo(Integer lineNo) { this.lineNo = lineNo; }
    public Long getBomDetailId() { return bomDetailId; }
    public void setBomDetailId(Long bomDetailId) { this.bomDetailId = bomDetailId; }
    public String getMaterialType() { return materialType; }
    public void setMaterialType(String materialType) { this.materialType = materialType; }
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
    public BigDecimal getReqQty() { return reqQty; }
    public void setReqQty(BigDecimal reqQty) { this.reqQty = reqQty; }
    public Long getWarehouseId() { return warehouseId; }
    public void setWarehouseId(Long warehouseId) { this.warehouseId = warehouseId; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public BigDecimal getAvailableQty() { return availableQty; }
    public void setAvailableQty(BigDecimal availableQty) { this.availableQty = availableQty; }
    public BigDecimal getBomBaseQty() { return bomBaseQty; }
    public void setBomBaseQty(BigDecimal bomBaseQty) { this.bomBaseQty = bomBaseQty; }
    public BigDecimal getLossRate() { return lossRate; }
    public void setLossRate(BigDecimal lossRate) { this.lossRate = lossRate; }
    public java.util.List<com.industrial.erp.modules.production.vo.StockBatchOptionVO> getBatches() { return batches; }
    public void setBatches(java.util.List<com.industrial.erp.modules.production.vo.StockBatchOptionVO> batches) { this.batches = batches; }
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
