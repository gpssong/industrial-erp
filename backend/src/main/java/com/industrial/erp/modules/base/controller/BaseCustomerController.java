package com.industrial.erp.modules.base.controller;

import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.base.entity.BaseCustomer;
import com.industrial.erp.modules.base.service.BaseCustomerService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@Tag(name = "客户管理")
@RestController
@RequestMapping("/base/customer")
public class BaseCustomerController {

    public BaseCustomerController(BaseCustomerService service) {
        this.service = service;
    }
    private final BaseCustomerService service;

    @GetMapping("/page")
    public R<PageResult<BaseCustomer>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                            @RequestParam(defaultValue = "20") Integer pageSize,
                                            @RequestParam(required = false) String keyword) {
        return R.ok(PageResult.of(service.page(pageNum, pageSize, keyword)));
    }

    @GetMapping("/{id}")
    public R<BaseCustomer> detail(@PathVariable Long id) { return R.ok(service.detail(id)); }

    @GetMapping("/list")
    public R<java.util.List<BaseCustomer>> list() {
        return R.ok(service.list());
    }

    @PostMapping
    public R<Void> add(@RequestBody BaseCustomer c) { service.add(c); return R.ok(); }

    @PutMapping
    public R<Void> update(@RequestBody BaseCustomer c) { service.update(c); return R.ok(); }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) { service.delete(id); return R.ok(); }
}
