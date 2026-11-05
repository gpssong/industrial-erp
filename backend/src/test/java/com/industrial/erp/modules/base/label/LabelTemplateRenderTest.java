package com.industrial.erp.modules.base.label;

import com.industrial.erp.modules.base.entity.BaseLabelTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LabelTemplateRenderTest {

    private final LabelTemplateRenderer renderer = new LabelTemplateRenderer();

    private BaseLabelTemplate tpl(String elements) {
        BaseLabelTemplate t = new BaseLabelTemplate();
        t.setBizType("FG_ROLL");
        t.setWidthMm(80); t.setHeightMm(60); t.setDpi(203); t.setGapMm(3); t.setCopies(2);
        t.setDirection("NORMAL");
        t.setElements(elements);
        return t;
    }

    @Test
    @DisplayName("字面量与字段各取所需；BigDecimal 去尾零、LocalDate 出 yyyy-MM-dd")
    void resolvesValues() {
        RenderResult r = renderer.render(tpl(
                "[{\"type\":\"text\",\"x\":0,\"y\":0,\"text\":\"批号\",\"size\":24},"
              + "{\"type\":\"text\",\"x\":80,\"y\":0,\"field\":\"batchNo\",\"size\":24},"
              + "{\"type\":\"text\",\"x\":0,\"y\":40,\"field\":\"qty\",\"size\":24},"
              + "{\"type\":\"text\",\"x\":0,\"y\":80,\"field\":\"productionDate\",\"size\":24}]"),
                Map.of("batchNo", "B20261104", "qty", new BigDecimal("5000.0000"),
                        "productionDate", LocalDate.of(2026, 10, 28)));

        List<ResolvedElement> es = r.getElements();
        assertThat(es).hasSize(4);
        assertThat(es.get(0).getContent()).isEqualTo("批号");
        assertThat(es.get(1).getContent()).isEqualTo("B20261104");
        assertThat(es.get(2).getContent()).isEqualTo("5000");
        assertThat(es.get(3).getContent()).isEqualTo("2026-10-28");
        assertThat(r.getCopies()).isEqualTo(2);
        assertThat(r.getWidthDots()).isEqualTo(640);
        assertThat(r.getHeightDots()).isEqualTo(480);
        assertThat(r.getMissingRequired()).isEmpty();
    }

    @Test
    @DisplayName("required 字段为空 → 标 missing + 进 missingRequired 清单，但**不抛错不阻断** (docs/32 §5)")
    void missingRequiredIsFlaggedNotThrown() {
        RenderResult r = renderer.render(tpl(
                "[{\"type\":\"text\",\"x\":0,\"y\":0,\"field\":\"batchNo\",\"size\":24,\"required\":true},"
              + "{\"type\":\"text\",\"x\":0,\"y\":40,\"field\":\"productName\",\"size\":24,\"required\":true}]"),
                Map.of("batchNo", "", "productName", "PE 袋"));

        assertThat(r.getMissingRequired()).containsExactly("batchNo");
        assertThat(r.getElements().get(0).isMissing()).isTrue();
        assertThat(r.getElements().get(1).isMissing()).isFalse();
        assertThat(r.getElements().get(0).getContent()).isEmpty();
    }

    @Test
    @DisplayName("上下文里根本没有的字段名 → 渲染成空串并记进清单（保存闸已拦，这里是防历史模板/手改库的兜底）")
    void unknownFieldRendersBlank() {
        RenderResult r = renderer.render(tpl(
                "[{\"type\":\"text\",\"x\":0,\"y\":0,\"field\":\"nonexistent\",\"size\":24}]"), Map.of());
        assertThat(r.getElements().get(0).getContent()).isEmpty();
    }

    @Test
    @DisplayName("取值后**拆行**会溢出下边界 → 元素标 overflow（CARTON 的 batchNo | 串就是这一类）")
    void flagsOverflowAfterResolution() {
        RenderResult r = renderer.render(tpl(
                "[{\"type\":\"text\",\"x\":500,\"y\":440,\"field\":\"batchNo\",\"size\":24}]"),
                Map.of("batchNo", "B20261104|B20261103|B20261102|"));
        assertThat(r.getElements().get(0).isOverflow()).isTrue();
    }

    @Test
    @DisplayName("同一条长批号串挪到纸面上方 ⇒ 拆 6 行仍放得下 ⇒ **不标橙**（旧口径按单行估宽会误报）")
    void longTextThatFitsAfterWrappingIsNotFlagged() {
        RenderResult r = renderer.render(tpl(
                "[{\"type\":\"text\",\"x\":500,\"y\":100,\"field\":\"batchNo\",\"size\":24}]"),
                Map.of("batchNo", "B20261104|B20261103|B20261102|"));
        assertThat(r.getElements().get(0).isOverflow()).isFalse();
    }

    @Test
    @DisplayName("line/rect 只带几何，content 用 text 当标题")
    void shapesCarryGeometry() {
        RenderResult r = renderer.render(tpl(
                "[{\"type\":\"line\",\"x\":20,\"y\":180,\"w\":600,\"h\":2},"
              + "{\"type\":\"rect\",\"x\":10,\"y\":10,\"w\":620,\"h\":460,\"text\":\"外框\"}]"), Map.of());
        assertThat(r.getElements().get(0).getType()).isEqualTo("line");
        assertThat(r.getElements().get(0).getW()).isEqualTo(600);
        assertThat(r.getElements().get(1).getContent()).isEqualTo("外框");
    }

    @Test
    @DisplayName("同一元素既写 field 又写 text：取到值用值，取不到就是**空串**——绝不拿 text 当兜底值 (A7 裁定 R1)")
    void fieldNeverFallsBackToLiteralText() {
        // docs/32 §3 里 text 与 field 是「二选一」，rect 的 text 才是标题；
        // 若让空字段回落到字面量，「批号：」这种标题会被当成批号打出去，
        // 而且 required 的预览标红会因此**永远不出现** —— 合规缺口就这么被抹平。
        RenderResult r = renderer.render(tpl(
                "[{\"type\":\"text\",\"x\":0,\"y\":0,\"field\":\"batchNo\",\"text\":\"批号：\",\"size\":24,\"required\":true}]"),
                Map.of("batchNo", ""));
        assertThat(r.getElements().get(0).getContent()).isEmpty();
        assertThat(r.getElements().get(0).isMissing()).isTrue();
        assertThat(r.getMissingRequired()).containsExactly("batchNo");

        RenderResult hit = renderer.render(tpl(
                "[{\"type\":\"text\",\"x\":0,\"y\":0,\"field\":\"batchNo\",\"text\":\"批号：\",\"size\":24}]"),
                Map.of("batchNo", "B20261104"));
        assertThat(hit.getElements().get(0).getContent()).isEqualTo("B20261104");
    }

    @Test
    @DisplayName("barcode/qrcode 不标越界：宽度按内容与模块自适应，和 A6 保存闸的条码免检同口径 (A7 裁定 R2)")
    void codeTypesAreNotWidthEstimated() {
        RenderResult r = renderer.render(tpl(
                "[{\"type\":\"barcode\",\"x\":500,\"y\":100,\"field\":\"batchNo\",\"size\":60},"
              + "{\"type\":\"qrcode\",\"x\":500,\"y\":300,\"field\":\"batchNo\",\"size\":8}]"),
                Map.of("batchNo", "B20261104|B20261103|B20261102|"));
        assertThat(r.getElements().get(0).isOverflow()).isFalse();
        assertThat(r.getElements().get(1).isOverflow()).isFalse();
        // 但显式声明了 w 就要按 w 查 —— 那是用户自己给的尺寸，不是我们的估算
        RenderResult declared = renderer.render(tpl(
                "[{\"type\":\"barcode\",\"x\":500,\"y\":100,\"field\":\"batchNo\",\"size\":60,\"w\":300}]"),
                Map.of("batchNo", "B20261104"));
        assertThat(declared.getElements().get(0).isOverflow()).isTrue();
    }

    @Test
    @DisplayName("日期时间列统一只取日期；null 一律空串")
    void stringifyRules() {
        assertThat(renderer.stringify(null)).isEmpty();
        assertThat(renderer.stringify(LocalDateTime.of(2026, 11, 4, 9, 30))).isEqualTo("2026-11-04");
        assertThat(renderer.stringify(new BigDecimal("12.500"))).isEqualTo("12.5");
    }
}
