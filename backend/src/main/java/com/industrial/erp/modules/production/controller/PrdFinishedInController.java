package com.industrial.erp.modules.production.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.production.entity.PrdFinishedIn;
import com.industrial.erp.modules.production.service.PrdFinishedInService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@Tag(name = "成品入库")
@RestController
@RequestMapping("/production/finished-in")
public class PrdFinishedInController {

    public PrdFinishedInController(PrdFinishedInService service) {
        this.service = service;
    }
    private final PrdFinishedInService service;

    @SaCheckPermission(value = {"production:finished-in:list"}, orRole = "admin")
    @GetMapping("/page")
    public R<PageResult<PrdFinishedIn>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                              @RequestParam(defaultValue = "20") Integer pageSize,
                                              @RequestParam(required = false) String billNo) {
        return R.ok(PageResult.of(service.page(pageNum, pageSize, billNo)));
    }

    @SaCheckPermission(value = {"production:finished-in:add"}, orRole = "admin")
    @PostMapping
    public R<Void> add(@RequestBody PrdFinishedIn in) { service.add(in); return R.ok(); }

    @SaCheckPermission(value = {"production:finished-in:check"}, orRole = "admin")
    @PostMapping("/{id}/check")
    public R<Void> check(@PathVariable Long id) { service.check(id); return R.ok(); }

    /** v1.1.95+: 删除入库单 (仅 DRAFT). */
    @SaCheckPermission(value = {"production:finished-in:delete"}, orRole = "admin")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) { service.delete(id); return R.ok(); }

    /** v1.1.95+: 反审核 (CHECKED→DRAFT), 同步出库冲掉入库库存. */
    @SaCheckPermission(value = {"production:finished-in:uncheck"}, orRole = "admin")
    @PostMapping("/{id}/uncheck")
    public R<Void> uncheck(@PathVariable Long id) { service.uncheck(id); return R.ok(); }
}
