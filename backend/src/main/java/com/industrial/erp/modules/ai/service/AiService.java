package com.industrial.erp.modules.ai.service;

import cn.hutool.json.JSONObject;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.ai.client.LlmClient;
import com.industrial.erp.modules.ai.vo.AiAnalysisVO;
import com.industrial.erp.modules.production.entity.PrdOrder;
import com.industrial.erp.modules.production.service.PrdOrderService;
import com.industrial.erp.modules.purchase.entity.PurReceipt;
import com.industrial.erp.modules.purchase.service.PurReceiptService;
import com.industrial.erp.modules.sales.entity.SalOrder;
import com.industrial.erp.modules.sales.entity.SalDelivery;
import com.industrial.erp.modules.sales.service.SalDeliveryService;
import com.industrial.erp.modules.sales.service.SalOrderService;
import com.industrial.erp.modules.system.annotation.OperLog;
import com.industrial.erp.security.PermissionService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * v1.1.75 AI 集成 — 只读单据解读 service.
 *
 * <p>统一链路: 某单据字段 + 明细 → 组装结构化 prompt → 调 {@link LlmClient} → 返回解读文本。
 * 全部只读 (复用各 {@code XxxService.detail} 查数据), 不做任何写操作。
 *
 * <p>权限: 端点级 {@code @SaCheckPermission("report:view")} + 此处 {@code requirePerm} 双保险
 * (与回收站同款), 复用报表查看权限, 不新增 perm 载体。
 *
 * <p>审计: {@code @OperLog(module="AI 分析")} 记录调用 (谁/何时/入参), 走既有 AOP + 异步落库。
 *
 * <p>已支持单据: 销售出库 / 采购入库 / 销售订单 / 生产单。新增单据照 {@link #analyzeSalDelivery}
 * 模板加一个 {@code analyzeXxx} + 对应 prompt 构造 + 控制器端点即可。
 */
@Service
public class AiService {

    private final LlmClient llmClient;
    private final SalDeliveryService salDeliveryService;
    private final PurReceiptService purReceiptService;
    private final SalOrderService salOrderService;
    private final PrdOrderService prdOrderService;
    private final PermissionService permService;

    public AiService(LlmClient llmClient,
                     SalDeliveryService salDeliveryService,
                     PurReceiptService purReceiptService,
                     SalOrderService salOrderService,
                     PrdOrderService prdOrderService,
                     PermissionService permService) {
        this.llmClient = llmClient;
        this.salDeliveryService = salDeliveryService;
        this.purReceiptService = purReceiptService;
        this.salOrderService = salOrderService;
        this.prdOrderService = prdOrderService;
        this.permService = permService;
    }

    // ============ 销售出库单 ============
    @OperLog(module = "AI 分析", businessType = "QUERY", saveParam = true)
    public AiAnalysisVO analyzeSalDelivery(Long deliveryId) {
        if (!llmClient.enabled()) throw notConfigured();
        permService.requirePerm("report:view");
        SalDelivery d = salDeliveryService.detail(deliveryId);
        if (d == null) throw new BizException("销售出库单不存在: id=" + deliveryId);
        List<String> detailLines = lineItems("明细行 (共 N 条):", d.getDetails().size(),
                line(), d.getDetails());
        List<JSONObject> messages = prompt(d.getBillNo(),
                "销售出库单",
                "1) 单据当前状态意味着什么; 2) 金额/数量是否有异常; 3) 若已审核, 对库存与应收的影响。",
                header("单号", d.getBillNo(), "日期", d.getBillDate(), "状态", billStatus(d.getBillStatus()),
                        "客户", d.getCustomerName(), "仓库", d.getWarehouseName(), "交货方式", d.getDeliveryMethodLabel(),
                        "总金额(含税)", fmt(d.getTotalAmount())),
                detailLines);
        return run("SAL_DELIVERY", d.getId(), messages);
    }

    // ============ 采购入库单 ============
    @OperLog(module = "AI 分析", businessType = "QUERY", saveParam = true)
    public AiAnalysisVO analyzePurReceipt(Long receiptId) {
        if (!llmClient.enabled()) throw notConfigured();
        permService.requirePerm("report:view");
        PurReceipt r = purReceiptService.detail(receiptId);
        if (r == null) throw new BizException("采购入库单不存在: id=" + receiptId);
        List<String> detailLines = lineItems("明细行 (共 N 条):", r.getDetails().size(), line(), r.getDetails());
        List<JSONObject> messages = prompt(r.getBillNo(),
                "采购入库单",
                "1) 单据当前状态意味着什么; 2) 金额/数量是否有异常; 3) 若已审核, 对库存与应付的影响。",
                header("单号", r.getBillNo(), "日期", r.getBillDate(), "状态", billStatus(r.getBillStatus()),
                        "供应商", r.getSupplierName(), "仓库", r.getWarehouseName(),
                        "总金额(含税)", fmt(r.getTotalAmount()), "付款方式", r.getPayType()),
                detailLines);
        return run("PUR_RECEIPT", r.getId(), messages);
    }

    // ============ 销售订单 ============
    @OperLog(module = "AI 分析", businessType = "QUERY", saveParam = true)
    public AiAnalysisVO analyzeSalOrder(Long orderId) {
        if (!llmClient.enabled()) throw notConfigured();
        permService.requirePerm("report:view");
        SalOrder o = salOrderService.detail(orderId);
        if (o == null) throw new BizException("销售订单不存在: id=" + orderId);
        int cnt = o.getDetails() == null ? 0 : o.getDetails().size();
        List<String> detailLines = lineItems("明细行 (共 N 条):", cnt, line(), o.getDetails());
        List<JSONObject> messages = prompt(o.getBillNo(),
                "销售订单",
                "1) 单据当前状态意味着什么; 2) 金额/数量是否有异常; 3) 发货进度 (已发/未发) 与建议。",
                header("单号", o.getBillNo(), "日期", o.getBillDate(), "状态", billStatus(o.getBillStatus()),
                        "客户", o.getCustomerName(), "采购订单号", o.getPoNo(), "交货方式", o.getDeliveryMethod(),
                        "订单类型", o.getOrderType(), "总金额(含税)", fmt(o.getTotalAmountTax()),
                        "已收款", fmt(o.getReceivedAmount())),
                detailLines);
        return run("SAL_ORDER", o.getId(), messages);
    }

    // ============ 生产单 ============
    @OperLog(module = "AI 分析", businessType = "QUERY", saveParam = true)
    public AiAnalysisVO analyzePrdOrder(Long prdOrderId) {
        if (!llmClient.enabled()) throw notConfigured();
        permService.requirePerm("report:view");
        PrdOrder o = prdOrderService.detail(prdOrderId);
        if (o == null) throw new BizException("生产单不存在: id=" + prdOrderId);
        String hdr = header("单号", o.getBillNo(), "日期", o.getBillDate(), "状态", billStatus(o.getBillStatus()),
                "产品", o.getProductName() + " (" + o.getModel() + ")", "BOM", o.getBomName(),
                "车间", o.getWorkshop(), "负责人", o.getLeader(),
                "计划开工", o.getStartDate(), "计划完工", o.getEndDate(),
                "计划数量", fmt(o.getPlanQty()), "实际数量", fmt(o.getActualQty()),
                "良品", fmt(o.getGoodQty()), "不良", fmt(o.getLossQty()))
                + prdRequisitionLines(o.getRequisitionDetails());
        List<JSONObject> messages = prompt(o.getBillNo(), "生产加工单",
                "1) 单据当前状态/进度意味着什么; 2) 计划 vs 实际/良品/不良 数量是否合理; 3) 开工到完工的周期风险。",
                hdr, null);
        return run("PRD_ORDER", o.getId(), messages);
    }

    // ================= 通用工具 =================

    /** 组装 messages: system + user (user = promptText + 明细拼接). */
    private List<JSONObject> prompt(String billNo, String billTypeLabel, String questions, String headerText, List<String> detailLines) {
        List<JSONObject> messages = new ArrayList<>();
        messages.add(role("system",
                "你是 ERP 系统里的单据分析助手。用中文简洁、结构化回答, 基于给定单据数据做客观解读, " +
                "不要臆测数据里没有的内容。金额单位为元。"));
        StringBuilder user = new StringBuilder();
        user.append("请解读这张").append(billTypeLabel).append(":\n");
        user.append(headerText).append('\n');
        if (detailLines != null && !detailLines.isEmpty()) {
            user.append(String.join("\n", detailLines));
        } else {
            user.append("明细行: (无)\n");
        }
        user.append("\n请回答: ").append(questions);
        messages.add(role("user", user.toString()));
        return messages;
    }

    /** 调模型并包装 VO (统一错误处理: 模型失败抛 BizException, 不吞). */
    private AiAnalysisVO run(String bizType, Long bizId, List<JSONObject> messages) {
        String content = llmClient.chat(messages, 2048, 0.3);
        return new AiAnalysisVO(bizType, bizId, llmClient.model(), content);
    }

    private static BizException notConfigured() {
        return new BizException("AI 功能未配置: 请设置环境变量 ERP_AI_API_KEY 后重试");
    }

    private static JSONObject role(String role, String content) {
        JSONObject o = new JSONObject();
        o.put("role", role);
        o.put("content", content);
        return o;
    }

    /** 把多组 键值对 拼成 "k: v  k: v" 单行文本. */
    @SafeVarargs
    private static String header(Object... kv) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            sb.append(nvl(kv[i])).append(": ").append(nvl(kv[i + 1])).append("  ");
        }
        return sb.toString();
    }

    /** 通用明细行展开 (销售/采购/订单明细, 反射取共性字段: 商品名/型号/规格/数量/单价/金额/批次). */
    private java.util.function.Function<Object, String> line() {
        return (detailItem) -> {
            StringBuilder sb = new StringBuilder("  - ");
            sb.append(nvl(str(detailItem, "getProductName")))
                    .append(" | 型号 ").append(nvl(str(detailItem, "getPModel")))
                    .append(" | 规格 ").append(nvl(str(detailItem, "getSpec")))
                    .append(" | 数量 ").append(nvl(str(detailItem, "getQty")))
                    .append(" | 单价 ").append(nvl(str(detailItem, "getPrice")))
                    .append(" | 金额 ").append(nvl(str(detailItem, "getAmount")))
                    .append(" | 批次 ").append(nvl(str(detailItem, "getBatchNo")));
            return sb.toString();
        };
    }

    /** 生成 "标题 + 逐行明细" 的列表文本片段. */
    private List<String> lineItems(String titleTemplate, int count, java.util.function.Function<Object, String> lineFn,
                                   List<?> details) {
        List<String> out = new ArrayList<>();
        if (details == null || details.isEmpty()) return out;
        out.add(titleTemplate.replace("N", String.valueOf(count)));
        for (Object d : details) out.add(lineFn.apply(d));
        return out;
    }

    /** 生产单领料明细 (PrdRequisitionDetail) 文本片段, 拼进 header. */
    private String prdRequisitionLines(List<?> details) {
        if (details == null || details.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("\n领料明细行 (共 ").append(details.size()).append(" 条):\n");
        for (Object d : details) {
            sb.append("  - ").append(nvl(str(d, "getProductName")))
                    .append(" | 数量 ").append(nvl(str(d, "getQty")))
                    .append(" | 批次 ").append(nvl(str(d, "getBatchNo")))
                    .append(" | 金额 ").append(nvl(str(d, "getAmount")))
                    .append('\n');
        }
        return sb.toString();
    }

    // ---------- 反射取 getter ----------
    private static String str(Object obj, String getter) {
        try {
            Object v = obj.getClass().getMethod(getter).invoke(obj);
            return v == null ? "" : String.valueOf(v);
        } catch (Exception e) {
            return "";
        }
    }

    private static String nvl(Object v) {
        return (v == null || String.valueOf(v).trim().isEmpty()) ? "-" : String.valueOf(v);
    }

    private static String fmt(BigDecimal v) {
        return v == null ? "-" : v.toPlainString();
    }

    /** 单据状态码 → 中文, 给模型减少歧义. */
    private static String billStatus(String s) {
        if (s == null) return "-";
        switch (s) {
            case "DRAFT": return "草稿";
            case "CHECKED": return "已审核";
            case "PICKING": return "拣货中";
            case "FINISHED": return "已完工";
            default: return s;
        }
    }
}
