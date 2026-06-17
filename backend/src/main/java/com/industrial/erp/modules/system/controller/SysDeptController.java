package com.industrial.erp.modules.system.controller;

import com.industrial.erp.common.R;
import com.industrial.erp.modules.system.entity.SysDept;
import com.industrial.erp.modules.system.service.SysDeptService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "部门管理")
@RestController
@RequestMapping("/system/dept")
public class SysDeptController {

    public SysDeptController(SysDeptService service) {
        this.service = service;
    }

    private final SysDeptService service;

    @GetMapping("/list")
    public R<List<SysDept>> list() { return R.ok(service.list()); }

    @GetMapping("/tree")
    public R<List<SysDept>> tree() { return R.ok(service.listTree()); }

    @PostMapping
    public R<Void> add(@RequestBody @Valid SysDept d) { service.add(d); return R.ok(); }

    @PutMapping
    public R<Void> update(@RequestBody @Valid SysDept d) { service.update(d); return R.ok(); }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) { service.delete(id); return R.ok(); }
}
