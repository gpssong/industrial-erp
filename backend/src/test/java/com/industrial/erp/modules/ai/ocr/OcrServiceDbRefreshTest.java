package com.industrial.erp.modules.ai.ocr;

import com.industrial.erp.modules.ai.client.LlmClient;
import com.industrial.erp.modules.ai.config.AiProperties;
import com.industrial.erp.modules.system.service.SysConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v1.1.165 — 状态查询侧也要吃 DB 覆盖.
 *
 * <p>线上现象 (2026-11-03 canary 部署后实测)：`sys_config.AI_INTEGRATION.ocr` 里 TextIn 凭证齐了,
 * 但 `GET /ai/ocr/status` 仍回 {@code provider=none / enabled=false} —— 因为原先只有
 * {@code recognizeLabels()} 会读 DB, 而按钮被 {@code enabled()} 灰掉后用户根本触发不了识别,
 * 于是「配了等于没配」是死循环。这三例把这条口径钉住.
 */
@DisplayName("OcrService: DB 覆盖在 enabled()/provider() 查询侧生效")
class OcrServiceDbRefreshTest {

    private static final String TEXTIN_JSON = "{\"ocr\":{\"provider\":\"textin\","
            + "\"textinAppId\":\"app-id-abc\",\"textinSecretCode\":\"secret-xyz\"}}";

    private OcrService newService(SysConfigService cfg) throws Exception {
        OcrService svc = new OcrService(new AiProperties());
        Field f = OcrService.class.getDeclaredField("sysConfigService");
        f.setAccessible(true);
        f.set(svc, cfg);
        return svc;
    }

    @Test
    @DisplayName("没调过识别、只查状态也能看到 DB 里的 TextIn")
    void statusReadsDbOverride() throws Exception {
        SysConfigService cfg = mock(SysConfigService.class);
        when(cfg.getByKey(LlmClient.DB_KEY)).thenReturn(TEXTIN_JSON);
        OcrService svc = newService(cfg);

        assertThat(svc.enabled()).isTrue();
        assertThat(svc.provider()).isEqualTo(OcrService.PROVIDER_TEXTIN);
        assertThat(svc.model()).isEqualTo("textin-general-ocr");
    }

    @Test
    @DisplayName("DB 没配 OCR → 服务商 none, 不影响 env-only 站点")
    void nothingConfiguredIsNone() throws Exception {
        SysConfigService cfg = mock(SysConfigService.class);
        when(cfg.getByKey(LlmClient.DB_KEY)).thenReturn("{\"llm\":{\"providerOrder\":\"agnes\"}}");
        OcrService svc = newService(cfg);

        assertThat(svc.provider()).isEqualTo(OcrService.PROVIDER_NONE);
        assertThat(svc.enabled()).isFalse();
        assertThat(svc.model()).isEmpty();
    }

    @Test
    @DisplayName("保存后立即 refreshNow() 就见到新值 (TTL 窗口不挡保存路径)")
    void refreshNowBypassesTtl() throws Exception {
        SysConfigService cfg = mock(SysConfigService.class);
        when(cfg.getByKey(LlmClient.DB_KEY)).thenReturn(null);
        OcrService svc = newService(cfg);
        assertThat(svc.provider()).isEqualTo(OcrService.PROVIDER_NONE);   // 吃掉 TTL 起点

        when(cfg.getByKey(LlmClient.DB_KEY)).thenReturn(TEXTIN_JSON);
        svc.refreshNow();
        assertThat(svc.provider()).isEqualTo(OcrService.PROVIDER_TEXTIN);
    }
}
