package com.industrial.erp.modules.base.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.base.entity.BaseWarehouse;
import com.industrial.erp.modules.base.service.BaseWarehouseService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "仓库管理")
@RestController
@RequestMapping("/base/warehouse")
public class BaseWarehouseController {

    public BaseWarehouseController(BaseWarehouseService service) {
        this.service = service;
    }
    private final BaseWarehouseService service;

    @SaCheckPermission(value = {"base:warehouse:list"}, orRole = "admin")
    @GetMapping("/list")
    public R<List<BaseWarehouse>> list() { return R.ok(service.list()); }

    @SaCheckPermission(value = {"base:warehouse:list"}, orRole = "admin")
    @GetMapping("/{id}")
    public R<BaseWarehouse> detail(@PathVariable Long id) { return R.ok(service.detail(id)); }

    @SaCheckPermission(value = {"base:warehouse:add"}, orRole = "admin")
    @PostMapping
    public R<Void> add(@RequestBody BaseWarehouse w) { service.add(w); return R.ok(); }

    @SaCheckPermission(value = {"base:warehouse:edit"}, orRole = "admin")
    @PutMapping
    public R<Void> update(@RequestBody BaseWarehouse w) { service.update(w); return R.ok(); }

    @SaCheckPermission(value = {"base:warehouse:delete"}, orRole = "admin")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) { service.delete(id); return R.ok(); }
}
