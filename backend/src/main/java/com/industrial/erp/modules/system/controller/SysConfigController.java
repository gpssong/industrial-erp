package com.industrial.erp.modules.system.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.system.entity.SysConfig;
import com.industrial.erp.modules.system.service.SysConfigService;
import com.industrial.erp.security.PermissionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@Tag(name = "系统配置")
@RestController
@RequestMapping("/system/config")
public class SysConfigController {

    public SysConfigController(SysConfigService service, PermissionService permService) {
        this.service = service;
        this.permService = permService;
    }
    private final SysConfigService service;
    private final PermissionService permService;

    @GetMapping("/page")
    public R<PageResult<SysConfig>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                          @RequestParam(defaultValue = "20") Integer pageSize,
                                          @RequestParam(required = false) String configName,
                                          @RequestParam(required = false) Integer configType) {
        return R.ok(PageResult.of(service.page(pageNum, pageSize, configName, configType)));
    }

    @GetMapping("/{id}")
    public R<SysConfig> detail(@PathVariable Long id) { return R.ok(service.detail(id)); }

    @PostMapping
    public R<Void> add(@RequestBody SysConfig c) { service.add(c); return R.ok(); }

    @PutMapping
    public R<Void> update(@RequestBody SysConfig c) { service.update(c); return R.ok(); }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) { service.delete(id); return R.ok(); }

    @GetMapping("/key/{key}")
    public R<String> getByKey(@PathVariable String key) { return R.ok(service.getByKey(key)); }

    @PutMapping("/value")
    public R<Void> updateValue(@RequestParam String key, @RequestParam String value) {
        permService.requirePerm("system:config:edit");
        service.updateValue(key, value);
        return R.ok();
    }
}
