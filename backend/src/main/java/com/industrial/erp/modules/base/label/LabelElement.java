package com.industrial.erp.modules.base.label;

/** elements JSON 里的一个元素 (docs/32 §3)。x/y/size/w/h 单位一律是**点**。 */
public class LabelElement {
    private String type;      // text | multiline | barcode | qrcode | line | rect
    private int x;
    private int y;
    private String text;      // 字面量
    private String field;     // 或从上下文取值的字段名
    // size 三种类型三种用法，别当成同一个「字号」：
    //   text/multiline = 字符点高（再换算成字库放大倍率）
    //   barcode        = 条码高度（没声明 h 时就是它，见 LabelGeometry#barcodeHeightDots）
    //   qrcode         = **名义字号**，不是模块边长。渲染器换算 cell = clamp(2,8, round(max(12,size)/6))，
    //                    所以 size 给到 24 才有 cell=4（约 10.5mm）；直接给 7 会兜到 cell=2、扫不出来。
    private int size;
    /** 声明值，0 沿用 size 推导；有效值由 LabelGeometry 计算，不回写声明。 */
    private int mulX;
    private int mulY;
    private int cell;
    private boolean bold;
    private String align;     // LEFT | CENTER | RIGHT
    private int w;
    private int h;
    private boolean required;

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public int getX() { return x; }
    public void setX(int x) { this.x = x; }
    public int getY() { return y; }
    public void setY(int y) { this.y = y; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getField() { return field; }
    public void setField(String field) { this.field = field; }
    public int getSize() { return size; }
    public void setSize(int size) { this.size = size; }
    public int getMulX() { return mulX; }
    public void setMulX(int mulX) { this.mulX = mulX; }
    public int getMulY() { return mulY; }
    public void setMulY(int mulY) { this.mulY = mulY; }
    public int getCell() { return cell; }
    public void setCell(int cell) { this.cell = cell; }
    public boolean isBold() { return bold; }
    public void setBold(boolean bold) { this.bold = bold; }
    public String getAlign() { return align; }
    public void setAlign(String align) { this.align = align; }
    public int getW() { return w; }
    public void setW(int w) { this.w = w; }
    public int getH() { return h; }
    public void setH(int h) { this.h = h; }
    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }
}
