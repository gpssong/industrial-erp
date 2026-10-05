package com.industrial.erp.modules.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.ai.service.AiService;
import com.industrial.erp.modules.ai.vo.AiAnalysisVO;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/**
 * v1.1.75 AI 集成第一步 — 只读 demo 端点.
 *
 * <p>{@code GET /ai/analyze/sal-delivery/{id}} — 让 LLM 解读一张销售出库单。
 * 权限: 复用报表查看 {@code report:view} (超管走 orRole 短路)。写操作类 AI 能力在后续版本再上。
 */
@Tag(name = "AI 分析")
@RestController
@RequestMapping("/ai")
public class AiController {

    private final AiService aiService;

    public AiController(AiService aiService) {
        this.aiService = aiService;
    }

    @SaCheckPermission(value = {"report:view"}, orRole = "admin")
    @GetMapping("/analyze/sal-delivery/{id}")
    public R<AiAnalysisVO> analyzeSalDelivery(@PathVariable Long id) {
        return R.ok(aiService.analyzeSalDelivery(id));
    }
}
