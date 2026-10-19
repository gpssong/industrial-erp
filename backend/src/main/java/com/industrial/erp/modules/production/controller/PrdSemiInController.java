package com.industrial.erp.modules.production.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.production.entity.PrdSemiIn;
import com.industrial.erp.modules.production.service.PrdSemiInService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@Tag(name = "半成品入库")
@RestController
@RequestMapping("/production/semi-in")
public class PrdSemiInController {

    public PrdSemiInController(PrdSemiInService service) {
        this.service = service;
    }

    private final PrdSemiInService service;

    @SaCheckPermission(value = {"production:semi-in:list"}, orRole = "admin")
    @GetMapping("/page")
    public R<PageResult<PrdSemiIn>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                         @RequestParam(defaultValue = "20") Integer pageSize,
                                         @RequestParam(required = false) String billNo) {
        return R.ok(PageResult.of(service.page(pageNum, pageSize, billNo)));
    }

    @SaCheckPermission(value = {"production:semi-in:add"}, orRole = "admin")
    @PostMapping
    public R<Void> add(@RequestBody PrdSemiIn in) {
        service.add(in);
        return R.ok();
    }

    @SaCheckPermission(value = {"production:semi-in:check"}, orRole = "admin")
    @PostMapping("/{id}/check")
    public R<Void> check(@PathVariable Long id) {
        service.check(id);
        return R.ok();
    }

    @SaCheckPermission(value = {"production:semi-in:delete"}, orRole = "admin")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.ok();
    }

    @SaCheckPermission(value = {"production:semi-in:uncheck"}, orRole = "admin")
    @PostMapping("/{id}/uncheck")
    public R<Void> uncheck(@PathVariable Long id) {
        service.uncheck(id);
        return R.ok();
    }
}
