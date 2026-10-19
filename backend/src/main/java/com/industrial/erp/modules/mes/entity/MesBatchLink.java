package com.industrial.erp.modules.mes.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * v1.1.80 MES — 批次流向绑定 (料→半成品卷→成品→客户).
 *
 * <p>核心追溯表: 一条记录 = 「上游批次 ({@code parentBatchNo}) 的 N 个单位, 在
 * {@code billType}/{@code billId} 单据里, 流入了下游批次 ({@code childBatchNo})」。
 * <ul>
 *   <li>追溯「成品用了哪批料」: 沿 {@code child→parent} 递归上溯 (FG→SEMI→RAW)</li>
 *   <li>追溯「这批料流到哪个客户」: 沿 {@code parent→child} 下探到 FG 批次 + {@code customerId}</li>
 * </ul>
 * 追加型流水, 无软删 (报工/领料/成品入库触发写入), 无 @Version (只 insert 不 update).
 */
@TableName("mes_batch_link")
public class MesBatchLink {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 下游批次/卷 (半成品卷或成品) */
    private String childBatchNo;
    /** 上游批次 (原料批或半成品卷) */
    private String parentBatchNo;
    /** 本次用量 (主单位) */
    private BigDecimal qty;
    private Long prdOrderId;
    /** 哪个工序产生此流向 (可空) */
    private Long prdProcessId;
    /** ISSUE领料 / CUT切 / REWIND复卷 / SLIT分切 / FG_IN成品入库 */
    private String billType;
    /** 关联 prd_requisition / inv_cut_process id */
    private Long billId;
    private String billNo;
    /** 成品最终流向客户 (追溯出口) */
    private Long customerId;
    /** v1.1.90+: 关联 mes_quality_inspection.id (锁定/放行的上游单据, disposition 行专用) */
    private Long inspectionId;
    private Long createBy;
    private LocalDateTime createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getChildBatchNo() { return childBatchNo; }
    public void setChildBatchNo(String childBatchNo) { this.childBatchNo = childBatchNo; }
    public String getParentBatchNo() { return parentBatchNo; }
    public void setParentBatchNo(String parentBatchNo) { this.parentBatchNo = parentBatchNo; }
    public BigDecimal getQty() { return qty; }
    public void setQty(BigDecimal qty) { this.qty = qty; }
    public Long getPrdOrderId() { return prdOrderId; }
    public void setPrdOrderId(Long prdOrderId) { this.prdOrderId = prdOrderId; }
    public Long getPrdProcessId() { return prdProcessId; }
    public void setPrdProcessId(Long prdProcessId) { this.prdProcessId = prdProcessId; }
    public String getBillType() { return billType; }
    public void setBillType(String billType) { this.billType = billType; }
    public Long getBillId() { return billId; }
    public void setBillId(Long billId) { this.billId = billId; }
    public String getBillNo() { return billNo; }
    public void setBillNo(String billNo) { this.billNo = billNo; }
    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }
    public Long getInspectionId() { return inspectionId; }
    public void setInspectionId(Long inspectionId) { this.inspectionId = inspectionId; }
    public Long getCreateBy() { return createBy; }
    public void setCreateBy(Long createBy) { this.createBy = createBy; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
