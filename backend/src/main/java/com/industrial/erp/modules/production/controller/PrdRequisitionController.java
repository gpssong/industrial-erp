package com.industrial.erp.modules.production.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.industrial.erp.common.CreateByNameInjector;
import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.production.entity.PrdRequisition;
import com.industrial.erp.modules.production.service.PrdRequisitionService;
import com.industrial.erp.modules.system.mapper.SysUserMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "领料/退料")
@RestController
@RequestMapping("/production/requisition")
public class PrdRequisitionController {

    private final PrdRequisitionService service;
    private final SysUserMapper userMapper;

    public PrdRequisitionController(PrdRequisitionService service, SysUserMapper userMapper) {
        this.service = service;
        this.userMapper = userMapper;
    }

    @SaCheckPermission(value = {"production:requisition:list"}, orRole = "admin")
    @GetMapping("/page")
    public R<PageResult<PrdRequisition>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                              @RequestParam(defaultValue = "20") Integer pageSize,
                                              @RequestParam(required = false) String billNo,
                                              @RequestParam(required = false) String billType) {
        PageResult<PrdRequisition> pr = PageResult.of(service.page(pageNum, pageSize, billNo, billType));
        CreateByNameInjector.inject(userMapper, pr.getRecords(),
                PrdRequisition::getCreateBy, PrdRequisition::setCreateByName);
        return R.ok(pr);
    }

    @SaCheckPermission(value = {"production:requisition:list"}, orRole = "admin")
    @GetMapping("/{id}")
    public R<PrdRequisition> detail(@PathVariable Long id) {
        PrdRequisition req = service.detail(id);
        if (req != null) {
            CreateByNameInjector.inject(userMapper, List.of(req),
                    PrdRequisition::getCreateBy, PrdRequisition::setCreateByName);
        }
        return R.ok(req);
    }

    @SaCheckPermission(value = {"production:requisition:add"}, orRole = "admin")
    @PostMapping
    public R<Void> add(@RequestBody PrdRequisition req) { service.add(req); return R.ok(); }

    @SaCheckPermission(value = {"production:requisition:check"}, orRole = "admin")
    @PostMapping("/{id}/check")
    public R<Void> check(@PathVariable Long id) { service.check(id); return R.ok(); }

    @SaCheckPermission(value = {"production:requisition:delete"}, orRole = "admin")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) { service.delete(id); return R.ok(); }
}
