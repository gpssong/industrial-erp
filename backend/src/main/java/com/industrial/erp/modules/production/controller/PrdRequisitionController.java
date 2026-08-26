package com.industrial.erp.modules.production.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.production.entity.PrdRequisition;
import com.industrial.erp.modules.production.service.PrdRequisitionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@Tag(name = "领料/退料")
@RestController
@RequestMapping("/production/requisition")
public class PrdRequisitionController {

    public PrdRequisitionController(PrdRequisitionService service) {
        this.service = service;
    }
    private final PrdRequisitionService service;

    @SaCheckPermission(value = {"production:requisition:list"}, orRole = "admin")
    @GetMapping("/page")
    public R<PageResult<PrdRequisition>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                              @RequestParam(defaultValue = "20") Integer pageSize,
                                              @RequestParam(required = false) String billNo,
                                              @RequestParam(required = false) String billType) {
        return R.ok(PageResult.of(service.page(pageNum, pageSize, billNo, billType)));
    }

    @SaCheckPermission(value = {"production:requisition:list"}, orRole = "admin")
    @GetMapping("/{id}")
    public R<PrdRequisition> detail(@PathVariable Long id) { return R.ok(service.detail(id)); }

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
