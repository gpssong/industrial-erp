package com.industrial.erp.modules.ai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * v1.1.77 AI 网关配置 — Provider Failover.
 *
 * <p>设计原则 (与项目既有 @Value 风格一致, 不引入 @ConfigurationProperties):
 * <ul>
 *   <li>legacy 单 provider 配置保留 (api-key / model / base-url / timeout-ms), 供 v1.1.75 老容器零改动继续工作。</li>
 *   <li>新增 provider slot 0/1 + 启用顺序 {@code providers} (逗号分隔). {@code providers} 为空时回退 legacy。</li>
 *   <li>{@code providers} 未指定但 legacy api-key 非空 → 自动构造 1 个名为 {@code default} 的 provider。</li>
 *   <li>slot key 为空 → 该 slot 不参与 chain (disable 该家).</li>
 * </ul>
 *
 * <p>env 注入示例 (双站 .env 末尾追加):
 * <pre>
 * ERP_AI_PROVIDERS=agnes,minimax
 * ERP_AI_PROVIDER0_KEY=sk-ag...
 * ERP_AI_PROVIDER1_KEY=         # 留空 = 该家 disabled
 * </pre>
 */
@Component
public class AiProperties {

    // === legacy (向后兼容 v1.1.75 老 env) ===
    @Value("${erp.ai.api-key:}")
    private String legacyApiKey;

    @Value("${erp.ai.model:qwen-plus}")
    private String legacyModel;

    @Value("${erp.ai.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String legacyBaseUrl;

    @Value("${erp.ai.timeout-ms:60000}")
    private int legacyTimeoutMs;

    // === v1.11.77 failover ===
    /** 短冷却 (5xx / timeout / 通用 429), 默认 5 分钟. */
    @Value("${erp.ai.cooldown-ms:300000}")
    private long cooldownMs;

    /** 长冷却 (429 quota / rate_limit_exceeded), 默认 30 分钟. */
    @Value("${erp.ai.cooldown-long-ms:1800000}")
    private long cooldownLongMs;

    /** 全局 thinking 剥离开关 (per-provider 字段可覆盖). */
    @Value("${erp.ai.strip-thinking:true}")
    private boolean stripThinking;

    /** 启用的 provider 顺序, 逗号分隔 (e.g. "agnes,minimax"). 空则回退 legacy. */
    @Value("${erp.ai.providers:}")
    private String providers;

    // === provider slot 0 (主) ===
    @Value("${erp.ai.provider0-name:agnes}")
    private String provider0Name;

    @Value("${erp.ai.provider0-key:}")
    private String provider0Key;

    @Value("${erp.ai.provider0-model:qwen-plus}")
    private String provider0Model;

    @Value("${erp.ai.provider0-base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String provider0BaseUrl;

    @Value("${erp.ai.provider0-timeout-ms:60000}")
    private int provider0TimeoutMs;

    @Value("${erp.ai.provider0-strip-thinking:false}")
    private boolean provider0StripThinking;

    // === provider slot 1 (备) ===
    @Value("${erp.ai.provider1-name:minimax}")
    private String provider1Name;

    @Value("${erp.ai.provider1-key:}")
    private String provider1Key;

    @Value("${erp.ai.provider1-model:MiniMax-M3}")
    private String provider1Model;

    @Value("${erp.ai.provider1-base-url:https://api.minimaxi.com/v1}")
    private String provider1BaseUrl;

    @Value("${erp.ai.provider1-timeout-ms:60000}")
    private int provider1TimeoutMs;

    @Value("${erp.ai.provider1-strip-thinking:true}")
    private boolean provider1StripThinking;

    // === v1.1.78 ASR (App 端语音输入后端兜底, 可选) ===
    // DashScope 录音文件识别 (paraformer), OpenAI 不兼容协议, 不复用 LlmClient.
    @Value("${erp.ai.asr-key:}")
    private String asrKey;

    @Value("${erp.ai.asr-model:paraformer-v2}")
    private String asrModel;

    @Value("${erp.ai.asr-base-url:https://dashscope.aliyuncs.com/api/v1/services/audio/asr}")
    private String asrBaseUrl;

    @Value("${erp.ai.asr-timeout-ms:30000}")
    private int asrTimeoutMs;

    // ====== getters ======
    public String getLegacyApiKey() { return legacyApiKey; }
    public String getLegacyModel() { return legacyModel; }
    public String getLegacyBaseUrl() { return legacyBaseUrl; }
    public int getLegacyTimeoutMs() { return legacyTimeoutMs; }
    public long getCooldownMs() { return cooldownMs; }
    public long getCooldownLongMs() { return cooldownLongMs; }
    public boolean isStripThinking() { return stripThinking; }
    public String getProviders() { return providers; }

    public String getProvider0Name() { return provider0Name; }
    public String getProvider0Key() { return provider0Key; }
    public String getProvider0Model() { return provider0Model; }
    public String getProvider0BaseUrl() { return provider0BaseUrl; }
    public int getProvider0TimeoutMs() { return provider0TimeoutMs; }
    public boolean isProvider0StripThinking() { return provider0StripThinking; }

    public String getProvider1Name() { return provider1Name; }
    public String getProvider1Key() { return provider1Key; }
    public String getProvider1Model() { return provider1Model; }
    public String getProvider1BaseUrl() { return provider1BaseUrl; }
    public int getProvider1TimeoutMs() { return provider1TimeoutMs; }
    public boolean isProvider1StripThinking() { return provider1StripThinking; }

    public String getAsrKey() { return asrKey; }
    public String getAsrModel() { return asrModel; }
    public String getAsrBaseUrl() { return asrBaseUrl; }
    public int getAsrTimeoutMs() { return asrTimeoutMs; }
}