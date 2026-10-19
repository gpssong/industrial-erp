package com.industrial.erp.modules.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * v1.1.80 MES — 工序实例 (启用 sql/06 的 prd_process 死表).
 *
 * <p>开生产单 {@code PrdOrderService.release()} 时, 按产品默认工序链
 * ({@code mes_process_def.is_default=1}) 展开 N 行 prd_process, 每行 status=未开工。
 * 工人扫码报工时由 {@code MesReportService.reportWork()} 更新对应行。
 *
 * <p>{@code status} 语义 (MES 一期新增, 原 sql/06 的 TINYINT 默认 1):
 * <pre>
 *   0 = 未开工   (UNSTARTED, 开单展开后初始态)
 *   1 = 进行中   (IN_PROGRESS, 已报过工但未完成)
 *   2 = 完成     (FINISHED, 合格数达 plan 或人工标记完成)
 *   3 = 回退     (ROLLED_BACK, 反报工)
 * </pre>
 */
@TableName("prd_process")
public class PrdProcess {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long prdOrderId;
    private String prdOrderNo;
    private Integer processNo;
    /** 展开自哪条 mes_process_def (开单回填, 可空=手工工序) */
    private Long fromDefId;
    private String processName;
    /** v1.1.90+: 开单时使用的工艺参数版本 (mes_process_param snapshot) */
    private String processParamVersion;
    private String workshop;
    private String operator;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private BigDecimal planQty;
    private BigDecimal actualQty;
    private BigDecimal goodQty;
    private BigDecimal lossQty;
    private BigDecimal costAmount;
    /** 0未开工/1进行中/2完成/3回退 */
    private Integer status;
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
    public Long getPrdOrderId() { return prdOrderId; }
    public void setPrdOrderId(Long prdOrderId) { this.prdOrderId = prdOrderId; }
    public String getPrdOrderNo() { return prdOrderNo; }
    public void setPrdOrderNo(String prdOrderNo) { this.prdOrderNo = prdOrderNo; }
    public Integer getProcessNo() { return processNo; }
    public void setProcessNo(Integer processNo) { this.processNo = processNo; }
    public Long getFromDefId() { return fromDefId; }
    public void setFromDefId(Long fromDefId) { this.fromDefId = fromDefId; }
    public String getProcessParamVersion() { return processParamVersion; }
    public void setProcessParamVersion(String processParamVersion) { this.processParamVersion = processParamVersion; }
    public String getProcessName() { return processName; }
    public void setProcessName(String processName) { this.processName = processName; }
    public String getWorkshop() { return workshop; }
    public void setWorkshop(String workshop) { this.workshop = workshop; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
    public BigDecimal getPlanQty() { return planQty; }
    public void setPlanQty(BigDecimal planQty) { this.planQty = planQty; }
    public BigDecimal getActualQty() { return actualQty; }
    public void setActualQty(BigDecimal actualQty) { this.actualQty = actualQty; }
    public BigDecimal getGoodQty() { return goodQty; }
    public void setGoodQty(BigDecimal goodQty) { this.goodQty = goodQty; }
    public BigDecimal getLossQty() { return lossQty; }
    public void setLossQty(BigDecimal lossQty) { this.lossQty = lossQty; }
    public BigDecimal getCostAmount() { return costAmount; }
    public void setCostAmount(BigDecimal costAmount) { this.costAmount = costAmount; }
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
