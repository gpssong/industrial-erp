package com.industrial.erp.modules.workflow.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.industrial.erp.common.CreateByNameInjector;
import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.system.mapper.SysUserMapper;
import com.industrial.erp.modules.workflow.entity.WfMaintainRecord;
import com.industrial.erp.modules.workflow.service.WfMaintainRecordService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@Tag(name = "设备维保记录")
@RestController
@RequestMapping("/workflow/maintain")
public class WfMaintainRecordController {

    private final WfMaintainRecordService service;
    private final SysUserMapper userMapper;

    public WfMaintainRecordController(WfMaintainRecordService service, SysUserMapper userMapper) {
        this.service = service;
        this.userMapper = userMapper;
    }

    @SaCheckPermission(value = {"work:maintain:list"}, orRole = "admin")
    @GetMapping("/page")
    public R<PageResult<WfMaintainRecord>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                                @RequestParam(defaultValue = "20") Integer pageSize,
                                                @RequestParam(required = false) String deviceName,
                                                @RequestParam(required = false) String billStatus,
                                                @RequestParam(required = false) Boolean overdue) {
        PageResult<WfMaintainRecord> pr = PageResult.of(service.page(pageNum, pageSize, deviceName, billStatus, overdue));
        CreateByNameInjector.inject(userMapper, pr.getRecords(), WfMaintainRecord::getCreateBy, WfMaintainRecord::setCreateByName);
        return R.ok(pr);
    }

    @SaCheckPermission(value = {"work:maintain:list"}, orRole = "admin")
    @GetMapping("/{id}")
    public R<WfMaintainRecord> detail(@PathVariable Long id) { return R.ok(service.detail(id)); }

    @SaCheckPermission(value = {"work:maintain:add"}, orRole = "admin")
    @PostMapping
    public R<Void> add(@RequestBody WfMaintainRecord r) { service.add(r); return R.ok(); }

    @SaCheckPermission(value = {"work:maintain:edit"}, orRole = "admin")
    @PutMapping
    public R<Void> update(@RequestBody WfMaintainRecord r) { service.update(r); return R.ok(); }

    @SaCheckPermission(value = {"work:maintain:delete"}, orRole = "admin")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) { service.delete(id); return R.ok(); }

    @SaCheckPermission(value = {"work:maintain:check"}, orRole = "admin")
    @PostMapping("/{id}/check")
    public R<Void> check(@PathVariable Long id) { service.check(id); return R.ok(); }

    @SaCheckPermission(value = {"work:maintain:uncheck"}, orRole = "admin")
    @PostMapping("/{id}/uncheck")
    public R<Void> uncheck(@PathVariable Long id) { service.uncheck(id); return R.ok(); }
}
