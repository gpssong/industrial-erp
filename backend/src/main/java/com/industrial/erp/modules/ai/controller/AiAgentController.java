package com.industrial.erp.modules.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.hutool.json.JSONObject;
import com.industrial.erp.common.R;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.ai.agent.AgentService;
import com.industrial.erp.modules.report.service.SalesSummaryService;
import com.industrial.erp.modules.ai.asr.AsrService;
import com.industrial.erp.modules.ai.rag.RagService;
import com.industrial.erp.modules.ai.replenish.ReplenishService;
import com.industrial.erp.modules.ai.vo.AgentResultVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * v1.1.75 任务2/3 — AI agent 端点 + RAG 文档问答 + 补货预测.
 *
 * <p>{@code POST /ai/agent/chat}: 自由问答, agent 自主查只读工具 (数据 + 文档); 涉及写操作
 * 返回 {@code proposedActions} 供前端确认。
 * <p>{@code GET /ai/rag/search?keyword=}: RAG 直接检索文档 (不经 LLM, 返回命中片段)。
 * <p>{@code GET /ai/replenish/suggest?keyword=&limit=}: 补货预测建议。
 * <p>{@code GET /ai/sales-summary/csv} (v1.1.144): AI 销售汇总表下载 —— 对话里那张表由后端算好直接给前端,
 * 下载时按同一套参数回后端重查, 保证 PC/App 拿到的 CSV 与屏幕上看到的数一模一样。
 * <p>权限: 全部复用 AI 助手独立权限 {@code report:ai} (v1.1.76; 超管 orRole 短路)。
 */
@Tag(name = "AI Agent")
@RestController
@RequestMapping("/ai")
public class AiAgentController {

    private final AgentService agentService;
    private final RagService ragService;
    private final ReplenishService replenishService;
    private final AsrService asrService;
    private final SalesSummaryService salesSummaryService;

    public AiAgentController(AgentService agentService, RagService ragService,
                             ReplenishService replenishService, AsrService asrService,
                             SalesSummaryService salesSummaryService) {
        this.agentService = agentService;
        this.ragService = ragService;
        this.replenishService = replenishService;
        this.asrService = asrService;
        this.salesSummaryService = salesSummaryService;
    }

    /** 请求体: {"question": "查一下透明胶带现在库存多少"} */
    @SaCheckPermission(value = {"report:ai"}, orRole = "admin")
    @PostMapping("/agent/chat")
    public R<AgentResultVO> chat(@RequestBody JSONObject body) {
        String question = body == null ? "" : body.getStr("question", "");
        AgentService.Result r = agentService.run(question == null ? "" : question);
        AgentResultVO vo = new AgentResultVO(r.answer, r.proposedActions, r.stepsUsed, r.model);
        vo.setLlmProvider(r.llmProvider);
        // v1.1.144: 只读工具算好的表格原样带给前端 (数字不经模型转述), 前端渲染 + 下载 CSV
        vo.setTables(r.tables);
        return R.ok(vo);
    }

    /**
     * v1.1.144 — AI 销售汇总 CSV 下载 (PC 直接拼 Blob, App 原生走这个 URL).
     *
     * <p>为什么回后端重查而不是把前端那张表转成 CSV: 表来自 {@link SalesSummaryService},
     * 口径 (已审核出库 + 含税金额) 与权限 (销售数据读权限) 都只在这一处判定。前端传回来的
     * 参数只做查询, 拿不到任何数据就拿到错误 —— 不能因为"表已经在屏幕上"就绕过权限。
     */
    @Operation(summary = "AI 销售汇总 CSV 下载")
    @SaCheckPermission(value = {"report:ai"}, orRole = "admin")
    @GetMapping("/sales-summary/csv")
    public void salesSummaryCsv(@RequestParam String startDate,
                                @RequestParam String endDate,
                                @RequestParam(required = false) String customer,
                                @RequestParam(required = false) String groupBy,
                                @RequestParam(required = false) Integer limit,
                                HttpServletResponse response) throws IOException {
        SalesSummaryService.Table table =
                salesSummaryService.query(startDate, endDate, customer, groupBy, limit);
        if (table.error != null) {
            throw new BizException(table.error);
        }
        byte[] csv = salesSummaryService.toCsv(table).getBytes(StandardCharsets.UTF_8);
        String filename = table.fileName + ".csv";
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType("text/csv;charset=UTF-8");
        response.setContentLength(csv.length);
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded);
        try (OutputStream out = response.getOutputStream()) {
            out.write(csv);
            out.flush();
        }
    }

    /** RAG 文档检索 (topN 片段, 不经 LLM; 需要 LLM 解读时走 agent)。 */
    @SaCheckPermission(value = {"report:ai"}, orRole = "admin")
    @GetMapping("/rag/search")
    public R<JSONObject> ragSearch(@RequestParam String keyword,
                                   @RequestParam(defaultValue = "5") int limit) {
        JSONObject out = new JSONObject();
        out.set("enabled", ragService.enabled());
        out.set("totalChunks", ragService.size());
        List<RagService.Chunk> hits = ragService.search(keyword, Math.min(limit, 20));
        out.set("hits", hits);
        return R.ok(out);
    }

    /** 补货预测建议 (基于近30天出库 + 当前库存 + 安全库存)。 */
    @SaCheckPermission(value = {"report:ai"}, orRole = "admin")
    @GetMapping("/replenish/suggest")
    public R<List<ReplenishService.Suggestion>> replenishSuggest(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "10") int limit) {
        return R.ok(replenishService.suggest(keyword, Math.min(limit, 50)));
    }

    /**
     * 语音识别 (v1.1.78 App 端语音输入后端兜底). 上传录音文件 (m4a/mp3), 返回识别文本.
     * 复用 report:ai 权限. 未配 ASR key (ERP_AI_ASR_KEY) 时快速失败, 不影响其他功能.
     * 请求: multipart/form-data, 字段 {@code file}.
     */
    @Operation(summary = "语音识别 (上传音频)")
    @SaCheckPermission(value = {"report:ai"}, orRole = "admin")
    @PostMapping("/asr")
    public R<JSONObject> asr(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("未收到音频文件");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (java.io.IOException e) {
            throw new BizException("读取音频文件失败: " + e.getMessage());
        }
        if (bytes.length == 0) {
            throw new BizException("音频文件为空");
        }
        if (bytes.length > 10 * 1024 * 1024) {
            throw new BizException("音频文件过大 (上限 10MB), 请缩短录音");
        }
        String text = asrService.transcribe(bytes, file.getContentType());
        JSONObject out = new JSONObject();
        out.set("text", text);
        out.set("asrProvider", "dashscope:" + asrService.model());
        return R.ok(out);
    }
}
