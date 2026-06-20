package com.industrial.erp.modules.purchase.controller;

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

    @GetMapping("/page")
    public R<PageResult<PurReturn>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                         @RequestParam(defaultValue = "20") Integer pageSize,
                                         @RequestParam(required = false) String billNo,
                                         @RequestParam(required = false) Long supplierId,
                                         @RequestParam(required = false) String billStatus) {
        return R.ok(PageResult.of(service.page(pageNum, pageSize, billNo, supplierId, billStatus)));
    }

    @GetMapping("/{id}")
    public R<PurReturn> detail(@PathVariable Long id) { return R.ok(service.detail(id)); }

    @PostMapping
    public R<Void> add(@RequestBody PurReturn ret) {
        service.add(ret);
        return R.ok();
    }

    @PostMapping("/{id}/check")
    public R<Void> check(@PathVariable Long id) {
        service.check(id);
        return R.ok();
    }
}
