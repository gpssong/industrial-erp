package com.industrial.erp.modules.system.controller;

import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.system.entity.SysMenu;
import com.industrial.erp.modules.system.entity.SysRole;
import com.industrial.erp.modules.system.service.SysRoleService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "角色管理")
@RestController
@RequestMapping("/system/role")
public class SysRoleController {

    public SysRoleController(SysRoleService service) {
        this.service = service;
    }

    private final SysRoleService service;

    @GetMapping("/page")
    public R<PageResult<SysRole>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                       @RequestParam(defaultValue = "20") Integer pageSize,
                                       @RequestParam(required = false) String roleName) {
        return R.ok(PageResult.of(service.page(pageNum, pageSize, roleName)));
    }

    @GetMapping("/{id}")
    public R<SysRole> detail(@PathVariable Long id) { return R.ok(service.detail(id)); }

    @PostMapping
    public R<Void> add(@RequestBody @Valid SysRole r) { service.add(r); return R.ok(); }

    @PutMapping
    public R<Void> update(@RequestBody @Valid SysRole r) { service.update(r); return R.ok(); }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) { service.delete(id); return R.ok(); }

    @GetMapping("/{id}/menus")
    public R<List<SysMenu>> roleMenus(@PathVariable Long id) {
        return R.ok(service.getMenusByRoleId(id));
    }

    @PutMapping("/{id}/menus")
    public R<Void> grantMenus(@PathVariable Long id, @RequestBody List<Long> menuIds) {
        service.grantMenus(id, menuIds);
        return R.ok();
    }

    @GetMapping("/{id}/users")
    public R<List<Long>> roleUsers(@PathVariable Long id) {
        return R.ok(service.getUserIdsByRoleId(id));
    }

    @PutMapping("/{id}/users")
    public R<Void> assignUsers(@PathVariable Long id, @RequestBody List<Long> userIds) {
        service.assignUsers(id, userIds);
        return R.ok();
    }
}
