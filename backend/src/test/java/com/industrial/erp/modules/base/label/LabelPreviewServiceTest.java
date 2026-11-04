package com.industrial.erp.modules.base.label;

import com.industrial.erp.modules.base.entity.BaseLabelTemplate;
import com.industrial.erp.modules.base.mapper.BaseLabelTemplateMapper;
import com.industrial.erp.modules.base.service.BaseLabelTemplateService;
import com.industrial.erp.security.PermissionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

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

        assertThat(out.get(0).getElements().get(2).getSvg()).isNull();
        assertThat(out.get(0).getElements().get(2).getContent()).isEqualTo("透明无色");
    }
}
