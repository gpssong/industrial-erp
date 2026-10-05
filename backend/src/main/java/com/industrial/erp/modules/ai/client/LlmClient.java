package com.industrial.erp.modules.ai.client;

import cn.hutool.http.HttpException;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.ai.config.AiProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * v1.1.77 AI 网关 — Provider Failover 链 + Cooldown + Thinking Strip.
 *
 * <p>设计:
 * <ul>
 *   <li>从 {@link AiProperties} 读 {@code providers} (逗号串) 或 fallback 到 legacy 单 provider。</li>
 *   <li>链按声明顺序轮询, 每个 provider 持有 {@code cooldownUntil} (epoch ms), 触发 failover 后置 cooldown。</li>
 *   <li>触发 failover 的错误: 5xx / 408 / Hutool HttpException (超时/网络) / 429 quota + 429 通用。</li>
 *   <li><strong>不</strong>触发 failover 的错误: 401/403/400/业务错误 (choices 空/JSON 坏), 直接抛 BizException。</li>
 *   <li>每家 provider 可独立配置 {@code strip-thinking} (per-provider 覆盖全局).</li>
 *   <li>返回 {@link ChatResult} 带 {@code usedProvider} (供 VO 透传 / 运维日志).</li>
 * </ul>
 *
 * <p>兼容 v1.1.75: 只配 legacy 4 env ({@code ERP_AI_API_KEY/MODEL/BASE_URL/TIMEOUT_MS}) 时, providers 链只有 1 家
 * ({@code default}), 行为与 v1.1.75 完全一致, 零 failover.
 */
@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final AiProperties props;
    private final List<ProviderState> chain = new ArrayList<>();

    public LlmClient(AiProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() {
        // 1) 解析 providers (逗号串) → slot name 顺序
        Set<String> order = parseProviders(props.getProviders());

        // 2) 按 slot 索引 0/1 取 provider 配置, 顺序由 order 决定
        if (!order.isEmpty()) {
            for (String name : order) {
                ProviderState st = buildSlot(name);
                if (st != null) chain.add(st);
            }
            if (hasLegacy() && chain.size() == 1
                    && "default".equals(chain.get(0).cfg.name)) {
                // 单 provider + legacy: 不 warn (行为兼容 v1.1.75)
            } else if (hasLegacy()) {
                log.warn("[LLM] legacy env ignored: providers={}", order);
            }
        } else if (hasLegacy()) {
            // v1.1.75 兼容路径: 单 default provider
            chain.add(new ProviderState(new ProviderConfig(
                    "default",
                    props.getLegacyApiKey(),
                    props.getLegacyModel(),
                    props.getLegacyBaseUrl(),
                    props.getLegacyTimeoutMs(),
                    false)));
        } else {
            log.warn("[LLM] 未启用: providers 空且 legacy api-key 也为空");
        }

        log.info("[LLM] initialized {} providers: [{}]",
                chain.size(),
                chain.stream().map(s -> s.cfg.name).reduce((a, b) -> a + "," + b).orElse(""));
    }

    private Set<String> parseProviders(String csv) {
        if (csv == null || csv.trim().isEmpty()) return Collections.emptySet();
        Set<String> out = new LinkedHashSet<>();
        for (String s : csv.split(",")) {
            String n = s.trim();
            if (!n.isEmpty()) out.add(n);
        }
        return out;
    }

    /** 根据 slot name 取配置. name 不在 0/1 slot 内返回 null (skip). */
    private ProviderState buildSlot(String name) {
        ProviderConfig cfg;
        if ("agnes".equals(name)) {
            cfg = new ProviderConfig(name,
                    props.getProvider0Key(),
                    props.getProvider0Model(),
                    props.getProvider0BaseUrl(),
                    props.getProvider0TimeoutMs(),
                    props.isProvider0StripThinking());
        } else if ("minimax".equals(name)) {
            cfg = new ProviderConfig(name,
                    props.getProvider1Key(),
                    props.getProvider1Model(),
                    props.getProvider1BaseUrl(),
                    props.getProvider1TimeoutMs(),
                    props.isProvider1StripThinking());
        } else {
            log.warn("[LLM] unknown provider name '{}', skipped (slot 0/1 only)", name);
            return null;
        }
        if (cfg.apiKey == null || cfg.apiKey.trim().isEmpty()) {
            log.warn("[LLM] provider '{}' key 空, skipped", name);
            return null;
        }
        return new ProviderState(cfg);
    }

    private boolean hasLegacy() {
        return props.getLegacyApiKey() != null && !props.getLegacyApiKey().trim().isEmpty();
    }

    /**
     * 是否启用. 与 v1.1.75 语义一致 (key 非空即启用, 不区分 provider 数).
     */
    public boolean enabled() {
        if (chain.isEmpty()) return hasLegacy();
        return true;
    }

    /**
     * 当前首选 provider 的 model (兼容 v1.1.75 前端展示).
     * 首选 = 第一个非 cooldown 的; 全 cooldown 时返回第一个.
     */
    public String model() {
        if (!enabled()) return "disabled";
        long now = System.currentTimeMillis();
        for (ProviderState s : chain) {
            if (now >= s.cooldownUntil) return s.cfg.model;
        }
        return chain.get(0).cfg.model;
    }

    /**
     * 调 chat/completions, 返回 assistant 的 text.
     *
     * @param messages 形如 [{"role":"system","content":"..."},{"role":"user","content":"..."}]
     * @param maxTokens 生成上限 (0 = 用模型默认)
     * @param temperature 采样温度 (Double.NaN = 用默认)
     * @return 模型回复文本 (已 strip thinking + 前后空白)
     */
    public String chat(List<JSONObject> messages, int maxTokens, double temperature) {
        ChatResult cr = chatInternal(messages, null, maxTokens, temperature);
        return cr.content;
    }

    /**
     * 同 {@link #chat}, 但返回完整 {@link ChatResult} (含 {@code usedProvider}).
     * v1.1.77 新增, 供 {@code AiService} 在填 VO 时拿到 provider name.
     */
    public ChatResult chatWithResult(List<JSONObject> messages, int maxTokens, double temperature) {
        return chatInternal(messages, null, maxTokens, temperature);
    }

    /**
     * 带工具 (function-calling) 的 chat, 返回完整 assistant 消息.
     * @see ChatResult
     */
    public ChatResult chatWithTools(List<JSONObject> messages, JSONArray tools, int maxTokens, double temperature) {
        return chatInternal(messages, tools, maxTokens, temperature);
    }

    /** 统一的 failover 主循环. */
    private ChatResult chatInternal(List<JSONObject> messages, JSONArray tools,
                                    int maxTokens, double temperature) {
        if (!enabled()) {
            throw new BizException(
                    "AI 功能未配置: 请设置环境变量 ERP_AI_API_KEY "
                            + "(或 ERP_AI_PROVIDER0_KEY / ERP_AI_PROVIDER1_KEY 启用 failover)");
        }

        List<String> attempted = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (ProviderState state : chain) {
            if (now < state.cooldownUntil) {
                attempted.add(state.cfg.name + "(cooldown)");
                continue;
            }
            try {
                ChatResult cr = (tools == null)
                        ? doHttpChat(state.cfg, messages, maxTokens, temperature)
                        : doHttpChatWithTools(state.cfg, messages, tools, maxTokens, temperature);
                cr.usedProvider = state.cfg.name;
                if (shouldStrip(state.cfg)) {
                    cr.content = stripThoughts(cr.content);
                }
                log.info("[LLM] using provider={}", state.cfg.name);
                return cr;
            } catch (HttpFailoverSignal sig) {
                long cd = sig.isQuota ? props.getCooldownLongMs() : props.getCooldownMs();
                state.cooldownUntil = System.currentTimeMillis() + cd;
                log.warn("[LLM] provider {} failed (status={}, isQuota={}), cooldown {}s, try next",
                        state.cfg.name, sig.status, sig.isQuota, cd / 1000);
                attempted.add(state.cfg.name + "(fail " + sig.status + ")");
            } catch (BizException be) {
                // 非 failover 错误 (401/403/400/业务错误) — 不切, 立即抛
                log.error("[LLM] provider {} non-failover error: {}",
                        state.cfg.name, be.getMessage());
                throw be;
            }
        }

        log.error("[LLM] all providers exhausted, attempted={}", attempted);
        throw new BizException("AI 服务暂时不可用，请稍后再试 (attempted: " + attempted + ")");
    }

    /** per-provider 是否启用 thinking strip: provider 字段 OR 全局默认. */
    private boolean shouldStrip(ProviderConfig cfg) {
        return cfg.stripThinking || props.isStripThinking();
    }

    /** 单 provider 单次 HTTP 调 (chat/completions, 无 function-calling). */
    private ChatResult doHttpChat(ProviderConfig cfg, List<JSONObject> messages,
                                  int maxTokens, double temperature) {
        JSONObject body = new JSONObject();
        body.put("model", cfg.model);
        body.put("messages", messages);
        if (maxTokens > 0) body.put("max_tokens", maxTokens);
        if (!Double.isNaN(temperature)) body.put("temperature", temperature);

        JSONObject resp = httpPost(cfg, "/chat/completions", body);

        JSONArray choices = resp.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new BizException("LLM 返回无 choices: " + abbreviate(resp.toString(), 300));
        }
        JSONObject message = choices.getJSONObject(0).getJSONObject("message");
        if (message == null) throw new BizException("LLM 返回 message 缺失");

        ChatResult r = new ChatResult();
        r.content = message.getStr("content", "");
        return r;
    }

    /** 单 provider 单次 HTTP 调 (chat/completions, with tools). */
    private ChatResult doHttpChatWithTools(ProviderConfig cfg, List<JSONObject> messages,
                                           JSONArray tools, int maxTokens, double temperature) {
        JSONObject body = new JSONObject();
        body.put("model", cfg.model);
        body.put("messages", messages);
        if (tools != null && !tools.isEmpty()) body.put("tools", tools);
        if (maxTokens > 0) body.put("max_tokens", maxTokens);
        if (!Double.isNaN(temperature)) body.put("temperature", temperature);

        JSONObject resp = httpPost(cfg, "/chat/completions", body);

        JSONArray choices = resp.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new BizException("LLM 返回无 choices: " + abbreviate(resp.toString(), 300));
        }
        JSONObject message = choices.getJSONObject(0).getJSONObject("message");
        if (message == null) throw new BizException("LLM 返回 message 缺失");

        ChatResult r = new ChatResult();
        r.content = message.getStr("content", "");
        JSONArray tcs = message.getJSONArray("tool_calls");
        if (tcs != null && !tcs.isEmpty()) {
            r.toolCalls = new ArrayList<>();
            for (int i = 0; i < tcs.size(); i++) {
                JSONObject tc = tcs.getJSONObject(i);
                ToolCall call = new ToolCall();
                call.id = tc.getStr("id", "call_" + i);
                JSONObject fn = tc.getJSONObject("function");
                call.name = fn == null ? "" : fn.getStr("name", "");
                call.argumentsRaw = fn == null ? "{}" : fn.getStr("arguments", "{}");
                r.toolCalls.add(call);
            }
        }
        return r;
    }

    /** 实际 HTTP POST. 错误分类参见字段注释. */
    private JSONObject httpPost(ProviderConfig cfg, String path, JSONObject body) {
        String url = cfg.baseUrl.endsWith("/")
                ? cfg.baseUrl.substring(0, cfg.baseUrl.length() - 1) + path
                : cfg.baseUrl + path;

        String raw;
        int status;
        try (HttpResponse response = HttpRequest.post(url)
                .header("Authorization", "Bearer " + cfg.apiKey)
                .header("Content-Type", "application/json")
                .body(body.toString())
                .timeout(cfg.timeoutMs)
                .execute()) {
            status = response.getStatus();
            raw = response.body();
        } catch (HttpException he) {
            // Hutool 网络/超时/连接失败 → 触发 short cooldown
            throw new HttpFailoverSignal(-1, false, "HttpException: " + he.getMessage());
        } catch (Exception e) {
            // 其它 IO → 触发 short cooldown
            throw new HttpFailoverSignal(-1, false, e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        if (status < 200 || status >= 300) {
            String summary = abbreviate(raw, 300);
            // 429 quota 信号 → long cooldown; 429 其它 / 5xx / 408 → short; 401/403/400 → 不 failover
            if (status == 429) {
                boolean quota = containsAnyCI(summary,
                        "rate_limit_exceeded", "quota", "insufficient_quota");
                throw new HttpFailoverSignal(status, quota, summary);
            }
            if (status >= 500 || status == 408) {
                throw new HttpFailoverSignal(status, false, summary);
            }
            // 401/403/400 → 不切, 走 BizException
            throw new BizException("LLM 调用失败 (HTTP " + status + "): " + summary);
        }

        try {
            return JSONUtil.parseObj(raw);
        } catch (Exception e) {
            throw new BizException("LLM 返回 JSON 解析失败: " + e.getMessage());
        }
    }

    /** 一次 LLM 返回: 要么最终文本 (content), 要么要调的工具 (toolCalls). */
    public static class ChatResult {
        public String content;
        public List<ToolCall> toolCalls;
        /** v1.1.77 新增: 实际命中的 provider name. 老调用方不读, 兼容. */
        public String usedProvider;
        public boolean wantsToolCall() { return toolCalls != null && !toolCalls.isEmpty(); }
    }

    /** 模型请求调用的工具. */
    public static class ToolCall {
        public String id;
        public String name;
        public String argumentsRaw;
        public JSONObject arguments() {
            try {
                return JSONUtil.parseObj(argumentsRaw);
            } catch (Exception e) {
                return new JSONObject();
            }
        }
    }

    /** 单 provider 配置 (immutable). */
    static class ProviderConfig {
        final String name;
        final String apiKey;
        final String model;
        final String baseUrl;
        final int timeoutMs;
        final boolean stripThinking;
        ProviderConfig(String name, String apiKey, String model, String baseUrl,
                       int timeoutMs, boolean stripThinking) {
            this.name = name;
            this.apiKey = apiKey;
            this.model = model;
            this.baseUrl = baseUrl;
            this.timeoutMs = timeoutMs;
            this.stripThinking = stripThinking;
        }
    }

    /** 进程内 provider 运行时状态. */
    static class ProviderState {
        final ProviderConfig cfg;
        volatile long cooldownUntil;   // epoch ms, 0 = 可用
        ProviderState(ProviderConfig cfg) {
            this.cfg = cfg;
            this.cooldownUntil = 0L;
        }
    }

    /** 触发 failover 的 HTTP 错误信号. */
    static class HttpFailoverSignal extends RuntimeException {
        final int status;
        final boolean isQuota;
        HttpFailoverSignal(int status, boolean isQuota, String msg) {
            super("HTTP " + status + (isQuota ? " (quota)" : "") + ": " + msg);
            this.status = status;
            this.isQuota = isQuota;
        }
    }

    // ===== thinking strip =====

    private static final Pattern THINK1 = Pattern.compile("(?is)<thinking>.*?</thinking>");
    private static final Pattern THINK2 = Pattern.compile("(?is)<think>.*?</think>");

    /** 剥离 &lt;thinking&gt; 和 &lt;think&gt; 段 (兼容 MiniMax / DeepSeek-R1 等). */
    static String stripThoughts(String s) {
        if (s == null || s.isEmpty()) return s;
        s = THINK1.matcher(s).replaceAll("");
        s = THINK2.matcher(s).replaceAll("");
        return s.trim();
    }

    // ===== util =====

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        s = s.replace('\n', ' ');
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static boolean containsAnyCI(String haystack, String... needles) {
        if (haystack == null) return false;
        String h = haystack.toLowerCase(Locale.ROOT);
        for (String n : needles) {
            if (h.contains(n.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }
}