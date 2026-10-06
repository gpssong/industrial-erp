package com.industrial.erp.modules.workflow.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.industrial.erp.common.CreateByNameInjector;
import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.system.mapper.SysUserMapper;
import com.industrial.erp.modules.workflow.entity.WfEmergencyPlan;
import com.industrial.erp.modules.workflow.service.WfEmergencyPlanService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@Tag(name = "应急预案")
@RestController
@RequestMapping("/workflow/emergency")
public class WfEmergencyPlanController {

    private final WfEmergencyPlanService service;
    private final SysUserMapper userMapper;

    public WfEmergencyPlanController(WfEmergencyPlanService service, SysUserMapper userMapper) {
        this.service = service;
        this.userMapper = userMapper;
    }

    @SaCheckPermission(value = {"work:emergency:list"}, orRole = "admin")
    @GetMapping("/page")
    public R<PageResult<WfEmergencyPlan>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                               @RequestParam(defaultValue = "20") Integer pageSize,
                                               @RequestParam(required = false) Long tenantId,
                                               @RequestParam(required = false) String billStatus,
                                               @RequestParam(required = false) Boolean overdue) {
        PageResult<WfEmergencyPlan> pr = PageResult.of(service.page(pageNum, pageSize, tenantId, billStatus, overdue));
        CreateByNameInjector.inject(userMapper, pr.getRecords(), WfEmergencyPlan::getCreateBy, WfEmergencyPlan::setCreateByName);
        return R.ok(pr);
    }

    @SaCheckPermission(value = {"work:emergency:list"}, orRole = "admin")
    @GetMapping("/{id}")
    public R<WfEmergencyPlan> detail(@PathVariable Long id) { return R.ok(service.detail(id)); }

    @SaCheckPermission(value = {"work:emergency:add"}, orRole = "admin")
    @PostMapping
    public R<Void> add(@RequestBody WfEmergencyPlan r) { service.add(r); return R.ok(); }

    @SaCheckPermission(value = {"work:emergency:edit"}, orRole = "admin")
    @PutMapping
    public R<Void> update(@RequestBody WfEmergencyPlan r) { service.update(r); return R.ok(); }

    @SaCheckPermission(value = {"work:emergency:delete"}, orRole = "admin")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) { service.delete(id); return R.ok(); }

    @SaCheckPermission(value = {"work:emergency:check"}, orRole = "admin")
    @PostMapping("/{id}/check")
    public R<Void> check(@PathVariable Long id) { service.check(id); return R.ok(); }

    @SaCheckPermission(value = {"work:emergency:uncheck"}, orRole = "admin")
    @PostMapping("/{id}/uncheck")
    public R<Void> uncheck(@PathVariable Long id) { service.uncheck(id); return R.ok(); }
}
