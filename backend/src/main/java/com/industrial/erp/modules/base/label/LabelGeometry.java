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

    /**
     * 估算一段文本占多少点宽，**只用于保存模板时的越界校验**，不是排版引擎：
     * 中日韩字符按一个字号，其余按 0.6 个字号（打印机内置字库的真实宽度只有真机取证后才知道，见 §9）。
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
