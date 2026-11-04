package com.industrial.erp.modules.base.label;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 飞鹅云次通道 (docs/32 §3.1 / §4)。
 *
 * <p>**如实说明的退化**：`sys_feie_*` 那套是 58mm **流式点阵小票机**，没有绝对定位能力，
 * 所以这里按元素的 y 分行、行内按 x 排序拼成文本行，x/y 坐标与 line/rect 画不出（直接丢）。
 * 1D 条码的飞鹅指令未经真机验证，退化成 "(条码 内容)" 明文，避免打出一张扫不出来的假码。
 * 真正按坐标出图的主通道是蓝牙 TSPL（闸后）。
 */
@Component
public class FeieLabelRenderer {

    /** 同一行判定：y 差小于此值算一行（24 点字号 ≈ 3mm @203dpi）。 */
    private static final int ROW_TOLERANCE_DOT = 20;

    public String render(RenderResult r) {
        StringBuilder body = new StringBuilder();
        List<ResolvedElement> sorted = new ArrayList<>(r.getElements());
        sorted.sort(Comparator.comparingInt(ResolvedElement::getY).thenComparingInt(ResolvedElement::getX));

        int i = 0;
        while (i < sorted.size()) {
            ResolvedElement head = sorted.get(i);
            if (!isPrintable(head.getType())) { i++; continue; }
            List<ResolvedElement> row = new ArrayList<>();
            row.add(head);
            int j = i + 1;
            while (j < sorted.size()
                    && Math.abs(sorted.get(j).getY() - head.getY()) < ROW_TOLERANCE_DOT
                    && isPrintable(sorted.get(j).getType()) && "text".equals(sorted.get(j).getType())) {
                row.add(sorted.get(j));
                j++;
            }
            i = j;

            String type = head.getType();
            if ("qrcode".equals(type)) {
                body.append("<QR>").append(nullToEmpty(head.getContent())).append("</QR><BR>\n");
            } else if ("barcode".equals(type)) {
                body.append("(条码 ").append(nullToEmpty(head.getContent())).append(")<BR>\n");
            } else {
                String line = row.stream().map(e -> nullToEmpty(e.getContent()))
                        .reduce((a, b) -> a + " " + b).orElse("");
                if (line.isBlank()) continue;
                if (row.stream().allMatch(ResolvedElement::isBold)) {
                    body.append("<B>").append(line).append("</B><BR>\n");
                } else {
                    body.append(line).append("<BR>\n");
                }
            }
        }
        body.append("<CUT>\n");

        int copies = Math.max(1, r.getCopies());
        return body.toString().repeat(copies);
    }

    private static boolean isPrintable(String type) {
        return "text".equals(type) || "multiline".equals(type)
                || "barcode".equals(type) || "qrcode".equals(type);
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }
}
