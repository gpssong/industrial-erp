package com.industrial.erp.modules.base.label;

/** elements JSON 里的一个元素 (docs/32 §3)。x/y/size/w/h 单位一律是**点**。 */
public class LabelElement {
    private String type;      // text | multiline | barcode | qrcode | line | rect
    private int x;
    private int y;
    private String text;      // 字面量
    private String field;     // 或从上下文取值的字段名
    private int size;         // text: 字号点高; barcode: 条码高; qrcode: 模块尺寸
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
