package com.industrial.erp.modules.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.hutool.json.JSONObject;
import com.industrial.erp.common.R;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.ai.agent.AgentService;
import com.industrial.erp.modules.ai.asr.AsrService;
import com.industrial.erp.modules.ai.rag.RagService;
import com.industrial.erp.modules.ai.replenish.ReplenishService;
import com.industrial.erp.modules.ai.vo.AgentResultVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * v1.1.75 任务2/3 — AI agent 端点 + RAG 文档问答 + 补货预测.
 *
 * <p>{@code POST /ai/agent/chat}: 自由问答, agent 自主查只读工具 (数据 + 文档); 涉及写操作
 * 返回 {@code proposedActions} 供前端确认。
 * <p>{@code GET /ai/rag/search?keyword=}: RAG 直接检索文档 (不经 LLM, 返回命中片段)。
 * <p>{@code GET /ai/replenish/suggest?keyword=&limit=}: 补货预测建议。
 * 权限: 全部复用 AI 助手独立权限 {@code report:ai} (v1.1.76; 超管 orRole 短路)。
 */
@Tag(name = "AI Agent")
@RestController
@RequestMapping("/ai")
public class AiAgentController {

    private final AgentService agentService;
    private final RagService ragService;
    private final ReplenishService replenishService;
    private final AsrService asrService;

    public AiAgentController(AgentService agentService, RagService ragService,
                             ReplenishService replenishService, AsrService asrService) {
        this.agentService = agentService;
        this.ragService = ragService;
        this.replenishService = replenishService;
        this.asrService = asrService;
    }

    /** 请求体: {"question": "查一下透明胶带现在库存多少"} */
    @SaCheckPermission(value = {"report:ai"}, orRole = "admin")
    @PostMapping("/agent/chat")
    public R<AgentResultVO> chat(@RequestBody JSONObject body) {
        String question = body == null ? "" : body.getStr("question", "");
        AgentService.Result r = agentService.run(question == null ? "" : question);
        AgentResultVO vo = new AgentResultVO(r.answer, r.proposedActions, r.stepsUsed, r.model);
        vo.setLlmProvider(r.llmProvider);
        return R.ok(vo);
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
