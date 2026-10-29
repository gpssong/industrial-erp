package com.industrial.erp.modules.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * v1.1.132 XK16-204 — 开工确认台账.
 *
 * <p>App 车间现场点「开工」时写入, 一行 = 一次开工确认: 生产环境是否合格 +
 * 自动生成的 2 份 {@code xk_env_check} 记录 id + 吹膜投料原料批号 + 批号照片.
 *
 * <p>为什么单开一张表: {@code prd_process} 没有承载批号/照片的字段, 把批号塞进
 * {@code remark} 会让 XK 审计取数依赖自由文本; 原料批与流向仍走 {@code mes_batch}
 * / {@code mes_batch_link}, 本表只记「谁在什么时候确认了什么」。
 */
@TableName("mes_work_start_confirm")
public class MesWorkStartConfirm {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 开工确认单号 (WS+yyyyMMdd+序号) */
    private String confirmNo;
    private Long prdOrderId;
    private String prdOrderNo;
    /** BLOW 吹膜开工 / BAG 制袋开工 */
    private String stage;
    /** 吹膜车间 / 制袋车间 */
    private String workshop;
    /** BAG 开工对应的工序实例 */
    private Long prdProcessId;
    /** 生产环境是否确认合格 (0 = 不合格, 禁止开工) */
    private Integer envQualified;
    /** 自动生成的 2 份 xk_env_check id, 逗号分隔 */
    private String envCheckIds;
    /** 投料原料批号, 逗号分隔 */
    private String rawBatchNos;
    /** 原料批号照片 URL (JSON 数组字符串) */
    private String photoUrls;
    /** 制袋开工自动领用半成品生成的领料单 */
    private Long requisitionId;
    private String requisitionNo;
    private Long confirmBy;
    private String confirmName;
    private LocalDateTime confirmTime;
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
    public String getConfirmNo() { return confirmNo; }
    public void setConfirmNo(String confirmNo) { this.confirmNo = confirmNo; }
    public Long getPrdOrderId() { return prdOrderId; }
    public void setPrdOrderId(Long prdOrderId) { this.prdOrderId = prdOrderId; }
    public String getPrdOrderNo() { return prdOrderNo; }
    public void setPrdOrderNo(String prdOrderNo) { this.prdOrderNo = prdOrderNo; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public String getWorkshop() { return workshop; }
    public void setWorkshop(String workshop) { this.workshop = workshop; }
    public Long getPrdProcessId() { return prdProcessId; }
    public void setPrdProcessId(Long prdProcessId) { this.prdProcessId = prdProcessId; }
    public Integer getEnvQualified() { return envQualified; }
    public void setEnvQualified(Integer envQualified) { this.envQualified = envQualified; }
    public String getEnvCheckIds() { return envCheckIds; }
    public void setEnvCheckIds(String envCheckIds) { this.envCheckIds = envCheckIds; }
    public String getRawBatchNos() { return rawBatchNos; }
    public void setRawBatchNos(String rawBatchNos) { this.rawBatchNos = rawBatchNos; }
    public String getPhotoUrls() { return photoUrls; }
    public void setPhotoUrls(String photoUrls) { this.photoUrls = photoUrls; }
    public Long getRequisitionId() { return requisitionId; }
    public void setRequisitionId(Long requisitionId) { this.requisitionId = requisitionId; }
    public String getRequisitionNo() { return requisitionNo; }
    public void setRequisitionNo(String requisitionNo) { this.requisitionNo = requisitionNo; }
    public Long getConfirmBy() { return confirmBy; }
    public void setConfirmBy(Long confirmBy) { this.confirmBy = confirmBy; }
    public String getConfirmName() { return confirmName; }
    public void setConfirmName(String confirmName) { this.confirmName = confirmName; }
    public LocalDateTime getConfirmTime() { return confirmTime; }
    public void setConfirmTime(LocalDateTime confirmTime) { this.confirmTime = confirmTime; }
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
