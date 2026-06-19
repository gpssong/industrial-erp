package com.industrial.erp.modules.sales.controller;

import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.sales.entity.SalOrder;
import com.industrial.erp.modules.sales.service.SalOrderService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@Tag(name = "销售订单")
@RestController
@RequestMapping("/sales/order")
public class SalOrderController {

    public SalOrderController(SalOrderService service) {
        this.service = service;
    }
    private final SalOrderService service;

    @GetMapping("/page")
    public R<PageResult<SalOrder>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                        @RequestParam(defaultValue = "20") Integer pageSize,
                                        @RequestParam(required = false) String billNo,
                                        @RequestParam(required = false) Long customerId,
                                        @RequestParam(required = false) String billStatus) {
        return R.ok(PageResult.of(service.page(pageNum, pageSize, billNo, customerId, billStatus)));
    }

    @GetMapping("/{id}")
    public R<SalOrder> detail(@PathVariable Long id) { return R.ok(service.detail(id)); }

    @PostMapping
    public R<Void> add(@RequestBody SalOrder o) { service.add(o); return R.ok(); }

    @PutMapping
    public R<Void> update(@RequestBody SalOrder o) { service.update(o); return R.ok(); }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) { service.delete(id); return R.ok(); }

    /** 查询指定客户+商品的上次出库单价 */
    @GetMapping("/last-price")
    public R<BigDecimal> lastPrice(@RequestParam Long customerId, @RequestParam Long productId) {
        return R.ok(service.getLastPrice(customerId, productId));
    }
}
