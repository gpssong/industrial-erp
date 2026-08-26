package com.industrial.erp.modules.purchase.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.purchase.entity.PurReturn;
import com.industrial.erp.modules.purchase.service.PurReturnService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@Tag(name = "采购退货")
@RestController
@RequestMapping("/purchase/return")
public class PurReturnController {

    public PurReturnController(PurReturnService service) {
        this.service = service;
    }

    private final PurReturnService service;

    @SaCheckPermission(value = {"purchase:return:list"}, orRole = "admin")
    @GetMapping("/page")
    public R<PageResult<PurReturn>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                         @RequestParam(defaultValue = "20") Integer pageSize,
                                         @RequestParam(required = false) String billNo,
                                         @RequestParam(required = false) Long supplierId,
                                         @RequestParam(required = false) String billStatus) {
        return R.ok(PageResult.of(service.page(pageNum, pageSize, billNo, supplierId, billStatus)));
    }

    @SaCheckPermission(value = {"purchase:return:list"}, orRole = "admin")
    @GetMapping("/{id}")
    public R<PurReturn> detail(@PathVariable Long id) { return R.ok(service.detail(id)); }

    @SaCheckPermission(value = {"purchase:return:add"}, orRole = "admin")
    @PostMapping
    public R<Void> add(@RequestBody PurReturn ret) {
        service.add(ret);
        return R.ok();
    }

    @SaCheckPermission(value = {"purchase:return:check"}, orRole = "admin")
    @PostMapping("/{id}/check")
    public R<Void> check(@PathVariable Long id) {
        service.check(id);
        return R.ok();
    }

    /** v1.1.11+ 反审核 */
    @SaCheckPermission(value = {"purchase:return:check"}, orRole = "admin")
    @PostMapping("/{id}/uncheck")
    public R<Void> uncheck(@PathVariable Long id) {
        service.uncheck(id);
        return R.ok();
    }
}
