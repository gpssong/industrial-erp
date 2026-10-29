package com.industrial.erp.modules.xk.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.xk.entity.XkSupplierCert;
import com.industrial.erp.modules.xk.entity.XkSupplierPerf;
import com.industrial.erp.modules.xk.entity.XkSupplierReview;
import com.industrial.erp.modules.xk.entity.XkSupplierStatusLog;
import com.industrial.erp.modules.xk.service.XkSupplierRecordService;
import com.industrial.erp.modules.xk.service.XkSupplierStatusLogService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * v1.1.130 XK 合格供应商准入 — 名录统计 / 暂停恢复台账 / 准入评审 / 证照报告 / 年度绩效.
 */
@Tag(name = "XK 合格供应商准入")
@RestController
@RequestMapping("/xk/supplier")
public class XkSupplierController {

    private final XkSupplierStatusLogService svc;
    private final XkSupplierRecordService recordSvc;

    public XkSupplierController(XkSupplierStatusLogService svc, XkSupplierRecordService recordSvc) {
        this.svc = svc;
        this.recordSvc = recordSvc;
    }

    @SaCheckPermission(value = {"base:supplier:list"}, orRole = "admin")
    @GetMapping("/stats")
    public R<Map<String, Object>> stats() {
        return R.ok(recordSvc.stats());
    }

    @SaCheckPermission(value = {"base:supplier:list"}, orRole = "admin")
    @GetMapping("/dashboard")
    public R<Map<String, Object>> dashboard() {
        return R.ok(recordSvc.dashboard());
    }

    // ---------------- 暂停 / 恢复台账 ----------------

    @SaCheckPermission(value = {"base:supplier:list"}, orRole = "admin")
    @GetMapping("/status-log/list")
    public R<List<XkSupplierStatusLog>> statusLogs(@RequestParam(required = false) String action,
                                                   @RequestParam(required = false) Long supplierId,
                                                   @RequestParam(required = false) Integer limit) {
        return R.ok(svc.listLogs(action, supplierId, limit));
    }

    @SaCheckPermission(value = {"base:supplier:edit"}, orRole = "admin")
    @PostMapping("/{id}/suspend")
    public R<XkSupplierStatusLog> suspend(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        Map<String, String> b = body == null ? Map.of() : body;
        return R.ok(svc.suspend(id, b.get("reasonType"), b.get("reason"),
                b.get("effectiveDate"), b.get("evidenceUrl")));
    }

    @SaCheckPermission(value = {"base:supplier:edit"}, orRole = "admin")
    @PostMapping("/{id}/resume")
    public R<XkSupplierStatusLog> resume(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        Map<String, String> b = body == null ? Map.of() : body;
        return R.ok(svc.resume(id, b.get("reason"), b.get("effectiveDate"), b.get("evidenceUrl")));
    }

    // ---------------- 准入评审记录 ----------------

    @SaCheckPermission(value = {"base:supplier:list"}, orRole = "admin")
    @GetMapping("/review/list")
    public R<List<XkSupplierReview>> reviews(@RequestParam(required = false) Long supplierId,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) Integer limit) {
        return R.ok(recordSvc.listReviews(supplierId, keyword, limit));
    }

    @SaCheckPermission(value = {"base:supplier:edit"}, orRole = "admin")
    @PostMapping("/review")
    public R<XkSupplierReview> saveReview(@RequestBody XkSupplierReview review) {
        return R.ok(recordSvc.saveReview(review));
    }

    @SaCheckPermission(value = {"base:supplier:edit"}, orRole = "admin")
    @DeleteMapping("/review/{id}")
    public R<Void> deleteReview(@PathVariable Long id) {
        recordSvc.deleteReview(id);
        return R.ok();
    }

    // ---------------- 证照与检测报告 ----------------

    @SaCheckPermission(value = {"base:supplier:list"}, orRole = "admin")
    @GetMapping("/cert/list")
    public R<List<XkSupplierCert>> certs(@RequestParam(required = false) Long supplierId,
                                        @RequestParam(required = false) String certType,
                                        @RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) Integer limit) {
        return R.ok(recordSvc.listCerts(supplierId, certType, keyword, limit));
    }

    @SaCheckPermission(value = {"base:supplier:edit"}, orRole = "admin")
    @PostMapping("/cert")
    public R<XkSupplierCert> saveCert(@RequestBody XkSupplierCert cert) {
        return R.ok(recordSvc.saveCert(cert));
    }

    @SaCheckPermission(value = {"base:supplier:edit"}, orRole = "admin")
    @DeleteMapping("/cert/{id}")
    public R<Void> deleteCert(@PathVariable Long id) {
        recordSvc.deleteCert(id);
        return R.ok();
    }

    // ---------------- 年度绩效评分 ----------------

    @SaCheckPermission(value = {"base:supplier:list"}, orRole = "admin")
    @GetMapping("/perf/list")
    public R<List<XkSupplierPerf>> perf(@RequestParam(required = false) Integer evalYear,
                                        @RequestParam(required = false) Long supplierId,
                                        @RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) Integer limit) {
        return R.ok(recordSvc.listPerf(evalYear, supplierId, keyword, limit));
    }

    @SaCheckPermission(value = {"base:supplier:edit"}, orRole = "admin")
    @PostMapping("/perf")
    public R<XkSupplierPerf> savePerf(@RequestBody XkSupplierPerf perf) {
        return R.ok(recordSvc.savePerf(perf));
    }

    @SaCheckPermission(value = {"base:supplier:edit"}, orRole = "admin")
    @DeleteMapping("/perf/{id}")
    public R<Void> deletePerf(@PathVariable Long id) {
        recordSvc.deletePerf(id);
        return R.ok();
    }
}
