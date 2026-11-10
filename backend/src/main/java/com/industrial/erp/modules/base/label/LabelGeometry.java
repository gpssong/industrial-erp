package com.industrial.erp.modules.base.label;

import java.util.ArrayList;
import java.util.List;

/**
 * v1.1.167 标签几何与内置字库度量的**唯一出口** (docs/32 §3、docs/34 Q2 实测)。
 *
 * <p>元素存的是点，毫米只在模板表头（width_mm/height_mm/gap_mm）出现。
 *
 * <p>**为什么字库度量也归这里**（B3b）：内置字库的点阵宽同时是三件事的输入 ——
 * 渲染器发 `TEXT` 的放大倍率、保存闸判越界、预览标橙。三处各写一套数，就会出现
 * 「保存时说没事、打出来少一行」，或者反过来「闸把本来打得好的模板拦下来」。
 * 所以度量只有一个出口，三个消费方都调这里。
 */
public final class LabelGeometry {

    private LabelGeometry() {}

    /** Q2 取证结论（docs/34）：ASCII 内置字体号与基准点阵 12×20 点。 */
    public static final String ASCII_FONT = "2";
    public static final int ASCII_BASE_W = 12;
    public static final int ASCII_BASE_H = 20;
    /** Q2 取证结论：中文内置字库名与基准点阵 TSS24.BF2 = 24×24 点简中。 */
    public static final String CHINESE_FONT = "TSS24.BF2";
    public static final int CJK_BASE = 24;
    /** 条码最低可扫高度 10 点 = 1.25 mm：这个高度纸面上已经是一条细黑带，再低不如不出。 */
    public static final int MIN_BARCODE_HEIGHT = 10;
    /**
     * 二维码**没声明字号**时按这个字号出。24 不是拍的：sql/155 三条种子模板的 qrcode 都写 `size:24`，
     * PC 编辑器「新建一行」也给 24 —— 兜底值必须和「正常配出来的值」是同一个，否则同一条标签会有两种码宽。
     */
    public static final int DEFAULT_QR_FONT_SIZE = 24;
    /**
     * 进入数学的坐标/尺寸上限 (docs/36 §0.3)。夹的是**参与计算的值**，不是库里的声明：
     * 声明照原样存回，负数仍由保存闸按原始值点名 —— 夹成 0 等于把错归到别处。
     */
    public static final int MAX_GEOMETRY_DOTS = 1_000_000;
    public static final int MAX_CONTENT_CODEPOINTS = 4096;
    public static final int MAX_ELEMENTS_JSON_BYTES = 262_144;
    public static final int MAX_ELEMENTS = 128;
    /** TSPL 没有画框指令，矩形用四条 BAR 描边；边框厚与「画得出来的最小外框」是一体的。 */
    public static final int RECT_BORDER_DOTS = 2;
    public static final int MIN_RECT_FRAME_DOTS = RECT_BORDER_DOTS * 2;
    /** line 没声明 h 时的厚度：2 点 = 0.25 mm，与 sql/155 三种子的 `h:2` 同值。 */
    public static final int DEFAULT_LINE_THICKNESS_DOTS = 2;

    /**
     * `line` 在纸面上的实际厚度 = `BAR` 的第 4 个形参（R13：声明的 `h` 就是线粗）。
     * 没声明（≤0）仍按 2 点 = 0.25 mm，与 sql/155 三种子的 `h:2` 同值 ⇒ 存量模板逐字节不变。
     */
    public static int lineThicknessDots(int h) {
        return h <= 0 ? DEFAULT_LINE_THICKNESS_DOTS : Math.min(h, MAX_GEOMETRY_DOTS);
    }

    /** 坐标进数学前夹绝对值：`y + height` 溢出成负数会把天大的越界判成合格（A6 I-2 同一条规矩）。 */
    public static int clampCoordDots(int v) {
        return Math.max(-MAX_GEOMETRY_DOTS, Math.min(MAX_GEOMETRY_DOTS, v));
    }

    /** 尺寸进数学前夹到 0…上限；负数的 w/h 交给保存闸按**原始值**点名，不在这里偷偷修正。 */
    public static int clampExtentDots(int v) {
        return Math.max(0, Math.min(MAX_GEOMETRY_DOTS, v));
    }

    /**
     * 名义 dpi → 真值。**203dpi 的打印机物理上就是 8 点/毫米**（真值 203.2 = 8 × 25.4），
     * 拿 203 直算 80mm 得 639 点 —— 比机器自己的画布少 1 点，元素会莫名越界 1 点。
     * 300dpi 及以上没有已取证的真值（§9 硬件闸），先按名义值算，取证后再补这张表。
     */
    public static double trueDpi(int dpi) {
        return dpi == 203 ? 203.2d : (double) dpi;
    }

    /** dots = floor(mm × trueDpi(dpi) / 25.4)。203dpi 下 80mm → 640 点、1mm → 8 点。 */
    public static int dots(int mm, int dpi) {
        return (int) Math.floor((double) mm * trueDpi(dpi) / 25.4d);
    }

    /** dots → mm（四舍五入）。TSPL 的 SIZE/GAP 只认毫米，元素坐标是点，两头都要换算。分母必须是 trueDpi，否则正反映射不自洽。 */
    public static int mm(int dots, int dpi) {
        return (int) Math.round(dots * 25.4d / trueDpi(dpi));
    }

    /**
     * `BARCODE` 这条指令实际发出去的高度（点）。**渲染器和保存闸必须用同一个函数**（B3b）：
     * 元素没声明 `h` 时按 `size` 给 —— sql/155 的种子模板里两条 barcode（成品卷标、原料垛标）全部只写 `size:60`，
     * 两头都不声明才会兜到 10 点。之前渲染器只认 `h`，`size:60` 被整条忽略 ⇒
     * 2026-11-05 三张纸上的 CODE128 全是 1.25 mm 高的细带，肉眼可读但没人扫得动。
     */
    public static int barcodeHeightDots(int declaredH, int fontSize) {
        return Math.max(MIN_BARCODE_HEIGHT, declaredH > 0 ? declaredH : fontSize);
    }

    /**
     * 二维码的模块边长（点/模块）= TSPL `QRCODE` 的第 4 个形参。
     * 显式 cell 非 0 时夹到 2..8；0 保留旧字号回落（字号最多按 4096 点）。
     * **渲染器和「没声明字号」的兜底只有这一个出口**（与 {@link #barcodeHeightDots} 同一处置）：
     * 字号 ÷ 6 夹在 2..8，字号缺失（≤0，PC 编辑器清空 `size` 清洗后就是 0）先按
     * {@link #DEFAULT_QR_FONT_SIZE} 补，再进公式。
     *
     * <p>为什么不能直接兜到公式下限 2：版本 1 的二维码是 21 模块，cell 2 ⇒ 42 点 ≈ 5.25 mm ——
     * 和 2026-11-05 纸面上那三条 1.25 mm 高的 CODE128 是同一类病（肉眼看得见、车间扫不出）。
     */
    public static int qrCell(int size, int declaredCell) {
        if (declaredCell != 0) return Math.max(2, Math.min(8, declaredCell));
        int effectiveSize = size > 0 ? Math.min(size, 4096) : DEFAULT_QR_FONT_SIZE;
        return Math.min(8, Math.max(2, Math.round((float) Math.max(12, effectiveSize) / 6f)));
    }

    /** 一条 `TEXT` 用哪个内置字库、横纵各放大几倍。倍率是整数（TSPL 只认整数倍）。 */
    public record Font(String name, int baseW, int baseH, int mulX, int mulY) {
        /** 一个字占多少点宽 —— 一行能放几个字就由它除出来。 */
        public int perCharDots() { return baseW * mulX; }
        /** 行高：拆行时后续段在 y 上下移这么多。 */
        public int lineHeightDots() { return baseH * mulY; }
    }

    /**
     * 内容 + 模板字号 + 声明倍率 → 字体与有效倍率的唯一公开出口。
     * 每轴独立：0 沿用旧字号推导，非 0 夹到 1..8；字号最多按 4096 点参与回落。
     *
     * <p>有中日韩字符就**整段**走中文字库：内置字库没有混排能力，一行只能指定一个字体。
     * 混合内容里夹的 ASCII 也按中文字库全额计价 —— 中文行内夹英文到底占 12 还是 24 点
     * **没有实测数据**（docs/34 只在纯 ASCII 行上钉死了点阵宽）。宁可多算（早拆一行、纸面留白）
     * 也不能少算（少算就是固件静默丢尾，等于把 docs/34 ② 那张纸的 bug 抄进产品）。
     *
     * <p>`size ≤ 0` 按中文基准 24 点兜底：模板漏填字号不该崩，也不该打出 0 倍率的指令。
     */
    public static Font effectiveMul(String content, int size, int mx, int my) {
        Font old = fontFor(content, Math.min(size, 4096));
        return new Font(old.name(), old.baseW(), old.baseH(),
                mx == 0 ? old.mulX() : Math.max(1, Math.min(8, mx)),
                my == 0 ? old.mulY() : Math.max(1, Math.min(8, my)));
    }

    /** 旧字号推导仅作私有回落；未声明轴的倍率不强行夹到 8。 */
    private static Font fontFor(String content, int size) {
        int eff = size <= 0 ? CJK_BASE : size;
        if (hasCjk(content)) {
            int mul = Math.max(1, Math.round((float) eff / CJK_BASE));
            return new Font(CHINESE_FONT, CJK_BASE, CJK_BASE, mul, mul);
        }
        return new Font(ASCII_FONT, ASCII_BASE_W, ASCII_BASE_H,
                Math.max(1, Math.round((float) eff / ASCII_BASE_W)),
                Math.max(1, Math.round((float) eff / ASCII_BASE_H)));
    }

    /**
     * 有没有中日韩字符。判据照搬 B2 渲染器那条（**不是**照搬旧估算方法那条已被作废的 `c > 0x2E80`）：
     * **它决定发哪个字库，度量必须跟着它**，否则算出来的宽预测的不是实际打出去的那条指令。
     */
    public static boolean hasCjk(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x3000 && c <= 0x9FFF) return true;   // 中日韩标点 + 基本汉字
            if (c >= 0xFF00 && c <= 0xFFEF) return true;   // 全角
        }
        return false;
    }

    /** 一行不拆的话这段内容占多少点宽 = 码点数 × 字宽。空/null = 0。 */
    public static int textWidthDots(String content, int size) {
        if (content == null || content.isEmpty()) return 0;
        return content.codePointCount(0, content.length()) * effectiveMul(content, size, 0, 0).perCharDots();
    }

    /**
     * 换行预算 = 起点 x 到「纸右边界」与「声明宽度右边界」里**较近**的那个。
     *
     * <p>声明 `w ≤ 0`（text 元素通常不声明）就只受纸宽约束；multiline 的 `w` 才是用户自己圈的
     * 栏宽 —— 渲染器按它换行，闸也必须按它判，否则「圈了窄栏的模板」会被按纸宽放行。
     *
     * <p>x 已在纸外时返回**负数**（照实，不夹成 0）：起点越界是模板排版的错，
     * 由 {@link #lineSegments} 的守卫保证不静默改坐标、由保存闸点名，不在这里偷偷修正。
     * 相加走 long 再夹：坐标大到能溢出 int 的模板，不该被判成合格（A6 I-2 同一条规矩）。
     */
    public static int wrapBudgetDots(int x, int declaredW, int paperW) {
        long right = paperW;
        if (declaredW > 0) right = Math.min((long) paperW, (long) x + declaredW);
        return (int) (right - x);
    }

    /**
     * 一行放不下就按字符边界拆段（拆不断半个汉字：按码点推进，代理对不会被劈开）。
     *
     * <p>这是**渲染器与保存闸共用的同一个函数**（B3b 的全部意义）：固件对超出纸宽的行尾
     * **静默丢弃**（docs/34 硬约束 2），实纸是拆行的；闸要判「拆完之后纸面放不放得下」，
     * 就得用同一个拆法自己拆一遍。闸自己另算一次行数 = 迟早漂移。
     *
     * @param availableDots 换行预算；小于一个字符宽（含起点已在纸外、纸宽没填）时**不拆**，
     *                      原样一段交出去 —— 起点越界归保存闸管，不在这里静默改坐标。
     */
    public static List<String> lineSegments(String content, int perCharDots, int availableDots) {
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
}
