package com.industrial.erp.modules.ai.agent;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.ai.client.LlmClient;
import com.industrial.erp.modules.ai.config.AiProperties;
import com.industrial.erp.modules.system.annotation.OperLog;
import com.industrial.erp.security.PermissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
 * <p>v1.1.143: 白名单加「成品入账」两步 (建草稿单 {@code addPrdFinishedIn} → 审核 {@code checkPrdFinishedIn}),
 * 仍然只是提议, 由 PC/App 的 AI 助手确认按钮调 {@code POST /production/finished-in} 与 {@code /{id}/check}。
 *
 * <p>权限: {@code report:ai} (AI 助手独立权限, v1.1.76, 与 AI 解读同款); 写确认走各写端点自身权限。
 * 审计: {@code @OperLog("AI Agent")}.
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final LlmClient llmClient;
    private final ErpToolRegistry tools;
    private final PermissionService permService;
    /** v1.1.117: 能力开关 (系统设置「模型接口配置」3 勾选, 按组裁剪工具 + 写动作门控). */
    private final AiProperties aiProps;

    /** 最大工具调用轮数 (防死循环). */
    private static final int MAX_STEPS = 6;

    public AgentService(LlmClient llmClient, ErpToolRegistry tools,
                        PermissionService permService, AiProperties aiProps) {
        this.llmClient = llmClient;
        this.tools = tools;
        this.permService = permService;
        this.aiProps = aiProps;
    }

    /** agent 结果: 最终文本 + 提议的写动作 (不执行) + 实际调用过的只读工具轨迹. */
    public static class Result {
        public String answer;
        public List<String> proposedActions = new ArrayList<>();
        public int stepsUsed;
        public String model;
        /** v1.1.77 新增: 实际命中的 provider name. */
        public String llmProvider;
        /**
         * v1.1.144 新增: 只读工具产出的结构化表格 (原样透传, 前端渲染 + 提供 CSV 下载)。
         * 元素就是工具返回体本身 ({@code title/columns/rows/totals/fileName/note/params}),
         * 不经模型转述 —— 模型抄表格数字会出错, 用户看到的必须是后端算出来的那份。
         */
        public List<Object> tables = new ArrayList<>();
    }

    @OperLog(module = "AI Agent", businessType = "QUERY", saveParam = true)
    public Result run(String userQuestion) {
        if (!llmClient.enabled()) {
            throw new BizException("AI 功能未配置: 请设置环境变量 ERP_AI_API_KEY");
        }
        permService.requirePerm("report:ai");

        // v1.1.117: 读能力开关, 按组裁剪可用工具 + 写动作门控
        AiProperties.Caps caps = aiProps.effectiveCapabilities();
        Set<String> enabledGroups = new HashSet<>();
        if (caps.business) enabledGroups.add(ErpToolRegistry.GROUP_BUSINESS);
        if (caps.quality) enabledGroups.add(ErpToolRegistry.GROUP_QUALITY);
        // v1.1.143: 写动作提议 (成品入库/采购入库/审核) 依赖只读工具先拿真实 productId/warehouseId/单据 id,
        // 而这些工具全在 business 组; 只开写不开经营数据 = 模型只能臆造 id, 故开写时强制放开 business 工具组
        if (caps.allowWrite) enabledGroups.add(ErpToolRegistry.GROUP_BUSINESS);

        List<JSONObject> messages = new ArrayList<>();
        messages.add(sys(caps));
        messages.add(role("user", userQuestion));

        JSONArray toolSchemas = tools.toolsEnabled(enabledGroups);
        Result result = new Result();
        result.model = llmClient.model();

        for (int step = 0; step < MAX_STEPS; step++) {
            LlmClient.ChatResult cr = llmClient.chatWithTools(messages, toolSchemas, 2048, 0.2);
            // 收集本轮可能产生的"写动作提议" (模型在 content 里按约定输出 PROPOSE: 行)
            // v1.1.117: 仅当开启写操作才保留; 关闭时即便模型误输出也丢弃 (前端不弹确认框)
            if (caps.allowWrite) {
                collectProposals(cr.content, result.proposedActions);
            }

            if (!cr.wantsToolCall()) {
                // 模型给了最终答案 (content 里可能含 PROPOSE: 写动作, 已收集; 文本本身作为 answer)
                result.answer = cleanAnswer(cr.content, caps.allowWrite ? result.proposedActions : java.util.Collections.emptyList());
                result.stepsUsed = step + 1;
                result.llmProvider = cr.usedProvider;
                log.info("[AI Agent] 收敛 step={} provider={} proposals={}", step + 1, cr.usedProvider,
                        result.proposedActions.size());
                return result;
            }

            // 模型要调工具: 把 assistant 消息 (含 tool_calls) 记入, 逐个执行只读工具, 结果以 role=tool 喂回
            if (log.isInfoEnabled()) {
                StringBuilder names = new StringBuilder();
                for (LlmClient.ToolCall tc : cr.toolCalls) {
                    if (names.length() > 0) names.append(',');
                    names.append(tc.name).append(tc.argumentsRaw == null ? "" : tc.argumentsRaw);
                }
                log.info("[AI Agent] step={} provider={} tools=[{}]", step + 1, cr.usedProvider, names);
            }
            messages.add(assistantToolCallMessage(cr));
            for (LlmClient.ToolCall tc : cr.toolCalls) {
                String toolResult = tools.execute(tc);
                collectTable(tc.name, toolResult, result.tables);
                JSONObject toolMsg = new JSONObject();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", tc.id);
                toolMsg.put("name", tc.name);
                toolMsg.put("content", toolResult == null ? "" : toolResult);
                messages.add(toolMsg);
            }
            result.stepsUsed = step + 1;
        }

        // 达到步数上限仍未收敛 (备援 provider 常犯): 禁掉工具再问一次, 强制基于已查到的数据给文本答案,
        // 而不是把 "(已达到最大分析轮数)" 这种内部提示丢给用户
        log.warn("[AI Agent] {} 轮未收敛, 禁用工具强制收尾", MAX_STEPS);
        messages.add(role("user", "不要再调用任何工具。直接用中文给出最终回答。"));
        LlmClient.ChatResult finCr = llmClient.chatWithTools(messages, null, 2048, 0.2);
        result.llmProvider = finCr.usedProvider;
        if (caps.allowWrite) {
            collectProposals(finCr.content, result.proposedActions);
        }
        String finText = cleanAnswer(finCr.content, caps.allowWrite ? result.proposedActions : java.util.Collections.emptyList());
        result.answer = (finText == null || finText.trim().isEmpty())
                ? "(已达到最大分析轮数 " + MAX_STEPS + ", 未能得出结论, 请把问题拆细一点再问)" : finText;
        return result;
    }

    /**
     * system 提示. v1.1.117 按能力开关裁剪:
     * <ul>
     *   <li>{@code allowWrite=true} → 注入 PROPOSE 写动作提议段 (现有行为)。</li>
     *   <li>{@code allowWrite=false} → 明确「未开启写操作, 涉及写需求引导用户手工操作」, 不教 PROPOSE。</li>
     * </ul>
     * v1.1.143: 白名单加成品入账两步 —— {@code addPrdFinishedIn} (建草稿单) + {@code checkPrdFinishedIn} (审核入库存)。
     */
    private static JSONObject sys(AiProperties.Caps caps) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 ERP 系统里的智能助手, 可以调用工具查询库存/客户/产品/供应商/仓库/单据。\n");
        sb.append("规则:\n");
        sb.append("1. 需要数据时调用工具 (只读), 基于工具返回的真实数据回答, 不臆测。若当前没有任何工具可用, 就说明无法查询实时数据, 不要臆测。\n");
        // v1.1.144: 销售汇总有专门工具, 且表格由后端直接渲染给用户 —— 模型既不能说"没有接口", 也不该把表格数字重抄一遍
        sb.append("   【销售汇总】凡是「某客户某段时间一共卖了多少 / 销售额排行 / 按月看销量」这类问题, ");
        sb.append("【必须】调用 getSalesSummary 工具 (按客户和时间段统计已审核销售出库单, 金额=含税销售额), ");
        sb.append("严禁回答「没有销售汇总接口」或「只能查出库单列表」。\n");
        sb.append("     (a) 用户说的相对时间要换算成具体日期再传参: 「9月」= 本年 2026-09-01 ~ 2026-09-30, ");
        sb.append("「上个月」「最近三个月」按当前日期算; 拿不准就直接用 YYYY-MM 月份写法, 后端会自动展开成整月;\n");
        sb.append("     (b) 客户名不确定时先调 listCustomers 核对关键字, 严禁猜客户名 (猜错就查不到数, 用户会以为没卖过);\n");
        sb.append("     (c) 工具返回里带 columns/rows/totals 时, 系统会在对话里直接把表格展示给用户并支持下载 CSV —— ");
        sb.append("你【不要】把整张表重抄一遍 (抄数字容易错), 只用一两句话点出合计数、涨幅或需要注意的点;\n");
        sb.append("     (d) 工具返回 error (无权限/日期非法/空结果) 时, 如实转述错误里的提示, 不要编一个销售额出来。\n");
        if (caps.allowWrite) {
            sb.append("2. 若用户要求执行【写操作】(新增/修改/删除/审核/反审核/下单等), 你【不要】直接执行, ");
            sb.append("也不要调用写工具; 而是在回复正文里输出一行形如: ");
            sb.append("PROPOSE: {\"action\":\"<写操作类型>\", \"params\":{...}, \"summary\":\"<一句话说明要做什么>\"}\n");
            sb.append("   【重要】action 字段只能从下面这套固定枚举里选, 一字不差, 不要自创别名:\n");
            sb.append("   - checkSalDelivery  审核销售出库单     params: {\"id\": 出库单ID}\n");
            sb.append("   - addPurIn          生成采购入库单(草稿) params: {\"supplierId\":供应商ID, \"warehouseId\":仓库ID, \"details\":[{\"productId\":商品ID,\"qty\":数量,\"price\":成本价}], \"remark\":可选}\n");
            sb.append("     【硬性规则 - addPurIn】:\n");
            sb.append("     (a) 提议 addPurIn 前【必须】先调 listSuppliers 工具拿到至少一个供应商 (status=1), 取其 id 填入 supplierId;\n");
            sb.append("     (b) 如果 listSuppliers 返回空数组 (库里没有任何启用中的供应商), 才允许提议时不带 supplierId, 此时在答案正文里说明\"无可用供应商, 请到基础资料-供应商先建档\";\n");
            sb.append("     (c) 严禁基于臆想 (如「自产」「默认」或任何数字) 瞎编一个 supplierId — 后端会 selectById 校验失败报\"供应商不存在\";\n");
            sb.append("     (d) 严禁省略 supplierId 字段 — 后端校验 @NotNull 必填, 缺了直接 500。\n");
            // v1.1.143: 成品入账 (车间生产出来的货登记入库) 纳入白名单 —— 建草稿 + 审核两步
            sb.append("   - addPrdFinishedIn  生成成品入库单(草稿), 即\"生产入库\"记账     params: {\"productId\":成品商品ID, \"warehouseId\":仓库ID, \"qty\":实际入库数量, \"price\":单价(可选, 不填按0), \"batchNo\":批号(可选), \"prdOrderId\":生产工单ID(可选), \"prdOrderNo\":生产工单号(可选), \"remark\":备注(可选)}\n");
            sb.append("     【硬性规则 - addPrdFinishedIn】:\n");
            sb.append("     (a) 提议前【必须】先调 listProducts 拿到真实 productId; ");
            sb.append("本厂生产出来的成品在商品档案里可能标成 FG 或 GOODS (如「马鲛鱼」「胶带」这类成品就是 GOODS), ");
            sb.append("成品入库单不限 productType, 【不要】因为类型不是 FG 就拒绝建单; ");
            sb.append("若同名/近似有多个候选, 先把候选列出来问用户是哪一个, 严禁猜;\n");
            sb.append("     (b) 提议前【必须】先调 listWarehouses 拿到真实 warehouseId; 用户没说入哪个仓时用 isDefault=1 的那个, 并在 summary 里写明仓库名;\n");
            sb.append("     (c) qty【只能】用用户明确说出的实际产量; 用户没说数量就先追问, 严禁拿工单计划量/库存数/克重自己推算;\n");
            sb.append("     (d) 不要填 billNo/billDate/billStatus/spec/unitId/unitName/amount/productCode/productName —— 后端自动生成单号并按商品主数据回填规格与单位;\n");
            sb.append("     (e) 一张成品入库单只对应一个商品; 多个商品要入账就提多条 PROPOSE 行, 每条一个商品;\n");
            sb.append("     (f) 若该商品 productType 是 RAW/SEMI (原料/半成品), 【不要】用本动作, 直接说明半成品/原料入库暂不支持自动执行, 请到对应模块手工操作;\n");
            sb.append("   - checkPrdFinishedIn 审核成品入库单 (草稿→已审核, 库存这时才真正入账)   params: {\"id\": 成品入库单ID}\n");
            sb.append("     【硬性规则 - checkPrdFinishedIn】: 必须先调 listFinishedInBills 拿到真实 id 且该单 billStatus=DRAFT; ");
            sb.append("用户说\"刚建的那张\"时取列表里最新一条 DRAFT, 并把单号写进 summary 让用户核对; 严禁臆造 id, 严禁审核 CHECKED 单。\n");
            sb.append("     提示: 成品入账是两步 —— 先提议 addPrdFinishedIn 建草稿, 用户确认执行后若要真正入库存, 再提议 checkPrdFinishedIn; 一次只提一步, 不要合并。\n");
            sb.append("   其它写操作 (新增出库/改单/删单/半成品入库等) 暂无白名单, 不要提议, 直接说明 \"该操作暂不支持自动执行, 请到对应模块手工操作\"。\n");
            sb.append("   【id 写法】params 里的 id / productId / warehouseId / supplierId 是 19 位长数字, ");
            sb.append("必须写成带引号的字符串, 如 \"id\":\"2116353847494504578\" —— 写成裸数字前端会掉精度拿错单。\n");
            sb.append("系统会把该提议交给用户在前端确认后才真正执行。你只做提议, 不做执行。\n");
        } else {
            sb.append("2. 本系统当前【未开启写操作】(管理员在系统设置关闭了「允许执行写操作」)。若用户要求执行");
            sb.append("【写操作】(新增/修改/删除/审核/反审核/下单等), 你【一律不要】执行或提议任何写操作, ");
            sb.append("也【不要】输出 PROPOSE: 行; 直接告知用户「当前 AI 未开启写操作, 请前往对应模块手工操作」。\n");
        }
        sb.append("3. 用中文, 简洁、结构化。\n");
        return role("system", sb.toString());
    }

    /**
     * v1.1.144 — 把「表格类只读工具」的返回体原样收进 {@code result.tables}, 前端直接渲染 + 下载.
     *
     * <p>只认 {@link ErpToolRegistry#TABLE_TOOLS} 白名单, 且跳过报错和空表: 报错要让模型自己解释,
     * 空表渲染出来只有一行标题反而让用户以为系统坏了。
     */
    private static void collectTable(String toolName, String toolJson, List<Object> out) {
        if (toolName == null || !ErpToolRegistry.TABLE_TOOLS.contains(toolName)) return;
        if (toolJson == null || !toolJson.trim().startsWith("{")) return;
        try {
            JSONObject o = new JSONObject(toolJson);
            if (o.containsKey("error")) return;
            JSONArray rows = o.getJSONArray("rows");
            if (rows == null || rows.isEmpty()) return;
            out.add(o);
        } catch (Exception e) {
            log.warn("[AI Agent] 表格透传失败 tool={}: {}", toolName, e.getMessage());
        }
    }

    /** 收集模型文本里的 PROPOSE: 行到 list (可能多条). */
    private static void collectProposals(String text, List<String> out) {
        if (text == null) return;
        for (String line : text.split("\\n")) {
            String t = line.trim();
            if (t.startsWith("PROPOSE:")) {
                String payload = t.substring("PROPOSE:".length()).trim();
                if (!payload.isEmpty()) out.add(quoteLongIds(payload));
            }
        }
    }

    /**
     * 把提议 JSON 里的裸长整数 (19 位雪花 id) 改成字符串.
     *
     * <p>浏览器 {@code JSON.parse} 只能精确表示 2^53, {@code "id": 2116353847494504578} 会被抹成
     * {@code …504400}, 前端拿去审核就报「入库单不存在」。后端是唯一出口, 在这里统一加引号。
     */
    static String quoteLongIds(String payload) {
        return payload.replaceAll("([:,\\[]\\s*)(\\d{16,})(?=\\s*[,}\\]])", "$1\"$2\"");
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
