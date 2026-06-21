package com.industrial.erp.modules.purchase.controller;

import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.purchase.entity.PurReceipt;
import com.industrial.erp.modules.purchase.service.PurReceiptService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@Tag(name = "采购入库")
@RestController
@RequestMapping("/purchase/receipt")
public class PurReceiptController {

    public PurReceiptController(PurReceiptService service) {
        this.service = service;
    }

    private final PurReceiptService service;

    @GetMapping("/page")
    public R<PageResult<PurReceipt>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                         @RequestParam(defaultValue = "20") Integer pageSize,
                                         @RequestParam(required = false) String billNo,
                                         @RequestParam(required = false) Long supplierId,
                                         @RequestParam(required = false) String billStatus,
                                         @RequestParam(required = false) String productName) {
        return R.ok(PageResult.of(service.page(pageNum, pageSize, billNo, supplierId, billStatus, productName)));
    }

    @GetMapping("/{id}")
    public R<PurReceipt> detail(@PathVariable Long id) { return R.ok(service.detail(id)); }

    @PostMapping
    public R<Void> add(@RequestBody PurReceipt receipt) {
        service.add(receipt);
        return R.ok();
    }

    @PostMapping("/{id}/check")
    public R<Void> check(@PathVariable Long id) {
        service.check(id);
        return R.ok();
    }

    /** 查询指定供应商+商品的上次订单单价 */
    @GetMapping("/last-price")
    public R<BigDecimal> lastPrice(@RequestParam Long supplierId, @RequestParam Long productId) {
        return R.ok(service.getLastPrice(supplierId, productId));
    }
}
