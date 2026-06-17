package com.industrial.erp.modules.inventory.controller;

import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.inventory.entity.InvCheck;
import com.industrial.erp.modules.inventory.service.InvCheckService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@Tag(name = "库存盘点")
@RestController
@RequestMapping("/inventory/check")
public class InvCheckController {

    public InvCheckController(InvCheckService service) {
        this.service = service;
    }
    private final InvCheckService service;

    @GetMapping("/page")
    public R<PageResult<InvCheck>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                        @RequestParam(defaultValue = "20") Integer pageSize,
                                        @RequestParam(required = false) String billNo) {
        return R.ok(PageResult.of(service.page(pageNum, pageSize, billNo)));
    }

    @GetMapping("/{id}")
    public R<InvCheck> detail(@PathVariable Long id) { return R.ok(service.detail(id)); }

    @PostMapping
    public R<Void> add(@RequestBody InvCheck c) { service.add(c); return R.ok(); }

    @PostMapping("/{id}/check")
    public R<Void> check(@PathVariable Long id) { service.check(id); return R.ok(); }
}
