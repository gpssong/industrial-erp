package com.industrial.erp.modules.base.label;

import org.springframework.stereotype.Component;

import java.nio.charset.Charset;

/**
 * 印麦 IP-803 主通道渲染器 (docs/32 §4，§9 闸后)。
 *
 * <p>**取证结论的三个落点**：`CHARSET`(Q3)、字体号与点阵宽(Q2，已归 {@link LabelGeometry})、`QRCODE` 的形参顺序(Q1/④)。
 * 这三处以外不需要按机器改 —— 元素模型、模板表、日志、页面都与厂商无关。
 *
 * <p>TSPL 没有「加粗」也没有「画矩形」：加粗用右移 1 点重复打一次，矩形用四条实心 BAR 描边。
 *
 * <p>**超宽必须在这里拆行** (docs/34 硬约束 2，2026-11-05 实测)：这台的固件对**超出纸宽的行尾直接静默丢弃**
 * —— 不折行、不报错、`print()` 回过来的 `missingAcks=0` 只代表蓝牙发完了，不代表印全了。
 * 所以服务端不算「排版」（字距/连字之类仍归打印机内置字库），但**必须算「一行放得下几个字」**：
 * 按 {@link LabelGeometry} 的**实测点阵度量**与**同一个拆行函数**估宽、拆行 —— 保存闸判的是同一套数（B3b）。
 * 放不下就拆成多条 `TEXT`，后续段在 y 上依次下移一个行高。
 * 选择**拆行而不是缩字号**：缩字号会让同一模板因内容长短打出两种字号，版式不可预测。
 */
@Component
public class TsplLabelRenderer {

    /** Q3 取证结论：GBK / GB18030 / UTF-8 三选一。**改这一行**，其余代码跟着 `CHARSET.name()` 走。 */
    public static final Charset CHARSET = Charset.forName("GBK");

    public static Charset charset() { return CHARSET; }

    public String renderCommandText(RenderResult r) {
        StringBuilder sb = new StringBuilder(header(r));
        int paperW = r.getWidthDots();
        for (ResolvedElement e : r.getElements()) sb.append(command(e, paperW));
        sb.append("PRINT ").append(Math.max(1, r.getCopies())).append(",1\r\n");
        return sb.toString();
    }

    public byte[] renderBytes(RenderResult r) {
        return renderCommandText(r).getBytes(CHARSET);
    }

    private String header(RenderResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("SIZE ").append(LabelGeometry.mm(r.getWidthDots(), r.getDpi())).append(" mm,")
                .append(LabelGeometry.mm(r.getHeightDots(), r.getDpi())).append(" mm\r\n");
        sb.append("GAP ").append(Math.max(0, r.getGapMm())).append(" mm,0 mm\r\n");
        // 模板里存的是 NORMAL/REVERSE（A1 DDL 口径），TSPL 要的是 0/1。
        // 实测 `DIRECTION 1` 会把内容转 180°（docs/34 硬约束 1，纸面三次复现）⇒ 这里只做字面映射，
        // **不在渲染器里翻转坐标去“修正”**：朝向归模板决定，NORMAL→0 与 REVERSE→1 两条都有单测钉着。
        sb.append("DIRECTION ").append("REVERSE".equals(r.getDirection()) ? 1 : 0).append("\r\n");
        String cs = CHARSET.name();
        if ("GBK".equals(cs) || "GB18030".equals(cs) || "MS936".equals(cs)) {
            sb.append("CODEPAGE 936\r\n");
        } else if ("UTF-8".equals(cs)) {
            sb.append("CODEPAGE UTF-8\r\n");
        }
        sb.append("CLS\r\n");
        return sb.toString();
    }

    private String command(ResolvedElement e, int paperW) {
        String type = e.getType() == null ? "" : e.getType();
        switch (type) {
            case "text":
            case "multiline": {
                String content = LabelLayout.sanitize(e.getContent());
                if (content.isEmpty()) return "";
                return textBlock(e, content, paperW);
            }
            case "qrcode": {
                String content = LabelLayout.sanitize(e.getContent());
                if (content.isEmpty()) return "";
                int cell = LabelGeometry.qrCell(e.getFontSize(), e.getCell());
                // 形参顺序 = docs/34 ④ 写法 A：x,y,<纠错 L>,<模块宽>,<旋转 0>,<模式 A>,"内容"（Ruling，B4 真机补扫闭环）。
                return "QRCODE " + e.getX() + "," + e.getY() + ",L," + cell + ",0,A,\"" + content + "\"\r\n";
            }
            case "barcode": {
                String content = LabelLayout.sanitize(e.getContent());
                if (content.isEmpty()) return "";
                int h = LabelGeometry.barcodeHeightDots(e.getH(), e.getFontSize());
                return "BARCODE " + e.getX() + "," + e.getY() + ",\"128\"," + h + ",2,0,2,2,\"" + content + "\"\r\n";
            }
            case "line": {
                LabelLayout.Box line = LabelLayout.shapeBox(e);
                return bar(line.x(), line.y(), line.w(), line.h());
            }
            case "rect":
                return rect(e);
            default:
                return "";
        }
    }

    /**
     * 一段文本 → 一条或多条 `TEXT`（超宽拆行）。字体、倍率、行高、拆段全部来自
     * {@link LabelGeometry}：**渲染器不再自带一套字宽假设**，所以保存闸说「放得下」
     * 就等于这张纸真放得下（B3b）。
     *
     * <p>换行预算用 `wrapBudgetDots(x, e.getW(), paperW)`：声明了 w 的（种子模板里三条
     * `multiline` 都声明了）按 w 圈栏，没声明的按纸宽 —— 用户在编辑器里圈的栏宽就是实纸上的栏宽。
     */
    private static String textBlock(ResolvedElement e, String content, int paperW) {
        // 混排拆出的纯 ASCII 段也必须沿用整段字库，否则字宽、行高和旧指令都会变。
        LabelGeometry.Font font = LabelGeometry.effectiveMul(content, e.getFontSize(), e.getMulX(), e.getMulY());
        StringBuilder sb = new StringBuilder();
        for (LabelLayout.Box box : LabelLayout.segmentsFor(e, paperW)) {
            sb.append(textLine(box.x(), box.y(), font.name(), font.mulX(), font.mulY(), box.content()));
            // 加粗的 x+1 重复行跟着**每一段**走，只拆第一行等于把加粗弄丢。
            if (e.isBold()) sb.append(textLine(box.x() + 1, box.y(), font.name(), font.mulX(), font.mulY(), box.content()));
        }
        return sb.toString();
    }

    /** x/y 单独作形参：加粗那条只是同一行的 x+1，其余（字体/倍率/本段内容）必须与普通那条完全一致。 */
    private static String textLine(int x, int y, String font, int mulX, int mulY, String content) {
        return "TEXT " + x + "," + y + ",\"" + font + "\",0," + mulX + "," + mulY + ",\"" + content + "\"\r\n";
    }

    /**
     * 四条实心 BAR 描边：TSPL 没有画矩形指令。外框与保存闸、画布预览用的是
     * {@link LabelLayout#shapeBox} 同一个框（含最小外框），边框厚取 {@link LabelGeometry#RECT_BORDER_DOTS}。
     */
    private String rect(ResolvedElement e) {
        LabelLayout.Box frame = LabelLayout.shapeBox(e);
        int t = LabelGeometry.RECT_BORDER_DOTS;
        int x = frame.x(), y = frame.y(), w = frame.w(), h = frame.h();
        return bar(x, y, w, t)
                + bar(x, y + h - t, w, t)
                + bar(x, y, t, h)
                + bar(x + w - t, y, t, h);
    }

    private static String bar(int x, int y, int w, int h) {
        return "BAR " + x + "," + y + "," + w + "," + h + "\r\n";
    }
}
