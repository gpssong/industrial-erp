package com.industrial.erp.modules.ai.agent;

import cn.hutool.json.JSONArray;
import com.industrial.erp.modules.ai.client.LlmClient;
import com.industrial.erp.modules.ai.config.AiProperties;
import com.industrial.erp.security.PermissionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v1.1.143 — agent 把「成品入账」纳入写提议白名单后的三条纪律:
 * <ol>
 *   <li>模型输出的 {@code PROPOSE: addPrdFinishedIn} 只被收集进 proposedActions, <b>后端不执行</b>;</li>
 *   <li>关掉「允许执行写操作」时, 即便模型误输出 PROPOSE 也丢弃;</li>
 *   <li>开写能力时强制放开 business 工具组 —— 提议必须带真实 productId/warehouseId, 靠的就是这组只读工具。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AgentService 成品入账写提议")
class AgentServiceTest {

    private static final String PROPOSE_LINE =
            "已按你说的准备好, 点确认后才会建单。\n"
                    + "PROPOSE: {\"action\":\"addPrdFinishedIn\",\"params\":{\"productId\":2104473647277748226,"
                    + "\"warehouseId\":7001,\"qty\":5000},\"summary\":\"带鱼袋 5000 只入成品仓\"}";

    @Mock private LlmClient llmClient;
    @Mock private ErpToolRegistry tools;
    @Mock private PermissionService permService;
    @Mock private AiProperties aiProps;

    @InjectMocks private AgentService agent;

    private void stubCaps(boolean business, boolean allowWrite) {
        when(llmClient.enabled()).thenReturn(true);
        when(llmClient.model()).thenReturn("test-model");
        AiProperties.Caps caps = new AiProperties.Caps();
        caps.business = business;
        caps.allowWrite = allowWrite;
        when(aiProps.effectiveCapabilities()).thenReturn(caps);
        when(tools.toolsEnabled(any())).thenReturn(new JSONArray());
    }

    private void stubAnswer(String content) {
        LlmClient.ChatResult cr = new LlmClient.ChatResult();
        cr.content = content;
        cr.toolCalls = Collections.emptyList();
        cr.usedProvider = "test-provider";
        when(llmClient.chatWithTools(anyList(), any(JSONArray.class), anyInt(), anyDouble())).thenReturn(cr);
    }

    @Test
    @DisplayName("开写能力: PROPOSE 被收进 proposedActions, 答案里剥掉该行, 且只走只读工具")
    void run_shouldCollectProposalWithoutExecuting() {
        stubCaps(true, true);
        stubAnswer(PROPOSE_LINE);

        AgentService.Result r = agent.run("把刚生产的带鱼袋 5000 只入到成品仓");

        verify(permService).requirePerm("report:ai");
        assertThat(r.proposedActions).hasSize(1);
        assertThat(r.proposedActions.get(0)).contains("addPrdFinishedIn").contains("\"qty\":5000");
        assertThat(r.answer).doesNotContain("PROPOSE").contains("点确认后才会建单");
        // 模型没要求调工具 → 只读注册表一次都没被执行, 写动作更不可能在后端落地
        verify(tools, never()).execute(any());
    }

    @Test
    @DisplayName("关写能力: 模型误输出的 PROPOSE 一样丢弃, 前端不会弹确认框")
    void run_shouldDropProposalWhenWriteDisabled() {
        stubCaps(true, false);
        stubAnswer(PROPOSE_LINE);

        AgentService.Result r = agent.run("把刚生产的带鱼袋 5000 只入到成品仓");

        assertThat(r.proposedActions).isEmpty();
        assertThat(r.answer).doesNotContain("PROPOSE");
    }

    @Test
    @DisplayName("开写能力但关经营数据: 仍强制放开 business 工具组 (提议要带真实 id, 靠只读工具查)")
    void run_shouldForceBusinessToolsWhenWriteEnabled() {
        stubCaps(false, true);
        stubAnswer("好的");

        agent.run("开一张成品入库单");

        verify(tools).toolsEnabled(org.mockito.ArgumentMatchers
                .argThat(g -> g != null && g.contains(ErpToolRegistry.GROUP_BUSINESS)));
    }

    @Test
    @DisplayName("提议里的 19 位雪花 id 出后端前改成字符串, 免得前端 JSON.parse 掉精度拿错单")
    void run_shouldQuoteSnowflakeIdsInProposal() {
        stubCaps(true, true);
        stubAnswer(PROPOSE_LINE);

        AgentService.Result r = agent.run("开一张成品入库单");

        // 只动 16 位以上的长整数, 短数字 (仓库/数量) 原样保留
        assertThat(r.proposedActions.get(0))
                .contains("\"productId\":\"2104473647277748226\"")
                .contains("\"warehouseId\":7001")
                .contains("\"qty\":5000");
    }

    @Test
    @DisplayName("已带引号的 id 不重复包裹, 末尾/嵌套位置都能识别")
    void quoteLongIds_onlyTouchesBareLongIntegers() {
        assertThat(AgentService.quoteLongIds("{\"id\":\"2116353847494504578\"}"))
                .isEqualTo("{\"id\":\"2116353847494504578\"}");
        assertThat(AgentService.quoteLongIds("{\"id\":2116353847494504578,\"qty\":10}"))
                .isEqualTo("{\"id\":\"2116353847494504578\",\"qty\":10}");
        assertThat(AgentService.quoteLongIds("{\"details\":[{\"productId\":2104473647277748226}]}"))
                .isEqualTo("{\"details\":[{\"productId\":\"2104473647277748226\"}]}");
    }

    // ===== v1.1.144: 表格透传 (用户看到的必须是后端算的那份数, 不是模型抄的) =====

    /** 第一轮让模型点 getSalesSummary, 第二轮给最终文本; toolJson 就是注册表 execute 的返回. */
    private void stubToolRound(String toolJson, String finalText) {
        LlmClient.ToolCall tc = new LlmClient.ToolCall();
        tc.id = "call_1";
        tc.name = "getSalesSummary";
        tc.argumentsRaw = "{\"startDate\":\"2026-09\",\"endDate\":\"2026-09\",\"customer\":\"长晟\"}";
        LlmClient.ChatResult cr1 = new LlmClient.ChatResult();
        cr1.content = "";
        cr1.toolCalls = Collections.singletonList(tc);
        cr1.usedProvider = "test-provider";
        LlmClient.ChatResult cr2 = new LlmClient.ChatResult();
        cr2.content = finalText;
        cr2.toolCalls = Collections.emptyList();
        cr2.usedProvider = "test-provider";
        when(tools.execute(any())).thenReturn(toolJson);
        when(llmClient.chatWithTools(anyList(), any(JSONArray.class), anyInt(), anyDouble()))
                .thenReturn(cr1, cr2);
    }

    private static final String SALES_TABLE_JSON =
            "{\"title\":\"客户「长晟」 2026-09-01 ~ 2026-09-30 销售汇总\","
                    + "\"fileName\":\"销售汇总_长晟_20260901-20260930\","
                    + "\"columns\":[{\"key\":\"customerName\",\"label\":\"客户\"},{\"key\":\"totalAmount\",\"label\":\"销售额(元)\"}],"
                    + "\"rows\":[{\"customerName\":\"长晟塑业\",\"totalAmount\":\"14622.00\"}],"
                    + "\"totals\":{\"customerName\":\"合计\",\"totalAmount\":\"14622.00\"},"
                    + "\"count\":1,\"truncated\":false,"
                    + "\"params\":{\"startDate\":\"2026-09-01\",\"endDate\":\"2026-09-30\",\"customer\":\"长晟\"}}";

    @Test
    @DisplayName("getSalesSummary 的返回体原样进 Result.tables, 模型只补一句话")
    void run_shouldPassSalesTableThroughUnchanged() {
        stubCaps(true, false);
        stubToolRound(SALES_TABLE_JSON, "长晟 9 月共 3 单。");

        AgentService.Result r = agent.run("长晟9月份一共销售多少");

        assertThat(r.tables).hasSize(1);
        String passed = String.valueOf(r.tables.get(0));
        assertThat(passed).contains("14622.00")
                .contains("销售汇总_长晟_20260901-20260930")
                .contains("2026-09-01");
        assertThat(r.answer).contains("长晟 9 月共 3 单");
        verify(tools).execute(any());
    }

    @Test
    @DisplayName("报错不进 tables — 让模型解释错误, 别给用户一张只有标题的空表")
    void run_shouldNotCollectErrorTable() {
        stubCaps(true, false);
        stubToolRound("{\"error\":\"当前账号没有销售数据的查看权限\"}", "你没权限查销售数据。");

        AgentService.Result r = agent.run("长晟9月份一共销售多少");

        assertThat(r.tables).isEmpty();
        assertThat(r.answer).contains("你没权限");
    }

    @Test
    @DisplayName("空结果 (rows 为空) 也不透传")
    void run_shouldNotCollectEmptyRows() {
        stubCaps(true, false);
        stubToolRound("{\"title\":\"没查到\",\"columns\":[],\"rows\":[]}", "这段时间没有出库记录。");

        AgentService.Result r = agent.run("长晟9月份一共销售多少");

        assertThat(r.tables).isEmpty();
    }

    @Test
    @DisplayName("非表格工具 (queryStock 那种纯列表) 不会被误当表格透传")
    void run_shouldIgnoreNonTableToolPayload() {
        stubCaps(true, false);
        stubToolRound("[{\"productName\":\"透明胶带\",\"qty\":12}]", "库存 12 卷。");

        AgentService.Result r = agent.run("透明胶带库存多少");

        assertThat(r.tables).isEmpty();
    }
}
