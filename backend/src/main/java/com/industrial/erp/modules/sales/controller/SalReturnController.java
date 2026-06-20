package com.industrial.erp.modules.sales.controller;

import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.sales.entity.SalReturn;
import com.industrial.erp.modules.sales.service.SalReturnService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@Tag(name = "销售退货")
@RestController
@RequestMapping("/sales/return")
public class SalReturnController {

    public SalReturnController(SalReturnService service) {
        this.service = service;
    }

    private final SalReturnService service;

    @GetMapping("/page")
    public R<PageResult<SalReturn>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                         @RequestParam(defaultValue = "20") Integer pageSize,
                                         @RequestParam(required = false) String billNo,
                                         @RequestParam(required = false) Long customerId,
                                         @RequestParam(required = false) String billStatus) {
        return R.ok(PageResult.of(service.page(pageNum, pageSize, billNo, customerId, billStatus)));
    }

    @GetMapping("/{id}")
    public R<SalReturn> detail(@PathVariable Long id) { return R.ok(service.detail(id)); }

    @PostMapping
    public R<Void> add(@RequestBody SalReturn ret) {
        service.add(ret);
        return R.ok();
    }

    @PostMapping("/{id}/check")
    public R<Void> check(@PathVariable Long id) {
        service.check(id);
        return R.ok();
    }
}
