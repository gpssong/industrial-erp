package com.industrial.erp.modules.system.controller;

import com.industrial.erp.common.R;
import com.industrial.erp.modules.system.entity.SysMenu;
import com.industrial.erp.modules.system.service.SysMenuService;
import com.industrial.erp.security.SecurityContext;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "菜单管理")
@RestController
@RequestMapping("/system/menu")
public class SysMenuController {

    public SysMenuController(SysMenuService service) {
        this.service = service;
    }

    private final SysMenuService service;

    @GetMapping("/list")
    public R<List<SysMenu>> list() { return R.ok(service.listAll()); }

    @GetMapping("/mine")
    public R<List<SysMenu>> mine() {
        if (!SecurityContext.isLogin()) return R.ok(List.of());
        return R.ok(service.listByUserId(SecurityContext.getUserId()));
    }

    @PostMapping
    public R<Void> add(@RequestBody @Valid SysMenu m) { service.add(m); return R.ok(); }

    @PutMapping
    public R<Void> update(@RequestBody @Valid SysMenu m) { service.update(m); return R.ok(); }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) { service.delete(id); return R.ok(); }
}
