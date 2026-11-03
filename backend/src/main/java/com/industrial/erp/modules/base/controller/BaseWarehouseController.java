package com.industrial.erp.modules.base.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.base.entity.BaseWarehouse;
import com.industrial.erp.modules.base.service.BaseWarehouseService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "仓库管理")
@RestController
@RequestMapping("/base/warehouse")
public class BaseWarehouseController {

    public BaseWarehouseController(BaseWarehouseService service) {
        this.service = service;
    }
    private final BaseWarehouseService service;

    /**
     * v1.1.163 — App 采购入库 (scan/in) 新增仓库 picker: 只做采购的角色 (FINANCE / WAREHOUSE_MGR / zdg)
     * 库里根本没有 base:warehouse:list 授权行, 只认它会 403; 而这一项不在 PC「App 权限树」白名单里,
     * 界面上授不出去 → 只能把端点 OR 上采购入库列表权限 (App 端一定持有).
     * 授权面只放宽到"读仓库名录", 写端点 (add/edit/delete) 有意不动.
     */
    @SaCheckPermission(value = {"base:warehouse:list", "purchase:receipt:list"}, mode = SaMode.OR, orRole = "admin")
    @GetMapping("/list")
    public R<List<BaseWarehouse>> list() { return R.ok(service.list()); }

    @SaCheckPermission(value = {"base:warehouse:list", "purchase:receipt:list"}, mode = SaMode.OR, orRole = "admin")
    @GetMapping("/{id}")
    public R<BaseWarehouse> detail(@PathVariable Long id) { return R.ok(service.detail(id)); }

    @SaCheckPermission(value = {"base:warehouse:add"}, orRole = "admin")
    @PostMapping
    public R<Void> add(@RequestBody BaseWarehouse w) { service.add(w); return R.ok(); }

    @SaCheckPermission(value = {"base:warehouse:edit"}, orRole = "admin")
    @PutMapping
    public R<Void> update(@RequestBody BaseWarehouse w) { service.update(w); return R.ok(); }

    @SaCheckPermission(value = {"base:warehouse:delete"}, orRole = "admin")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) { service.delete(id); return R.ok(); }
}
