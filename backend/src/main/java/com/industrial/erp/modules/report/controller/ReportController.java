package com.industrial.erp.modules.report.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.industrial.erp.common.R;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.report.mapper.ReportMapper;
import com.industrial.erp.modules.report.service.RecycleBinService;
import com.industrial.erp.modules.report.service.SalesSummaryService;
import com.industrial.erp.security.PermissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Tag(name = "报表中心")
@RestController
@RequestMapping("/report")
// v1.1.53: 拆掉类级 @SaCheckPermission("report:view").
// 类级注解会强制整个 controller 的所有端点都要 report:view, 但现在工作台 (dashboard)
// 要拆细: KPI/趋势/排行 用 dashboard:* perm 控制. 报表中心子页面 (/report/sales
// /report/inventory) 继续由 report:view 控制 — 在具体方法上加注解.
public class ReportController {

    public ReportController(ReportMapper reportMapper, RecycleBinService recycleBinService,
                            PermissionService permissionService,
                            SalesSummaryService salesSummaryService) {
        this.reportMapper = reportMapper;
        this.recycleBinService = recycleBinService;
        this.permissionService = permissionService;
        this.salesSummaryService = salesSummaryService;
    }

    private final ReportMapper reportMapper;
    private final RecycleBinService recycleBinService;
    private final PermissionService permissionService;
    /** v1.1.145: 明细/汇总/CSV 的口径与列定义都只在这一个 service 里. */
    private final SalesSummaryService salesSummaryService;

    // v1.1.53: 工作台 KPI 卡片 — 独立鉴权 dashboard:kpi.
    // 同时清理孤儿 requirePerm("dashboard:view") (v1.0.10+ 遗留, sql 里无对应 sys_menu 行).
    // v1.1.130: App「经营简报」入口 perm=report:view, 该页要读 KPI 卡片 → 认 report:view.
    @SaCheckPermission(value = {"dashboard:kpi", "report:view"}, mode = SaMode.OR)
    @GetMapping("/dashboard")
    public R<Map<String, Object>> dashboard() {
        return R.ok(reportMapper.dashboardKpi().get(0));
    }

    // v1.1.53: 工作台销售趋势 — 独立鉴权 dashboard:sales-trend
    // v1.1.145: 加 report:view OR —— 报表中心销售报表页顶部折线图用的就是这个端点,
    //   只授权了「报表中心」的销售岗会 403, 整页图表静默空白。
    @SaCheckPermission(value = {"dashboard:sales-trend", "report:view"}, mode = SaMode.OR)
    @GetMapping("/sales/summary")
    public R<List<Map<String, Object>>> salesSummary(@RequestParam String startDate, @RequestParam String endDate) {
        return R.ok(reportMapper.salesSummary(startDate, endDate));
    }

    // v1.1.53: 工作台销售排行 TOP10 — 独立鉴权 dashboard:sales-ranking
    @SaCheckPermission(value = {"dashboard:sales-ranking", "report:view"}, mode = SaMode.OR)
    @GetMapping("/sales/ranking")
    public R<List<Map<String, Object>>> salesRanking(@RequestParam String startDate, @RequestParam String endDate,
                                                     @RequestParam(defaultValue = "20") Integer limit) {
        return R.ok(reportMapper.salesRanking(startDate, endDate, limit));
    }

    // 报表中心菜单 (/report/sales /report/inventory 子页面) 继续由 report:view 控制
    @SaCheckPermission("report:view")
    @GetMapping("/inventory/summary")
    public R<List<Map<String, Object>>> inventorySummary() { return R.ok(reportMapper.inventorySummary()); }

    @SaCheckPermission("report:view")
    @GetMapping("/inventory/aging")
    public R<List<Map<String, Object>>> inventoryAging() { return R.ok(reportMapper.inventoryAging()); }

    @SaCheckPermission("report:view")
    @GetMapping("/arap")
    public R<List<Map<String, Object>>> arap(@RequestParam String billType) { return R.ok(reportMapper.arapSummary(billType)); }

    @SaCheckPermission("report:view")
    @GetMapping("/profit")
    public R<List<Map<String, Object>>> profit(@RequestParam String startDate, @RequestParam String endDate) {
        return R.ok(reportMapper.profitAnalysis(startDate, endDate));
    }


    // =======================================================================
    // v1.1.145 报表中心 — 按客户/时间段查「销售明细 + 汇总」并下载 CSV.
    // 表结构/数字格式/CSV 全由 SalesSummaryService 出 (与 AI 助手同一份口径), 这里只做门禁与拼装.
    // 门禁 OR: report:view (报表中心) / sales:delivery:list (销售出库列表) —— 销售岗常只有后者,
    //   不该因为没勾"报表中心"就看不了自家客户的销售数.
    // 返回值里带 error 而不是抛 500: 前端要能把「参数不对 / 没权限」原样显示在表格位置.
    // =======================================================================

    /** 销售汇总: 按客户 (默认) 或按月; totals 行是本次查询范围的合计, 不是当前页. */
    @Operation(summary = "销售汇总 (按客户或按月)")
    @SaCheckPermission(value = {"report:view", "sales:delivery:list"}, mode = SaMode.OR)
    @GetMapping("/sales/by-customer")
    public R<SalesSummaryService.Table> salesSummaryByCustomer(
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) String customer,
            @RequestParam(required = false) String groupBy,
            @RequestParam(required = false) Integer limit) {
        return R.ok(salesSummaryService.query(startDate, endDate, customerId, customer, groupBy, limit));
    }

    /** 销售明细: 一行 = 一张出库单的一个商品行; 分页返回, 合计行始终是全查询范围. */
    @Operation(summary = "销售明细 (单据商品行级, 分页)")
    @SaCheckPermission(value = {"report:view", "sales:delivery:list"}, mode = SaMode.OR)
    @GetMapping("/sales/lines")
    public R<SalesSummaryService.Table> salesDetailLines(
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) String customer,
            @RequestParam(required = false) String product,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return R.ok(salesSummaryService.detail(startDate, endDate, customerId, customer, product, page, pageSize));
    }

    /**
     * CSV 导出: scope=summary|detail. 回服务端按同一批参数重查, 不接前端传来的表 ——
     * 屏幕上是当前页, 导出去拿屏幕数据会导出一份缺数据的报表; 权限判定也只能在这一侧做.
     */
    @Operation(summary = "销售明细/汇总 CSV 导出")
    @SaCheckPermission(value = {"report:view", "sales:delivery:list"}, mode = SaMode.OR)
    @GetMapping("/sales/export")
    public void salesExport(@RequestParam(defaultValue = "detail") String scope,
                            @RequestParam String startDate,
                            @RequestParam String endDate,
                            @RequestParam(required = false) Long customerId,
                            @RequestParam(required = false) String customer,
                            @RequestParam(required = false) String groupBy,
                            @RequestParam(required = false) String product,
                            HttpServletResponse response) throws IOException {
        SalesSummaryService.Table table;
        if ("summary".equalsIgnoreCase(scope)) {
            table = salesSummaryService.query(startDate, endDate, customerId, customer, groupBy, null);
        } else if ("detail".equalsIgnoreCase(scope)) {
            table = salesSummaryService.detailForExport(startDate, endDate, customerId, customer, product);
        } else {
            throw new BizException("scope 只支持 summary 或 detail, 收到: " + scope);
        }
        if (table.error != null) {
            throw new BizException(table.error);
        }
        byte[] csv = salesSummaryService.toCsv(table).getBytes(StandardCharsets.UTF_8);
        String encoded = URLEncoder.encode(table.fileName + ".csv", StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType("text/csv;charset=UTF-8");
        response.setContentLength(csv.length);
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded);
        try (OutputStream out = response.getOutputStream()) {
            out.write(csv);
            out.flush();
        }
    }

    // =======================================================================
    // v1.1.68 回收站 — 被软删除 (deleted=1) 单据的分类 / 恢复 / 彻底删除.
    // v1.1.72: 门禁由 report:view 改独立 report:recycle (使回收站在角色权限树
    //   里可单独勾选/保存; 见 sql/47_v172_recycle_perm.sql).
    // =======================================================================

    /** 回收站列表: 一次返回全部 11 类被删单据, 按 type 分组. keyword/日期可选. */
    @SaCheckPermission("report:recycle")
    @GetMapping("/recycle/bin")
    public R<Map<String, List<Map<String, Object>>>> recycleBin(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return R.ok(recycleBinService.listAll(keyword, startDate, endDate));
    }

    /** 恢复某被删单据 (head + detail 的 deleted 翻回 0). 不可逆操作的逆操作, 受 report:recycle 门禁. */
    @SaCheckPermission("report:recycle")
    @PostMapping("/recycle/restore")
    public R<Void> recycleRestore(@RequestParam String type, @RequestParam Long id) {
        recycleBinService.restore(type, id);
        return R.ok();
    }

    /** 彻底删除某被删单据 (物理 DELETE head + detail, 不可恢复). */
    @SaCheckPermission("report:recycle")
    @PostMapping("/recycle/purge")
    public R<Void> recyclePurge(@RequestParam String type, @RequestParam Long id) {
        recycleBinService.physicalDelete(type, id);
        return R.ok();
    }
}
