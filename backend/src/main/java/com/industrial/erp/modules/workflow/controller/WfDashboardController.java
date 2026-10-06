package com.industrial.erp.modules.workflow.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.workflow.service.WfDashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * v1.1.79 hotfix: 工作流看板 — 三类 next_due_date / next_drill_date 聚合.
 * <p>返回未来 N 天内到期的 维保 / 检查 / 预案 列表, 首页「工作流看板」卡片用.
 * 权限: 任一 work:*:list 即可 (orRole admin 短路, 超管直接可看).
 */
@Tag(name = "工作流看板")
@RestController
@RequestMapping("/workflow/dashboard")
public class WfDashboardController {

    private final WfDashboardService service;

    public WfDashboardController(WfDashboardService service) {
        this.service = service;
    }

    @Operation(summary = "未来 N 天内到期的工作流记录 (维保/检查/预案)")
    @SaCheckPermission(value = {"work:maintain:list", "work:safety:list", "work:emergency:list"}, orRole = "admin")
    @GetMapping("/upcoming")
    public R<List<Map<String, Object>>> upcoming(@RequestParam(defaultValue = "30") Integer days) {
        return R.ok(service.upcoming(Math.min(days, 365)));
    }
}
