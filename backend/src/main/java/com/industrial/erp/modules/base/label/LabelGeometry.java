package com.industrial.erp.modules.base.label;

/**
 * v1.1.167 标签几何换算的**唯一出口** (docs/32 §3)。
 *
 * <p>元素存的是点，毫米只在模板表头（width_mm/height_mm/gap_mm）出现。
 */
public final class LabelGeometry {

    private LabelGeometry() {}

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
     * 估算一段文本占多少点宽，**只用于保存模板时的越界校验**，不是排版引擎：
     * 中日韩字符按一个字号，其余按 0.6 个字号（打印机内置字库的真实宽度只有真机取证后才知道，见 §9）。
     *
     * <p>注意它和 {@link TsplLabelRenderer} 的拆行估算**不是同一套数**：真机取证（docs/34）之后
     * 打印侧的字库宽是定值 —— ASCII 字体"2" = 12 点/字符、中文 TSS24.BF2 = 24 点/字符（再乘放大倍率），
     * 而这里 0.6×字号在 24 点时只算 14 点，对 ASCII 明显偏松。保存闸口径本期不动（它拦的是"字面量能算出来
     * 的越界"，偏松只是少拦），但预览与实纸的差异、以及要不要统一这两套数，归 B3/B4 收口。
     */
    public static int estWidthDots(String s, int size) {
        if (s == null || s.isEmpty() || size <= 0) return 0;
        int dots = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean cjk = c > 0x2E80;
            dots += cjk ? size : (int) Math.floor(size * 0.6d);
        }
        return dots;
    }
}
