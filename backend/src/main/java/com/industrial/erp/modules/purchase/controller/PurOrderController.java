package com.industrial.erp.modules.purchase.controller;

import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.purchase.entity.PurOrder;
import com.industrial.erp.modules.purchase.service.PurOrderService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@Tag(name = "采购订单")
@RestController
@RequestMapping("/purchase/order")
public class PurOrderController {
    private final PurOrderService service;

    public PurOrderController(PurOrderService service) {
        this.service = service;
    }

    @GetMapping("/page")
    public R<PageResult<PurOrder>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                        @RequestParam(defaultValue = "20") Integer pageSize,
                                        @RequestParam(required = false) String billNo,
                                        @RequestParam(required = false) Long supplierId,
                                        @RequestParam(required = false) String billStatus) {
        return R.ok(PageResult.of(service.page(pageNum, pageSize, billNo, supplierId, billStatus)));
    }

    @GetMapping("/{id}")
    public R<PurOrder> detail(@PathVariable Long id) { return R.ok(service.detail(id)); }

    @PostMapping
    public R<Void> add(@RequestBody PurOrder o) { service.add(o); return R.ok(); }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) { service.delete(id); return R.ok(); }
}
