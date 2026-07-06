package com.industrial.erp.modules.system.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.system.entity.SysLoginLog;
import com.industrial.erp.modules.system.mapper.SysLoginLogMapper;
import com.industrial.erp.security.PermissionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@Tag(name = "登录日志")
@RestController
@RequestMapping("/system/login-log")
public class SysLoginLogController {

    public SysLoginLogController(SysLoginLogMapper mapper, PermissionService permService) {
        this.mapper = mapper;
        this.permService = permService;
    }
    private final SysLoginLogMapper mapper;
    private final PermissionService permService;

    /**
     * 登录日志分页
     * @param pageNum  页码 (从 1 开始)
     * @param pageSize 每页条数
     * @param username 操作人 (模糊匹配)
     * @param status   1=成功 / 0=失败 / null=全部
     * @param start    开始时间 (yyyy-MM-dd HH:mm:ss)
     * @param end      结束时间 (yyyy-MM-dd HH:mm:ss)
     */
    @GetMapping("/page")
    public R<PageResult<SysLoginLog>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                          @RequestParam(defaultValue = "20") Integer pageSize,
                                          @RequestParam(required = false) String username,
                                          @RequestParam(required = false) Integer status,
                                          @RequestParam(required = false) String start,
                                          @RequestParam(required = false) String end) {
        permService.requirePerm("system:login-log:list");
        Page<SysLoginLog> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<SysLoginLog> w = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(username)) w.like(SysLoginLog::getUsername, username);
        if (status != null) w.eq(SysLoginLog::getStatus, status);
        if (StrUtil.isNotBlank(start)) {
            try { w.ge(SysLoginLog::getLoginTime, LocalDateTime.parse(start, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))); }
            catch (Exception ignore) {}
        }
        if (StrUtil.isNotBlank(end)) {
            try { w.le(SysLoginLog::getLoginTime, LocalDateTime.parse(end, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))); }
            catch (Exception ignore) {}
        }
        w.orderByDesc(SysLoginLog::getId);
        return R.ok(PageResult.of(mapper.selectPage(p, w)));
    }
}
