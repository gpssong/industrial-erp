package com.industrial.erp.modules.base.label;

/**
 * 取值/格式化之后的元素 —— **唯一的中间接口** (docs/32 §4 约束 1)。
 *
 * <p>以后加第三家渲染器（CPCL / 爱印云 / ZPL）只写第三个类，主数据、模板、元素模型、日志、页面都不动。
 */
public class ResolvedElement {
    private String type;
    private int x;
    private int y;
    /** 解析后的最终文本；barcode/qrcode 时这就是要编码的内容。 */
    private String content;
    private int fontSize;
    /** 模板原始声明，0 表示按 fontSize 回落，不存计算后的有效值。 */
    private int mulX;
    private int mulY;
    private int cell;
    private String align;
    private boolean bold;
    private int w;
    private int h;
    /** required 且取值为空 —— 预览标红，但不阻断打印 (docs/32 §5)。 */
    private boolean missing;
    /** 取值后按实测字库量宽、按**拆行后的实际盒**判越界（预览标橙，与保存闸同一个函数）。 */
    private boolean overflow;
    private String field;
    /**
     * 只服务屏显预览：zxing 出的 PNG data URL（<b>PNG，不是 SVG</b> —— 键名沿用 A4 元素模型 /
     * docs/32 §6.3 的对外契约，A9→A11→A12→B 系列四处都按 `svg` 取值，改名要单独一轮，不是 A9 顺手）。
     * 打印通道**忽略**它 (docs/32 §4 约束 4)。
     */
    private String svg;

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public int getX() { return x; }
    public void setX(int x) { this.x = x; }
    public int getY() { return y; }
    public void setY(int y) { this.y = y; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public int getFontSize() { return fontSize; }
    public void setFontSize(int fontSize) { this.fontSize = fontSize; }
    public int getMulX() { return mulX; }
    public void setMulX(int mulX) { this.mulX = mulX; }
    public int getMulY() { return mulY; }
    public void setMulY(int mulY) { this.mulY = mulY; }
    public int getCell() { return cell; }
    public void setCell(int cell) { this.cell = cell; }
    public String getAlign() { return align; }
    public void setAlign(String align) { this.align = align; }
    public boolean isBold() { return bold; }
    public void setBold(boolean bold) { this.bold = bold; }
    public int getW() { return w; }
    public void setW(int w) { this.w = w; }
    public int getH() { return h; }
    public void setH(int h) { this.h = h; }
    public boolean isMissing() { return missing; }
    public void setMissing(boolean missing) { this.missing = missing; }
    public boolean isOverflow() { return overflow; }
    public void setOverflow(boolean overflow) { this.overflow = overflow; }
    public String getField() { return field; }
    public void setField(String field) { this.field = field; }
    public String getSvg() { return svg; }
    public void setSvg(String svg) { this.svg = svg; }
}
