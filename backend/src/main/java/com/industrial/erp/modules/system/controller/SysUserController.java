package com.industrial.erp.modules.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
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
    @SaCheckPermission(value = {"system:user:list"}, orRole = "admin")
    @GetMapping("/page")
    public R<PageResult<SysUser>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                       @RequestParam(defaultValue = "20") Integer pageSize,
                                       @RequestParam(required = false) String username,
                                       @RequestParam(required = false) String realName,
                                       @RequestParam(required = false) Long deptId) {
        return R.ok(PageResult.of(userService.page(pageNum, pageSize, username, realName, deptId)));
    }

    @Operation(summary = "详情")
    @SaCheckPermission(value = {"system:user:list"}, orRole = "admin")
    @GetMapping("/{id}")
    public R<SysUser> detail(@PathVariable Long id) {
        return R.ok(userService.detail(id));
    }

    @Operation(summary = "新增")
    @SaCheckPermission(value = {"system:user:add"}, orRole = "admin")
    @PostMapping
    public R<Long> add(@RequestBody @Valid SysUser user) {
        userService.add(user);
        return R.ok(user.getId());
    }

    @Operation(summary = "修改")
    @SaCheckPermission(value = {"system:user:edit"}, orRole = "admin")
    @PutMapping
    public R<Void> update(@RequestBody @Valid SysUser user) {
        userService.update(user);
        return R.ok();
    }

    @Operation(summary = "修改密码")
    @SaCheckPermission(value = {"system:user:reset-pwd"}, orRole = "admin")
    @PutMapping("/{id}/password")
    public R<Void> updatePassword(@PathVariable Long id,
                                  @RequestBody java.util.Map<String, String> body) {
        // v1.1.10+ 调整鉴权: 本人改自己需 oldPassword; 超管改他人不需要 (v1.0.6 反模式已修复)
        userService.updatePassword(id, body.get("password"), body.get("oldPassword"));
        return R.ok();
    }

    /**
     * 当前登录用户改自己的密码 — 需传 {oldPassword, newPassword}.
     * 注意: 这个路径必须在 /{id}... 之前注册, 但实际 @PutMapping("/me/password")
     * 是字面量不会跟 @PathVariable 冲突.
     * <p>无需 system:user:reset-pwd 权限 — 改自己的密码是基本功能.
     */
    @Operation(summary = "改自己的密码")
    @PutMapping("/me/password")
    public R<Void> changeMyPassword(@RequestBody java.util.Map<String, String> body) {
        userService.changeOwnPassword(body.get("oldPassword"), body.get("newPassword"));
        return R.ok();
    }

    @Operation(summary = "删除")
    @SaCheckPermission(value = {"system:user:delete"}, orRole = "admin")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        userService.delete(id);
        return R.ok();
    }

    @Operation(summary = "重置密码")
    @SaCheckPermission(value = {"system:user:reset-pwd"}, orRole = "admin")
    @PostMapping("/{id}/resetPwd")
    public R<Void> resetPwd(@PathVariable Long id, @RequestParam(defaultValue = "123456") String newPwd) {
        userService.resetPassword(id, newPwd);
        return R.ok();
    }

    @Operation(summary = "获取用户角色")
    @SaCheckPermission(value = {"system:user:list"}, orRole = "admin")
    @GetMapping("/{id}/roles")
    public R<List<Long>> getRoles(@PathVariable Long id) {
        return R.ok(userService.getRoleIds(id));
    }

    @Operation(summary = "分配角色")
    @SaCheckPermission(value = {"system:user:assign-role"}, orRole = "admin")
    @PutMapping("/{id}/roles")
    public R<Void> assignRoles(@PathVariable Long id, @RequestBody List<Long> roleIds) {
        userService.assignRoles(id, roleIds);
        return R.ok();
    }
}
