package com.industrial.erp.modules.qms.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.common.Constants;
import com.industrial.erp.common.R;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.qms.dto.InspectionCreateDTO;
import com.industrial.erp.modules.qms.entity.MesQualityInspection;
import com.industrial.erp.modules.qms.service.QmsInspectionService;
import com.industrial.erp.modules.purchase.entity.PurInboundPlan;
import com.industrial.erp.modules.purchase.service.PurInboundPlanService;
import com.industrial.erp.security.SecurityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * v1.1.90 QMS — 质量检验单 (IQC / FAI / PAT / OQC / OQC_RETURN) 接口.
 *
 * <p>权限沿用 {@code quality:inspection:list/add/check} (与 sql/55 一致, 待部署补授权);
 * 触发器单 (IQC/OQC/OQC_RETURN) 自动生成, 不要求 user 显式调 {@code add} 端点, 因此
 * 用 {@code orRole="admin"} 兜底触发器路径 (上游 service 调用 QmsInspectionService.createOnReceipt
 * 等内部方法, 不走 controller, 不需要 perm).
 *
 * <p>PC 端编辑器 + App 端复核共用本端点.
 */
@Tag(name = "质量检验")
@RestController
@RequestMapping("/qms/inspection")
public class QmsInspectionController {

    private final QmsInspectionService qms;
    private final PurInboundPlanService inboundPlan;
    private final com.industrial.erp.modules.purchase.service.PurOrderService purOrderService;

    public QmsInspectionController(QmsInspectionService qms, PurInboundPlanService inboundPlan,
                                   com.industrial.erp.modules.purchase.service.PurOrderService purOrderService) {
        this.qms = qms;
        this.inboundPlan = inboundPlan;
        this.purOrderService = purOrderService;
    }

    @Operation(summary = "新建检验单 (DRAFT)")
    @SaCheckPermission(value = {"quality:inspection:add"}, orRole = "admin")
    @PostMapping
    public R<Long> create(@RequestBody InspectionCreateDTO dto) {
        return R.ok(qms.create(dto, SecurityContext.getUsername()));
    }

    @Operation(summary = "检验单分页")
    @SaCheckPermission(value = {"quality:inspection:list"}, orRole = "admin")
    @GetMapping("/page")
    public R<IPage<MesQualityInspection>> page(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String inspectionType,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String status) {
        return R.ok(qms.page(pageNum, pageSize, inspectionType, result, status));
    }

    @Operation(summary = "检验单详情 (head + defects)")
    @SaCheckPermission(value = {"quality:inspection:list"}, orRole = "admin")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.ok(qms.detail(id));
    }

    @Operation(summary = "审核检验单 (写 result + 联动批次锁/放行)")
    @SaCheckPermission(value = {"quality:inspection:check"}, mode = SaMode.OR, orRole = "admin")
    @PostMapping("/{id}/check")
    public R<Void> check(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String result = body == null ? null : body.get("result");
        String disposition = body == null ? null
                : body.getOrDefault("disposition", Constants.BATCH_FROZEN);
        String reason = body == null ? "" : body.getOrDefault("reason", "");
        // v1.1.100 OQC 3 签 + 留样: 前端透传 审核/批准人 姓名 + 留样编号/位置/期限 (缺则 service 按 OQC 规则回填)
        String reviewerName = body == null ? null : body.get("reviewerName");
        String approverName = body == null ? null : body.get("approverName");
        String retainNo = body == null ? null : body.get("retainNo");
        String retainLoc = body == null ? null : body.get("retainLoc");
        java.time.LocalDate retainDueDate = parseDate(body, "retainDueDate");
        qms.check(id, result, disposition, reason, reviewerName, approverName, retainNo, retainLoc, retainDueDate);
        return R.ok();
    }

    private static java.time.LocalDate parseDate(Map<String, String> body, String key) {
        if (body == null) return null;
        String v = body.get(key);
        if (v == null || v.trim().isEmpty()) return null;
        try { return java.time.LocalDate.parse(v.trim()); } catch (Exception e) { return null; }
    }

    @Operation(summary = "反向拒收 (= FAIL_REJECT + SCRAP)")
    @SaCheckPermission(value = {"quality:inspection:check"}, mode = SaMode.OR, orRole = "admin")
    @PostMapping("/{id}/reject")
    public R<Void> reject(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String reason = body == null ? "" : body.getOrDefault("reason", "");
        qms.reject(id, reason);
        return R.ok();
    }

    /**
     * v1.1.95 XK 三步采购入库 — 为采购入库单「签署合格检验报告」.
     * 一步建成/审核该入库单关联的 IQC 为 PASS, 并冗余快照写回入库单.
     * 之后前端调 purchase/receipt/{id}/check 完成入库 (门禁已在 check() 里).
     */
    @Operation(summary = "XK: 为采购入库单签署合格检验 (PASS), 放行入库")
    @SaCheckPermission(value = {"quality:inspection:check"}, mode = SaMode.OR, orRole = "admin")
    @PostMapping("/receipt/{receiptId}/sign")
    public R<Long> signReceiptIqc(@PathVariable Long receiptId, @RequestBody Map<String, String> body) {
        java.math.BigDecimal sampleQty = null;
        if (body != null && body.get("sampleQty") != null && !body.get("sampleQty").isEmpty()) {
            try { sampleQty = new java.math.BigDecimal(body.get("sampleQty")); } catch (Exception ignore) {}
        }
        String reportNo = body == null ? null : body.get("reportNo");
        String inspector = body == null ? null : body.get("inspector");
        String photos = body == null ? null : body.get("photoUrls");
        Long insId = qms.signPassedIqcForReceipt(receiptId, reportNo, sampleQty, inspector, photos);
        return R.ok(insId);
    }

    /** 静态包装: 防止 controller lambda 引用变量 — 无业务用途, 仅占位 */
    @SuppressWarnings("unused")
    private static <T> LambdaQueryWrapper<T> placeholder() {
        return new LambdaQueryWrapper<>();
    }

    // ============ v1.1.101 XK — App 端 pages/scan/in 三步采购计划 → 进货检验 → 自动入库 ============

    /**
     * ① 从采购计划触发, 建/复用 IQC 草稿. App 端 "选计划" 后调.
     * 幂等: 已有关联 IQC 直接返回其 id.
     */
    @Operation(summary = "XK: 从采购计划自动建/复用 IQC 草稿")
    @SaCheckPermission(value = {"quality:inspection:add"}, orRole = "admin")
    @PostMapping("/create-from-plan")
    public R<Long> createFromPlan(@RequestBody Map<String, Long> body) {
        Long planId = body == null ? null : body.get("planId");
        PurInboundPlan plan = inboundPlan.findById(planId);
        if (plan == null) throw BizException.of("采购计划不存在: " + planId);
        return R.ok(qms.createFromPlan(plan));
    }
    /**
     * ② IQC 详情 (head + items + attachments + plan) — App 端主入口, 4 段一次回传.
     */
    @Operation(summary = "XK: IQC 详情 (head+items+attachments+plan)")
    @SaCheckPermission(value = {"qms:iqc:detail"}, orRole = "admin")
    @GetMapping("/{id}/detail-for-plan")
    public R<Map<String, Object>> detailForPlan(@PathVariable Long id) {
        return R.ok(qms.detailForPlan(id));
    }

    /**
     * ③ 签署合格 + 自动放行 (全部检验项 pass 才放行) — App 端 "生成报告并自动入库" 第一步.
     * 成功后再调 purchase/receipt/create-from-iqc 自动建 DRAFT 入库单.
     */
    @Operation(summary = "XK: 签署合格 IQC + 放行 (校验全部检验项合格)")
    @SaCheckPermission(value = {"quality:inspection:check"}, mode = SaMode.OR, orRole = "admin")
    @PostMapping("/sign-for-plan")
    public R<Void> signForPlan(@RequestBody Map<String, Object> body) {
        Long planId = toLong(body.get("planId"));
        Long iqcId = toLong(body.get("iqcId"));
        String reportNo = (String) body.get("reportNo");
        java.math.BigDecimal sampleQty = toBigDecimal(body.get("sampleQty"));
        String inspectorName = (String) body.get("inspectorName");
        String photoUrls = (String) body.get("photoUrls");
        qms.signPassedIqcForPlan(planId, iqcId, reportNo, sampleQty, inspectorName, photoUrls);
        return R.ok();
    }

    // ============ v1.1.101 订单为源 — App 端 关联采购订单 → 进货检验 → 自动入库 (完全对齐 PC) ============

    /**
     * ①' 从采购订单触发, 建/复用 IQC 草稿 (sourceBillType=PUR_ORDER). 幂等.
     */
    @Operation(summary = "XK: 从采购订单自动建/复用 IQC 草稿")
    @SaCheckPermission(value = {"quality:inspection:add"}, orRole = "admin")
    @PostMapping("/create-from-order")
    public R<Long> createFromOrder(@RequestBody Map<String, Long> body) {
        Long orderId = body == null ? null : body.get("orderId");
        com.industrial.erp.modules.purchase.entity.PurOrder order = purOrderService.detail(orderId);
        if (order == null) throw BizException.of("采购订单不存在: " + orderId);
        return R.ok(qms.createFromOrder(order));
    }

    /**
     * ②' IQC 详情 (head + items + attachments + order) — 订单版 App 主入口.
     */
    @Operation(summary = "XK: IQC 详情 (head+items+attachments+order)")
    @SaCheckPermission(value = {"qms:iqc:detail"}, orRole = "admin")
    @GetMapping("/{id}/detail-for-order")
    public R<Map<String, Object>> detailForOrder(@PathVariable Long id) {
        return R.ok(qms.detailForOrder(id));
    }

    /**
     * ③' 签署合格 + 放行 (订单版). 成功后再调 purchase/receipt/create-from-order 自动建 DRAFT 入库单.
     */
    @Operation(summary = "XK: 签署合格 IQC + 放行 (订单版)")
    @SaCheckPermission(value = {"quality:inspection:check"}, mode = SaMode.OR, orRole = "admin")
    @PostMapping("/sign-for-order")
    public R<Void> signForOrder(@RequestBody Map<String, Object> body) {
        Long orderId = toLong(body.get("orderId"));
        Long iqcId = toLong(body.get("iqcId"));
        String reportNo = (String) body.get("reportNo");
        java.math.BigDecimal sampleQty = toBigDecimal(body.get("sampleQty"));
        String inspectorName = (String) body.get("inspectorName");
        String photoUrls = (String) body.get("photoUrls");
        qms.signPassedIqcForOrder(orderId, iqcId, reportNo, sampleQty, inspectorName, photoUrls);
        return R.ok();
    }

    // ============ v1.1.101 App XK 吹膜生产全流程 — 半成品 FAI / 成品 OQC 现场签合格 ============

    /**
     * App 端现场签合格 — 半成品入库 FAI 版.
     * 检验员逐检验项打分 + 拍照后, 直接 check(PASS) 写回门禁快照, 仓管随即能过半成品入库门禁.
     * body: { semiInId, faiId?(可空, 空则自动定位), reportNo, sampleQty, inspectorName, photoUrls }
     */
    @Operation(summary = "XK: 现场签合格 FAI + 放行 (半成品入库)")
    @SaCheckPermission(value = {"quality:inspection:check"}, mode = SaMode.OR, orRole = "admin")
    @PostMapping("/sign-fai-for-semi-in")
    public R<Void> signFaiForSemiIn(@RequestBody Map<String, Object> body) {
        Long semiInId = toLong(body.get("semiInId"));
        Long faiId = toLong(body.get("faiId"));
        String reportNo = (String) body.get("reportNo");
        java.math.BigDecimal sampleQty = toBigDecimal(body.get("sampleQty"));
        String inspectorName = (String) body.get("inspectorName");
        String photoUrls = (String) body.get("photoUrls");
        qms.signPassedFaiForSemiIn(semiInId, faiId, reportNo, sampleQty, inspectorName, photoUrls);
        return R.ok();
    }

    /**
     * App 端现场签合格 — 成品入库 OQC 版.
     * 检验员成品检逐检验项打分 + 拍照后, 直接 check(PASS), 仓管随即能过成品入库 OQC 软门禁.
     * body: { finishedInId, oqcId?(可空), reportNo, sampleQty, inspectorName, photoUrls }
     */
    @Operation(summary = "XK: 现场签合格 OQC + 放行 (成品入库)")
    @SaCheckPermission(value = {"quality:inspection:check"}, mode = SaMode.OR, orRole = "admin")
    @PostMapping("/sign-oqc-for-finished-in")
    public R<Void> signOqcForFinishedIn(@RequestBody Map<String, Object> body) {
        Long finishedInId = toLong(body.get("finishedInId"));
        Long oqcId = toLong(body.get("oqcId"));
        String reportNo = (String) body.get("reportNo");
        java.math.BigDecimal sampleQty = toBigDecimal(body.get("sampleQty"));
        String inspectorName = (String) body.get("inspectorName");
        String photoUrls = (String) body.get("photoUrls");
        qms.signPassedOqcForFinishedIn(finishedInId, oqcId, reportNo, sampleQty, inspectorName, photoUrls);
        return R.ok();
    }

    private static Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(o.toString().trim()); } catch (Exception e) { return null; }
    }

    private static java.math.BigDecimal toBigDecimal(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return new java.math.BigDecimal(n.toString());
        try { return new java.math.BigDecimal(o.toString().trim()); } catch (Exception e) { return null; }
    }

    /** 静态包装: 同上. */
    @SuppressWarnings("unused")
    private static <T> Page<T> placeholderPage() {
        return new Page<>();
    }
}
