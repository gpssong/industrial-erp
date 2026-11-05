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
    @DisplayName("宽度估算：中文按字号全额、ASCII 按 0.6 倍 —— 只为保存闸的越界检测服务")
    void estimatesWidth() {
        assertThat(LabelGeometry.estWidthDots("批号", 24)).isEqualTo(48);
        assertThat(LabelGeometry.estWidthDots("AB", 24)).isEqualTo(28);   // floor(24*0.6)=14 each
        assertThat(LabelGeometry.estWidthDots("", 24)).isZero();
        assertThat(LabelGeometry.estWidthDots(null, 24)).isZero();
    }

    @Test
    @DisplayName("dots → mm：与 dots() 互逆，误差 < 0.5mm 打印机不敏感（TSPL SIZE 只认毫米）")
    void convertsDotsBackToMm() {
        assertThat(LabelGeometry.mm(640, 203)).isEqualTo(80);
        assertThat(LabelGeometry.mm(480, 203)).isEqualTo(60);
        assertThat(LabelGeometry.mm(472, 300)).isEqualTo(40);
    }
}
