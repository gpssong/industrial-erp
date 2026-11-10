package com.industrial.erp.modules.xk.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.industrial.erp.common.CreateByNameInjector;
import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseCustomer;
import com.industrial.erp.modules.base.mapper.BaseCustomerMapper;
import com.industrial.erp.modules.em.entity.BaseEquipment;
import com.industrial.erp.modules.em.service.EquipmentService;
import com.industrial.erp.modules.mes.entity.MesBatch;
import com.industrial.erp.modules.mes.service.MesReportService;
import com.industrial.erp.modules.purchase.entity.PurReceipt;
import com.industrial.erp.modules.purchase.service.PurReceiptService;
import com.industrial.erp.modules.qms.entity.MesQualityInspection;
import com.industrial.erp.modules.qms.service.QmsInspectionService;
import com.industrial.erp.modules.report.mapper.ReportMapper;
import com.industrial.erp.modules.system.mapper.SysUserMapper;
import com.industrial.erp.modules.system.service.SysConfigService;
import com.industrial.erp.modules.workflow.service.WfDashboardService;
import com.industrial.erp.modules.workflow.service.WfMaintainRecordService;
import com.industrial.erp.modules.xk.entity.XkAuditLog;
import com.industrial.erp.modules.xk.entity.XkEnvCheck;
import com.industrial.erp.modules.xk.entity.XkSafetyCheck;
import com.industrial.erp.modules.xk.service.XkAuditService;
import com.industrial.erp.modules.xk.service.XkEnvCheckService;
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
    /** v1.1.153 App「XK 单据打印」— 生产环境检查记录 (只给打印只读口径) */
    private final XkEnvCheckService xkEnvCheckService;
    private final SysConfigService sysConfigService;
    /** v1.1.147 XK 单据打印: 入库单要有制单人姓名 (仓库员签署), 追溯要有客户名 (流向出口). */
    private final SysUserMapper userMapper;
    private final BaseCustomerMapper customerMapper;

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
                        XkEnvCheckService xkEnvCheckService,
                        SysConfigService sysConfigService,
                        SysUserMapper userMapper,
                        BaseCustomerMapper customerMapper) {
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
        this.xkEnvCheckService = xkEnvCheckService;
        this.sysConfigService = sysConfigService;
        this.userMapper = userMapper;
        this.customerMapper = customerMapper;
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
    @SaCheckPermission(value = {"purchase:receipt:list", "report:print"}, mode = SaMode.OR, orRole = "admin")
    @GetMapping("/material/page")
    public R<PageResult<PurReceipt>> materialPage(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) String billNo,
            @RequestParam(required = false) String billStatus,
            @RequestParam(required = false) String productName) {
        PageResult<PurReceipt> pr = PageResult.of(purReceiptService.page(pageNum, pageSize, billNo, null, billStatus, productName));
        // v1.1.147: 打印页要显示「制单人/仓库员」, 与 /purchase/receipt/page 同口径注入姓名
        CreateByNameInjector.inject(userMapper, pr.getRecords(), PurReceipt::getCreateBy, PurReceipt::setCreateByName);
        return R.ok(pr);
    }

    @Operation(summary = "XK 原料入库 — 入库单详情 (含明细)")
    // v1.1.147: report:print 也要能读详情 — 否则只有打印权限的账号能列单却打不开单
    @SaCheckPermission(value = {"purchase:receipt:list", "report:print"}, mode = SaMode.OR, orRole = "admin")
    @GetMapping("/material/{id}")
    public R<PurReceipt> materialDetail(@PathVariable Long id) {
        PurReceipt r = purReceiptService.detail(id);
        if (r != null) {
            CreateByNameInjector.inject(userMapper, java.util.Collections.singletonList(r),
                    PurReceipt::getCreateBy, PurReceipt::setCreateByName);
        }
        return R.ok(r);
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
    // v1.1.130: 拆出独立 perm production:report:trace (与扫码报工分开授权); OR 兼容旧授权
    @SaCheckPermission(value = {"production:report:trace", "production:report:list"}, mode = SaMode.OR, orRole = "admin")
    @GetMapping("/trace/batches")
    public R<List<MesBatch>> traceBatches(@RequestParam(required = false) String batchType,
                                          @RequestParam(required = false) Long productId) {
        return R.ok(mesReportService.listBatches(batchType, productId));
    }

    @Operation(summary = "XK 批次溯源 — 双向链路追溯")
    @SaCheckPermission(value = {"production:report:trace", "production:report:list"}, mode = SaMode.OR, orRole = "admin")
    @GetMapping("/trace/{batchNo}")
    public R<MesReportService.TraceResult> trace(@PathVariable String batchNo,
                                                 @RequestParam(defaultValue = "5") int depth) {
        return R.ok(mesReportService.batchTrace(batchNo, depth));
    }

    @Operation(summary = "XK 批次溯源 — 服务端全链追溯 (料→半成品→成品→客户 一次展开)")
    @SaCheckPermission(value = {"production:report:trace", "production:report:list"}, mode = SaMode.OR, orRole = "admin")
    @GetMapping("/trace/full/{batchNo}")
    public R<MesReportService.FullTrace> traceFull(@PathVariable String batchNo) {
        MesReportService.FullTrace t = mesReportService.traceFull(batchNo);
        // v1.1.147: FullTrace.customerName 一直是空壳 —— 这里按 customer 回填, 现场才能看到「流向哪个客户」
        if (t != null && t.customer != null && StrUtil.isBlank(t.customerName)) {
            BaseCustomer c = customerMapper.selectById(t.customer);
            if (c != null) t.customerName = c.getCustomerName();
        }
        return R.ok(t);
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
    @SaCheckPermission(value = {"xk:audit:archive", "report:print"}, mode = SaMode.OR, orRole = "admin")
    @GetMapping("/print/preview")
    public R<Map<String, Object>> printPreview() {
        Map<String, Object> data = new HashMap<>();
        String retention = sysConfigService.getByKey("XK_AUDIT_RETENTION_YEARS");
        data.put("retentionYears", retention == null ? "2" : retention);
        data.put("serverTime", java.time.LocalDateTime.now().toString());
        // 报告抬头品牌/工厂/图标读项目配置 (超管可在「品牌与工厂」改), 不再写死华塑智造
        Map<String, Object> brand = new HashMap<>();
        String name = sysConfigService.getByKey("SYS_NAME");
        String enName = sysConfigService.getByKey("SYS_EN_NAME");
        String factory = sysConfigService.getByKey("SYS_FACTORY");
        String logo = sysConfigService.getByKey("SYS_LOGO");
        brand.put("name", name == null || name.isBlank() ? "工业ERP" : name);
        brand.put("enName", enName == null ? "" : enName);
        brand.put("factory", factory == null ? "" : factory);
        brand.put("logo", logo == null ? "" : logo);
        data.put("brand", brand);
        // 返回 XK 合规水印所需配置 (XK16-204 R5 服务器时间戳)
        return R.ok(data);
    }

    @Operation(summary = "XK 单据打印 — 记录一条 PDF 生成审计 (含 SHA256)")
    // 打印留痕必须由「有权打印」的人写入: 只挂 xk:audit:archive 会让 report:print 账号
    // (单据与打印 / App 单据打印的实际使用者) 静默 403, 审计链整段缺失.
    @SaCheckPermission(value = {"xk:audit:archive", "report:print"}, mode = SaMode.OR, orRole = "admin")
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
    // 6.1 v1.1.153 App「XK 单据打印」— 统一可打印单据清单 + 详情
    //
    // App 端只有 report:print 一个权限, 不能要求用户同时具备各源模块 list perm,
    // 所以端点只认「XK 单据打印」(report:print) 一个权限;
    // 服务层一律走无鉴权读方法 (qms 本身不拦; env 用 *ForPrint).
    // ==================================================================

    /** docType → {检验类型, 工序阶段}; 不在本表里的走 env / purchaseReceipt 专用分支. */
    private static final Map<String, String[]> PRINT_INSPECTION_TYPES = Map.of(
            "iqc", new String[]{"IQC", null},
            "faiBlow", new String[]{"FAI", "BLOW"},
            "faiBag", new String[]{"FAI", "BAG"},
            "patBlow", new String[]{"PAT", "BLOW"},
            "patBag", new String[]{"PAT", "BAG"},
            "oqc", new String[]{"OQC", null},
            "oqcReturn", new String[]{"OQC_RETURN", null});

    private static final Map<String, String> PRINT_DOC_TITLES = Map.of(
            "iqc", "原料进货检验记录 (IQC)",
            "faiBlow", "吹膜首检记录 (FAI)",
            "faiBag", "制袋首检记录 (FAI)",
            "patBlow", "吹膜巡检记录 (PAT)",
            "patBag", "制袋巡检记录 (PAT)",
            "oqc", "成品检验记录 (OQC)",
            "oqcReturn", "退货检验记录 (OQC_RETURN)",
            "env", "生产环境检查记录 (ENV)",
            "purchaseReceipt", "采购入库单");

    private static final Map<String, String> PRINT_DOC_GROUPS = Map.of(
            "iqc", "质量检验",
            "faiBlow", "质量检验",
            "faiBag", "质量检验",
            "patBlow", "质量检验",
            "patBag", "质量检验",
            "oqc", "质量检验",
            "oqcReturn", "质量检验",
            "env", "环境卫生检查",
            "purchaseReceipt", "采购管理");

    private static final Map<String, String> PRINT_DOC_BASIS = Map.of(
            "iqc", "Q/ZPC 1-2017",
            "faiBlow", "Q/ZPC 1-2017 表1~表3",
            "faiBag", "Q/ZPC 1-2017 表1~表5",
            "patBlow", "Q/ZPC 1-2017 表1~表3",
            "patBag", "Q/ZPC 1-2017 表1~表5",
            "oqc", "Q/ZPC 1-2017",
            "oqcReturn", "Q/ZPC 1-2017",
            "env", "GB 31603-2015",
            "purchaseReceipt", "Q/ZPC 1-2017");

    private static final Map<String, String> PRINT_STATUS_TEXT = Map.of(
            "DRAFT", "草稿", "CHECKED", "已审核", "FINISHED", "已完成",
            "UNCHECKED", "已弃审", "VOID", "已作废");

    private static String printResultText(String result) {
        if (StrUtil.isBlank(result)) return null;
        return switch (result) {
            case "PASS" -> "合格";
            case "PASS_DISPOSITION" -> "合格(让步放行)";
            case "FAIL" -> "不合格";
            case "FAIL_REJECT" -> "不合格(拒收)";
            case "HOLD" -> "待定";
            default -> result;
        };
    }

    private static String printLabel(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (Object part : parts) {
            if (part == null) continue;
            String s = String.valueOf(part).replace('T', ' ').trim();
            if (s.isEmpty() || "-".equals(s)) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(s);
        }
        return sb.toString();
    }

    @Operation(summary = "XK 单据打印 — 可打印单据清单 (App)")
    @SaCheckPermission(value = {"report:print"}, orRole = "admin")
    @GetMapping("/print/docs")
    public R<Map<String, Object>> printDocs(@RequestParam String docType,
                                            @RequestParam(defaultValue = "1") Integer pageNum,
                                            @RequestParam(defaultValue = "50") Integer pageSize) {
        String type = StrUtil.blankToDefault(docType, "iqc");
        int page = Math.max(pageNum == null ? 1 : pageNum, 1);
        int size = Math.min(Math.max(pageSize == null ? 50 : pageSize, 1), 100);

        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        long total = 0;
        String[] insp = PRINT_INSPECTION_TYPES.get(type);
        if (insp != null) {
            IPage<MesQualityInspection> p = qmsInspectionService.page(page, size, insp[0], null, null, insp[1]);
            total = p.getTotal();
            for (MesQualityInspection x : p.getRecords()) {
                String resultText = printResultText(x.getResult());
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("id", x.getId());
                row.put("docType", type);
                row.put("billNo", x.getBillNo());
                row.put("billDate", x.getBillDate());
                row.put("status", x.getStatus());
                row.put("result", x.getResult());
                row.put("productName", x.getProductName());
                row.put("batchNo", x.getBatchNo());
                row.put("processName", x.getProcessName());
                row.put("label", printLabel(x.getBillNo(), x.getBillDate(), x.getProductName(),
                        x.getBatchNo(), x.getProcessName(),
                        resultText != null ? resultText : PRINT_STATUS_TEXT.get(x.getStatus())));
                rows.add(row);
            }
        } else if ("env".equals(type)) {
            IPage<XkEnvCheck> p = xkEnvCheckService.pageForPrint(null, null, null, page, size);
            total = p.getTotal();
            for (XkEnvCheck x : p.getRecords()) {
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("id", x.getId());
                row.put("docType", type);
                row.put("billNo", x.getCheckNo());
                row.put("billDate", x.getPlannedAt());
                row.put("status", x.getStatus());
                row.put("productName", x.getCheckTitle());
                row.put("processName", x.getEnvType());
                // 标题本身常带车间 (如「吹膜车间 吹膜开工前检查」), 再拼一次车间会很啰嗦
                String envTitle = StrUtil.blankToDefault(x.getCheckTitle(), x.getEnvType());
                String envWorkshop = StrUtil.isNotBlank(x.getWorkshop())
                        && !StrUtil.contains(envTitle, x.getWorkshop()) ? x.getWorkshop() : null;
                row.put("label", printLabel(x.getCheckNo(), x.getPlannedAt(), envWorkshop,
                        envTitle, PRINT_STATUS_TEXT.get(x.getStatus())));
                rows.add(row);
            }
        } else if ("purchaseReceipt".equals(type)) {
            PageResult<PurReceipt> pr = PageResult.of(
                    purReceiptService.page(page, size, null, null, null, null));
            total = pr.getTotal();
            for (PurReceipt x : pr.getRecords()) {
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("id", x.getId());
                row.put("docType", type);
                row.put("billNo", x.getBillNo());
                row.put("billDate", x.getBillDate());
                row.put("status", x.getBillStatus());
                row.put("label", printLabel(x.getBillNo(), x.getBillDate(), x.getSupplierName(),
                        PRINT_STATUS_TEXT.get(x.getBillStatus())));
                rows.add(row);
            }
        } else {
            throw BizException.of("不支持的单据类型: " + type);
        }

        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("docType", type);
        data.put("title", PRINT_DOC_TITLES.getOrDefault(type, "XK 受控记录"));
        data.put("group", PRINT_DOC_GROUPS.getOrDefault(type, "XK 合规"));
        data.put("total", total);
        data.put("records", rows);
        return R.ok(data);
    }

    @Operation(summary = "XK 单据打印 — 单据详情 (App, 检验项/缺陷/照片一次取全)")
    // PC 打印页详情原走 quality:inspection:list; 统一接本出口后 OR 上该 perm, 避免仅有检验读取权的角色 403
    @SaCheckPermission(value = {"report:print", "quality:inspection:list"}, mode = SaMode.OR, orRole = "admin")
    @GetMapping("/print/docs/{docType}/{id}")
    public R<Map<String, Object>> printDocDetail(@PathVariable String docType,
                                                 @PathVariable Long id) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("docType", docType);
        data.put("title", PRINT_DOC_TITLES.getOrDefault(docType, "XK 受控记录"));
        data.put("group", PRINT_DOC_GROUPS.getOrDefault(docType, "XK 合规"));
        data.put("basis", PRINT_DOC_BASIS.getOrDefault(docType, "Q/ZPC 1-2017"));

        String[] insp = PRINT_INSPECTION_TYPES.get(docType);
        if (insp != null) {
            Map<String, Object> d = qmsInspectionService.detailForPrint(id);
            MesQualityInspection head = (MesQualityInspection) d.get("head");
            if (!insp[0].equals(head.getInspectionType())) {
                throw BizException.of("单据类型不匹配: 该单据不是「" + PRINT_DOC_TITLES.get(docType) + "」");
            }
            if (insp[1] != null && !insp[1].equals(head.getStage())) {
                throw BizException.of("单据类型不匹配: 该单据不属于「" + PRINT_DOC_TITLES.get(docType) + "」对应工序");
            }
            data.putAll(d);
        } else if ("env".equals(docType)) {
            XkEnvCheck head = xkEnvCheckService.findByIdForPrint(id);
            if (head == null) throw BizException.of("环境检查单不存在");
            data.put("head", head);
        } else if ("purchaseReceipt".equals(docType)) {
            PurReceipt head = purReceiptService.detail(id);
            if (head == null) throw BizException.of("采购入库单不存在");
            CreateByNameInjector.inject(userMapper, java.util.Collections.singletonList(head),
                    PurReceipt::getCreateBy, PurReceipt::setCreateByName);
            data.put("head", head);
        } else {
            throw BizException.of("不支持的单据类型: " + docType);
        }
        return R.ok(data);
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
