package com.industrial.erp.modules.system.controller;

import com.industrial.erp.common.R;
import com.industrial.erp.modules.system.dto.LoginDTO;
import com.industrial.erp.modules.system.service.AuthService;
import com.industrial.erp.modules.system.vo.LoginVO;
import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@Tag(name = "认证管理")
@RestController
@RequestMapping("/auth")
public class AuthController {

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    private final AuthService authService;

    @Operation(summary = "登录")
    @PostMapping("/login")
    public R<LoginVO> login(@RequestBody @Valid LoginDTO dto, HttpServletRequest request) {
        return R.ok(authService.login(dto, request));
    }

    @Operation(summary = "登出")
    @PostMapping("/logout")
    public R<Void> logout() {
        authService.logout();
        return R.ok();
    }

    @Operation(summary = "获取当前登录用户")
    @GetMapping("/me")
    public R<LoginVO> me(HttpServletRequest request) {
        // v1.1.55 hotfix-3 R9: 透传 X-Client-Type header 给 service, 让 App /me 返回 APP-only perms
        return R.ok(authService.currentUser(request));
    }

    @Operation(summary = "生成图形验证码")
    @GetMapping("/captcha")
    public R<Object> captcha() {
        return R.ok(authService.generateCaptcha());
    }

    /**
     * 设置密码 (临时接口, 仅限 admin 角色使用).
     */
    @Operation(summary = "设置密码(临时)")
    @PostMapping("/setpwd")
    @SaCheckLogin
    @SaCheckRole("admin")
    public R<Void> setpwd(@RequestBody LoginDTO dto) {
        authService.setPassword(dto.getUsername(), dto.getPassword());
        return R.ok();
    }
}
