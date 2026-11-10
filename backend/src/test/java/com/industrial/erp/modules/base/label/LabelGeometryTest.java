package com.industrial.erp.modules.base.label;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LabelGeometryTest {

    @Test
    @DisplayName("80×60mm @203dpi = 640×480 点 (docs/32 §3 的换算唯一出口)")
    void convertsMmToDots() {
        assertThat(LabelGeometry.dots(80, 203)).isEqualTo(640);
        assertThat(LabelGeometry.dots(60, 203)).isEqualTo(480);
        assertThat(LabelGeometry.dots(40, 300)).isEqualTo(472); // 非 203dpi 仍按名义值 floor(40*300/25.4)=472
        assertThat(LabelGeometry.dots(1, 203)).isEqualTo(8);    // 203dpi = 8 点/毫米（真值 203.2）
    }

    @Test
    @DisplayName("字宽按 docs/34 实测点阵：中文 24 点/字，ASCII 字体\"2\" 12 点/字，都再乘横向倍率")
    void measuresFontWidths() {
        // 24 点字号：中文正好 1 倍 ⇒ 24 点/字
        assertThat(LabelGeometry.textWidthDots("批号", 24)).isEqualTo(48);
        // 24 点字号的 ASCII：倍率 round(24/12)=2 ⇒ 12×2=24 点/字（旧的 0.6×字号只算 14，偏松）
        assertThat(LabelGeometry.textWidthDots("AB", 24)).isEqualTo(48);
        assertThat(LabelGeometry.textWidthDots("AB", 12)).isEqualTo(24);   // ×1
        assertThat(LabelGeometry.textWidthDots("", 24)).isZero();
        assertThat(LabelGeometry.textWidthDots(null, 24)).isZero();
    }

    @Test
    @DisplayName("混合内容整段按中文字库全额计价 —— 内置字库没有混排，宁可多算也不能少算")
    void mixedContentIsChargedAsCjk() {
        assertThat(LabelGeometry.textWidthDots("批号A", 24)).isEqualTo(72);   // 3 × 24，不是 48+14
    }

    @Test
    @DisplayName("中日韩判据与渲染器同一条：0x3000–0x9FFF 与全角 0xFF00–0xFFEF")
    void cjkTestMatchesTheRenderer() {
        assertThat(LabelGeometry.hasCjk("批号")).isTrue();
        assertThat(LabelGeometry.hasCjk("、")).isTrue();   // 中日韩标点 U+3001
        assertThat(LabelGeometry.hasCjk("Ａ")).isTrue();   // 全角 A U+FF21
        assertThat(LabelGeometry.hasCjk("，")).isTrue();   // 全角逗号 U+FF0C（不在 0x3000 段，靠全角那条命中）
        assertThat(LabelGeometry.hasCjk("PFI202611040001")).isFalse();
        assertThat(LabelGeometry.hasCjk(null)).isFalse();
    }

    @Test
    @DisplayName("行高：ASCII 字体\"2\" 基准 20 点、中文 24 点；倍率各按自己的基准算，不共用")
    void givesLineHeights() {
        assertThat(LabelGeometry.effectiveMul("AB", 24, 0, 0).lineHeightDots()).isEqualTo(20);   // round(24/20)=1 ⇒ ×1
        assertThat(LabelGeometry.effectiveMul("AB", 24, 0, 0).mulX()).isEqualTo(2);              // 横纵不同倍率
        assertThat(LabelGeometry.effectiveMul("批号", 48, 0, 0).lineHeightDots()).isEqualTo(48); // 24×2
        assertThat(LabelGeometry.effectiveMul("AB", 12, 0, 0).mulX()).isEqualTo(1);
        assertThat(LabelGeometry.effectiveMul("AB", 12, 0, 0).mulY()).isEqualTo(1);              // round(12/20)=0 ⇒ 兜到 1
    }

    @Test
    @DisplayName("字号漏填（≤0）按中文基准 24 点兜底 —— 与 B2 渲染器原样一致，不该崩")
    void zeroSizeFallsBackToCjkBase() {
        assertThat(LabelGeometry.effectiveMul("合格", 0, 0, 0).mulX()).isEqualTo(1);
        assertThat(LabelGeometry.effectiveMul("合格", -8, 0, 0).perCharDots()).isEqualTo(24);
        assertThat(LabelGeometry.textWidthDots("AB", 0)).isEqualTo(48);   // 兜底后 eff=24 ⇒ ×2 ⇒ 24 点/字
    }

    @Test
    @DisplayName("换行预算：取「纸右边界」与「声明 w 右边界」里较近的那个；没声明 w 只看纸宽")
    void budgetsTheWrapWidth() {
        assertThat(LabelGeometry.wrapBudgetDots(560, 0, 640)).isEqualTo(80);
        assertThat(LabelGeometry.wrapBudgetDots(0, 100, 640)).isEqualTo(100);
        assertThat(LabelGeometry.wrapBudgetDots(500, 300, 640)).isEqualTo(140);  // min(640, 800) − 500
        assertThat(LabelGeometry.wrapBudgetDots(700, 0, 640)).isEqualTo(-60);    // 起点已在纸外：负数照实返回
    }

    @Test
    @DisplayName("拆段按码点推进：代理对不许被劈成两半，预算不足一个字符时不拆")
    void splitsOnCodePointBoundaries() {
        // 预算 80 / 24 点 = 一行 3 个 ⇒ 5 个字拆成 3 + 2（尾段短于满行，正好钉住「不补齐」）
        assertThat(LabelGeometry.lineSegments("一二三四五", 24, 80)).containsExactly("一二三", "四五");
        assertThat(LabelGeometry.lineSegments("一二三", 24, 8)).containsExactly("一二三");   // 放不下一个字符 ⇒ 不拆
        assertThat(LabelGeometry.lineSegments("AB", 24, 0)).containsExactly("AB");           // 纸宽没填 ⇒ 不拆
        // 代理对不许劈开：字体"2"×2 = 24 点/字，预算 56 ⇒ 一行 2 个码点；按 char 推进就会把一个 emoji 切成两半
        assertThat(LabelGeometry.lineSegments("😀😀😀", 24, 56)).containsExactly("😀😀", "😀");
    }

    @Test
    @DisplayName("dots → mm：与 dots() 互逆，误差 < 0.5mm 打印机不敏感（TSPL SIZE 只认毫米）")
    void convertsDotsBackToMm() {
        assertThat(LabelGeometry.mm(640, 203)).isEqualTo(80);
        assertThat(LabelGeometry.mm(480, 203)).isEqualTo(60);
        assertThat(LabelGeometry.mm(472, 300)).isEqualTo(40);
    }
}
