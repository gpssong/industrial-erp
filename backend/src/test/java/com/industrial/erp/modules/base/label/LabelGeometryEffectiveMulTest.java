package com.industrial.erp.modules.base.label;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LabelGeometryEffectiveMulTest {

    @ParameterizedTest
    @CsvSource({"AB,24,2,1,12,20", "中文,24,1,1,24,24", "中文,48,2,2,24,24",
            "AB,12,1,1,12,20", "AB,0,2,1,12,20", "中文,-8,1,1,24,24",
            "中文,384,16,16,24,24"})
    void zeroDeclarationsPreserveLegacyFont(String content, int size, int mx, int my, int bw, int bh) {
        var font = LabelGeometry.effectiveMul(content, size, 0, 0);
        assertEquals(mx, font.mulX());
        assertEquals(my, font.mulY());
        assertEquals(bw, font.baseW());
        assertEquals(bh, font.baseH());
    }

    @Test
    void horizontalDeclarationDoesNotReplaceVerticalFallback() {
        var font = LabelGeometry.effectiveMul("AB", 24, 3, 0);
        assertEquals("2", font.name());
        assertEquals(3, font.mulX());
        assertEquals(1, font.mulY());
        assertEquals(36, font.perCharDots());
        assertEquals(20, font.lineHeightDots());
    }

    @Test
    void verticalDeclarationDoesNotReplaceHorizontalFallback() {
        var font = LabelGeometry.effectiveMul("AB", 24, 0, 3);
        assertEquals(2, font.mulX());
        assertEquals(3, font.mulY());
        assertEquals(24, font.perCharDots());
        assertEquals(60, font.lineHeightDots());
    }

    @ParameterizedTest
    @CsvSource({"999,1,8,1", "-1,1,1,1", "1,999,1,8", "1,-1,1,1",
            "2147483647,-2147483648,8,1", "0,3,16,3", "3,0,3,16"})
    void clampsOnlyDeclaredAxesNotTheLegacyFallback(int mx, int my, int expectedX, int expectedY) {
        var font = LabelGeometry.effectiveMul("中文", 384, mx, my);
        assertEquals("TSS24.BF2", font.name());
        assertEquals(expectedX, font.mulX());
        assertEquals(expectedY, font.mulY());
    }

    @ParameterizedTest
    @CsvSource({"中文,4096,171,171", "中文,2147483647,171,171",
            "AB,4096,341,205", "AB,2147483647,341,205"})
    void boundsSizeBeforeLegacyCalculationWithoutClampingFallbackToEight(
            String content, int size, int mx, int my) {
        var font = LabelGeometry.effectiveMul(content, size, 0, 0);
        assertEquals(mx, font.mulX());
        assertEquals(my, font.mulY());
    }

    @Test
    void legacyWidthHelperUsesTheBoundedZeroDeclarationPath() {
        assertEquals(8208, LabelGeometry.textWidthDots("中文", Integer.MAX_VALUE));
        assertEquals(8184, LabelGeometry.textWidthDots("AB", Integer.MAX_VALUE));
    }

    @ParameterizedTest
    @CsvSource({"24,0,4", "0,0,4", "-8,0,4", "12,0,2", "30,0,5",
            "4096,0,8", "2147483647,0,8", "24,1,2", "24,-1,2", "24,999,8",
            "0,3,3", "384,3,3", "24,-2147483648,2", "24,2147483647,8"})
    void qrCellUsesDeclarationOrLegacySizeFallback(int size, int cell, int expected) {
        assertEquals(expected, LabelGeometry.qrCell(size, cell));
    }
}
