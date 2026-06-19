package com.industrial.erp.modules.system.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.system.entity.SysPrintTemplate;
import com.industrial.erp.modules.system.service.SysPrintTemplateService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@Tag(name = "打印模板")
@RestController
@RequestMapping("/system/print")
public class SysPrintTemplateController {

    public SysPrintTemplateController(SysPrintTemplateService service) { this.service = service; }
    private final SysPrintTemplateService service;

    @GetMapping("/page")
    public R<PageResult<SysPrintTemplate>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                                @RequestParam(defaultValue = "20") Integer pageSize,
                                                @RequestParam(required = false) String templateName,
                                                @RequestParam(required = false) String templateType) {
        return R.ok(PageResult.of(service.page(pageNum, pageSize, templateName, templateType)));
    }

    @GetMapping("/{id}")
    public R<SysPrintTemplate> detail(@PathVariable Long id) { return R.ok(service.detail(id)); }

    @PostMapping
    public R<Void> add(@RequestBody SysPrintTemplate t) { service.add(t); return R.ok(); }

    @PutMapping
    public R<Void> update(@RequestBody SysPrintTemplate t) { service.update(t); return R.ok(); }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) { service.delete(id); return R.ok(); }
}
