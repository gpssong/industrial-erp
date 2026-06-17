package com.industrial.erp.modules.system.controller;

import com.industrial.erp.common.R;
import com.industrial.erp.modules.system.dto.LoginDTO;
import com.industrial.erp.modules.system.service.AuthService;
import com.industrial.erp.modules.system.vo.LoginVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
    public R<LoginVO> login(@RequestBody @Valid LoginDTO dto) {
        return R.ok(authService.login(dto));
    }

    @Operation(summary = "登出")
    @PostMapping("/logout")
    public R<Void> logout() {
        authService.logout();
        return R.ok();
    }

    @Operation(summary = "获取当前登录用户")
    @GetMapping("/me")
    public R<LoginVO> me() {
        return R.ok(authService.currentUser());
    }

    @Operation(summary = "生成图形验证码")
    @GetMapping("/captcha")
    public R<Object> captcha() {
        return R.ok(authService.generateCaptcha());
    }

    @Operation(summary = "设置密码(临时)")
    @PostMapping("/setpwd")
    public R<Void> setpwd(@RequestBody LoginDTO dto) {
        authService.setPassword(dto.getUsername(), dto.getPassword());
        return R.ok();
    }
}
