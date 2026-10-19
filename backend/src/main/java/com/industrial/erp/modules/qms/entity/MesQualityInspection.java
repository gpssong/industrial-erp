package com.industrial.erp.modules.qms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * v1.1.90 QMS — 质量检验单主表 (IQC 来料 / FAI 首件 / PAT 巡检 / OQC 出货 / OQC_RETURN 退货复检).
 *
 * <p>与业务单据的联动 (自动生成 DRAFT 草稿, 由触发方审核后变 CHECKED):
 * <ul>
 *   <li>采购入库 → IQC: {@code PurReceiptService.check()} 末调 {@code qms.createOnReceipt(receipt)}</li>
 *   <li>成品入库 → OQC: {@code PrdFinishedInService.check()} 末调 {@code qms.createOnFinish(finishedIn)}</li>
 *   <li>销售退货 → OQC_RETURN: {@code SalReturnService.check()} 末调 {@code qms.createOnReturn(salReturn)}</li>
 * </ul>
 *
 * <p>审核 (QMS controller /{id}/check) 时:
 * <ul>
 *   <li>{@code result=FAIL + disposition=FROZEN} → 关联 {@code mes_batch.status=FROZEN/frozen_inspection_id=this.id}</li>
 *   <li>{@code result=FAIL_REJECT} → 关联 {@code mes_batch.status=SCRAP}</li>
 *   <li>PASS / PASS_DISPOSITION → 不锁批, 仅放行 + 写 {@code mes_batch_link(billType='DISPOSITION')}</li>
 * </ul>
 *
 * <p>审计: 沿用 {@code McpAuditService} 教训 — 同步直写 {@code SysOperLogMapper.insert}
 * (高频 createOnReceipt 触发器也走同步, 不走 @Async).
 */
@TableName("mes_quality_inspection")
public class MesQualityInspection {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 单据号 (BILL_QI = "QI" 前缀, 由 BillNoGenerator 生成, 全局唯一 + soft-deleted 唯一) */
    private String billNo;
    /** 单据日期 */
    private LocalDate billDate;
    /** IQC / FAI / PAT / OQC / OQC_RETURN */
    private String inspectionType;
    /** PUR_RECEIPT / PRD_PROCESS / SAL_RETURN / SAL_DELIVERY (手工单可空, 触发器单自动填) */
    private String sourceBillType;
    /** 源单据 id (触发器自动填) */
    private Long sourceBillId;
    /** 源单据单号 (冗余, 列表不 JOIN) */
    private String sourceBillNo;
    /** 检验产品 */
    private Long productId;
    /** 冗余产品编码/名称/规格/型号 (录入时从 base_product 快照, 不实时 JOIN) */
    private String productCode;
    private String productName;
    private String spec;
    private String model;
    /** 关联批次 (mes_batch.batch_no, IQC/OQC 必填, FAI 可空) */
    private String batchNo;
    /** 供应商 (IQC 专用) */
    private Long supplierId;
    /** 客户 (OQC/OQC_RETURN 专用) */
    private Long customerId;
    /** 车间 (PAT 专用) */
    private String workshop;
    private BigDecimal sampleQty;
    private BigDecimal qualifiedQty;
    private BigDecimal unqualifiedQty;
    /** PASS / FAIL / HOLD / PASS_DISPOSITION / FAIL_REJECT */
    private String result;
    /** NONE / FROZEN / SCRAP / REWORK / SORT / CONCESSION */
    private String disposition;
    private String dispositionReason;
    /** 检验员 user_id (当前登录用户) */
    private Long inspector;
    /** 检验员姓名冗余 (列表不 JOIN sys_user) */
    private String inspectorName;
    /** v1.1.100 OQC 成品单 3 签 (检验/审核/批准) + 留样: 把 Print.vue mock 签字/留样真正落库 */
    private Long reviewer;
    private String reviewerName;
    private Long approver;
    private String approverName;
    private String retainNo;
    private String retainLoc;
    private java.time.LocalDate retainDueDate;
    private LocalDateTime checkTime;
    /** 检验照片 JSON 数组字符串 ("[\"url1\", \"url2\"]"), service 层用 JSONUtil 反序列化为 List<String> */
    private String photoUrls;
    private String remark;
    /** DRAFT / CHECKED */
    private String status;
    @Version
    private Integer version;
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
    public String getBillNo() { return billNo; }
    public void setBillNo(String billNo) { this.billNo = billNo; }
    public LocalDate getBillDate() { return billDate; }
    public void setBillDate(LocalDate billDate) { this.billDate = billDate; }
    public String getInspectionType() { return inspectionType; }
    public void setInspectionType(String inspectionType) { this.inspectionType = inspectionType; }
    public String getSourceBillType() { return sourceBillType; }
    public void setSourceBillType(String sourceBillType) { this.sourceBillType = sourceBillType; }
    public Long getSourceBillId() { return sourceBillId; }
    public void setSourceBillId(Long sourceBillId) { this.sourceBillId = sourceBillId; }
    public String getSourceBillNo() { return sourceBillNo; }
    public void setSourceBillNo(String sourceBillNo) { this.sourceBillNo = sourceBillNo; }
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
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public Long getSupplierId() { return supplierId; }
    public void setSupplierId(Long supplierId) { this.supplierId = supplierId; }
    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }
    public String getWorkshop() { return workshop; }
    public void setWorkshop(String workshop) { this.workshop = workshop; }
    public BigDecimal getSampleQty() { return sampleQty; }
    public void setSampleQty(BigDecimal sampleQty) { this.sampleQty = sampleQty; }
    public BigDecimal getQualifiedQty() { return qualifiedQty; }
    public void setQualifiedQty(BigDecimal qualifiedQty) { this.qualifiedQty = qualifiedQty; }
    public BigDecimal getUnqualifiedQty() { return unqualifiedQty; }
    public void setUnqualifiedQty(BigDecimal unqualifiedQty) { this.unqualifiedQty = unqualifiedQty; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getDisposition() { return disposition; }
    public void setDisposition(String disposition) { this.disposition = disposition; }
    public String getDispositionReason() { return dispositionReason; }
    public void setDispositionReason(String dispositionReason) { this.dispositionReason = dispositionReason; }
    public Long getInspector() { return inspector; }
    public void setInspector(Long inspector) { this.inspector = inspector; }
    public String getInspectorName() { return inspectorName; }
    public void setInspectorName(String inspectorName) { this.inspectorName = inspectorName; }
    public Long getReviewer() { return reviewer; }
    public void setReviewer(Long reviewer) { this.reviewer = reviewer; }
    public String getReviewerName() { return reviewerName; }
    public void setReviewerName(String reviewerName) { this.reviewerName = reviewerName; }
    public Long getApprover() { return approver; }
    public void setApprover(Long approver) { this.approver = approver; }
    public String getApproverName() { return approverName; }
    public void setApproverName(String approverName) { this.approverName = approverName; }
    public String getRetainNo() { return retainNo; }
    public void setRetainNo(String retainNo) { this.retainNo = retainNo; }
    public String getRetainLoc() { return retainLoc; }
    public void setRetainLoc(String retainLoc) { this.retainLoc = retainLoc; }
    public java.time.LocalDate getRetainDueDate() { return retainDueDate; }
    public void setRetainDueDate(java.time.LocalDate retainDueDate) { this.retainDueDate = retainDueDate; }
    public LocalDateTime getCheckTime() { return checkTime; }
    public void setCheckTime(LocalDateTime checkTime) { this.checkTime = checkTime; }
    public String getPhotoUrls() { return photoUrls; }
    public void setPhotoUrls(String photoUrls) { this.photoUrls = photoUrls; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
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
