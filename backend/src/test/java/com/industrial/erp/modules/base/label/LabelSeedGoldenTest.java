package com.industrial.erp.modules.base.label;

import com.industrial.erp.modules.base.entity.BaseLabelTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class LabelSeedGoldenTest {

    private static final Charset GBK = Charset.forName("GBK");
    private static final String HEADER = "SIZE 80 mm,60 mm\r\nGAP 3 mm,0 mm\r\n"
            + "DIRECTION 0\r\nCODEPAGE 936\r\nCLS\r\n";
    private static final String PRINT = "PRINT 1,1\r\n";

    @ParameterizedTest
    @CsvSource({"fg-roll,FG_ROLL", "raw-pallet,RAW_PALLET", "carton,CARTON"})
    void seedKeepsFrozenGbkBytes(String name, String bizType) throws IOException {
        BaseLabelTemplate template = new BaseLabelTemplate();
        template.setBizType(bizType);
        template.setWidthMm(80);
        template.setHeightMm(60);
        template.setGapMm(3);
        template.setDpi(203);
        template.setDirection("NORMAL");
        template.setCopies(1);
        template.setElements(new String(resource(name + ".json"), StandardCharsets.UTF_8));

        RenderResult result = new LabelTemplateRenderer().render(template, context());

        assertArrayEquals(resource(name + ".tspl"), new TsplLabelRenderer().renderBytes(result));
    }

    @ParameterizedTest
    @ValueSource(strings = {"text", "multiline"})
    void longChineseKeepsFixedTextCommandsAndGbkCrLfBytes(String type) {
        ResolvedElement e = element(type, "甲乙丙丁戊己庚辛壬癸");
        e.setW(100);
        assertCommands(HEADER
                + "TEXT 20,40,\"TSS24.BF2\",0,1,1,\"甲乙丙丁\"\r\n"
                + "TEXT 20,64,\"TSS24.BF2\",0,1,1,\"戊己庚辛\"\r\n"
                + "TEXT 20,88,\"TSS24.BF2\",0,1,1,\"壬癸\"\r\n"
                + PRINT, e);
    }

    @Test
    void asciiOnlyWrappedSegmentKeepsWholeContentCjkFont() {
        ResolvedElement e = element("text", "AB甲乙");
        e.setW(48);
        assertCommands(HEADER
                + "TEXT 20,40,\"TSS24.BF2\",0,1,1,\"AB\"\r\n"
                + "TEXT 20,64,\"TSS24.BF2\",0,1,1,\"甲乙\"\r\n"
                + PRINT, e);
    }

    @Test
    void boldAddsOnlyOneXOffsetOverprint() {
        ResolvedElement e = element("text", "合格");
        e.setBold(true);
        assertCommands(HEADER
                + "TEXT 20,40,\"TSS24.BF2\",0,1,1,\"合格\"\r\n"
                + "TEXT 21,40,\"TSS24.BF2\",0,1,1,\"合格\"\r\n"
                + PRINT, e);
    }

    @Test
    void sanitizesTextBeforeWrappingWithoutTrimmingEachSegment() {
        ResolvedElement e = element("text", " 甲\"乙\n丙 ");
        e.setW(96);
        assertCommands(HEADER
                + "TEXT 20,40,\"TSS24.BF2\",0,1,1,\"甲'乙 \"\r\n"
                + "TEXT 20,64,\"TSS24.BF2\",0,1,1,\"丙\"\r\n"
                + PRINT, e);
    }

    @Test
    void codesKeepSanitizationAndOriginalParameters() {
        ResolvedElement barcode = element("barcode", " A\"B\r\nC ");
        barcode.setFontSize(60);
        ResolvedElement qrcode = element("qrcode", " A\"B\r\nC ");
        assertCommands(HEADER
                + "BARCODE 20,40,\"128\",60,2,0,2,2,\"A'B  C\"\r\n"
                + "QRCODE 20,40,L,4,0,A,\"A'B  C\"\r\n"
                + PRINT, barcode, qrcode);
    }

    private static ResolvedElement element(String type, String content) {
        ResolvedElement e = new ResolvedElement();
        e.setType(type);
        e.setContent(content);
        e.setFontSize(24);
        e.setX(20);
        e.setY(40);
        return e;
    }

    private static void assertCommands(String expected, ResolvedElement... elements) {
        RenderResult result = new RenderResult();
        result.setWidthDots(640);
        result.setHeightDots(480);
        result.setGapMm(3);
        result.setDpi(203);
        result.setDirection("NORMAL");
        result.setCopies(1);
        result.setElements(List.of(elements));
        TsplLabelRenderer renderer = new TsplLabelRenderer();
        assertEquals(expected, renderer.renderCommandText(result));
        assertArrayEquals(expected.getBytes(GBK), renderer.renderBytes(result));
    }

    private static byte[] resource(String name) throws IOException {
        try (var input = LabelSeedGoldenTest.class.getResourceAsStream("/label-p1/" + name)) {
            assertNotNull(input, "Missing frozen fixture: " + name);
            return input.readAllBytes();
        }
    }

    private static Map<String, Object> context() {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("productName", "背心袋"); ctx.put("productCode", "FG001");
        ctx.put("spec", "30x45cm"); ctx.put("unitName", "个");
        ctx.put("qty", new BigDecimal("1200.00")); ctx.put("batchNo", "PH20261106A");
        ctx.put("orderNo", "PD202611060001"); ctx.put("billNo", "PFI202611060001");
        ctx.put("productionDate", LocalDate.of(2026, 11, 6));
        ctx.put("supplierName", "示例供应商"); ctx.put("receiptBillNo", "PR202611060001");
        ctx.put("inspectionReportNo", "BG-YL-20261106-001");
        ctx.put("customerName", "示例客户"); ctx.put("deliveryAddress", "示例地址");
        ctx.put("deliveryPhone", "00000000000");
        ctx.put("deliveryBillNo", "SD202611060001");
        ctx.put("deliveryDate", LocalDate.of(2026, 11, 6));
        ctx.put("producerName", "示例生产者"); ctx.put("producerAddress", "示例厂址");
        ctx.put("xkNo", "示例证号"); ctx.put("execStandard", "示例标准");
        ctx.put("storageCondition", "阴凉干燥"); ctx.put("inspectorCode", "QC01");
        return ctx;
    }
}
