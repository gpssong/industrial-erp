package com.industrial.erp.modules.xk.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.em.entity.BaseEquipment;
import com.industrial.erp.modules.em.service.EquipmentService;
import com.industrial.erp.modules.mes.entity.MesBatch;
import com.industrial.erp.modules.mes.service.MesReportService;
import com.industrial.erp.modules.purchase.entity.PurReceipt;
import com.industrial.erp.modules.purchase.service.PurReceiptService;
import com.industrial.erp.modules.qms.service.QmsInspectionService;
import com.industrial.erp.modules.report.mapper.ReportMapper;
import com.industrial.erp.modules.system.service.SysConfigService;
import com.industrial.erp.modules.workflow.service.WfDashboardService;
import com.industrial.erp.modules.workflow.service.WfMaintainRecordService;
import com.industrial.erp.modules.xk.entity.XkAuditLog;
import com.industrial.erp.modules.xk.entity.XkSafetyCheck;
import com.industrial.erp.modules.xk.service.XkAuditService;
import com.industrial.erp.modules.xk.service.XkSafetyCheckService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.1.91 XK 合规中心 (XK16-204) — 7 个前端页面的后端数据源。
 *
 * <p>设计 (方式 A 复用): 本 controller 不重复造底层数据, 而是<b>聚合</b>既有 module:
 * <ul>
 *   <li>原料入库 → {@link PurReceiptService}</li>
 *   <li>批次溯源 → {@link MesReportService}</li>
 *   <li>设备维保 → {@link WfMaintainRecordService} + {@link EquipmentService}</li>
 *   <li>卫生检查 → {@link XkSafetyCheckService} (XK 专用新表)</li>
 *   <li>单据打印 / 审计 → {@link XkAuditService} (XK 专用新表)</li>
 *   <li>Dashboard KPI → {@link ReportMapper} + {@link WfDashboardService} + 维保/检验待办聚合</li>
 * </ul>
 *
 * <p>权限: 各端点复用既有 perm (purchase:report/work/xk:audit), orRole=admin 兜底。
 * 基础数据读接口走对应模块 list perm; XK 专用表 (卫生/审计) 走 xk:audit:* 或 work:* 兜底。
 */
@Tag(name = "XK 合规中心 (XK16-204)")
@RestController
@RequestMapping("/xk")
public class XkController {

    private final ReportMapper reportMapper;
    private final WfDashboardService wfDashboardService;
    private final WfMaintainRecordService wfMaintainRecordService;
    private final com.industrial.erp.modules.em.service.EmMaintainOrderService emMaintainOrderService;
    private final EquipmentService equipmentService;
    private final MesReportService mesReportService;
    private final PurReceiptService purReceiptService;
    private final QmsInspectionService qmsInspectionService;
    private final XkSafetyCheckService xkSafetyCheckService;
    private final XkAuditService xkAuditService;
    private final SysConfigService sysConfigService;

    public XkController(ReportMapper reportMapper,
                        WfDashboardService wfDashboardService,
                        WfMaintainRecordService wfMaintainRecordService,
                        com.industrial.erp.modules.em.service.EmMaintainOrderService emMaintainOrderService,
                        EquipmentService equipmentService,
                        MesReportService mesReportService,
                        PurReceiptService purReceiptService,
                        QmsInspectionService qmsInspectionService,
                        XkSafetyCheckService xkSafetyCheckService,
                        XkAuditService xkAuditService,
                        SysConfigService sysConfigService) {
        this.reportMapper = reportMapper;
        this.wfDashboardService = wfDashboardService;
        this.wfMaintainRecordService = wfMaintainRecordService;
        this.emMaintainOrderService = emMaintainOrderService;
        this.equipmentService = equipmentService;
        this.mesReportService = mesReportService;
        this.purReceiptService = purReceiptService;
        this.qmsInspectionService = qmsInspectionService;
        this.xkSafetyCheckService = xkSafetyCheckService;
        this.xkAuditService = xkAuditService;
        this.sysConfigService = sysConfigService;
    }

    // ==================================================================
    // 1. Dashboard (KPI + 产线 + 待办聚合)
    // ==================================================================

    @Operation(summary = "XK 工作台 KPI + 待办聚合")
    @SaCheckPermission(value = {"dashboard:kpi"}, orRole = "admin")
    @GetMapping("/dashboard")
    public R<Map<String, Object>> dashboard() {
        Map<String, Object> data = new HashMap<>();
        // 4 KPI 走 reportMapper.dashboardKpi() (与工作台同源, 真实销售/库存/应收数据)
        try {
            List<Map<String, Object>> kpi = reportMapper.dashboardKpi();
            if (kpi != null && !kpi.isEmpty()) {
                data.put("erpKpi", kpi.get(0));
            }
        } catch (Exception ignore) { /* 审计旁路: KPI 拉取失败不影响 XK 待办区 */ }

        // 维保待办 (v1.1.99 附带清理: 切 em_maintain 新表, 与维保功能页同源; status=PENDING/IN_PROGRESS 视为待办)
        try {
            var page = emMaintainOrderService.pageForXk(1, 8, null, "PENDING");
            data.put("maintainTodos", PageResult.of(page).getRecords());
        } catch (Exception ignore) { data.put("maintainTodos", java.util.Collections.emptyList()); }

        // 检验待办 (qms DRAFT) → todos 聚合
        try {
            var inspPage = qmsInspectionService.page(1, 8, null, null, "DRAFT");
            data.put("inspectTodos", PageResult.of(inspPage).getRecords());
        } catch (Exception ignore) { data.put("inspectTodos", java.util.Collections.emptyList()); }

        // 合规 KPI (待办数 = 维保 + 检验 + 卫生 CHECKED 以外)
        Map<String, Object> compliance = new HashMap<>();
        compliance.put("maintainPending", PageResult.of(emMaintainOrderService.pageForXk(1, 1, null, "PENDING")).getTotal());
        compliance.put("inspectPending", PageResult.of(qmsInspectionService.page(1, 1, null, null, "DRAFT")).getTotal());
        data.put("compliance", compliance);

        // 数据源时间戳 (XK16-204 R5 服务器时间)
        data.put("serverTime", java.time.LocalDateTime.now().toString());
        return R.ok(data);
    }

    // ==================================================================
    // 2. 原料入库 (复用 PurReceiptService)
    // ==================================================================

    @Operation(summary = "XK 原料入库 — 采购入库单分页")
    @SaCheckPermission(value = {"purchase:receipt:list"}, orRole = "admin")
    @GetMapping("/material/page")
    public R<PageResult<PurReceipt>> materialPage(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) String billNo,
            @RequestParam(required = false) String billStatus,
            @RequestParam(required = false) String productName) {
        return R.ok(PageResult.of(purReceiptService.page(pageNum, pageSize, billNo, null, billStatus, productName)));
    }

    @Operation(summary = "XK 原料入库 — 入库单详情 (含明细)")
    @SaCheckPermission(value = {"purchase:receipt:list"}, orRole = "admin")
    @GetMapping("/material/{id}")
    public R<PurReceipt> materialDetail(@PathVariable Long id) {
        return R.ok(purReceiptService.detail(id));
    }

    @Operation(summary = "XK 原料入库 — 提交入库单 (走采购入库 + XK 审计 INBOUND)")
    @SaCheckPermission(value = {"purchase:receipt:add"}, orRole = "admin")
    @PostMapping("/material")
    public R<Long> materialSubmit(@RequestBody PurReceipt receipt) {
        purReceiptService.add(receipt);
        xkAuditService.writeAudit("INBOUND", receipt.getBillNo(), "UPLOAD",
                "XK 原料入库提交 " + receipt.getBillNo(), null, null, null);
        return R.ok(receipt.getId());
    }

    // ==================================================================
    // 3. 批次溯源 (复用 MesReportService)
    // ==================================================================

    @Operation(summary = "XK 批次溯源 — 批次列表")
    @SaCheckPermission(value = {"production:report:list"}, orRole = "admin")
    @GetMapping("/trace/batches")
    public R<List<MesBatch>> traceBatches(@RequestParam(required = false) String batchType,
                                          @RequestParam(required = false) Long productId) {
        return R.ok(mesReportService.listBatches(batchType, productId));
    }

    @Operation(summary = "XK 批次溯源 — 双向链路追溯")
    @SaCheckPermission(value = {"production:report:list"}, orRole = "admin")
    @GetMapping("/trace/{batchNo}")
    public R<MesReportService.TraceResult> trace(@PathVariable String batchNo,
                                                 @RequestParam(defaultValue = "5") int depth) {
        return R.ok(mesReportService.batchTrace(batchNo, depth));
    }

    @Operation(summary = "XK 批次溯源 — 服务端全链追溯 (料→半成品→成品→客户 一次展开)")
    @SaCheckPermission(value = {"production:report:list"}, orRole = "admin")
    @GetMapping("/trace/full/{batchNo}")
    public R<MesReportService.FullTrace> traceFull(@PathVariable String batchNo) {
        return R.ok(mesReportService.traceFull(batchNo));
    }

    // ==================================================================
    // 4. 设备维保 (复用 WfMaintainRecordService + EquipmentService)
    // ==================================================================

    @Operation(summary = "XK 设备维保 — 工单分页 (em_maintain 新表, 含设备名)")
    @SaCheckPermission(value = {"work:maintain:list"}, orRole = "admin")
    @GetMapping("/maintenance/orders")
    public R<PageResult<com.industrial.erp.modules.em.entity.EmMaintainOrder>> maintenanceOrders(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) String deviceName,
            @RequestParam(required = false) String billStatus,
            @RequestParam(required = false) Boolean overdue) {
        // v1.1.99 附带清理: 数据源切 em_maintain (维保功能页同源), deviceName 模糊匹配设备编码/名称
        return R.ok(PageResult.of(emMaintainOrderService.pageForXk(pageNum, pageSize, deviceName, billStatus)));
    }

    @Operation(summary = "XK 设备维保 — 设备台账 (按设备码)")
    @SaCheckPermission(value = {"equipment:master:list"}, orRole = "admin")
    @GetMapping("/maintenance/equipment")
    public R<BaseEquipment> equipment(@RequestParam String code) {
        // 按 equipment_code 查 (EquipmentService 无 byCode, 直接 page + filter 兜底)
        var page = equipmentService.page(null, null, 1, 100);
        BaseEquipment hit = null;
        for (BaseEquipment e : PageResult.of(page).getRecords()) {
            if (code.equalsIgnoreCase(e.getEquipmentCode())) { hit = e; break; }
        }
        return R.ok(hit);
    }

    // ==================================================================
    // 5. 卫生检查 (XK 专用 xk_safety_check)
    // ==================================================================

    @Operation(summary = "XK 卫生检查 — 列表")
    @SaCheckPermission(value = {"xk:audit:list", "work:safety:list"}, orRole = "admin")
    @GetMapping("/safety/check")
    public R<PageResult<XkSafetyCheck>> safetyCheckPage(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) String billStatus) {
        return R.ok(PageResult.of(xkSafetyCheckService.page(pageNum, pageSize, billStatus)));
    }

    @Operation(summary = "XK 卫生检查 — 新增")
    @SaCheckPermission(value = {"xk:audit:list", "work:safety:add"}, orRole = "admin")
    @PostMapping("/safety/check")
    public R<XkSafetyCheck> safetyCheckAdd(@RequestBody XkSafetyCheck c) {
        return R.ok(xkSafetyCheckService.add(c));
    }

    @Operation(summary = "XK 卫生检查 — 提交 (DRAFT→CHECKED + 审计)")
    @SaCheckPermission(value = {"xk:audit:list", "work:safety:check"}, orRole = "admin")
    @PostMapping("/safety/check/{id}/submit")
    public R<Void> safetyCheckSubmit(@PathVariable Long id) {
        xkSafetyCheckService.check(id);
        return R.ok();
    }

    @Operation(summary = "XK 卫生检查 — 作废 (R2 不可物理删除)")
    @SaCheckPermission(value = {"xk:audit:list", "work:safety:edit"}, orRole = "admin")
    @PostMapping("/safety/check/{id}/void")
    public R<Void> safetyCheckVoid(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        xkSafetyCheckService.voidCheck(id, reason);
        return R.ok();
    }

    // ==================================================================
    // 6. 单据打印 (XK 审计 + 留存配置)
    // ==================================================================

    @Operation(summary = "XK 单据打印 — 打印参数 (留存年限等 XK 配置)")
    @SaCheckPermission(value = {"xk:audit:archive"}, orRole = "admin")
    @GetMapping("/print/preview")
    public R<Map<String, Object>> printPreview() {
        Map<String, Object> data = new HashMap<>();
        String retention = sysConfigService.getByKey("XK_AUDIT_RETENTION_YEARS");
        data.put("retentionYears", retention == null ? "2" : retention);
        data.put("serverTime", java.time.LocalDateTime.now().toString());
        // 返回 XK 合规水印所需配置 (XK16-204 R5 服务器时间戳)
        return R.ok(data);
    }

    @Operation(summary = "XK 单据打印 — 记录一条 PDF 生成审计 (含 SHA256)")
    @SaCheckPermission(value = {"xk:audit:archive"}, orRole = "admin")
    @PostMapping("/print/audit")
    public R<Void> printAudit(@RequestBody Map<String, String> body) {
        xkAuditService.writeAudit("PDF",
                body.get("billNo"),
                "PDF",
                "导出 XK 合规单据 " + body.get("billNo"),
                body.get("sha256"),
                body.get("ossPath"),
                body.get("sizeKb") == null ? null : Integer.parseInt(body.get("sizeKb")));
        return R.ok();
    }

    // ==================================================================
    // 7. 审计归档 (XK 专用 xk_audit_log)
    // ==================================================================

    @Operation(summary = "XK 审计日志 (R3 审计留痕)")
    @SaCheckPermission(value = {"xk:audit:list"}, orRole = "admin")
    @GetMapping("/audit/logs")
    public R<List<XkAuditLog>> auditLogs(@RequestParam(defaultValue = "50") int limit) {
        return R.ok(xkAuditService.listLogs(limit));
    }

    @Operation(summary = "XK 记录归档 (R4 数据留存 >= 2 年)")
    @SaCheckPermission(value = {"xk:audit:archive"}, orRole = "admin")
    @GetMapping("/audit/archive")
    public R<List<XkAuditLog>> auditArchive(@RequestParam(required = false) String bizType) {
        return R.ok(xkAuditService.archiveRecords(bizType));
    }

    // ==================================================================
    // 8. v1.1.101 XK — 原料进货检验 (IQC) 列表 + 详情 (供 PC 端 xk/Safety.vue "进货查验" tab 用)
    // ==================================================================

    @Operation(summary = "XK 进货查验 — IQC 列表")
    @SaCheckPermission(value = {"qms:iqc:list"}, orRole = "admin")
    @GetMapping("/iqc/page")
    public R<com.baomidou.mybatisplus.core.metadata.IPage<com.industrial.erp.modules.qms.entity.MesQualityInspection>> iqcPage(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String status) {
        return R.ok(qmsInspectionService.page(pageNum, pageSize, "IQC", result, status));
    }

    @Operation(summary = "XK 进货查验 — IQC 详情 (head + items + attachments + plan)")
    @SaCheckPermission(value = {"qms:iqc:detail"}, orRole = "admin")
    @GetMapping("/iqc/{id}")
    public R<java.util.Map<String, Object>> iqcDetail(@PathVariable Long id) {
        return R.ok(qmsInspectionService.detailForPlan(id));
    }
}
