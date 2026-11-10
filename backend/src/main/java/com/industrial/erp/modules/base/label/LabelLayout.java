package com.industrial.erp.modules.base.label;

import java.util.ArrayList;
import java.util.List;

public final class LabelLayout {

    private LabelLayout() {}

    public record Box(int x, int y, int w, int h, String content) {}

    public static String sanitize(String content) {
        return content == null ? "" : content.replace("\"", "'")
                .replace("\r", " ").replace("\n", " ").trim();
    }

    /**
     * 元素在纸面上占的盒子 —— **闸、预览、指令三条路径的同一个出口**。
     *
     * <p>**先按类型分派，再谈内容空不空**：line/rect 只有几何、没有内容，若先按空内容 return，
     * 画布上它们会凭空消失（而 `BAR` 照发），闸也算不出越界 —— 两边同时看不见同一个元素。
     * barcode/qrcode 返回空盒子：CODE128 的宽按内容自适应、二维码的模块数按内容变长，
     * **没有实测证据**就不承诺（docs/34），估算留给 resolve。
     */
    public static List<Box> segmentsFor(ResolvedElement e, int paperW) {
        String type = e.getType() == null ? "" : e.getType();
        switch (type) {
            case "line", "rect" -> {
                return List.of(shapeBox(e));
            }
            case "barcode", "qrcode" -> {
                return List.of();
            }
            default -> {}
        }
        String content = sanitize(e.getContent());
        if (content.isEmpty()) return List.of();
        LabelGeometry.Font font = LabelGeometry.effectiveMul(content, e.getFontSize(), e.getMulX(), e.getMulY());
        int available = LabelGeometry.wrapBudgetDots(e.getX(), e.getW(), paperW);
        List<String> lines = LabelGeometry.lineSegments(content, font.perCharDots(), available);
        List<Box> result = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            result.add(new Box(e.getX(), e.getY() + i * font.lineHeightDots(),
                    line.codePointCount(0, line.length()) * font.perCharDots(),
                    font.lineHeightDots(), line));
        }
        return result;
    }

    /**
     * line/rect 的真实外框（点）—— 渲染器发的就是这四个数，所以闸按同一个数判。
     * rect 的最小外框是边框厚的两倍：外框小于边框 ⇒ 描边自己叠自己。
     */
    public static Box shapeBox(ResolvedElement e) {
        if ("rect".equals(e.getType())) {
            return new Box(e.getX(), e.getY(),
                    Math.max(LabelGeometry.MIN_RECT_FRAME_DOTS, e.getW()),
                    Math.max(LabelGeometry.MIN_RECT_FRAME_DOTS, e.getH()), "");
        }
        return new Box(e.getX(), e.getY(), Math.max(1, e.getW()),
                LabelGeometry.lineThicknessDots(e.getH()), "");
    }
}
