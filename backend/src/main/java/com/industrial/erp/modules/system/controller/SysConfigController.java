package com.industrial.erp.modules.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
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

    @SaCheckPermission(value = {"system:config:list"}, orRole = "admin")
    @GetMapping("/page")
    public R<PageResult<SysConfig>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                          @RequestParam(defaultValue = "20") Integer pageSize,
                                          @RequestParam(required = false) String configName,
                                          @RequestParam(required = false) Integer configType) {
        return R.ok(PageResult.of(service.page(pageNum, pageSize, configName, configType)));
    }

    @SaCheckPermission(value = {"system:config:list"}, orRole = "admin")
    @GetMapping("/{id}")
    public R<SysConfig> detail(@PathVariable Long id) { return R.ok(service.detail(id)); }

    @SaCheckPermission(value = {"system:config:add"}, orRole = "admin")
    @PostMapping
    public R<Void> add(@RequestBody SysConfig c) { service.add(c); return R.ok(); }

    @SaCheckPermission(value = {"system:config:edit"}, orRole = "admin")
    @PutMapping
    public R<Void> update(@RequestBody SysConfig c) { service.update(c); return R.ok(); }

    @SaCheckPermission(value = {"system:config:delete"}, orRole = "admin")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) { service.delete(id); return R.ok(); }

    /** 查配置: 登录即可, 业务代码高频读取 */
    @GetMapping("/key/{key}")
    public R<String> getByKey(@PathVariable String key) { return R.ok(service.getByKey(key)); }

    @SaCheckPermission(value = {"system:config:edit"}, orRole = "admin")
    @PutMapping("/value")
    public R<Void> updateValue(@RequestBody java.util.Map<String, String> body) {
        service.updateValue(body.get("key"), body.get("value"));
        return R.ok();
    }
}
