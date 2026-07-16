package com.industrial.erp.modules.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.system.dto.PrintTemplateQuery;
import com.industrial.erp.modules.system.entity.SysPrintTemplate;
import com.industrial.erp.modules.system.service.SysPrintTemplateService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/**
 * 打印模板管理 (myprint-design)
 */
@Tag(name = "打印模板")
@RestController
@RequestMapping("/system/print-template")
@SaCheckLogin
public class SysPrintTemplateController {

    public SysPrintTemplateController(SysPrintTemplateService service) {
        this.service = service;
    }
    private final SysPrintTemplateService service;

    @GetMapping("/page")
    public R<PageResult<SysPrintTemplate>> page(PrintTemplateQuery q) {
        return R.ok(PageResult.of(service.page(q)));
    }

    @GetMapping("/{id}")
    public R<SysPrintTemplate> detail(@PathVariable Long id) {
        return R.ok(service.detail(id));
    }

    /**
     * 按 biz_type 取该单据类型当前生效的模板 (业务单据打印按钮调用)
     */
    @GetMapping("/biz-type/{bizType}")
    public R<SysPrintTemplate> getByBizType(@PathVariable String bizType) {
        return R.ok(service.getActiveByBizType(bizType));
    }

    @PostMapping
    public R<Void> add(@RequestBody SysPrintTemplate t) {
        service.add(t);
        return R.ok();
    }

    @PutMapping
    public R<Void> update(@RequestBody SysPrintTemplate t) {
        service.update(t);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.ok();
    }
}