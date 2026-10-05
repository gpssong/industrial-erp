package com.industrial.erp.modules.ai.client;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * v1.1.75 AI 集成第一步: 通用 LLM 网关 (OpenAI 兼容 chat/completions 协议).
 *
 * <p>设计原则:
 * <ul>
 *   <li>OpenAI 兼容协议 — 通义 DashScope (compatible-mode)、DeepSeek、OpenAI、
 *       OpenRouter、任何 OpenAI 兼容端点都能用, 只需改 {@code erp.ai.base-url}.</li>
 *   <li>密钥/模型/端点全部走 env (见 application.yml {@code erp.ai.*}), 不写死、不进 git。</li>
 *   <li>无 key 时快速失败并给出明确提示, 便于区分"没配"和"真出错"。</li>
 * </ul>
 *
 * <p>HTTP 用 Hutool {@link HttpRequest} + {@link JSONUtil} (与项目 FeiePrintClient 同款, 不引入新依赖).
 * 失败统一抛 {@link com.industrial.erp.exception.BizException}, 由 GlobalExceptionHandler 整形为 R.fail。
 */
@Component
public class LlmClient {

    @Value("${erp.ai.api-key:}")
    private String apiKey;

    @Value("${erp.ai.model:qwen-plus}")
    private String model;

    @Value("${erp.ai.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String baseUrl;

    /** 单次请求超时 (ms). 模型响应可能较慢, 默认 60s. */
    @Value("${erp.ai.timeout-ms:60000}")
    private int timeoutMs;

    /**
     * 是否启用: 有 key 且 model 非空才算启用. 未启用时调 {@link #chat} 会直接抛"未配置".
     */
    public boolean enabled() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    /** 当前模型名 (供 VO / 日志展示). 未配置时返回 "disabled". */
    public String model() {
        return enabled() ? model : "disabled";
    }

    /**
     * 调 chat/completions, 返回 assistant 的 text.
     *
     * @param messages 形如 [{"role":"system","content":"..."},{"role":"user","content":"..."}]
     * @param maxTokens 生成上限 (null 表示用模型默认)
     * @param temperature 采样温度 (null 表示默认)
     * @return 模型回复文本 (已去前后空白)
     */
    public String chat(List<JSONObject> messages, Integer maxTokens, Double temperature) {
        if (!enabled()) {
            throw new com.industrial.erp.exception.BizException(
                    "AI 功能未配置: 请设置环境变量 ERP_AI_API_KEY (可在 .env / 容器 env 中配置)");
        }

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("messages", messages);
        if (maxTokens != null) body.put("max_tokens", maxTokens);
        if (temperature != null) body.put("temperature", temperature);

        String url = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) + "/chat/completions"
                : baseUrl + "/chat/completions";

        JSONObject resp;
        try (HttpResponse response = HttpRequest.post(url)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .body(body.toString())
                .timeout(timeoutMs)
                .execute()) {
            int status = response.getStatus();
            String raw = response.body();
            if (status < 200 || status >= 300) {
                // 5xx/4xx: 把上游状态 + 摘要带出来, 便于排查 (截断防日志过长)
                throw new com.industrial.erp.exception.BizException(
                        "LLM 调用失败 (HTTP " + status + "): " + abbreviate(raw, 300));
            }
            resp = JSONUtil.parseObj(raw);
        } catch (com.industrial.erp.exception.BizException be) {
            throw be;
        } catch (Exception e) {
            // 网络/超时/JSON 解析
            throw new com.industrial.erp.exception.BizException(
                    "LLM 调用异常: " + e.getMessage(), e);
        }

        JSONArray choices = resp.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new com.industrial.erp.exception.BizException("LLM 返回无 choices: " + abbreviate(resp.toString(), 300));
        }
        JSONObject message = choices.getJSONObject(0).getJSONObject("message");
        if (message == null) {
            throw new com.industrial.erp.exception.BizException("LLM 返回 message 缺失");
        }
        String text = message.getStr("content", "");
        return text == null ? "" : text.trim();
    }

    /**
     * 带工具 (function-calling) 的 chat, 返回完整 assistant 消息 (含 content 与 tool_calls).
     *
     * <p>供 agent 循环用: 模型可能返回 "文本" (最终答案) 或 "tool_calls" (要调工具)。
     * 返回的 {@link ChatResult} 里, 若 {@code toolCalls} 非空, 调用方执行工具并把结果
     * 以 role="tool" 消息 (带 toolCallId) 喂回, 再次调本方法; 直到 toolCalls 为空 (最终答案)。
     *
     * @param tools OpenAI 格式工具数组: [{"type":"function","function":{"name":..,"description":..,"parameters":{...}}}]
     */
    public ChatResult chatWithTools(List<JSONObject> messages, JSONArray tools, Integer maxTokens, Double temperature) {
        if (!enabled()) {
            throw new com.industrial.erp.exception.BizException(
                    "AI 功能未配置: 请设置环境变量 ERP_AI_API_KEY");
        }
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("messages", messages);
        if (tools != null && !tools.isEmpty()) body.put("tools", tools);
        if (maxTokens != null) body.put("max_tokens", maxTokens);
        if (temperature != null) body.put("temperature", temperature);

        String url = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) + "/chat/completions"
                : baseUrl + "/chat/completions";

        JSONObject resp;
        try (HttpResponse response = HttpRequest.post(url)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .body(body.toString())
                .timeout(timeoutMs)
                .execute()) {
            int status = response.getStatus();
            String raw = response.body();
            if (status < 200 || status >= 300) {
                throw new com.industrial.erp.exception.BizException("LLM 调用失败 (HTTP " + status + "): " + abbreviate(raw, 300));
            }
            resp = JSONUtil.parseObj(raw);
        } catch (com.industrial.erp.exception.BizException be) {
            throw be;
        } catch (Exception e) {
            throw new com.industrial.erp.exception.BizException("LLM 调用异常: " + e.getMessage(), e);
        }

        JSONArray choices = resp.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new com.industrial.erp.exception.BizException("LLM 返回无 choices: " + abbreviate(resp.toString(), 300));
        }
        JSONObject message = choices.getJSONObject(0).getJSONObject("message");
        if (message == null) throw new com.industrial.erp.exception.BizException("LLM 返回 message 缺失");

        ChatResult r = new ChatResult();
        r.content = message.getStr("content", "");
        JSONArray tcs = message.getJSONArray("tool_calls");
        if (tcs != null && !tcs.isEmpty()) {
            r.toolCalls = new java.util.ArrayList<>();
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

    /** 一次 LLM 返回: 要么最终文本 (content), 要么要调的工具 (toolCalls). */
    public static class ChatResult {
        public String content;
        public java.util.List<ToolCall> toolCalls;
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

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        s = s.replace('\n', ' ');
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}

