package com.industrial.erp.modules.ai.config;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    // ==================================================================
    // v1.1.80 — DB 覆盖层 (sys_config.AI_INTEGRATION JSON).
    //
    // 设计: env 为底, DB 为顶. AiProperties 的 @Value 字段是「底」(env 决定),
    // 这里的 override 字段是「顶」(DB 决定). effectiveXxx() 读 override,
    // override 空则回落 env. applyIntegrationJson(json) 被 LlmClient / AsrService
    // 在每次调用前调 (带 TTL cache), 改 DB 即时生效, 无需重建 jar.
    //
    // 老库 (sys_config 无 AI_INTEGRATION 行) 时 json=null, override 全 null,
    // 行为与纯 env 完全一致 — 零破坏.
    // ==================================================================

    /** DB 覆盖: LLM provider 顺序 (逗号串, 如 "minimax,agnes"). null = 用 env providers. */
    private volatile String ovProviders;

    /** DB 覆盖: provider slot 0/1 的 6 字段. null = 用 env. */
    private volatile String ovProvider0Key;
    private volatile String ovProvider0Model;
    private volatile String ovProvider0BaseUrl;
    private volatile String ovProvider0TimeoutMs;
    private volatile String ovProvider0StripThinking;
    private volatile String ovProvider1Key;
    private volatile String ovProvider1Model;
    private volatile String ovProvider1BaseUrl;
    private volatile String ovProvider1TimeoutMs;
    private volatile String ovProvider1StripThinking;

    /** DB 覆盖: legacy 单 provider. */
    private volatile String ovLegacyApiKey;
    private volatile String ovLegacyModel;
    private volatile String ovLegacyBaseUrl;

    /** DB 覆盖: ASR. */
    private volatile String ovAsrKey;
    private volatile String ovAsrModel;
    private volatile String ovAsrBaseUrl;
    private volatile String ovAsrTimeoutMs;

    // ===== v1.1.117 — 模型接口配置卡: 能力开关 + 每日上限 (UI 默认 business/quality=true, allowWrite=false) =====
    /** DB 覆盖: 能力开关. null = 未设 (回落 UI 默认). */
    private volatile Boolean ovBusiness;
    private volatile Boolean ovQuality;
    private volatile Boolean ovAllowWrite;
    /** DB 覆盖: 每日调用上限 (v1.1.117 仅存 + 展示, 限流计数留 v1.1.118). */
    private volatile String ovDailyLimit;

    /** 上次成功解析的 DB JSON (供日志/对账; 空 JSON 视为无覆盖). */
    private volatile String lastIntegrationJson;

    /**
     * 解析 sys_config.AI_INTEGRATION 的 JSON 并覆盖 override 字段.
     *
     * <p>JSON 形态 (所有字段可选, 空对象 = 全回落 env):
     * <pre>
     * {
     *   "llm": {
     *     "providerOrder": "minimax,agnes",
     *     "slot0": {"name":"agnes","key":"***","model":"...","baseUrl":"...","timeoutMs":60000,"stripThinking":false},
     *     "slot1": {"name":"minimax","key":"***","model":"MiniMax-M3","baseUrl":"..."}
     *   },
     *   "legacy": {"key":"...","model":"...","baseUrl":"..."},
     *   "asr":  {"key":"...","model":"...","baseUrl":"...","timeoutMs":30000}
     * }
     * </pre>
     *
     * <p>幂等 + 容错: 解析失败 → 清空 override (回落纯 env) + warn, 不抛 (R13 静默失败).
     * null/空串 json → 清 override, 行为 = 纯 env.
     */
    public void applyIntegrationJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            clearOverrides();
            lastIntegrationJson = null;
            return;
        }
        try {
            JSONObject root = JSONUtil.parseObj(json);
            lastIntegrationJson = json;

            // --- LLM ---
            JSONObject llm = root.getJSONObject("llm");
            if (llm != null) {
                ovProviders = llm.getStr("providerOrder");   // null = 用 env
                JSONObject s0 = llm.getJSONObject("slot0");
                if (s0 != null) {
                    ovProvider0Key   = s0.getStr("key");
                    ovProvider0Model = s0.getStr("model");
                    ovProvider0BaseUrl = s0.getStr("baseUrl");
                    ovProvider0TimeoutMs = s0.getStr("timeoutMs");
                    ovProvider0StripThinking = s0.getStr("stripThinking");
                } else {
                    ovProvider0Key = ovProvider0Model = ovProvider0BaseUrl = null;
                    ovProvider0TimeoutMs = ovProvider0StripThinking = null;
                }
                JSONObject s1 = llm.getJSONObject("slot1");
                if (s1 != null) {
                    ovProvider1Key   = s1.getStr("key");
                    ovProvider1Model = s1.getStr("model");
                    ovProvider1BaseUrl = s1.getStr("baseUrl");
                    ovProvider1TimeoutMs = s1.getStr("timeoutMs");
                    ovProvider1StripThinking = s1.getStr("stripThinking");
                } else {
                    ovProvider1Key = ovProvider1Model = ovProvider1BaseUrl = null;
                    ovProvider1TimeoutMs = ovProvider1StripThinking = null;
                }
                // v1.1.117 — 能力开关 + 每日上限 (缺省 = 回落 UI 默认: business/quality=true, allowWrite=false)
                JSONObject caps = llm.getJSONObject("capabilities");
                if (caps != null) {
                    ovBusiness   = caps.containsKey("business")   ? caps.getBool("business")   : Boolean.TRUE;
                    ovQuality    = caps.containsKey("quality")    ? caps.getBool("quality")    : Boolean.TRUE;
                    ovAllowWrite = caps.containsKey("allowWrite") ? caps.getBool("allowWrite") : Boolean.FALSE;
                } else {
                    ovBusiness = ovQuality = ovAllowWrite = null;
                }
                ovDailyLimit = llm.getStr("dailyLimit");
            } else {
                ovProviders = null;
                ovBusiness = ovQuality = ovAllowWrite = null;
                ovDailyLimit = null;
            }

            // --- legacy ---
            JSONObject legacy = root.getJSONObject("legacy");
            if (legacy != null) {
                ovLegacyApiKey = legacy.getStr("key");
                ovLegacyModel = legacy.getStr("model");
                ovLegacyBaseUrl = legacy.getStr("baseUrl");
            } else {
                ovLegacyApiKey = ovLegacyModel = ovLegacyBaseUrl = null;
            }

            // --- ASR ---
            JSONObject asr = root.getJSONObject("asr");
            if (asr != null) {
                ovAsrKey = asr.getStr("key");
                ovAsrModel = asr.getStr("model");
                ovAsrBaseUrl = asr.getStr("baseUrl");
                ovAsrTimeoutMs = asr.getStr("timeoutMs");
            } else {
                ovAsrKey = ovAsrModel = ovAsrBaseUrl = ovAsrTimeoutMs = null;
            }
        } catch (Exception e) {
            log.warn("[AiProperties] AI_INTEGRATION JSON 解析失败, 回落纯 env: {}", e.getMessage());
            clearOverrides();
            lastIntegrationJson = null;
        }
    }

    private void clearOverrides() {
        ovProviders = null;
        ovProvider0Key = ovProvider0Model = ovProvider0BaseUrl = ovProvider0TimeoutMs = ovProvider0StripThinking = null;
        ovProvider1Key = ovProvider1Model = ovProvider1BaseUrl = ovProvider1TimeoutMs = ovProvider1StripThinking = null;
        ovLegacyApiKey = ovLegacyModel = ovLegacyBaseUrl = null;
        ovAsrKey = ovAsrModel = ovAsrBaseUrl = ovAsrTimeoutMs = null;
        // v1.1.117
        ovBusiness = ovQuality = ovAllowWrite = null;
        ovDailyLimit = null;
    }

    /** 当前覆盖是否非空 (供 LlmClient 决定是否重建链). */
    public boolean hasOverrides() {
        return ovProviders != null || ovProvider0Key != null || ovProvider1Key != null
                || ovLegacyApiKey != null || ovAsrKey != null
                || ovBusiness != null || ovQuality != null || ovAllowWrite != null || ovDailyLimit != null;
    }

    public String getLastIntegrationJson() { return lastIntegrationJson; }

    // ===== v1.1.117 — 能力开关 + 每日上限 (effective: DB 覆盖优先, null 回落 UI 默认) =====

    /**
     * 能力开关快照. 每个布尔: DB 覆盖 (ov) 非空用覆盖值, 否则回落 UI 默认
     * (business=true / quality=true / allowWrite=false). dailyLimit 未设 → 0 = 不限.
     */
    public Caps effectiveCapabilities() {
        Caps c = new Caps();
        c.business   = ovBusiness   != null ? ovBusiness   : true;
        c.quality    = ovQuality    != null ? ovQuality    : true;
        c.allowWrite = ovAllowWrite != null ? ovAllowWrite : false;
        c.dailyLimit = ovDailyLimit == null ? 0 : parseIntSafe(ovDailyLimit, 0);
        return c;
    }

    private static int parseIntSafe(String s, int dflt) {
        if (s == null || s.trim().isEmpty()) return dflt;
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return dflt; }
    }

    /** 能力开关 (经营/质量/写操作 + 每日上限). */
    public static class Caps {
        public boolean business = true;
        public boolean quality = true;
        public boolean allowWrite = false;
        public int dailyLimit = 0;   // 0 = 不限 (v1.1.117 仅存展示, 计数留 v1.1.118)
    }

    // ===== effective getters (DB 覆盖优先, 空回落 env) =====

    private static String coalesce(String ov, String envDefault) {
        return (ov == null || ov.trim().isEmpty()) ? envDefault : ov.trim();
    }

    /** LLM provider 顺序 (DB 覆盖优先). */
    public String effectiveProviders() { return coalesce(ovProviders, providers); }

    public String effectiveProvider0Key() { return coalesce(ovProvider0Key, provider0Key); }
    public String effectiveProvider0Model() { return coalesce(ovProvider0Model, provider0Model); }
    public String effectiveProvider0BaseUrl() { return coalesce(ovProvider0BaseUrl, provider0BaseUrl); }
    public int effectiveProvider0TimeoutMs() {
        if (ovProvider0TimeoutMs != null && !ovProvider0TimeoutMs.isEmpty()) {
            try { return Integer.parseInt(ovProvider0TimeoutMs.trim()); } catch (Exception ignore) {}
        }
        return provider0TimeoutMs;
    }
    public boolean effectiveProvider0StripThinking() {
        if (ovProvider0StripThinking != null && !ovProvider0StripThinking.isEmpty())
            return Boolean.parseBoolean(ovProvider0StripThinking.trim());
        return provider0StripThinking;
    }

    public String effectiveProvider1Key() { return coalesce(ovProvider1Key, provider1Key); }
    public String effectiveProvider1Model() { return coalesce(ovProvider1Model, provider1Model); }
    public String effectiveProvider1BaseUrl() { return coalesce(ovProvider1BaseUrl, provider1BaseUrl); }
    public int effectiveProvider1TimeoutMs() {
        if (ovProvider1TimeoutMs != null && !ovProvider1TimeoutMs.isEmpty()) {
            try { return Integer.parseInt(ovProvider1TimeoutMs.trim()); } catch (Exception ignore) {}
        }
        return provider1TimeoutMs;
    }
    public boolean effectiveProvider1StripThinking() {
        if (ovProvider1StripThinking != null && !ovProvider1StripThinking.isEmpty())
            return Boolean.parseBoolean(ovProvider1StripThinking.trim());
        return provider1StripThinking;
    }

    public String effectiveLegacyApiKey() { return coalesce(ovLegacyApiKey, legacyApiKey); }
    public String effectiveLegacyModel() { return coalesce(ovLegacyModel, legacyModel); }
    public String effectiveLegacyBaseUrl() { return coalesce(ovLegacyBaseUrl, legacyBaseUrl); }

    public String effectiveAsrKey() { return coalesce(ovAsrKey, asrKey); }
    public String effectiveAsrModel() { return coalesce(ovAsrModel, asrModel); }
    public String effectiveAsrBaseUrl() { return coalesce(ovAsrBaseUrl, asrBaseUrl); }
    public int effectiveAsrTimeoutMs() {
        if (ovAsrTimeoutMs != null && !ovAsrTimeoutMs.isEmpty()) {
            try { return Integer.parseInt(ovAsrTimeoutMs.trim()); } catch (Exception ignore) {}
        }
        return asrTimeoutMs;
    }

    private static final Logger log = LoggerFactory.getLogger(AiProperties.class);
}