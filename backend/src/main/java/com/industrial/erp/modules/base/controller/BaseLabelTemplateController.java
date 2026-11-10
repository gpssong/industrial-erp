package com.industrial.erp.modules.base.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.base.entity.BaseLabelTemplate;
import com.industrial.erp.modules.base.entity.BaseLabelTemplateHist;
import com.industrial.erp.modules.base.label.LabelHistorySummary;
import com.industrial.erp.modules.base.label.LabelRollbackRequest;
import com.industrial.erp.modules.base.service.BaseLabelTemplateService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "标签模板")
@RestController
@RequestMapping("/base/label/template")
public class BaseLabelTemplateController {

    public BaseLabelTemplateController(BaseLabelTemplateService service) { this.service = service; }
    private final BaseLabelTemplateService service;

    @SaCheckPermission(value = {"base:label:template", "base:label:list"}, mode = SaMode.OR, orRole = "admin")
    @GetMapping("/list")
    public R<List<BaseLabelTemplate>> list(@RequestParam(required = false) String bizType,
                                           @RequestParam(required = false) Integer status) {
        return R.ok(service.list(bizType, status));
    }

    @SaCheckPermission(value = {"base:label:template", "base:label:list"}, mode = SaMode.OR, orRole = "admin")
    @GetMapping("/{id}")
    public R<BaseLabelTemplate> detail(@PathVariable Long id) { return R.ok(service.detail(id)); }

    @SaCheckPermission(value = {"base:label:template", "base:label:list"}, mode = SaMode.OR, orRole = "admin")
    @GetMapping("/default")
    public R<BaseLabelTemplate> defaultOne(@RequestParam String bizType) {
        return R.ok(service.defaultOf(bizType));
    }

    @SaCheckPermission(value = {"base:label:template"}, orRole = "admin")
    @PostMapping
    public R<Void> add(@RequestBody BaseLabelTemplate t) { service.add(t); return R.ok(); }

    @SaCheckPermission(value = {"base:label:template"}, orRole = "admin")
    @PutMapping
    public R<Void> update(@RequestBody BaseLabelTemplate t) { service.update(t); return R.ok(); }

    @SaCheckPermission(value = {"base:label:template"}, orRole = "admin")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) { service.delete(id); return R.ok(); }

    @SaCheckPermission(value = {"base:label:template"}, orRole = "admin")
    @PutMapping("/{id}/default")
    public R<Void> setDefault(@PathVariable Long id) { service.setDefault(id); return R.ok(); }

    // Task 7 三个端点：**不照抄上面旧 GET 的 list OR**（docs/36 Step 3 点名），读历史也是模板权限。
    @SaCheckPermission(value = "base:label:template", orRole = "admin")
    @GetMapping("/{id}/history")
    public R<List<LabelHistorySummary>> history(@PathVariable Long id) {
        return R.ok(service.history(id));
    }

    @SaCheckPermission(value = "base:label:template", orRole = "admin")
    @GetMapping("/{id}/history/{seq}")
    public R<BaseLabelTemplateHist> historyOne(@PathVariable Long id, @PathVariable Integer seq) {
        return R.ok(service.historyOne(id, seq));
    }

    @SaCheckPermission(value = "base:label:template", orRole = "admin")
    @PostMapping("/{id}/rollback")
    public R<Void> rollback(@PathVariable Long id, @Valid @RequestBody LabelRollbackRequest request) {
        service.rollback(id, request.seq());
        return R.ok();
    }
}
