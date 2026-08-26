package com.industrial.erp.modules.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
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

    @SaCheckPermission(value = {"system:dept:list"}, orRole = "admin")
    @GetMapping("/list")
    public R<List<SysDept>> list() { return R.ok(service.list()); }

    @SaCheckPermission(value = {"system:dept:list"}, orRole = "admin")
    @GetMapping("/tree")
    public R<List<SysDept>> tree() { return R.ok(service.listTree()); }

    @SaCheckPermission(value = {"system:dept:add"}, orRole = "admin")
    @PostMapping
    public R<Void> add(@RequestBody @Valid SysDept d) { service.add(d); return R.ok(); }

    @SaCheckPermission(value = {"system:dept:edit"}, orRole = "admin")
    @PutMapping
    public R<Void> update(@RequestBody @Valid SysDept d) { service.update(d); return R.ok(); }

    @SaCheckPermission(value = {"system:dept:delete"}, orRole = "admin")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) { service.delete(id); return R.ok(); }
}
