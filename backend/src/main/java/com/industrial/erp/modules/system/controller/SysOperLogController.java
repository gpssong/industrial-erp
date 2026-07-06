package com.industrial.erp.modules.system.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.system.entity.SysOperLog;
import com.industrial.erp.modules.system.mapper.SysOperLogMapper;
import com.industrial.erp.security.PermissionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@Tag(name = "操作日志")
@RestController
@RequestMapping("/system/oper-log")
public class SysOperLogController {

    public SysOperLogController(SysOperLogMapper mapper, PermissionService permService) {
        this.mapper = mapper;
        this.permService = permService;
    }
    private final SysOperLogMapper mapper;
    private final PermissionService permService;

    @GetMapping("/page")
    public R<PageResult<SysOperLog>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                          @RequestParam(defaultValue = "20") Integer pageSize,
                                          @RequestParam(required = false) String module,
                                          @RequestParam(required = false) String businessType,
                                          @RequestParam(required = false) String username) {
        permService.requirePerm("system:oper-log:list");
        Page<SysOperLog> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<SysOperLog> w = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(module)) w.like(SysOperLog::getModule, module);
        if (StrUtil.isNotBlank(businessType)) w.eq(SysOperLog::getBusinessType, businessType);
        if (StrUtil.isNotBlank(username)) w.like(SysOperLog::getUsername, username);
        w.orderByDesc(SysOperLog::getId);
        return R.ok(PageResult.of(mapper.selectPage(p, w)));
    }

    /**
     * 清理 N 天前的操作日志 (默认 90 天, 需 system:oper-log:delete)
     */
    @DeleteMapping("/clean")
    public R<Integer> clean(@RequestParam(defaultValue = "90") Integer days) {
        permService.requirePerm("system:oper-log:delete");
        java.time.LocalDateTime cutoff = java.time.LocalDateTime.now().minusDays(days);
        int n = mapper.delete(new LambdaUpdateWrapper<SysOperLog>()
                .lt(SysOperLog::getOperTime, cutoff));
        return R.ok(n);
    }
}

