package com.industrial.erp.modules.sales.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
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

    @SaCheckPermission(value = {"sales:order:list"}, orRole = "admin")
    @GetMapping("/page")
    public R<PageResult<SalOrder>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                        @RequestParam(defaultValue = "20") Integer pageSize,
                                        @RequestParam(required = false) String billNo,
                                        @RequestParam(required = false) Long customerId,
                                        @RequestParam(required = false) String billStatus) {
        return R.ok(PageResult.of(service.page(pageNum, pageSize, billNo, customerId, billStatus)));
    }

    @SaCheckPermission(value = {"sales:order:list"}, orRole = "admin")
    @GetMapping("/{id}")
    public R<SalOrder> detail(@PathVariable Long id) { return R.ok(service.detail(id)); }

    @SaCheckPermission(value = {"sales:order:add"}, orRole = "admin")
    @PostMapping
    public R<Void> add(@RequestBody SalOrder o) { service.add(o); return R.ok(); }

    @SaCheckPermission(value = {"sales:order:edit"}, orRole = "admin")
    @PutMapping
    public R<Void> update(@RequestBody SalOrder o) { service.update(o); return R.ok(); }

    @SaCheckPermission(value = {"sales:order:delete"}, orRole = "admin")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) { service.delete(id); return R.ok(); }

    /** v1.1.11+ 审核流程 */
    @SaCheckPermission(value = {"sales:order:check"}, orRole = "admin")
    @PostMapping("/{id}/check")
    public R<Void> check(@PathVariable Long id) { service.check(id); return R.ok(); }

    @SaCheckPermission(value = {"sales:order:check"}, orRole = "admin")
    @PostMapping("/{id}/uncheck")
    public R<Void> uncheck(@PathVariable Long id) { service.uncheck(id); return R.ok(); }

    /** 查询指定客户+商品的上次出库单价 */
    @SaCheckPermission(value = {"sales:order:list"}, orRole = "admin")
    @GetMapping("/last-price")
    public R<BigDecimal> lastPrice(@RequestParam Long customerId, @RequestParam Long productId) {
        return R.ok(service.getLastPrice(customerId, productId));
    }
}
