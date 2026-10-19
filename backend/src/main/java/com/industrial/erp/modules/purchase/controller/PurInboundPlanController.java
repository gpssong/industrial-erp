package com.industrial.erp.modules.purchase.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.purchase.entity.PurInboundPlan;
import com.industrial.erp.modules.purchase.service.PurInboundPlanService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * v1.1.101 XK — 采购计划接口.
 *
 * <p>App 端 pages/scan/in 接收 ?planId=... 自动 loadPlan + 触发 IQC.
 * PC 端由 PurReceipt.vue "查看采购计划" 入口跳转.
 */
@Tag(name = "采购计划")
@RestController
@RequestMapping("/purchase/inbound-plan")
public class PurInboundPlanController {

    private final PurInboundPlanService planService;

    public PurInboundPlanController(PurInboundPlanService planService) {
        this.planService = planService;
    }

    @SaCheckPermission(value = {"purchase:order:list", "purchase:order:add"}, orRole = "admin")
    @GetMapping("/page")
    public R<IPage<PurInboundPlan>> page(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String planNo,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) String planStatus) {
        return R.ok(planService.page(pageNum, pageSize, planNo, supplierId, planStatus));
    }

    @SaCheckPermission(value = {"purchase:order:list"}, orRole = "admin")
    @GetMapping("/arrived")
    public R<List<PurInboundPlan>> listArrived() {
        return R.ok(planService.listArrived());
    }

    @SaCheckPermission(value = {"purchase:order:list"}, orRole = "admin")
    @GetMapping("/{id}")
    public R<PurInboundPlan> detail(@PathVariable Long id) {
        return R.ok(planService.findById(id));
    }

    @SaCheckPermission(value = {"purchase:order:add"}, orRole = "admin")
    @PostMapping
    public R<Long> add(@RequestBody PurInboundPlan p) {
        return R.ok(planService.add(p));
    }

    @SaCheckPermission(value = {"purchase:order:edit"}, orRole = "admin")
    @PutMapping
    public R<Void> update(@RequestBody PurInboundPlan p) {
        planService.update(p);
        return R.ok();
    }

    @SaCheckPermission(value = {"purchase:order:delete"}, orRole = "admin")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        planService.delete(id);
        return R.ok();
    }
}
