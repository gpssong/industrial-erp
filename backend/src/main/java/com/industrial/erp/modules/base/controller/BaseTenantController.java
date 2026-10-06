package com.industrial.erp.modules.base.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.industrial.erp.common.CreateByNameInjector;
import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.base.entity.BaseTenant;
import com.industrial.erp.modules.base.service.BaseTenantService;
import com.industrial.erp.modules.system.mapper.SysUserMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "租客管理")
@RestController
@RequestMapping("/workflow/tenant")
public class BaseTenantController {

    private final BaseTenantService service;
    private final SysUserMapper userMapper;

    public BaseTenantController(BaseTenantService service, SysUserMapper userMapper) {
        this.service = service;
        this.userMapper = userMapper;
    }

    @SaCheckPermission(value = {"work:tenant:list"}, orRole = "admin")
    @GetMapping("/page")
    public R<PageResult<BaseTenant>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                          @RequestParam(defaultValue = "20") Integer pageSize,
                                          @RequestParam(required = false) String keyword) {
        PageResult<BaseTenant> pr = PageResult.of(service.page(pageNum, pageSize, keyword));
        CreateByNameInjector.inject(userMapper, pr.getRecords(), BaseTenant::getCreateBy, BaseTenant::setCreateByName);
        return R.ok(pr);
    }

    @SaCheckPermission(value = {"work:tenant:list"}, orRole = "admin")
    @GetMapping("/list")
    public R<List<BaseTenant>> list() { return R.ok(service.list()); }

    @SaCheckPermission(value = {"work:tenant:list"}, orRole = "admin")
    @GetMapping("/{id}")
    public R<BaseTenant> detail(@PathVariable Long id) { return R.ok(service.detail(id)); }

    @SaCheckPermission(value = {"work:tenant:add"}, orRole = "admin")
    @PostMapping
    public R<Void> add(@RequestBody BaseTenant t) { service.add(t); return R.ok(); }

    @SaCheckPermission(value = {"work:tenant:edit"}, orRole = "admin")
    @PutMapping
    public R<Void> update(@RequestBody BaseTenant t) { service.update(t); return R.ok(); }

    @SaCheckPermission(value = {"work:tenant:delete"}, orRole = "admin")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) { service.delete(id); return R.ok(); }
}
