package com.industrial.erp.modules.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.ai.ocr.OcrService;
import com.industrial.erp.modules.ai.service.AiService;
import com.industrial.erp.modules.ai.vo.AiAnalysisVO;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.1.75 AI 集成第一步 — 只读 demo 端点 (销售出库 + 采购入库 + 销售订单 + 生产单).
 *
 * <p>权限: 全部复用 AI 助手独立权限 {@code report:ai} (v1.1.76; 超管走 orRole 短路).
 * 写操作类 AI 能力在后续版本再上.
 */
@Tag(name = "AI 分析")
@RestController
@RequestMapping("/ai")
public class AiController {

    private final AiService aiService;
    private final OcrService ocrService;

    public AiController(AiService aiService, OcrService ocrService) {
        this.aiService = aiService;
        this.ocrService = ocrService;
    }

    @SaCheckPermission(value = {"report:ai"}, orRole = "admin")
    @GetMapping("/analyze/sal-delivery/{id}")
    public R<AiAnalysisVO> analyzeSalDelivery(@PathVariable Long id) {
        return R.ok(aiService.analyzeSalDelivery(id));
    }

    @SaCheckPermission(value = {"report:ai"}, orRole = "admin")
    @GetMapping("/analyze/pur-receipt/{id}")
    public R<AiAnalysisVO> analyzePurReceipt(@PathVariable Long id) {
        return R.ok(aiService.analyzePurReceipt(id));
    }

    @SaCheckPermission(value = {"report:ai"}, orRole = "admin")
    @GetMapping("/analyze/sal-order/{id}")
    public R<AiAnalysisVO> analyzeSalOrder(@PathVariable Long id) {
        return R.ok(aiService.analyzeSalOrder(id));
    }

    @SaCheckPermission(value = {"report:ai"}, orRole = "admin")
    @GetMapping("/analyze/prd-order/{id}")
    public R<AiAnalysisVO> analyzePrdOrder(@PathVariable Long id) {
        return R.ok(aiService.analyzePrdOrder(id));
    }

    // ===== v1.1.158 视觉 OCR: 原料标签批号识别 =====

    /** OCR 是否可用 (前端据此决定「识别批号」按钮是否启用; 不泄露 key). */
    @SaCheckPermission(value = {"report:ai", "purchase:receipt:add", "purchase:receipt:edit", "purchase:receipt:list"}, mode = SaMode.OR, orRole = "admin")
    @GetMapping("/ocr/status")
    public R<Map<String, Object>> ocrStatus() {
        Map<String, Object> m = new HashMap<>();
        boolean on = ocrService.enabled();
        m.put("enabled", on);
        m.put("model", on ? ocrService.model() : "");
        // v1.1.165: 服务商 (textin / llm / none) — 前端只用来把「已接 TextIn」显示给用户, 不含凭证
        m.put("provider", on ? ocrService.provider() : "none");
        return R.ok(m);
    }

    /**
     * 从已上传的原料标签照片识别批号候选 (供前端逐行带入 batchNo, 人工确认后落库).
     * body: { "photoUrls": ["http://.../upload/202610/31/xx.jpg"], "productName": "赛科0220kj" }
     * <p>v1.1.163: {@code productName} 可选 —— 一单多原料时按行定向识别 (只找该原料的标签);
     * 不传保持 v1.1.158 通用行为 (PC 端未升级前不受影响).
     */
    @SaCheckPermission(value = {"report:ai", "purchase:receipt:add", "purchase:receipt:edit", "purchase:receipt:list"}, mode = SaMode.OR, orRole = "admin")
    @PostMapping("/ocr/label-batchno")
    public R<Map<String, Object>> ocrLabelBatchNo(@RequestBody Map<String, Object> body) {
        Object raw = body == null ? null : body.get("photoUrls");
        List<String> urls = new ArrayList<>();
        if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                if (o != null) urls.add(String.valueOf(o));
            }
        }
        Object p = body == null ? null : body.get("productName");
        String productName = p == null ? null : String.valueOf(p);
        OcrService.OcrResult r = ocrService.recognizeLabels(urls, productName);
        Map<String, Object> m = new HashMap<>();
        m.put("candidates", new ArrayList<>(r.candidates));
        m.put("rawText", r.rawText);
        return R.ok(m);
    }
}
