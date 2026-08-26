package com.industrial.erp.modules.inventory.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.inventory.entity.InvTransfer;
import com.industrial.erp.modules.inventory.service.InvTransferService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@Tag(name = "库存调拨")
@RestController
@RequestMapping("/inventory/transfer")
public class InvTransferController {

    public InvTransferController(InvTransferService service) {
        this.service = service;
    }
    private final InvTransferService service;

    @SaCheckPermission(value = {"inventory:transfer:add"}, orRole = "admin")
    @PostMapping
    public R<Void> add(@RequestBody InvTransfer t) { service.add(t); return R.ok(); }

    @SaCheckPermission(value = {"inventory:transfer:check"}, orRole = "admin")
    @PostMapping("/{id}/check")
    public R<Void> check(@PathVariable Long id) { service.check(id); return R.ok(); }

    @SaCheckPermission(value = {"inventory:transfer:list"}, orRole = "admin")
    @GetMapping("/page")
    public R<?> page(@RequestParam(defaultValue = "1") Integer pageNum,
                     @RequestParam(defaultValue = "20") Integer pageSize) {
        return R.ok();
    }
}
