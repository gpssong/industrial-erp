package com.industrial.erp.modules.base.label;

import java.util.List;

/**
 * resolve 的独立响应 (docs/36 §0.4)：**不复用 {@link RenderResult}** —— 旧 `/preview` 的对外外形一个字节
 * 不许改 (docs/36 R14)，而画布要的元素索引、逐元素问题与可点选外框都是编辑期概念 (R10)，
 * 不该混进打印通道的中间载体。
 *
 * <p>响应里**没有** `rotate`，`issues` 里也**没有** `level`：P1 只有 mulX/mulY/cell 三个新键
 * （全局约束 2），橙/红来自元素自己的 overflow/missing 两个既有布尔，与 issues 数组分列（全局约束 8）。
 */
public record LabelResolveResult(int widthDots, int heightDots, List<ValidationIssue> issues,
                                 List<Label> labels) {

    /** 一张标签 = 一组元素盒子：上下文给几行就回几张（原料垛标/箱标按明细行展开）。 */
    public record Label(List<Element> elements) {}

    /**
     * @param index         元素在入参 JSON 数组里的下标（从 0 起）—— issues 与画布行的对应关系全靠它
     * @param mulX/mulY/cell **有效**倍率与二维码模块边长：前端不许自己再推一遍字库倍率（全局约束 5）；
     *                       cell 只对 qrcode 有意义，其余类型给 0
     * @param displayLabel  取值为空时画布显示的字段标签。它**不是**要打印的内容，content 仍是空串
     * @param bold          打印时发第二条 x+1；画布据此做轻微重复绘制，**不据 CSS font-weight 改盒宽**
     * @param segments      拆行之后的实际盒子，与 TSPL 发的 TEXT 行同一个出口
     * @param displayBounds 可点选/可拖的外框；码图那两类是示意框（estimated=true），不参与保存越界判定
     * @param issues        全局 issues 里 index 相同的那几条原文（空值标记不在这里，在 missing）
     */
    public record Element(int index, String type, int x, int y, String content, String field,
                          int mulX, int mulY, int cell, boolean bold, boolean overflow, boolean missing,
                          String displayLabel, String svg, List<LabelLayout.Box> segments,
                          DisplayBounds displayBounds, List<String> issues) {}

    /** 可点选/可拖的外框（点）。estimated=true 表示这是示意尺寸，不是打印机按内容自适应的实际尺寸 (R11)。 */
    public record DisplayBounds(int x, int y, int w, int h, boolean estimated, String hint) {}
}
