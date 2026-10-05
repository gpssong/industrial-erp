package com.industrial.erp.modules.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.ai.service.AiService;
import com.industrial.erp.modules.ai.vo.AiAnalysisVO;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/**
 * v1.1.75 AI 集成第一步 — 只读 demo 端点 (销售出库 + 采购入库 + 销售订单 + 生产单).
 *
 * <p>权限: 全部复用报表查看 {@code report:view} (超管走 orRole 短路)。写操作类 AI 能力在后续版本再上。
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

    @SaCheckPermission(value = {"report:view"}, orRole = "admin")
    @GetMapping("/analyze/pur-receipt/{id}")
    public R<AiAnalysisVO> analyzePurReceipt(@PathVariable Long id) {
        return R.ok(aiService.analyzePurReceipt(id));
    }

    @SaCheckPermission(value = {"report:view"}, orRole = "admin")
    @GetMapping("/analyze/sal-order/{id}")
    public R<AiAnalysisVO> analyzeSalOrder(@PathVariable Long id) {
        return R.ok(aiService.analyzeSalOrder(id));
    }

    @SaCheckPermission(value = {"report:view"}, orRole = "admin")
    @GetMapping("/analyze/prd-order/{id}")
    public R<AiAnalysisVO> analyzePrdOrder(@PathVariable Long id) {
        return R.ok(aiService.analyzePrdOrder(id));
    }
}
