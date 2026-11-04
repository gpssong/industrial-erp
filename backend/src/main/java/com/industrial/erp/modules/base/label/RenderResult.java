package com.industrial.erp.modules.base.label;

import java.util.List;

/** 一张标签的渲染结果（还没变成任何厂商指令）。 */
public class RenderResult {
    private List<ResolvedElement> elements = List.of();
    private int widthDots;
    private int heightDots;
    private List<String> missingRequired = List.of();
    private int copies = 1;
    private int gapMm;
    private int dpi;
    private String direction = "NORMAL";

    public List<ResolvedElement> getElements() { return elements; }
    public void setElements(List<ResolvedElement> elements) { this.elements = elements; }
    public int getWidthDots() { return widthDots; }
    public void setWidthDots(int widthDots) { this.widthDots = widthDots; }
    public int getHeightDots() { return heightDots; }
    public void setHeightDots(int heightDots) { this.heightDots = heightDots; }
    public List<String> getMissingRequired() { return missingRequired; }
    public void setMissingRequired(List<String> missingRequired) { this.missingRequired = missingRequired; }
    public int getCopies() { return copies; }
    public void setCopies(int copies) { this.copies = copies; }
    public int getGapMm() { return gapMm; }
    public void setGapMm(int gapMm) { this.gapMm = gapMm; }
    public int getDpi() { return dpi; }
    public void setDpi(int dpi) { this.dpi = dpi; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
}
