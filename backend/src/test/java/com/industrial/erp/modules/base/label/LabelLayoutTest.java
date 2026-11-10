package com.industrial.erp.modules.base.label;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LabelLayoutTest {

    @Test
    void wrapsCjkIntoFixedPrinterBoxes() {
        var e = text("甲乙丙丁戊己庚辛壬癸", 24, 20, 40, 100);
        assertEquals(List.of(
                new LabelLayout.Box(20, 40, 96, 24, "甲乙丙丁"),
                new LabelLayout.Box(20, 64, 96, 24, "戊己庚辛"),
                new LabelLayout.Box(20, 88, 48, 24, "壬癸")
        ), LabelLayout.segmentsFor(e, 640));
    }

    @Test
    void sanitizesBeforeMeasuringAndSplitting() {
        var e = text(" 甲\"乙\n丙 ", 24, 20, 40, 96);
        assertEquals(List.of(
                new LabelLayout.Box(20, 40, 96, 24, "甲'乙 "),
                new LabelLayout.Box(20, 64, 24, 24, "丙")
        ), LabelLayout.segmentsFor(e, 640));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t\r\n "})
    void blankContentHasNoBoxesOrText(String content) {
        var e = text(content, 24, 20, 40, 96);
        assertEquals(List.of(), LabelLayout.segmentsFor(e, 640));
        assertFalse(commands(e).contains("TEXT "));
        e.setType("multiline");
        assertFalse(commands(e).contains("TEXT "));
    }

    @ParameterizedTest
    @CsvSource({"700,0", "630,0", "20,12"})
    void keepsSingleBoxWhenNoCharacterFits(int x, int w) {
        var e = text("AB", 24, x, 40, w);
        assertEquals(List.of(new LabelLayout.Box(x, 40, 48, 20, "AB")),
                LabelLayout.segmentsFor(e, 640));
    }

    @Test
    void countsSurrogatePairAsOneCharacterWithoutSplittingIt() {
        var e = text("A\uD83D\uDE00B", 24, 20, 40, 48);
        assertEquals(List.of(
                new LabelLayout.Box(20, 40, 48, 20, "A\uD83D\uDE00"),
                new LabelLayout.Box(20, 60, 24, 20, "B")
        ), LabelLayout.segmentsFor(e, 640));
    }

    @Test
    void boldOverprintDoesNotDuplicateLayoutBox() {
        var e = text("合格", 24, 20, 40, 100);
        e.setBold(true);
        assertEquals(List.of(new LabelLayout.Box(20, 40, 48, 24, "合格")),
                LabelLayout.segmentsFor(e, 640));
        assertEquals(List.of(
                "TEXT 20,40,\"TSS24.BF2\",0,1,1,\"合格\"",
                "TEXT 21,40,\"TSS24.BF2\",0,1,1,\"合格\""
        ), commands(e).lines().filter(line -> line.startsWith("TEXT ")).toList());
    }

    @Test
    void asciiOnlyWrappedSegmentKeepsWholeContentCjkMetrics() {
        var e = text("AB甲乙", 24, 20, 40, 48);
        assertEquals(List.of(
                new LabelLayout.Box(20, 40, 48, 24, "AB"),
                new LabelLayout.Box(20, 64, 48, 24, "甲乙")
        ), LabelLayout.segmentsFor(e, 640));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 100})
    void paperEdgeLimitsBothUndeclaredAndWiderColumns(int w) {
        var e = text("甲乙丙", 24, 580, 40, w);
        assertEquals(List.of(
                new LabelLayout.Box(580, 40, 48, 24, "甲乙"),
                new LabelLayout.Box(580, 64, 24, 24, "丙")
        ), LabelLayout.segmentsFor(e, 640));
    }

    @Test
    void asciiBoxesKeepIndependentLegacyHorizontalAndVerticalScale() {
        var e = text("ABC", 48, 20, 40, 96);
        assertEquals(List.of(
                new LabelLayout.Box(20, 40, 96, 40, "AB"),
                new LabelLayout.Box(20, 80, 48, 40, "C")
        ), LabelLayout.segmentsFor(e, 640));
    }

    @ParameterizedTest
    @ValueSource(strings = {"barcode", "qrcode"})
    void codeTypesNeverClaimExactBoxes(String type) {
        var e = text("PH20261106", 24, 20, 40, 0);
        e.setType(type);
        e.setH(60);
        assertEquals(List.of(), LabelLayout.segmentsFor(e, 640));
    }

    @Test
    void lineBoxKeepsDeclaredLengthAndMinimumOneDot() {
        assertEquals(List.of(new LabelLayout.Box(20, 40, 1, 2, "")),
                LabelLayout.segmentsFor(shape("line", 0), 640));
        assertEquals(List.of(new LabelLayout.Box(20, 40, 600, 2, "")),
                LabelLayout.segmentsFor(shape("line", 600), 640));
    }

    @Test
    void rectBoxUsesRealOuterFrame() {
        var e = shape("rect", 100);
        e.setH(40);
        assertEquals(List.of(new LabelLayout.Box(20, 40, 100, 40, "")),
                LabelLayout.segmentsFor(e, 640));
    }

    private static ResolvedElement shape(String type, int w) {
        ResolvedElement e = new ResolvedElement();
        e.setType(type); e.setX(20); e.setY(40); e.setW(w); e.setFontSize(24);
        return e;
    }

    private static String commands(ResolvedElement e) {
        RenderResult result = new RenderResult();
        result.setWidthDots(640);
        result.setHeightDots(480);
        result.setGapMm(3);
        result.setDpi(203);
        result.setDirection("NORMAL");
        result.setCopies(1);
        result.setElements(List.of(e));
        return new TsplLabelRenderer().renderCommandText(result);
    }

    private static ResolvedElement text(String content, int size, int x, int y, int w) {
        ResolvedElement e = new ResolvedElement();
        e.setType("text"); e.setContent(content); e.setFontSize(size);
        e.setX(x); e.setY(y); e.setW(w);
        return e;
    }
}
