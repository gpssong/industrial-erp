package com.industrial.erp.modules.sales.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
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

    @SaCheckPermission(value = {"sales:delivery:list"}, orRole = "admin")
    @GetMapping("/page")
    public R<PageResult<SalDelivery>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                          @RequestParam(defaultValue = "20") Integer pageSize,
                                          @RequestParam(required = false) String billNo,
                                          @RequestParam(required = false) Long customerId,
                                          @RequestParam(required = false) String billStatus,
                                          @RequestParam(required = false) String productName) {
        return R.ok(PageResult.of(service.page(pageNum, pageSize, billNo, customerId, billStatus, productName)));
    }

    @SaCheckPermission(value = {"sales:delivery:list"}, orRole = "admin")
    @GetMapping("/{id}")
    public R<SalDelivery> detail(@PathVariable Long id) { return R.ok(service.detail(id)); }

    @SaCheckPermission(value = {"sales:delivery:add"}, orRole = "admin")
    @PostMapping
    public R<Void> add(@RequestBody SalDelivery d) {
        service.add(d);
        return R.ok();
    }

    @SaCheckPermission(value = {"sales:delivery:edit"}, orRole = "admin")
    @PutMapping
    public R<Void> update(@RequestBody SalDelivery d) {
        service.update(d);
        return R.ok();
    }

    @SaCheckPermission(value = {"sales:delivery:delete"}, orRole = "admin")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.ok();
    }

    @SaCheckPermission(value = {"sales:delivery:check"}, orRole = "admin")
    @PostMapping("/{id}/check")
    public R<Void> check(@PathVariable Long id) {
        service.check(id);
        return R.ok();
    }

    /** v1.1.11+ 反审核 */
    @SaCheckPermission(value = {"sales:delivery:check"}, orRole = "admin")
    @PostMapping("/{id}/uncheck")
    public R<Void> uncheck(@PathVariable Long id) {
        service.uncheck(id);
        return R.ok();
    }

    /** 查询指定客户+商品的上次订单单价 */
    @SaCheckPermission(value = {"sales:delivery:list"}, orRole = "admin")
    @GetMapping("/last-price")
    public R<BigDecimal> lastPrice(@RequestParam Long customerId, @RequestParam Long productId) {
        return R.ok(service.getLastPrice(customerId, productId));
    }

    /**
     * 查询指定客户最近 50 条历史销售出库明细 (按出库日期 DESC).
     * 用于销售出库新增/编辑弹窗底部的"该客户历史销售产品"下拉/列表.
     * v1.1.7+ 新增.
     */
    @SaCheckPermission(value = {"sales:delivery:list"}, orRole = "admin")
    @GetMapping("/customer-history-products")
    public R<java.util.List<java.util.Map<String, Object>>> customerHistoryProducts(@RequestParam Long customerId) {
        return R.ok(service.getCustomerHistoryProducts(customerId));
    }
}
