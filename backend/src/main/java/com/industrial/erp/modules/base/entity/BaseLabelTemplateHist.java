package com.industrial.erp.modules.base.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * v1.1.168 标签模板历史快照 (docs/35 §快照, docs/36 Task 6)。
 *
 * <p>一行 = **一次覆盖之前的那一版**：{@link BaseLabelTemplate} 被 update 改掉时，先把旧行的
 * 纸头与元素抄到这里，再写新行。所以这张表回答的是「当时把什么改掉了」，不是「谁动过数据」——
 * 审计语义由 {@code sys_oper_log} 负责，别把这两件事混在一起。
 *
 * <p>**列是刻意收窄的**（计划全局约束 12）：没有 template_code / template_name / is_default /
 * status / remark。改这些不算改标签内容，回滚时把它们一起拽回去会顺带把默认位和启停状态复原，
 * 那是比丢一版排版更糟的副作用。同样没有 deleted / update_* / tenant_id：
 * 历史只有「留够 20 版」和「裁掉更旧的」两种状态，软删一层只会让 {@code seq} 唯一性更难自证。
 *
 * <p>坐标单位与主表一致（点），{@code dpi} 一起进快照 —— 同一份 elements 在 203 与 300 dpi 下
 * 画出来的东西不同，少了 dpi 这版历史就没法原样重放。
 */
@TableName("base_label_template_hist")
public class BaseLabelTemplateHist {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 主表 base_label_template.id；seq 的作用域就是它 */
    private Long templateId;
    /** 每个 templateId 从 1 起连续递增；序号在锁内取当前最大值 +1，不用普通聚合快照读 */
    private Integer seq;
    private String bizType;
    private Integer widthMm;
    private Integer heightMm;
    private Integer gapMm;
    private Integer dpi;
    /** NORMAL / REVERSE */
    private String direction;
    private Integer copies;
    /** 结构化元素数组 JSON 原文，与主表 elements 同形状 */
    private String elements;
    /** 本次覆盖的操作者，取 permService.getCurrentUserId()，不是主表旧行的 createBy */
    private Long createBy;
    /** 本次覆盖的时刻，不抄旧行的 createTime */
    private LocalDateTime createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public Integer getSeq() { return seq; }
    public void setSeq(Integer seq) { this.seq = seq; }
    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public Integer getWidthMm() { return widthMm; }
    public void setWidthMm(Integer widthMm) { this.widthMm = widthMm; }
    public Integer getHeightMm() { return heightMm; }
    public void setHeightMm(Integer heightMm) { this.heightMm = heightMm; }
    public Integer getGapMm() { return gapMm; }
    public void setGapMm(Integer gapMm) { this.gapMm = gapMm; }
    public Integer getDpi() { return dpi; }
    public void setDpi(Integer dpi) { this.dpi = dpi; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public Integer getCopies() { return copies; }
    public void setCopies(Integer copies) { this.copies = copies; }
    public String getElements() { return elements; }
    public void setElements(String elements) { this.elements = elements; }
    public Long getCreateBy() { return createBy; }
    public void setCreateBy(Long createBy) { this.createBy = createBy; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
