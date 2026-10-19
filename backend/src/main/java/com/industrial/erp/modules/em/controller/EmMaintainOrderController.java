package com.industrial.erp.modules.em.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.em.entity.EmMaintainOrder;
import com.industrial.erp.modules.em.entity.EmMaintainRecord;
import com.industrial.erp.modules.em.service.EmMaintainOrderService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "设备维保工单")
@RestController
@RequestMapping("/em/maintain-order")
public class EmMaintainOrderController {

    private final EmMaintainOrderService svc;

    public EmMaintainOrderController(EmMaintainOrderService svc) {
        this.svc = svc;
    }

    @SaCheckPermission(value = {"equipment:maintain-order:list"}, orRole = "admin")
    @GetMapping("/page")
    public R<IPage<EmMaintainOrder>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                           @RequestParam(defaultValue = "20") Integer pageSize,
                                           @RequestParam(required = false) String equipmentCode,
                                           @RequestParam(required = false) String status) {
        return R.ok(svc.page(equipmentCode, status, pageNum, pageSize));
    }

    @SaCheckPermission(value = {"equipment:maintain-order:list"}, orRole = "admin")
    @GetMapping("/{id}")
    public R<EmMaintainOrder> detail(@PathVariable Long id) {
        return R.ok(svc.findById(id));
    }

    @SaCheckPermission(value = {"equipment:maintain-order:add", "equipment:maintain-order:edit"}, orRole = "admin")
    @PostMapping
    public R<Void> save(@RequestBody EmMaintainOrder order) {
        svc.save(order);
        return R.ok();
    }

    @SaCheckPermission(value = {"equipment:maintain-order:complete"}, orRole = "admin")
    @PostMapping("/{id}/complete")
    public R<EmMaintainRecord> complete(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String result = body == null ? null : body.get("result");
        String reviewer = body == null ? null : body.get("reviewer");
        return R.ok(svc.complete(id, result, reviewer));
    }

    @SaCheckPermission(value = {"equipment:maintain-order:edit"}, orRole = "admin")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        svc.delete(id);
        return R.ok();
    }
}
