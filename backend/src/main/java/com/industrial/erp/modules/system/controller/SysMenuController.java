package com.industrial.erp.modules.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
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

    @SaCheckPermission(value = {"system:menu:list"}, orRole = "admin")
    @GetMapping("/list")
    public R<List<SysMenu>> list() { return R.ok(service.listAll()); }

    /** 当前登录用户查自己的菜单 (登录即可, 免鉴权) */
    @GetMapping("/mine")
    public R<List<SysMenu>> mine() {
        if (!SecurityContext.isLogin()) return R.ok(List.of());
        return R.ok(service.listByUserId(SecurityContext.getUserId()));
    }

    @SaCheckPermission(value = {"system:menu:add"}, orRole = "admin")
    @PostMapping
    public R<Void> add(@RequestBody @Valid SysMenu m) { service.add(m); return R.ok(); }

    @SaCheckPermission(value = {"system:menu:edit"}, orRole = "admin")
    @PutMapping
    public R<Void> update(@RequestBody @Valid SysMenu m) { service.update(m); return R.ok(); }

    @SaCheckPermission(value = {"system:menu:delete"}, orRole = "admin")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) { service.delete(id); return R.ok(); }
}
