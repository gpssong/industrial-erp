package com.industrial.erp.modules.report.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.report.mapper.ReportMapper;
import com.industrial.erp.security.PermissionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "报表中心")
@RestController
@RequestMapping("/report")
@SaCheckPermission("report:view")
public class ReportController {

    public ReportController(ReportMapper reportMapper, PermissionService permissionService) {
        this.reportMapper = reportMapper;
        this.permissionService = permissionService;
    }

    private final ReportMapper reportMapper;
    private final PermissionService permissionService;

    @GetMapping("/dashboard")
    public R<Map<String, Object>> dashboard() {
        // v1.0.10+: 独立 KPI 鉴权 — 需要 "dashboard:view" 权限
        permissionService.requirePerm("dashboard:view");
        return R.ok(reportMapper.dashboardKpi().get(0));
    }

    @GetMapping("/sales/summary")
    public R<List<Map<String, Object>>> salesSummary(@RequestParam String startDate, @RequestParam String endDate) {
        return R.ok(reportMapper.salesSummary(startDate, endDate));
    }

    @GetMapping("/sales/ranking")
    public R<List<Map<String, Object>>> salesRanking(@RequestParam String startDate, @RequestParam String endDate,
                                                     @RequestParam(defaultValue = "20") Integer limit) {
        return R.ok(reportMapper.salesRanking(startDate, endDate, limit));
    }

    @GetMapping("/inventory/summary")
    public R<List<Map<String, Object>>> inventorySummary() { return R.ok(reportMapper.inventorySummary()); }

    @GetMapping("/inventory/aging")
    public R<List<Map<String, Object>>> inventoryAging() { return R.ok(reportMapper.inventoryAging()); }

    @GetMapping("/arap")
    public R<List<Map<String, Object>>> arap(@RequestParam String billType) { return R.ok(reportMapper.arapSummary(billType)); }

    @GetMapping("/profit")
    public R<List<Map<String, Object>>> profit(@RequestParam String startDate, @RequestParam String endDate) {
        return R.ok(reportMapper.profitAnalysis(startDate, endDate));
    }
}
