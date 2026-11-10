package com.industrial.erp.modules.base.label;

import com.industrial.erp.modules.base.entity.BaseLabelTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 3 (docs/36)：保存闸、渲染取值、TSPL 指令必须落在**同一套盒子**上。
 *
 * <p>存在的理由是 v1.1.162 那类漂移的标签版：闸自己算一遍行数、渲染器再算一遍，两套数迟早分叉，
 * 分叉的代价由车间在纸上看见。这里每个用例都同时钉三条路径（validate / render overflow / 实际指令），
 * 任何一条单独改口径都会立刻红。
 */
class LabelLayoutConsistencyTest {

    private LabelTemplateRenderer renderer;

    @BeforeEach
    void createRenderer() {
        renderer = new LabelTemplateRenderer();
    }

    /** 8 个汉字、size 24、声明倍率 1×1、栏宽 100 点 ⇒ 一行 4 字、两行 48 点。 */
    private static String literalAt(int y) {
        return "[{\"type\":\"text\",\"x\":20,\"y\":" + y + ",\"w\":100,\"size\":24,"
                + "\"text\":\"甲乙丙丁戊己庚辛\",\"mulX\":1,\"mulY\":1}]";
    }

    private static BaseLabelTemplate tpl(String elements) {
        BaseLabelTemplate t = new BaseLabelTemplate();
        t.setBizType("FG_ROLL");
        t.setWidthMm(80); t.setHeightMm(60); t.setDpi(203); t.setGapMm(3);
        t.setCopies(1); t.setDirection("NORMAL");
        t.setElements(elements);
        return t;
    }

    private static List<String> textLines(String tspl) {
        return tspl.lines().filter(line -> line.startsWith("TEXT ")).toList();
    }

    private static String commands(RenderResult r) {
        return new TsplLabelRenderer().renderCommandText(r);
    }

    @Test
    @DisplayName("字面量拆两行压在底边上：闸报的确切话术、盒子、TEXT 行数三处同一个结论")
    void literalBoundaryAgreesAcrossGateBoxesAndCommands() {
        List<ValidationIssue> issues = renderer.validate(literalAt(433), "FG_ROLL", 80, 60, 203);
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).index()).isZero();
        assertThat(issues.get(0).message())
                .isEqualTo("这段要拆成 2 行 × 24 点 = 48 点高，从 y=433 起越过下边界 480 点，越界 1 点");

        RenderResult r = renderer.render(tpl(literalAt(433)), Map.of());
        assertThat(r.getElements()).hasSize(1);
        assertThat(r.getElements().get(0).isOverflow()).isTrue();
        assertThat(LabelLayout.segmentsFor(r.getElements().get(0), r.getWidthDots())).containsExactly(
                new LabelLayout.Box(20, 433, 96, 24, "甲乙丙丁"),
                new LabelLayout.Box(20, 457, 96, 24, "戊己庚辛"));
        assertThat(textLines(commands(r))).hasSize(2);
    }

    @Test
    @DisplayName("把 y 从 433 挪到 432：三条路径同时放行（480 恰好压边，不是越界）")
    void sameContentOneDotHigherPassesEverywhere() {
        assertThat(renderer.validate(literalAt(432), "FG_ROLL", 80, 60, 203)).isEmpty();

        RenderResult r = renderer.render(tpl(literalAt(432)), Map.of());
        assertThat(r.getElements().get(0).isOverflow()).isFalse();
        assertThat(LabelLayout.segmentsFor(r.getElements().get(0), r.getWidthDots())).hasSize(2);
        assertThat(textLines(commands(r))).hasSize(2);
    }

    @Test
    @DisplayName("声明 field 的文本：闸不凭空取值（保存时没有单据上下文），取值那一步才按真实值判")
    void fieldContentIsJudgedOnlyAfterResolution() {
        String elements = "[{\"type\":\"text\",\"x\":20,\"y\":433,\"w\":100,\"size\":24,"
                + "\"field\":\"productName\",\"mulX\":1,\"mulY\":1}]";
        assertThat(renderer.validate(elements, "FG_ROLL", 80, 60, 203)).isEmpty();

        RenderResult r = renderer.render(tpl(elements), Map.of("productName", "甲乙丙丁戊己庚辛"));
        assertThat(r.getElements().get(0).isOverflow()).isTrue();
        assertThat(textLines(commands(r))).hasSize(2);
    }

    @Test
    @DisplayName("line 的 h 就是 BAR 厚度；没声明（0）仍是 2 点")
    void lineThicknessDrivesBothBarAndBox() {
        ResolvedElement five = shape("line", 20, 40, 100, 5);
        assertThat(LabelLayout.segmentsFor(five, 640))
                .containsExactly(new LabelLayout.Box(20, 40, 100, 5, ""));
        assertThat(commands(result(five))).contains("BAR 20,40,100,5\r\n");

        ResolvedElement legacy = shape("line", 20, 40, 100, 0);
        assertThat(LabelLayout.segmentsFor(legacy, 640))
                .containsExactly(new LabelLayout.Box(20, 40, 100, 2, ""));
        assertThat(commands(result(legacy))).contains("BAR 20,40,100,2\r\n");
    }

    @Test
    @DisplayName("形状没有内容也必须有盒子：先按类型分派，再谈空文本")
    void shapesKeepBoxesWithoutContent() {
        assertThat(LabelLayout.segmentsFor(shape("rect", 0, 0, 700, 10), 640))
                .containsExactly(new LabelLayout.Box(0, 0, 700, 10, ""));
        assertThat(commands(result(shape("rect", 300, 40, 100, 40))))
                .contains("BAR 300,40,100,2\r\n");
    }

    @Test
    @DisplayName("窄到画不下的矩形按真实外框给盒子（最小 4 点），与四条 BAR 用的是同一个框")
    void rectBoxKeepsMinimumOuterFrame() {
        ResolvedElement thin = shape("rect", 0, 0, 2, 3);
        assertThat(LabelLayout.segmentsFor(thin, 640))
                .containsExactly(new LabelLayout.Box(0, 0, 4, 4, ""));
    }

    @Test
    @DisplayName("条码/二维码给不出精确盒子：segments 为空，但指令照发")
    void codesHaveNoExactSegmentsButStillPrint() {
        for (String type : List.of("barcode", "qrcode")) {
            ResolvedElement e = shape(type, 20, 40, 0, 24);
            e.setContent("PH20261106");
            assertThat(LabelLayout.segmentsFor(e, 640)).isEmpty();
            assertThat(commands(result(e))).contains(type.toUpperCase() + " 20,40,");
        }
    }

    private static ResolvedElement shape(String type, int x, int y, int w, int h) {
        ResolvedElement e = new ResolvedElement();
        e.setType(type); e.setX(x); e.setY(y); e.setW(w); e.setH(h); e.setFontSize(24);
        return e;
    }

    private static RenderResult result(ResolvedElement e) {
        RenderResult r = new RenderResult();
        r.setWidthDots(640); r.setHeightDots(480); r.setDpi(203);
        r.setGapMm(3); r.setDirection("NORMAL"); r.setCopies(1);
        r.setElements(List.of(e));
        return r;
    }
}
