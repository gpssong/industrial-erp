package com.industrial.erp.modules.ai.service;

import cn.hutool.json.JSONObject;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.ai.client.LlmClient;
import com.industrial.erp.modules.ai.vo.AiAnalysisVO;
import com.industrial.erp.modules.sales.entity.SalDelivery;
import com.industrial.erp.modules.sales.entity.SalDeliveryDetail;
import com.industrial.erp.modules.sales.service.SalDeliveryService;
import com.industrial.erp.modules.system.annotation.OperLog;
import com.industrial.erp.security.PermissionService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * v1.1.75 AI 集成第一步 — 只读 demo service.
 *
 * <p>演示链路: 销售出库单字段 + 明细 → 组装 prompt → 调 LLM → 返回解读。
 * 全部只读 (复用 {@link SalDeliveryService#detail} 查数据), 不做任何写操作。
 *
 * <p>权限: 端点级 {@code @SaCheckPermission("report:view")} + 此处 requirePerm 双保险
 * (与回收站同款), 复用现有报表查看权限, 不新增 perm 载体。
 *
 * <p>审计: {@code @OperLog(module="AI 分析")} 记录调用 (谁/何时/入参), 走既有 AOP + 异步落库。
 *
 * <p>为什么选"销售出库单解读"做 demo: 它是读类、字段结构化 (单号/客户/仓库/状态/金额/明细行),
 * 最适合作为"AI 读 ERP 数据并解读"的最小闭环, 风险最低。
 */
@Service
public class AiService {

    private final LlmClient llmClient;
    private final SalDeliveryService salDeliveryService;
    private final PermissionService permService;

    public AiService(LlmClient llmClient,
                    SalDeliveryService salDeliveryService,
                    PermissionService permService) {
        this.llmClient = llmClient;
        this.salDeliveryService = salDeliveryService;
        this.permService = permService;
    }

    /**
     * 解读一张销售出库单: 它是什么状态、金额/数量/客户/仓库是否正常、明细是否可疑。
     *
     * @param deliveryId 销售出库单 id
     * @return 模型解读文本 + 元信息
     */
    @OperLog(module = "AI 分析", businessType = "QUERY", saveParam = true)
    public AiAnalysisVO analyzeSalDelivery(Long deliveryId) {
        if (!llmClient.enabled()) {
            // 未配置 key 时给出可读提示 (controller 层也会因此返回 R.fail, 不吞掉)
            throw new BizException("AI 功能未配置: 请设置环境变量 ERP_AI_API_KEY 后重试");
        }
        permService.requirePerm("report:view");

        SalDelivery d = salDeliveryService.detail(deliveryId);
        if (d == null) {
            throw new BizException("销售出库单不存在: id=" + deliveryId);
        }

        List<JSONObject> messages = new ArrayList<>();
        messages.add(role("system",
                "你是 ERP 系统里的单据分析助手。用中文简洁回答, 基于给定单据数据做客观解读, " +
                "不要臆测数据里没有的内容。"));
        messages.add(role("user", buildPrompt(d)));

        String content = llmClient.chat(messages, 2048, 0.3);
        return new AiAnalysisVO("SAL_DELIVERY", d.getId(), llmClient.model(), content);
    }

    private static JSONObject role(String role, String content) {
        JSONObject o = new JSONObject();
        o.put("role", role);
        o.put("content", content);
        return o;
    }

    /** 把出库单 + 明细拼成结构化 prompt (字段取最小必要集, 避免把大段冗余喂给模型). */
    private String buildPrompt(SalDelivery d) {
        StringBuilder sb = new StringBuilder();
        sb.append("请解读这张销售出库单:\n");
        sb.append("单号: ").append(nvl(d.getBillNo())).append('\n');
        sb.append("日期: ").append(nvl(d.getBillDate())).append('\n');
        sb.append("状态: ").append(nvl(d.getBillStatus()))
          .append(" (DRAFT=草稿, CHECKED=已审核)").append('\n');
        sb.append("客户: ").append(nvl(d.getCustomerName())).append('\n');
        sb.append("仓库: ").append(nvl(d.getWarehouseName())).append('\n');
        sb.append("交货方式: ").append(nvl(d.getDeliveryMethodLabel())).append('\n');
        sb.append("总金额(含税): ").append(fmt(d.getTotalAmount())).append('\n');
        if (d.getDetails() != null && !d.getDetails().isEmpty()) {
            sb.append("明细行 (共 ").append(d.getDetails().size()).append(" 条):\n");
            for (SalDeliveryDetail item : d.getDetails()) {
                sb.append("  - ").append(nvl(item.getProductName()))
                        .append(" | 型号 ").append(nvl(item.getPModel()))
                        .append(" | 规格 ").append(nvl(item.getSpec()))
                        .append(" | 数量 ").append(nvl(item.getQty()))
                        .append(" | 单价 ").append(fmt(item.getPrice()))
                        .append(" | 金额 ").append(fmt(item.getAmount()))
                        .append(" | 批次 ").append(nvl(item.getBatchNo()))
                        .append('\n');
            }
        } else {
            sb.append("明细行: (无)\n");
        }
        sb.append("\n请指出: 1) 单据当前状态意味着什么; 2) 金额/数量是否有异常; 3) 若已审核, 对库存与应收的影响。");
        return sb.toString();
    }

    private static String nvl(Object v) {
        return (v == null || String.valueOf(v).trim().isEmpty()) ? "-" : String.valueOf(v);
    }

    private static String fmt(BigDecimal v) {
        return v == null ? "-" : v.toPlainString();
    }
}
