package com.industrial.erp.modules.report.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.report.mapper.ReportMapper;
import com.industrial.erp.modules.report.service.RecycleBinService;
import com.industrial.erp.security.PermissionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
                            PermissionService permissionService) {
        this.reportMapper = reportMapper;
        this.recycleBinService = recycleBinService;
        this.permissionService = permissionService;
    }

    private final ReportMapper reportMapper;
    private final RecycleBinService recycleBinService;
    private final PermissionService permissionService;

    // v1.1.53: 工作台 KPI 卡片 — 独立鉴权 dashboard:kpi.
    // 同时清理孤儿 requirePerm("dashboard:view") (v1.0.10+ 遗留, sql 里无对应 sys_menu 行).
    @SaCheckPermission("dashboard:kpi")
    @GetMapping("/dashboard")
    public R<Map<String, Object>> dashboard() {
        return R.ok(reportMapper.dashboardKpi().get(0));
    }

    // v1.1.53: 工作台销售趋势 — 独立鉴权 dashboard:sales-trend
    @SaCheckPermission("dashboard:sales-trend")
    @GetMapping("/sales/summary")
    public R<List<Map<String, Object>>> salesSummary(@RequestParam String startDate, @RequestParam String endDate) {
        return R.ok(reportMapper.salesSummary(startDate, endDate));
    }

    // v1.1.53: 工作台销售排行 TOP10 — 独立鉴权 dashboard:sales-ranking
    @SaCheckPermission("dashboard:sales-ranking")
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
