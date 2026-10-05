package com.industrial.erp.modules.ai.agent;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.ai.client.LlmClient;
import com.industrial.erp.modules.system.annotation.OperLog;
import com.industrial.erp.security.PermissionService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * v1.1.75 任务2 — 只读 agent (function-calling 循环) + 写类动作拦截.
 *
 * <p>循环: LLM 拿到用户问题 → 若决定调工具 (只读) → 注册表执行 → 结果喂回 → 再问;
 * 直到 LLM 给出最终文本答案。步数上限防死循环。
 *
 * <p>写类红线: agent <b>不执行</b>任何写操作。若用户诉求涉及"下单/删除/审核"等,
 * 模型按 system 提示以 {@code PROPOSE: <json>} 前缀提出一个"待确认动作";
 * {@link #run} 解析出后放进返回的 {@code proposedActions}, <b>不执行</b>。
 * 前端拿到 proposedActions 弹确认框, 用户点确认后才走真实写端点 (前端直调, 走原有鉴权)。
 *
 * <p>权限: {@code report:ai} (AI 助手独立权限, v1.1.76, 与 AI 解读同款); 写确认走各写端点自身权限。
 * 审计: {@code @OperLog("AI Agent")}.
 */
@Service
public class AgentService {

    private final LlmClient llmClient;
    private final ErpToolRegistry tools;
    private final PermissionService permService;

    /** 最大工具调用轮数 (防死循环). */
    private static final int MAX_STEPS = 6;

    public AgentService(LlmClient llmClient, ErpToolRegistry tools, PermissionService permService) {
        this.llmClient = llmClient;
        this.tools = tools;
        this.permService = permService;
    }

    /** agent 结果: 最终文本 + 提议的写动作 (不执行) + 实际调用过的只读工具轨迹. */
    public static class Result {
        public String answer;
        public List<String> proposedActions = new ArrayList<>();
        public int stepsUsed;
        public String model;
        /** v1.1.77 新增: 实际命中的 provider name. */
        public String llmProvider;
    }

    @OperLog(module = "AI Agent", businessType = "QUERY", saveParam = true)
    public Result run(String userQuestion) {
        if (!llmClient.enabled()) {
            throw new BizException("AI 功能未配置: 请设置环境变量 ERP_AI_API_KEY");
        }
        permService.requirePerm("report:ai");

        List<JSONObject> messages = new ArrayList<>();
        messages.add(sys());
        messages.add(role("user", userQuestion));

        JSONArray toolSchemas = tools.tools();
        Result result = new Result();
        result.model = llmClient.model();
        LlmClient.ChatResult lastCr = null;

        for (int step = 0; step < MAX_STEPS; step++) {
            LlmClient.ChatResult cr = llmClient.chatWithTools(messages, toolSchemas, 2048, 0.2);
            lastCr = cr;

            // 收集本轮可能产生的"写动作提议" (模型在 content 里按约定输出 PROPOSE: 行)
            collectProposals(cr.content, result.proposedActions);

            if (!cr.wantsToolCall()) {
                // 模型给了最终答案 (content 里可能含 PROPOSE: 写动作, 已收集; 文本本身作为 answer)
                result.answer = cleanAnswer(cr.content, result.proposedActions);
                result.stepsUsed = step + 1;
                result.llmProvider = cr.usedProvider;
                return result;
            }

            // 模型要调工具: 把 assistant 消息 (含 tool_calls) 记入, 逐个执行只读工具, 结果以 role=tool 喂回
            messages.add(assistantToolCallMessage(cr));
            for (LlmClient.ToolCall tc : cr.toolCalls) {
                String toolResult = tools.execute(tc);
                JSONObject toolMsg = new JSONObject();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", tc.id);
                toolMsg.put("name", tc.name);
                toolMsg.put("content", toolResult == null ? "" : toolResult);
                messages.add(toolMsg);
            }
            result.stepsUsed = step + 1;
        }

        // 达到步数上限仍未收敛: 直接把最后一轮文本当答案 (通常模型早收敛, 极少到这)
        result.answer = "(已达到最大分析轮数 " + MAX_STEPS + ", 停止继续调用工具)";
        if (lastCr != null) result.llmProvider = lastCr.usedProvider;
        return result;
    }

    private static JSONObject sys() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 ERP 系统里的智能助手, 可以调用工具查询库存/客户/产品/单据。\n");
        sb.append("规则:\n");
        sb.append("1. 需要数据时调用工具 (只读), 基于工具返回的真实数据回答, 不臆测。\n");
        sb.append("2. 若用户要求执行【写操作】(新增/修改/删除/审核/反审核/下单等), 你【不要】直接执行, ");
        sb.append("也不要调用写工具; 而是在回复正文里输出一行形如: ");
        sb.append("PROPOSE: {\"action\":\"<写操作类型, 如 addSalDelivery|checkSalDelivery|deleteXxx>\", \"params\":{...}, \"summary\":\"<一句话说明要做什么>\"}\n");
        sb.append("系统会把该提议交给用户在前端确认后才真正执行。你只做提议, 不做执行。\n");
        sb.append("3. 用中文, 简洁、结构化。\n");
        return role("system", sb.toString());
    }

    /** 收集模型文本里的 PROPOSE: 行到 list (可能多条). */
    private void collectProposals(String text, List<String> out) {
        if (text == null) return;
        for (String line : text.split("\\n")) {
            String t = line.trim();
            if (t.startsWith("PROPOSE:")) {
                String payload = t.substring("PROPOSE:".length()).trim();
                if (!payload.isEmpty()) out.add(payload);
            }
        }
    }

    /** 从最终答案里剥掉 PROPOSE: 行 (这些已单独放进 proposedActions), 给用户看的干净文本. */
    private static String cleanAnswer(String text, List<String> proposals) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\\n")) {
            if (line.trim().startsWith("PROPOSE:")) continue;
            sb.append(line).append('\n');
        }
        String answer = sb.toString().trim();
        if (answer.isEmpty() && !proposals.isEmpty()) {
            answer = "已生成待确认的写操作提议, 请在下方确认后执行。";
        }
        return answer;
    }

    /** 把 ChatResult 里的 assistant tool_calls 转成可喂回的消息. */
    private static JSONObject assistantToolCallMessage(LlmClient.ChatResult cr) {
        JSONObject m = new JSONObject();
        m.put("role", "assistant");
        m.put("content", cr.content == null ? "" : cr.content);
        JSONArray tcs = new JSONArray();
        for (LlmClient.ToolCall tc : cr.toolCalls) {
            JSONObject tcj = new JSONObject();
            tcj.put("id", tc.id);
            tcj.put("type", "function");
            JSONObject fn = new JSONObject();
            fn.put("name", tc.name);
            fn.put("arguments", tc.argumentsRaw);
            tcj.put("function", fn);
            tcs.add(tcj);
        }
        m.put("tool_calls", tcs);
        return m;
    }

    private static JSONObject role(String role, String content) {
        JSONObject o = new JSONObject();
        o.put("role", role);
        o.put("content", content);
        return o;
    }
}
