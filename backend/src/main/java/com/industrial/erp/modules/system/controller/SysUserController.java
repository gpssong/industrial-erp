package com.industrial.erp.modules.system.controller;

import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.system.entity.SysUser;
import com.industrial.erp.modules.system.service.SysUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "用户管理")
@RestController
@RequestMapping("/system/user")
public class SysUserController {

    public SysUserController(SysUserService userService) {
        this.userService = userService;
    }

    private final SysUserService userService;

    @Operation(summary = "分页查询")
    @GetMapping("/page")
    public R<PageResult<SysUser>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                       @RequestParam(defaultValue = "20") Integer pageSize,
                                       @RequestParam(required = false) String username,
                                       @RequestParam(required = false) String realName,
                                       @RequestParam(required = false) Long deptId) {
        return R.ok(PageResult.of(userService.page(pageNum, pageSize, username, realName, deptId)));
    }

    @Operation(summary = "详情")
    @GetMapping("/{id}")
    public R<SysUser> detail(@PathVariable Long id) {
        return R.ok(userService.detail(id));
    }

    @Operation(summary = "新增")
    @PostMapping
    public R<Void> add(@RequestBody @Valid SysUser user) {
        userService.add(user);
        return R.ok();
    }

    @Operation(summary = "修改")
    @PutMapping
    public R<Void> update(@RequestBody @Valid SysUser user) {
        userService.update(user);
        return R.ok();
    }

    @Operation(summary = "删除")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        userService.delete(id);
        return R.ok();
    }

    @Operation(summary = "重置密码")
    @PostMapping("/{id}/resetPwd")
    public R<Void> resetPwd(@PathVariable Long id, @RequestParam(defaultValue = "123456") String newPwd) {
        userService.resetPassword(id, newPwd);
        return R.ok();
    }

    @Operation(summary = "获取用户角色")
    @GetMapping("/{id}/roles")
    public R<List<Long>> getRoles(@PathVariable Long id) {
        return R.ok(userService.getRoleIds(id));
    }

    @Operation(summary = "分配角色")
    @PutMapping("/{id}/roles")
    public R<Void> assignRoles(@PathVariable Long id, @RequestBody List<Long> roleIds) {
        userService.assignRoles(id, roleIds);
        return R.ok();
    }
}
