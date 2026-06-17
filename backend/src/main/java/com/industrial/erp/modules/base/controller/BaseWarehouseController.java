package com.industrial.erp.modules.base.controller;

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

    @GetMapping("/list")
    public R<List<BaseWarehouse>> list() { return R.ok(service.list()); }

    @GetMapping("/{id}")
    public R<BaseWarehouse> detail(@PathVariable Long id) { return R.ok(service.detail(id)); }

    @PostMapping
    public R<Void> add(@RequestBody BaseWarehouse w) { service.add(w); return R.ok(); }

    @PutMapping
    public R<Void> update(@RequestBody BaseWarehouse w) { service.update(w); return R.ok(); }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) { service.delete(id); return R.ok(); }
}
