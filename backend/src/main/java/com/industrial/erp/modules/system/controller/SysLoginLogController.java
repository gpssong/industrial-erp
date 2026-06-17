package com.industrial.erp.modules.system.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import cn.hutool.core.util.StrUtil;
import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.security.PermissionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@Tag(name = "登录日志")
@RestController
@RequestMapping("/system/login-log")
public class SysLoginLogController {

    public SysLoginLogController(PermissionService permService) {
        this.permService = permService;
    }
    private final PermissionService permService;

    @GetMapping("/page")
    public R<?> page(@RequestParam(defaultValue = "1") Integer pageNum,
                     @RequestParam(defaultValue = "20") Integer pageSize) {
        permService.requirePerm("system:login-log:list");
        // 简化: 返回空分页(实际接入 sys_login_log)
        Page<?> p = new Page<>(pageNum, pageSize);
        p.setRecords(java.util.List.of());
        p.setTotal(0);
        return R.ok(PageResult.of((com.baomidou.mybatisplus.core.metadata.IPage)p));
    }
}
