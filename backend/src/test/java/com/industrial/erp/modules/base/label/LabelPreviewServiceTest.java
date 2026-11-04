package com.industrial.erp.modules.base.label;

import com.industrial.erp.modules.base.entity.BaseLabelTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LabelPreviewServiceTest {

    @Mock private LabelContextService contextService;

    private BaseLabelTemplate tpl() {
        BaseLabelTemplate t = new BaseLabelTemplate();
        t.setId(1L); t.setBizType("FG_ROLL"); t.setTemplateCode("FG_ROLL_DEFAULT");
        t.setWidthMm(80); t.setHeightMm(60); t.setDpi(203); t.setGapMm(3); t.setCopies(1); t.setDirection("NORMAL");
        t.setElements("[{\"type\":\"text\",\"x\":20,\"y\":20,\"field\":\"batchNo\",\"size\":24},"
                + "{\"type\":\"qrcode\",\"x\":480,\"y\":200,\"size\":7,\"field\":\"batchNo\"},"
                + "{\"type\":\"barcode\",\"x\":20,\"y\":320,\"size\":60,\"field\":\"batchNo\"}]");
        return t;
    }

    @Test
    @DisplayName("预览把 batchNo 解析出来，并为二维码/条码各生成一张 PNG data URL")
    void fillsSvgForCodes() {
        when(contextService.load(any(), any(), any())).thenReturn(List.of(Map.of("batchNo", "B20261104")));

        List<RenderResult> out = new LabelPreviewService(contextService, new LabelTemplateRenderer())
                .preview(tpl(), 1L, null);

        assertThat(out).hasSize(1);
        List<ResolvedElement> es = out.get(0).getElements();
        assertThat(es.get(0).getContent()).isEqualTo("B20261104");
        assertThat(es.get(0).getSvg()).isNull();
        assertThat(es.get(1).getSvg()).startsWith("data:image/png;base64,");
        assertThat(es.get(2).getSvg()).startsWith("data:image/png;base64,");
    }

    @Test
    @DisplayName("中文进 CODE128 编不出来 → svg 留空 + content 仍给前端显示，不抛错、不阻断预览")
    void toleratesUnencodableContent() {
        when(contextService.load(any(), any(), any())).thenReturn(List.of(Map.of("batchNo", "透明无色")));

        List<RenderResult> out = new LabelPreviewService(contextService, new LabelTemplateRenderer())
                .preview(tpl(), 1L, null);

        // 同一份中文内容二维码**是**拿到 PNG 的（二维码按 UTF-8 装得下中文）——
        // 这条排除「encodePng 一律回 null」那种退化，上面那条 null 断言才有意义。
        assertThat(out.get(0).getElements().get(1).getSvg()).startsWith("data:image/png;base64,");
        assertThat(out.get(0).getElements().get(2).getSvg()).isNull();
        assertThat(out.get(0).getElements().get(2).getContent()).isEqualTo("透明无色");
    }

    @Test
    @DisplayName("asciiOnly：ASCII 预检自己判得准，不依赖 zxing 抛不抛（R5 的可证伪钉子）")
    void asciiOnlyJudgesASCIIRange() {
        assertThat(LabelPreviewService.asciiOnly("PFI0001-2026")).isTrue();
        assertThat(LabelPreviewService.asciiOnly("PE 平口袋")).isFalse();
        assertThat(LabelPreviewService.asciiOnly("")).isTrue();
    }

    @Test
    @DisplayName("模板 size 是用户存进库的数据 → 预览光栅两个维度都封顶 1024 点，正常模板尺寸不变")
    void clampsPreviewRasterSize() {
        when(contextService.load(any(), any(), any())).thenReturn(List.of(Map.of("batchNo", "PFI0001-2026")));

        List<RenderResult> out = new LabelPreviewService(contextService, new LabelTemplateRenderer())
                .preview(hugeTpl(), 1L, null);

        List<ResolvedElement> es = out.get(0).getElements();
        // 封顶不许把正常模板也削小：size 7 ⇒ 175 见方、size 60 ⇒ 300×60
        assertThat(pngSize(es.get(0).getSvg())).containsExactly(175, 175);
        assertThat(pngSize(es.get(2).getSvg())).containsExactly(300, 60);
        // 越过 1024 的夹到 1024：不夹的话 size 100 = 2500 见方、size 40000 = 300×40000，
        // size 再大就是 OutOfMemoryError —— Error 不是 Exception，encodePng 的 catch 拦不住，整页 500。
        assertThat(pngSize(es.get(1).getSvg())).containsExactly(1024, 1024);
        assertThat(pngSize(es.get(3).getSvg())).containsExactly(300, 1024);
    }

    /** 只把 size 换成越过 1024 点的两份码图，其余与 {@link #tpl()} 同一套配置。 */
    private BaseLabelTemplate hugeTpl() {
        BaseLabelTemplate t = new BaseLabelTemplate();
        t.setId(2L); t.setBizType("FG_ROLL"); t.setTemplateCode("FG_ROLL_HUGE");
        t.setWidthMm(80); t.setHeightMm(60); t.setDpi(203); t.setGapMm(3); t.setCopies(1); t.setDirection("NORMAL");
        t.setElements("[{\"type\":\"qrcode\",\"x\":20,\"y\":20,\"field\":\"batchNo\",\"size\":7},"
                + "{\"type\":\"qrcode\",\"x\":20,\"y\":600,\"field\":\"batchNo\",\"size\":100},"
                + "{\"type\":\"barcode\",\"x\":20,\"y\":700,\"field\":\"batchNo\",\"size\":60},"
                + "{\"type\":\"barcode\",\"x\":20,\"y\":800,\"field\":\"batchNo\",\"size\":40000}]");
        return t;
    }

    /**
     * 从 PNG data URL 读出光栅的宽/高 —— 只读文件头，不解码整张位图：
     * PNG 规定 IHDR 必是第一个块，所以签名 8 字节 + 块长 4 字节 + "IHDR" 4 字节之后就是宽、高各 4 字节大端。
     */
    private static int[] pngSize(String dataUrl) {
        byte[] png = Base64.getDecoder().decode(dataUrl.substring("data:image/png;base64,".length()));
        return new int[] { be32(png, 16), be32(png, 20) };
    }

    private static int be32(byte[] b, int i) {
        return ((b[i] & 0xFF) << 24) | ((b[i + 1] & 0xFF) << 16) | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
    }
}
