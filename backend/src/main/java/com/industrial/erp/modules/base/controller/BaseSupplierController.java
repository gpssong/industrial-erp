package com.industrial.erp.modules.base.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.base.entity.BaseSupplier;
import com.industrial.erp.modules.base.service.BaseSupplierService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@Tag(name = "供应商管理")
@RestController
@RequestMapping("/base/supplier")
public class BaseSupplierController {

    public BaseSupplierController(BaseSupplierService service) {
        this.service = service;
    }
    private final BaseSupplierService service;

    @SaCheckPermission(value = {"base:supplier:list"}, orRole = "admin")
    @GetMapping("/page")
    public R<PageResult<BaseSupplier>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                            @RequestParam(defaultValue = "20") Integer pageSize,
                                            @RequestParam(required = false) String keyword) {
        return R.ok(PageResult.of(service.page(pageNum, pageSize, keyword)));
    }

    @SaCheckPermission(value = {"base:supplier:list"}, orRole = "admin")
    @GetMapping("/list")
    public R<java.util.List<BaseSupplier>> list() {
        return R.ok(service.list());
    }

    @SaCheckPermission(value = {"base:supplier:list"}, orRole = "admin")
    @GetMapping("/{id}")
    public R<BaseSupplier> detail(@PathVariable Long id) { return R.ok(service.detail(id)); }

    @SaCheckPermission(value = {"base:supplier:add"}, orRole = "admin")
    @PostMapping
    public R<Void> add(@RequestBody BaseSupplier s) { service.add(s); return R.ok(); }

    @SaCheckPermission(value = {"base:supplier:edit"}, orRole = "admin")
    @PutMapping
    public R<Void> update(@RequestBody BaseSupplier s) { service.update(s); return R.ok(); }

    @SaCheckPermission(value = {"base:supplier:delete"}, orRole = "admin")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) { service.delete(id); return R.ok(); }
}
