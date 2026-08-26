package com.industrial.erp.modules.base.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
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

    @SaCheckPermission(value = {"base:customer:list"}, orRole = "admin")
    @GetMapping("/page")
    public R<PageResult<BaseCustomer>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                            @RequestParam(defaultValue = "20") Integer pageSize,
                                            @RequestParam(required = false) String keyword) {
        return R.ok(PageResult.of(service.page(pageNum, pageSize, keyword)));
    }

    @SaCheckPermission(value = {"base:customer:list"}, orRole = "admin")
    @GetMapping("/{id}")
    public R<BaseCustomer> detail(@PathVariable Long id) { return R.ok(service.detail(id)); }

    @SaCheckPermission(value = {"base:customer:list"}, orRole = "admin")
    @GetMapping("/list")
    public R<java.util.List<BaseCustomer>> list() {
        return R.ok(service.list());
    }

    @SaCheckPermission(value = {"base:customer:add"}, orRole = "admin")
    @PostMapping
    public R<Void> add(@RequestBody BaseCustomer c) { service.add(c); return R.ok(); }

    @SaCheckPermission(value = {"base:customer:edit"}, orRole = "admin")
    @PutMapping
    public R<Void> update(@RequestBody BaseCustomer c) { service.update(c); return R.ok(); }

    @SaCheckPermission(value = {"base:customer:delete"}, orRole = "admin")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) { service.delete(id); return R.ok(); }
}
