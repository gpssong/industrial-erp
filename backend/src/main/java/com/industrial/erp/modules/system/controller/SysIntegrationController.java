package com.industrial.erp.modules.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.ai.asr.AsrService;
import com.industrial.erp.modules.ai.client.LlmClient;
import com.industrial.erp.modules.ai.config.AiProperties;
import com.industrial.erp.modules.mcp.McpToolRegistry;
import com.industrial.erp.modules.system.entity.SysOperLog;
import com.industrial.erp.modules.system.entity.SysUser;
import com.industrial.erp.modules.system.mapper.SysOperLogMapper;
import com.industrial.erp.modules.system.mapper.SysUserMapper;
import com.industrial.erp.security.SecurityContext;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.1.80 — 「系统设置 → 对接方式」页的状态端点。
 *
 * <p>为前端 {@code Integration.vue} 提供 LLM / ASR / MCP(Hermes) / Dify 四类对接
 * 的只读状态汇总。设计原则:
 * <ul>
 *   <li><b>纯展示 + 意图记录</b> — LLM/ASR 的 provider/key 仍走 env
 *       ({@code ERP_AI_*} / {@code ERP_AI_ASR_*}), 这里只读脱敏状态, 不改 env。
 *       provider 顺序的「开关意图」由前端写 {@code sys_config.AI_PROVIDER_ORDER}
 *       记录, 实际生效仍取决于重建 jar 时注入的 env。</li>
 *   <li><b>key 一律打码</b> — API key 不进任何 UI 返回值, 只暴露 {@code configured=true/false}
 *       + 末 4 位 (便于运维核对是否配错)。</li>
 *   <li><b>零新 service</b> — 直接注入既有 {@link LlmClient} / {@link AsrService} /
 *       {@link AiProperties} / {@link McpToolRegistry} 读状态。</li>
 * </ul>
 *
 * <p>权限: 全部复用 {@code system:config:edit} (与「App管理」tab 同源; 超管走 orRole 短路)。
 */
@Tag(name = "对接方式 (Hermes / Dify / AI)")
@RestController
@RequestMapping("/system/integration")
public class SysIntegrationController {

    private static final Logger log = LoggerFactory.getLogger(SysIntegrationController.class);

    private final AiProperties ai;
    private final LlmClient llm;
    private final AsrService asr;
    private final McpToolRegistry mcpTools;
    private final SysUserMapper userMapper;
    /** v1.1.80: 读 DB 覆盖 + 改 DB 后强刷 provider 链 (即时生效). */
    private final com.industrial.erp.modules.system.service.SysConfigService configService;
    /** v1.1.80: 审计同步直写 (McpAuditService 教训: 高频异步 @Async 漏写). */
    private final SysOperLogMapper operLogMapper;

    public SysIntegrationController(AiProperties ai, LlmClient llm, AsrService asr,
                                    McpToolRegistry mcpTools, SysUserMapper userMapper,
                                    com.industrial.erp.modules.system.service.SysConfigService configService,
                                    SysOperLogMapper operLogMapper) {
        this.ai = ai;
        this.llm = llm;
        this.asr = asr;
        this.mcpTools = mcpTools;
        this.userMapper = userMapper;
        this.configService = configService;
        this.operLogMapper = operLogMapper;
    }

    /** LLM 网关 + 语音识别 状态 (脱敏). v1.1.80: 走 effective (DB 覆盖优先), 并暴露 DB 覆盖是否生效. */
    @SaCheckPermission(value = {"system:config:edit"}, orRole = "admin")
    @GetMapping("/ai")
    public R<Map<String, Object>> aiStatus() {
        // 先强刷一次链 (让 activeProvider/providerOrder 反映 DB 覆盖 + 最新 cooldown)
        llm.rebuildChain(false);

        Map<String, Object> out = new LinkedHashMap<>();
        boolean dbOverride = ai.hasOverrides();

        // === LLM provider 链 ===
        boolean llmEnabled = llm.enabled();
        List<Map<String, Object>> providers = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String name = i == 0 ? ai.getProvider0Name() : ai.getProvider1Name();
            String key = i == 0 ? ai.effectiveProvider0Key() : ai.effectiveProvider1Key();
            String envKey = i == 0 ? ai.getProvider0Key() : ai.getProvider1Key();
            String model = i == 0 ? ai.effectiveProvider0Model() : ai.effectiveProvider1Model();
            String baseUrl = i == 0 ? ai.effectiveProvider0BaseUrl() : ai.effectiveProvider1BaseUrl();
            boolean strip = i == 0 ? ai.effectiveProvider0StripThinking() : ai.effectiveProvider1StripThinking();
            int timeout = i == 0 ? ai.effectiveProvider0TimeoutMs() : ai.effectiveProvider1TimeoutMs();
            boolean configured = key != null && !key.trim().isEmpty();
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("slot", i);
            p.put("name", name);
            p.put("model", model);
            p.put("baseUrl", baseUrl);
            p.put("stripThinking", strip);
            p.put("timeoutMs", timeout);
            p.put("configured", configured);
            p.put("keyMasked", maskKey(key));
            // v1.1.80: 标记该 slot 的 key 是 DB 覆盖来的还是 env 来的 (envKey 空但 key 非空 = DB 覆盖)
            p.put("source", (envKey == null || envKey.trim().isEmpty() && configured) ? "DB" : "env");
            providers.add(p);
        }
        Map<String, Object> llmOut = new LinkedHashMap<>();
        llmOut.put("enabled", llmEnabled);
        llmOut.put("providers", providers);
        llmOut.put("providerOrder", ai.effectiveProviders());   // env ERP_AI_PROVIDERS + DB 覆盖后的有效值
        llmOut.put("activeProvider", llm.activeProvider());     // 当前实际命中 (非 cooldown)
        llmOut.put("liveProviderOrder", llm.providerOrder());    // DB 覆盖后生效的链顺序
        llmOut.put("dbOverrideActive", dbOverride);
        llmOut.put("cooldownMs", ai.getCooldownMs());
        llmOut.put("cooldownLongMs", ai.getCooldownLongMs());
        // v1.1.117: 模型接口配置卡回显 — 能力开关 + 每日上限 (脱敏, 供前端回填勾选框)
        com.industrial.erp.modules.ai.config.AiProperties.Caps caps = ai.effectiveCapabilities();
        Map<String, Object> capsOut = new LinkedHashMap<>();
        capsOut.put("business", caps.business);
        capsOut.put("quality", caps.quality);
        capsOut.put("allowWrite", caps.allowWrite);
        capsOut.put("dailyLimit", caps.dailyLimit);
        llmOut.put("capabilities", capsOut);
        out.put("llm", llmOut);

        // === ASR (effective) ===
        Map<String, Object> asrOut = new LinkedHashMap<>();
        asrOut.put("enabled", asr.enabled());
        asrOut.put("provider", "dashscope");
        asrOut.put("model", asr.model());
        asrOut.put("baseUrl", ai.effectiveAsrBaseUrl());
        asrOut.put("timeoutMs", ai.effectiveAsrTimeoutMs());
        asrOut.put("keyMasked", maskKey(ai.effectiveAsrKey()));
        asrOut.put("configured", asr.enabled());
        out.put("asr", asrOut);

        // === 当前 DB 覆盖内容 (脱敏回显, 供前端编辑) ===
        out.put("integrationJsonMasked", maskIntegrationJson(dbOverride ? currentIntegrationJson() : null));

        // === 提示文案: DB 即时生效, 无需重建 jar ===
        out.put("note", dbOverride
                ? "当前生效配置来自 sys_config.AI_INTEGRATION (改 DB 即时生效, 无需重建 jar)。未填字段回落 env。"
                : "当前用 backend env (ERP_AI_*)。在「对接方式」填 API key / 切换 provider 顺序后, 保存即写入 sys_config 并即时生效 (无需重建 jar)。");
        return R.ok(out);
    }

    /** MCP (Hermes) 对接状态 + 工具白名单. */
    @SaCheckPermission(value = {"system:config:edit"}, orRole = "admin")
    @GetMapping("/mcp")
    public R<Map<String, Object>> mcpStatus() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("endpoint", "POST /api/mcp (JSON-RPC 2.0)");
        out.put("protocolVersion", "2024-11-05");
        out.put("tools", mcpTools.listTools());
        // bot 账号是否存在 (查 sys_user)
        SysUser hermes = findUser("hermes_bot");
        Map<String, Object> bot = new LinkedHashMap<>();
        bot.put("username", "hermes_bot");
        bot.put("exists", hermes != null);
        bot.put("status", hermes == null ? null : hermes.getStatus());
        out.put("bot", bot);
        out.put("note", "Hermes 通过 stdio 桥 (scripts/erp_mcp_bridge.py) 调本端点。"
                + "「测试」按钮会直接对 /api/mcp 发 tools/list 探活。"
                + "工具白名单: " + McpToolRegistry.TOOL_CREATE + " / " + McpToolRegistry.TOOL_QUERY
                + " (check/uncheck/delete 一律不暴露).");
        return R.ok(out);
    }

    /** Dify 对接参数清单 (只读). Dify 是外部编排平台, 无后端 client 代码。 */
    @SaCheckPermission(value = {"system:config:edit"}, orRole = "admin")
    @GetMapping("/dify")
    public R<Map<String, Object>> difyStatus() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("clientExists", false);   // 后端无 Dify client, 纯外部 HTTP 调 ERP
        out.put("direction", "inbound (Dify 调 ERP /api/workflow/*)");
        out.put("botUsername", "dify_bot");
        SysUser difyBot = findUser("dify_bot");
        out.put("botExists", difyBot != null);
        out.put("botStatus", difyBot == null ? null : difyBot.getStatus());
        out.put("endpoints", List.of(
                "POST /api/workflow/maintain",
                "POST /api/workflow/safety",
                "POST /api/workflow/emergency"
        ));
        out.put("auth", "Sa-Token cookie; Dify 首个 HTTP 节点先调 /api/auth/login 拿 token, 后续用 ${http_response.data.token}");
        out.put("docs", "docs/23_Dify工作流自动归档触发指南.md");
        out.put("note", "Dify 未做后端代码集成 (无 inbound client), 仅文档化外部触发方案。"
                + "如需 Dify 反向调 MCP, 见 docs/24 (Hermes 升级版, 走 /api/mcp).");
        return R.ok(out);
    }

    // ===== helpers =====

    /**
     * v1.1.80 — 应用 AI 集成覆盖 (写 sys_config.AI_INTEGRATION JSON) → 强刷 LLM 链 + 写审计.
     * 前端「对接方式」保存 provider 顺序 / key / model 时调本端点 (非通用 /system/config/value),
     * 顺带 rebuild LlmClient 链, 改 DB 即时生效, 无需重建 jar.
     *
     * <p>请求体: {@code {"json": "<AI_INTEGRATION JSON 字符串>"}}; json 空串/null = 清除覆盖 (回落纯 env).
     */
    @SaCheckPermission(value = {"system:config:edit"}, orRole = "admin")
    @PostMapping("/apply")
    public R<Map<String, Object>> applyIntegration(@RequestBody Map<String, String> body) {
        String json = body == null ? null : body.get("json");
        String toStore = (json == null) ? "" : json;
        configService.updateValue("AI_INTEGRATION", toStore);
        llm.rebuild();          // 立即生效, 不等 30s TTL
        writeAudit(toStore);    // 同步写 sys_oper_log (McpAuditService 教训)
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("applied", true);
        out.put("active", !toStore.isEmpty());
        out.put("providerOrder", llm.providerOrder());
        out.put("activeProvider", llm.activeProvider());
        return R.ok(out);
    }

    /**
     * v1.1.117 — 「模型接口配置」卡的「测试连接」: 用表单里的 provider 配置发一次最小 chat 探活.
     *
     * <p>纯探活: 不落库、不改生效配置、不写 cooldown. 审计同步直写 (key 打码).
     * 请求体: {@code {"name":"agnes","key":"sk-...","model":"...","baseUrl":"https://.../v1","timeoutMs":60000}}.
     */
    @SaCheckPermission(value = {"system:config:edit"}, orRole = "admin")
    @PostMapping("/llm-test")
    public R<Map<String, Object>> testLlm(@RequestBody Map<String, Object> body) {
        String name = str(body, "name");
        String key = str(body, "key");
        String model = str(body, "model");
        String baseUrl = str(body, "baseUrl");
        int timeoutMs = 0;
        Object tv = body == null ? null : body.get("timeoutMs");
        if (tv != null) {
            try { timeoutMs = Integer.parseInt(String.valueOf(tv).trim()); } catch (Exception ignore) {}
        }
        LlmClient.TestResult r = llm.testConnection(name, key, model, baseUrl, timeoutMs);
        writeAuditTest(r.provider, baseUrl, model, r);
        return R.ok(r.toMap());
    }

    private static String str(Map<String, Object> m, String k) {
        if (m == null) return "";
        Object v = m.get(k);
        return v == null ? "" : String.valueOf(v);
    }

    /** 测试连接审计 (同步直写 sys_oper_log, 脱敏 key; McpAuditService 教训: 同步直写才 100% 落库). */
    private void writeAuditTest(String provider, String baseUrl, String model, LlmClient.TestResult r) {
        try {
            SysOperLog l = new SysOperLog();
            l.setModule("AI对接管理");
            l.setBusinessType("TEST");
            l.setMethod("[SYSTEM] test_llm_connection");
            l.setRequestMethod("POST");
            Map<String, Object> prm = new LinkedHashMap<>();
            prm.put("provider", provider);
            prm.put("baseUrl", baseUrl);
            prm.put("model", model);
            prm.put("ok", r.ok);
            prm.put("latencyMs", r.latencyMs);
            prm.put("errMsg", r.errMsg);
            l.setRequestParam(truncate(prm.toString(), 1000));
            l.setUserId(SecurityContext.getUserId());
            l.setUsername(SecurityContext.getUsername());
            l.setStatus(r.ok ? 0 : 1);
            l.setOperTime(LocalDateTime.now());
            operLogMapper.insert(l);
        } catch (Exception e) {
            log.warn("[Integration] 测试连接审计写入失败: {}", e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private SysUser findUser(String username) {
        if (username == null || username.isEmpty()) return null;
        try {
            return userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getUsername, username)
                    .last("LIMIT 1"));
        } catch (Exception e) {
            log.warn("[Integration] 查 bot 账号 {} 失败: {}", username, e.getMessage());
            return null;
        }
    }

    /** API key 打码: 全空 → "(未配置)"; 非空 → 首 4 + **** + 末 4 (短于 12 位则全掩). */
    private String maskKey(String key) {
        if (key == null || key.trim().isEmpty()) return "(未配置)";
        String k = key.trim();
        if (k.length() <= 12) return "****";
        return k.substring(0, 4) + "****" + k.substring(k.length() - 4);
    }

    // ===== v1.1.80 — DB 覆盖 写/回显/审计 =====

    /** 当前 sys_config.AI_INTEGRATION 原文 (供前端编辑回显). */
    private String currentIntegrationJson() {
        try {
            return configService.getByKey("AI_INTEGRATION");
        } catch (Exception e) {
            log.warn("[Integration] 读 AI_INTEGRATION 失败: {}", e.getMessage());
            return null;
        }
    }

    /** 把 integration JSON 里的 key 字段打码 (其余原样), 供前端回显脱敏. */
    private String maskIntegrationJson(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            cn.hutool.json.JSONObject root = cn.hutool.json.JSONUtil.parseObj(json);
            cn.hutool.json.JSONObject llmObj = root.getJSONObject("llm");
            if (llmObj != null) {
                for (String slot : new String[]{"slot0", "slot1"}) {
                    maskSingleKeys(llmObj.getJSONObject(slot));
                }
            }
            maskSingleKeys(root.getJSONObject("legacy"));
            maskSingleKeys(root.getJSONObject("asr"));
            return root.toString();
        } catch (Exception e) {
            return "(解析失败, 原文打码不可用)";
        }
    }

    private void maskSingleKeys(cn.hutool.json.JSONObject obj) {
        if (obj == null) return;
        String k = obj.getStr("key");
        if (k != null && !k.trim().isEmpty()) obj.set("key", maskKey(k));
    }

    /** 同步写 sys_oper_log (McpAuditService 同款直写, 不阻塞主流程). */
    private void writeAudit(String storedJson) {
        try {
            SysOperLog l = new SysOperLog();
            l.setModule("AI对接管理");
            l.setBusinessType("UPDATE");
            l.setMethod("[SYSTEM] apply_integration");
            l.setRequestMethod("POST");
            l.setRequestParam(maskIntegrationJson(storedJson));
            l.setUserId(SecurityContext.getUserId());
            l.setUsername(SecurityContext.getUsername());
            l.setStatus(0);
            l.setOperTime(LocalDateTime.now());
            operLogMapper.insert(l);
            log.info("[Integration] audit ok storedLen={}", storedJson == null ? 0 : storedJson.length());
        } catch (Exception e) {
            // 审计失败不阻塞主流程 (与 McpAuditService 一致)
            log.warn("[Integration] 审计写入失败: {}", e.getMessage());
        }
    }
}
