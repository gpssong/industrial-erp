package com.industrial.erp.modules.system.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import cn.hutool.core.util.StrUtil;
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
                                          @RequestParam(required = false) String username) {
        permService.requirePerm("system:oper-log:list");
        Page<SysOperLog> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<SysOperLog> w = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(module)) w.like(SysOperLog::getModule, module);
        if (StrUtil.isNotBlank(username)) w.like(SysOperLog::getUsername, username);
        w.orderByDesc(SysOperLog::getId);
        return R.ok(PageResult.of(mapper.selectPage(p, w)));
    }
}
