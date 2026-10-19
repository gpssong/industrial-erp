package com.industrial.erp.modules.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * v1.1.80 MES — 批次/卷号主数据 (RAW原料 / SEMI半成品卷 / FG成品).
 *
 * <p>{@code batch_no} 是批次唯一标识 (与 inv_stock 上的自由文本 batch_no 同名同值,
 * 但此处是「一等公民」实体, 带类型/来源/状态)。
 *
 * <p>登记时机 (用户决策「领料时才登记」):
 * <ul>
 *   <li>RAW 原料批: {@code PrdRequisitionService.check()} 首次领用到某 batch_no 时自动登记 (不碰采购入库)</li>
 *   <li>FG 成品批: {@code PrdFinishedInService.check()} 成品入库时登记 (batch_no = prd_finished_in.batch_no)</li>
 *   <li>SEMI 半成品卷: 一期建表但二期才写 (膜卷 inv_cut_process 产出卷)</li>
 * </ul>
 */
@TableName("mes_batch")
public class MesBatch {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 批次/卷号 (唯一标识, 与 inv_stock.batch_no 同值) */
    private String batchNo;
    /** RAW原料 / SEMI半成品卷 / FG成品 */
    private String batchType;
    /** 对应产品 (原料产品/半成品/成品) */
    private Long productId;
    /** RAW 来源供应商 */
    private Long supplierId;
    /** RAW 来源采购入库单号 (追溯) */
    private String receiptBillNo;
    /** SEMI/FG 来源生产单号 */
    private String sourcePrdOrderNo;
    /** SEMI 产生工序 (吹膜/分切...) */
    private String processName;
    /** 膜卷卷号 */
    private String rollNo;
    /** ACTIVE / FROZEN / SCRAP */
    private String status;
    /** v1.1.90+: 锁定原因 (FROZEN 时写入 "QMS-FAIL: <原因>") */
    private String holdReason;
    /** v1.1.90+: 触发冻结的检验单 (mes_quality_inspection.id) */
    private Long frozenInspectionId;
    /** 数量 (主单位) */
    private BigDecimal qty;
    private Long unitId;
    private LocalDate productionDate;
    private String remark;
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
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public String getBatchType() { return batchType; }
    public void setBatchType(String batchType) { this.batchType = batchType; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public Long getSupplierId() { return supplierId; }
    public void setSupplierId(Long supplierId) { this.supplierId = supplierId; }
    public String getReceiptBillNo() { return receiptBillNo; }
    public void setReceiptBillNo(String receiptBillNo) { this.receiptBillNo = receiptBillNo; }
    public String getSourcePrdOrderNo() { return sourcePrdOrderNo; }
    public void setSourcePrdOrderNo(String sourcePrdOrderNo) { this.sourcePrdOrderNo = sourcePrdOrderNo; }
    public String getProcessName() { return processName; }
    public void setProcessName(String processName) { this.processName = processName; }
    public String getRollNo() { return rollNo; }
    public void setRollNo(String rollNo) { this.rollNo = rollNo; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getHoldReason() { return holdReason; }
    public void setHoldReason(String holdReason) { this.holdReason = holdReason; }
    public Long getFrozenInspectionId() { return frozenInspectionId; }
    public void setFrozenInspectionId(Long frozenInspectionId) { this.frozenInspectionId = frozenInspectionId; }
    public BigDecimal getQty() { return qty; }
    public void setQty(BigDecimal qty) { this.qty = qty; }
    public Long getUnitId() { return unitId; }
    public void setUnitId(Long unitId) { this.unitId = unitId; }
    public LocalDate getProductionDate() { return productionDate; }
    public void setProductionDate(LocalDate productionDate) { this.productionDate = productionDate; }
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
