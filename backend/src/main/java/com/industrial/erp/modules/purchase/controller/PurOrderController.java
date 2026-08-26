package com.industrial.erp.modules.purchase.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.purchase.entity.PurOrder;
import com.industrial.erp.modules.purchase.service.PurOrderService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@Tag(name = "采购订单")
@RestController
@RequestMapping("/purchase/order")
public class PurOrderController {
    private final PurOrderService service;

    public PurOrderController(PurOrderService service) {
        this.service = service;
    }

    @SaCheckPermission(value = {"purchase:order:list"}, orRole = "admin")
    @GetMapping("/page")
    public R<PageResult<PurOrder>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                        @RequestParam(defaultValue = "20") Integer pageSize,
                                        @RequestParam(required = false) String billNo,
                                        @RequestParam(required = false) Long supplierId,
                                        @RequestParam(required = false) String billStatus) {
        return R.ok(PageResult.of(service.page(pageNum, pageSize, billNo, supplierId, billStatus)));
    }

    @SaCheckPermission(value = {"purchase:order:list"}, orRole = "admin")
    @GetMapping("/{id}")
    public R<PurOrder> detail(@PathVariable Long id) { return R.ok(service.detail(id)); }

    @SaCheckPermission(value = {"purchase:order:add"}, orRole = "admin")
    @PostMapping
    public R<Void> add(@RequestBody PurOrder o) { service.add(o); return R.ok(); }

    @SaCheckPermission(value = {"purchase:order:edit"}, orRole = "admin")
    @PutMapping
    public R<Void> update(@RequestBody PurOrder o) { service.update(o); return R.ok(); }

    @SaCheckPermission(value = {"purchase:order:delete"}, orRole = "admin")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) { service.delete(id); return R.ok(); }

    /** v1.1.11+ 审核流程 */
    @SaCheckPermission(value = {"purchase:order:check"}, orRole = "admin")
    @PostMapping("/{id}/check")
    public R<Void> check(@PathVariable Long id) { service.check(id); return R.ok(); }

    @SaCheckPermission(value = {"purchase:order:check"}, orRole = "admin")
    @PostMapping("/{id}/uncheck")
    public R<Void> uncheck(@PathVariable Long id) { service.uncheck(id); return R.ok(); }

    /** 查询指定供应商+商品的上次入库单价 */
    @SaCheckPermission(value = {"purchase:order:list"}, orRole = "admin")
    @GetMapping("/last-price")
    public R<BigDecimal> lastPrice(@RequestParam Long supplierId, @RequestParam Long productId) {
        return R.ok(service.getLastPrice(supplierId, productId));
    }
}
