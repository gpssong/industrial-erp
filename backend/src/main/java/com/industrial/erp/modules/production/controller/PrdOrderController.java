package com.industrial.erp.modules.production.controller;

import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.production.entity.PrdOrder;
import com.industrial.erp.modules.production.service.PrdOrderService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@Tag(name = "生产加工")
@RestController
@RequestMapping("/production/order")
public class PrdOrderController {

    public PrdOrderController(PrdOrderService service) {
        this.service = service;
    }

    private final PrdOrderService service;

    @GetMapping("/page")
    public R<PageResult<PrdOrder>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                        @RequestParam(defaultValue = "20") Integer pageSize,
                                        @RequestParam(required = false) String billNo,
                                        @RequestParam(required = false) String billStatus,
                                        @RequestParam(required = false) String productName) {
        return R.ok(PageResult.of(service.page(pageNum, pageSize, billNo, billStatus, productName)));
    }

    @GetMapping("/{id}")
    public R<PrdOrder> detail(@PathVariable Long id) { return R.ok(service.detail(id)); }

    @PostMapping
    public R<Void> add(@RequestBody PrdOrder o) { service.add(o); return R.ok(); }

    @PostMapping("/{id}/release")
    public R<Long> release(@PathVariable Long id) { return R.ok(service.release(id)); }

    @PostMapping("/{id}/finish")
    public R<Void> finish(@PathVariable Long id, @RequestParam BigDecimal goodQty,
                          @RequestParam BigDecimal lossQty, @RequestParam(required = false) Long warehouseId) {
        service.finish(id, goodQty, lossQty, warehouseId);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) { service.delete(id); return R.ok(); }
}
