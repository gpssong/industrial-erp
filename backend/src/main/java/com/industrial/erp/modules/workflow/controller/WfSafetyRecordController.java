package com.industrial.erp.modules.workflow.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.industrial.erp.common.CreateByNameInjector;
import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.system.mapper.SysUserMapper;
import com.industrial.erp.modules.workflow.entity.WfSafetyRecord;
import com.industrial.erp.modules.workflow.service.WfSafetyRecordService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@Tag(name = "安全检查记录")
@RestController
@RequestMapping("/workflow/safety")
public class WfSafetyRecordController {

    private final WfSafetyRecordService service;
    private final SysUserMapper userMapper;

    public WfSafetyRecordController(WfSafetyRecordService service, SysUserMapper userMapper) {
        this.service = service;
        this.userMapper = userMapper;
    }

    @SaCheckPermission(value = {"work:safety:list"}, orRole = "admin")
    @GetMapping("/page")
    public R<PageResult<WfSafetyRecord>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                              @RequestParam(defaultValue = "20") Integer pageSize,
                                              @RequestParam(required = false) String site,
                                              @RequestParam(required = false) String billStatus,
                                              @RequestParam(required = false) Boolean overdue) {
        PageResult<WfSafetyRecord> pr = PageResult.of(service.page(pageNum, pageSize, site, billStatus, overdue));
        CreateByNameInjector.inject(userMapper, pr.getRecords(), WfSafetyRecord::getCreateBy, WfSafetyRecord::setCreateByName);
        return R.ok(pr);
    }

    @SaCheckPermission(value = {"work:safety:list"}, orRole = "admin")
    @GetMapping("/{id}")
    public R<WfSafetyRecord> detail(@PathVariable Long id) { return R.ok(service.detail(id)); }

    @SaCheckPermission(value = {"work:safety:add"}, orRole = "admin")
    @PostMapping
    public R<Void> add(@RequestBody WfSafetyRecord r) { service.add(r); return R.ok(); }

    @SaCheckPermission(value = {"work:safety:edit"}, orRole = "admin")
    @PutMapping
    public R<Void> update(@RequestBody WfSafetyRecord r) { service.update(r); return R.ok(); }

    @SaCheckPermission(value = {"work:safety:delete"}, orRole = "admin")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) { service.delete(id); return R.ok(); }

    @SaCheckPermission(value = {"work:safety:check"}, orRole = "admin")
    @PostMapping("/{id}/check")
    public R<Void> check(@PathVariable Long id) { service.check(id); return R.ok(); }

    @SaCheckPermission(value = {"work:safety:uncheck"}, orRole = "admin")
    @PostMapping("/{id}/uncheck")
    public R<Void> uncheck(@PathVariable Long id) { service.uncheck(id); return R.ok(); }
}
