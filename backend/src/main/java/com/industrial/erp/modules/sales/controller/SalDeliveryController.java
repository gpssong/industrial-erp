package com.industrial.erp.modules.sales.controller;

import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.sales.entity.SalDelivery;
import com.industrial.erp.modules.sales.service.SalDeliveryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@Tag(name = "销售出库")
@RestController
@RequestMapping("/sales/delivery")
public class SalDeliveryController {

    public SalDeliveryController(SalDeliveryService service) {
        this.service = service;
    }

    private final SalDeliveryService service;

    @GetMapping("/page")
    public R<PageResult<SalDelivery>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                          @RequestParam(defaultValue = "20") Integer pageSize,
                                          @RequestParam(required = false) String billNo,
                                          @RequestParam(required = false) Long customerId,
                                          @RequestParam(required = false) String billStatus,
                                          @RequestParam(required = false) String productName) {
        return R.ok(PageResult.of(service.page(pageNum, pageSize, billNo, customerId, billStatus, productName)));
    }

    @GetMapping("/{id}")
    public R<SalDelivery> detail(@PathVariable Long id) { return R.ok(service.detail(id)); }

    @PostMapping
    public R<Void> add(@RequestBody SalDelivery d) {
        service.add(d);
        return R.ok();
    }

    @PostMapping("/{id}/check")
    public R<Void> check(@PathVariable Long id) {
        service.check(id);
        return R.ok();
    }

    /** 查询指定客户+商品的上次订单单价 */
    @GetMapping("/last-price")
    public R<BigDecimal> lastPrice(@RequestParam Long customerId, @RequestParam Long productId) {
        return R.ok(service.getLastPrice(customerId, productId));
    }
}
