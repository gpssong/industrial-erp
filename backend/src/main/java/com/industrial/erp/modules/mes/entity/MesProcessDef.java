package com.industrial.erp.modules.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * v1.1.80 MES — 工序定义 (按产品/BOM 定制工序链).
 *
 * <p>一个产品可配多条工序链 (吹膜→印刷→复合→分切→制袋→包装), {@code isDefault=1}
 * 的链在开生产单 {@code PrdOrderService.release()} 时自动展开成该单的工序实例
 * ({@code prd_process})。{@code product_id} 可空 = 通用工序 (所有产品共享)。
 */
@TableName("mes_process_def")
public class MesProcessDef {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 所属成品产品 (可空=通用工序) */
    private Long productId;
    /** 所属 BOM (可空) */
    private Long bomId;
    /** 工序顺序 (链内序号, 1..N) */
    private Integer processNo;
    /** 工序名: 吹膜/印刷/复合/分切/制袋/包装... */
    private String processName;
    /** 车间/工位 */
    private String workshop;
    /** 该产品的默认工序链 (开单自动展开) */
    private Integer isDefault;
    /** v1.1.90+: 工艺版本号, 默认 '1.0', SNAPSHOT 时写入 prd_process.process_param_version */
    private String version;
    /** v1.1.90+: 生效日期 */
    private LocalDate effectiveDate;
    /** v1.1.90+: 是否关键工序 (超限自动 NG, 留作 v1.1.91 强制规则) */
    private Integer isCritical;
    private Integer status;
    private String remark;
    @TableField(fill = FieldFill.INSERT)
    private Long createBy;
    @TableField(fill = FieldFill.INSERT)
    private java.time.LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private java.time.LocalDateTime updateTime;
    @TableLogic
    private Integer deleted = 0;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public Long getBomId() { return bomId; }
    public void setBomId(Long bomId) { this.bomId = bomId; }
    public Integer getProcessNo() { return processNo; }
    public void setProcessNo(Integer processNo) { this.processNo = processNo; }
    public String getProcessName() { return processName; }
    public void setProcessName(String processName) { this.processName = processName; }
    public String getWorkshop() { return workshop; }
    public void setWorkshop(String workshop) { this.workshop = workshop; }
    public Integer getIsDefault() { return isDefault; }
    public void setIsDefault(Integer isDefault) { this.isDefault = isDefault; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public LocalDate getEffectiveDate() { return effectiveDate; }
    public void setEffectiveDate(LocalDate effectiveDate) { this.effectiveDate = effectiveDate; }
    public Integer getIsCritical() { return isCritical; }
    public void setIsCritical(Integer isCritical) { this.isCritical = isCritical; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public Long getCreateBy() { return createBy; }
    public void setCreateBy(Long createBy) { this.createBy = createBy; }
    public java.time.LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(java.time.LocalDateTime createTime) { this.createTime = createTime; }
    public Long getUpdateBy() { return updateBy; }
    public void setUpdateBy(Long updateBy) { this.updateBy = updateBy; }
    public java.time.LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(java.time.LocalDateTime updateTime) { this.updateTime = updateTime; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
}
