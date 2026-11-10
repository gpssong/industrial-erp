package com.industrial.erp.modules.base.label;

import java.time.LocalDateTime;

/**
 * v1.1.168 历史摘要行 (docs/35 §7.3, docs/36 Task 7 Step 2)。
 *
 * <p>**刻意没有 elements 字段**：列表端点只回「这一版多大、几个元素」，20 版 × 全量 JSON 是白扔的
 * 带宽；点开某版再走 historyOne 拉全量。{@code elementsBytes} 是库里 OCTET_LENGTH 算出的
 * UTF-8 字节数（不是 String.length），{@code elementCount} 由 JSON_LENGTH 给出。
 *
 * <p>{@code createByName} 不是列，是 {@code CreateByNameInjector} 用 {@code createBy} 批量换来的
 * 显示名；real_name 为空回落 username，都取不到就留 null —— UI 还能显示 createBy，不伪造姓名。
 * 普通 DTO + 显式 getter/setter：MyBatis resultType 与 Jackson 都走 bean 属性。
 */
public class LabelHistorySummary {
    private Integer seq;
    private Long createBy;
    private String createByName;
    private LocalDateTime createTime;
    private Long elementsBytes;
    private Integer elementCount;

    public Integer getSeq() { return seq; }
    public void setSeq(Integer seq) { this.seq = seq; }
    public Long getCreateBy() { return createBy; }
    public void setCreateBy(Long createBy) { this.createBy = createBy; }
    public String getCreateByName() { return createByName; }
    public void setCreateByName(String createByName) { this.createByName = createByName; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public Long getElementsBytes() { return elementsBytes; }
    public void setElementsBytes(Long elementsBytes) { this.elementsBytes = elementsBytes; }
    public Integer getElementCount() { return elementCount; }
    public void setElementCount(Integer elementCount) { this.elementCount = elementCount; }
}
