package com.industrial.erp.modules.base.label;

import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * 印麦 IP-803 主通道渲染器 (docs/32 §4，§9 闸后)。
 *
 * <p>**取证结论的三个落点**：`CHARSET`(Q3)、`ASCII_FONT`/`CHINESE_FONT`(Q2)、`QRCODE` 的形参顺序(Q1/④)。
 * 这三处以外不需要按机器改 —— 元素模型、模板表、日志、页面都与厂商无关。
 *
 * <p>TSPL 没有「加粗」也没有「画矩形」：加粗用右移 1 点重复打一次，矩形用四条实心 BAR 描边。
 *
 * <p>**超宽必须在这里拆行** (docs/34 硬约束 2，2026-11-05 实测)：这台的固件对**超出纸宽的行尾直接静默丢弃**
 * —— 不折行、不报错、`print()` 回过来的 `missingAcks=0` 只代表蓝牙发完了，不代表印全了。
 * 所以服务端不算「排版」（字距/连字之类仍归打印机内置字库），但**必须算「一行放得下几个字」**：
 * 按内置字库的点阵宽（ASCII 字体"2" = 12 点、中文 TSS24.BF2 = 24 点，均再乘横向放大倍率）估宽，
 * 放不下就拆成多条 `TEXT`，后续段在 y 上依次下移一个行高。
 * 选择**拆行而不是缩字号**：缩字号会让同一模板因内容长短打出两种字号，版式不可预测。
 */
@Component
public class TsplLabelRenderer {

    /** Q3 取证结论：GBK / GB18030 / UTF-8 三选一。**改这一行**，其余代码跟着 `CHARSET.name()` 走。 */
    public static final Charset CHARSET = Charset.forName("GBK");

    /** Q2 取证结论：ASCII 内置字体号与其基准点阵（"2" = 12×20 点）。 */
    private static final String ASCII_FONT = "2";
    private static final int ASCII_BASE_W = 12;
    private static final int ASCII_BASE_H = 20;
    /** Q2 取证结论：中文内置字库名与基准点阵（TSS24.BF2 = 24×24 点简中）。 */
    private static final String CHINESE_FONT = "TSS24.BF2";
    private static final int CJK_BASE = 24;

    private static final int LINE_THICKNESS = 2;
    private static final int MIN_BARCODE_HEIGHT = 10;

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
                String content = safe(e.getContent());
                if (content.isEmpty()) return "";
                int size = e.getFontSize() <= 0 ? CJK_BASE : e.getFontSize();
                if (hasCjk(content)) {
                    int mul = Math.max(1, Math.round((float) size / CJK_BASE));
                    return textBlock(e, content, CHINESE_FONT, mul, mul, CJK_BASE, CJK_BASE, paperW);
                }
                int mx = Math.max(1, Math.round((float) size / ASCII_BASE_W));
                int my = Math.max(1, Math.round((float) size / ASCII_BASE_H));
                return textBlock(e, content, ASCII_FONT, mx, my, ASCII_BASE_W, ASCII_BASE_H, paperW);
            }
            case "qrcode": {
                String content = safe(e.getContent());
                if (content.isEmpty()) return "";
                int cell = Math.min(8, Math.max(2, Math.round((float) Math.max(12, e.getFontSize()) / 6f)));
                // 形参顺序 = docs/34 ④ 写法 A：x,y,<纠错 L>,<模块宽>,<旋转 0>,<模式 A>,"内容"（Ruling，B4 真机补扫闭环）。
                return "QRCODE " + e.getX() + "," + e.getY() + ",L," + cell + ",0,A,\"" + content + "\"\r\n";
            }
            case "barcode": {
                String content = safe(e.getContent());
                if (content.isEmpty()) return "";
                int h = Math.max(MIN_BARCODE_HEIGHT, e.getH());
                return "BARCODE " + e.getX() + "," + e.getY() + ",\"128\"," + h + ",2,0,2,2,\"" + content + "\"\r\n";
            }
            case "line":
                return "BAR " + e.getX() + "," + e.getY() + "," + Math.max(1, e.getW()) + "," + LINE_THICKNESS + "\r\n";
            case "rect":
                return rect(e);
            default:
                return "";
        }
    }

    /**
     * 一段文本 → 一条或多条 `TEXT`（超宽拆行）。
     *
     * @param baseW 所选字体的基准**宽**（点）：ASCII "2" = 12，中文 TSS24.BF2 = 24
     * @param baseH 所选字体的基准**高**（点）：拆行时后续段下移的行高 = baseH × mulY
     */
    private static String textBlock(ResolvedElement e, String content, String font,
                                    int mulX, int mulY, int baseW, int baseH, int paperW) {
        int perCharDots = baseW * mulX;
        List<String> segments = fitSegments(content, perCharDots, paperW - e.getX());
        int lineHeight = baseH * mulY;
        StringBuilder sb = new StringBuilder();
        int y = e.getY();
        for (String segment : segments) {
            sb.append(textLine(e.getX(), y, font, mulX, mulY, segment));
            // 加粗的 x+1 重复行跟着**每一段**走，只拆第一行等于把加粗弄丢。
            if (e.isBold()) sb.append(textLine(e.getX() + 1, y, font, mulX, mulY, segment));
            y += lineHeight;
        }
        return sb.toString();
    }

    /**
     * 一行放不下就按字符边界拆段（拆不断半个汉字：按码点推进，代理对不会被劈开）。
     *
     * <p>混合内容里 ASCII 也按所选字体的全额宽度预算 —— 中文行内夹英文时到底占 12 还是 24 点
     * **没有实测数据**（docs/34 只在纯 ASCII 行上钉死了 24 点/字符 @×2）。宁可多算（早拆一行、
     * 纸面多一行空白余量）也不能少算（少算就是固件静默丢尾，等于把 ② 那张纸的 bug 抄进产品）。
     *
     * @param availableDots 纸宽减去起点 x；小于一个字符宽（含 x 已在纸外、纸宽未填）时**不拆**，
     *                      原样一行交出去 —— 起点越界是模板排版的错，归 A6 保存闸，不在这里静默改坐标。
     */
    private static List<String> fitSegments(String content, int perCharDots, int availableDots) {
        List<String> segments = new ArrayList<>();
        if (perCharDots <= 0 || availableDots < perCharDots) {
            segments.add(content);
            return segments;
        }
        int maxChars = availableDots / perCharDots;
        int from = 0;
        while (from < content.length()) {
            int to = from;
            int chars = 0;
            while (chars < maxChars && to < content.length()) {
                to += Character.charCount(content.codePointAt(to));
                chars++;
            }
            segments.add(content.substring(from, to));
            from = to;
        }
        return segments;
    }

    /** x/y 单独作形参：加粗那条只是同一行的 x+1，其余（字体/倍率/本段内容）必须与普通那条完全一致。 */
    private static String textLine(int x, int y, String font, int mulX, int mulY, String content) {
        return "TEXT " + x + "," + y + ",\"" + font + "\",0," + mulX + "," + mulY + ",\"" + content + "\"\r\n";
    }

    /** 四条实心 BAR 描边：TSPL 没有画矩形指令。 */
    private String rect(ResolvedElement e) {
        int x = e.getX(), y = e.getY(), w = Math.max(LINE_THICKNESS * 2, e.getW());
        int h = Math.max(LINE_THICKNESS * 2, e.getH());
        return "BAR " + x + "," + y + "," + w + "," + LINE_THICKNESS + "\r\n"
                + "BAR " + x + "," + (y + h - LINE_THICKNESS) + "," + w + "," + LINE_THICKNESS + "\r\n"
                + "BAR " + x + "," + y + "," + LINE_THICKNESS + "," + h + "\r\n"
                + "BAR " + (x + w - LINE_THICKNESS) + "," + y + "," + LINE_THICKNESS + "," + h + "\r\n";
    }

    private static boolean hasCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x3000 && c <= 0x9FFF) return true;   // 中日韩标点 + 基本汉字
            if (c >= 0xFF00 && c <= 0xFFEF) return true;   // 全角
        }
        return false;
    }

    /** 指令的参数是双引号包住的字符串，内容里的引号/换行必须打掉，否则指令被截断（打出半张纸）。 */
    private static String safe(String s) {
        if (s == null) return "";
        return s.replace("\"", "'").replace("\r", " ").replace("\n", " ").trim();
    }
}
