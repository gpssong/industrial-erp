package com.industrial.erp.modules.ai.asr;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.ai.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Base64;

/**
 * v1.1.78 AI 网关 — 语音识别 (ASR).
 *
 * <p>设计:
 * <ul>
 *   <li>与 {@code LlmClient} 同款风格: Hutool {@code HttpRequest}, {@code @Value} 单字段配置, 不走 Spring AI.</li>
 *   <li>调用 DashScope 录音文件识别 ({@code paraformer-v2}), 短音频同步 base64 输入.</li>
 *   <li>key 为空时 {@link #enabled()} 返回 false, 调用方 {@code AsrService.transcribe} 快速失败
 *       (抛 {@code BizException}), 与 v1.1.75 LLM 未配置语义一致, 不影响其他功能.</li>
 *   <li>DashScope ASR 是 <strong>OpenAI 不兼容协议</strong>, 不复用 LlmClient.</li>
 * </ul>
 *
 * <p>env 注入示例:
 * <pre>
 * ERP_AI_ASR_KEY=sk-...
 * ERP_AI_ASR_MODEL=paraformer-v2
 * ERP_AI_ASR_BASE_URL=https://dashscope.aliyuncs.com/api/v1/services/audio/asr
 * ERP_AI_ASR_TIMEOUT_MS=30000
 * </pre>
 */
@Component
public class AsrService {

    private static final Logger log = LoggerFactory.getLogger(AsrService.class);

    private final AiProperties props;

    public AsrService(AiProperties props) {
        this.props = props;
    }

    /** ASR 是否配置 (key 非空即启用). 与 LlmClient.enabled() 同款语义. */
    public boolean enabled() {
        String k = props.getAsrKey();
        return k != null && !k.trim().isEmpty();
    }

    /** 当前 ASR model (供 VO / 运维日志; 兼容前端展示). */
    public String model() {
        return props.getAsrModel();
    }

    /**
     * 识别一段音频, 返回文本.
     *
     * @param audio       音频字节 (m4a/mp3/wav 短音频, 建议 &lt; 60s)
     * @param contentType 原始 Content-Type (用于日志, 不一定被 ASR 校验)
     * @return 识别文本 (前后空白已 trim; 空录音返回 "")
     * @throws BizException 未配 key / 网络失败 / HTTP 非 2xx
     */
    public String transcribe(byte[] audio, String contentType) {
        if (!enabled()) {
            throw new BizException("语音识别未配置: 请设置 ERP_AI_ASR_KEY (DashScope ASR)");
        }
        if (audio == null || audio.length == 0) {
            throw new BizException("音频内容为空");
        }

        String b64 = Base64.getEncoder().encodeToString(audio);
        String model = props.getAsrModel();
        String url = props.getAsrBaseUrl() + "/" + model;

        // DashScope 短音频同步识别: input.file_data 为 base64, parameters.sample_rate 固定 16000.
        // 响应字段 output.text (paraformer-v2 / paraformer-realtime-v2 通用). 字段名以实测为准.
        JSONObject body = JSONUtil.createObj()
                .set("model", model)
                .set("input", JSONUtil.createObj().set("file_data", b64))
                .set("parameters", JSONUtil.createObj().set("sample_rate", 16000));

        String raw;
        int status;
        try (HttpResponse resp = HttpRequest.post(url)
                .header("Authorization", "Bearer " + props.getAsrKey())
                .header("Content-Type", "application/json")
                .body(body.toString())
                .timeout(props.getAsrTimeoutMs())
                .execute()) {
            status = resp.getStatus();
            raw = resp.body();
        } catch (Exception e) {
            log.error("[ASR] 网络/超时失败: {}", e.getMessage());
            throw new BizException("语音识别网络失败, 请重试: " + e.getMessage());
        }

        if (status < 200 || status >= 300) {
            log.warn("[ASR] HTTP {} ({}): {}", status, model, abbreviate(raw, 300));
            throw new BizException("语音识别 HTTP " + status + ", 请稍后重试");
        }

        JSONObject out;
        try {
            out = JSONUtil.parseObj(raw);
        } catch (Exception e) {
            log.warn("[ASR] 响应非 JSON: {}", abbreviate(raw, 300));
            return "";
        }
        // paraformer 短音频: { output: { text: "..." } }  (空录音时 output 可能缺 text)
        String text = "";
        Object obj = out.getByPath("output.text");
        if (obj != null) text = obj.toString();
        log.info("[ASR] 识别完成 (model={}, ct={}, len={})", model, contentType, text.length());
        return text.trim();
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
