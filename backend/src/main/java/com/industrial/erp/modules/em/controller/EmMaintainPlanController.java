package com.industrial.erp.modules.em.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.em.entity.EmMaintainPlan;
import com.industrial.erp.modules.em.service.EmMaintainPlanService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@Tag(name = "设备维保计划")
@RestController
@RequestMapping("/em/maintain-plan")
public class EmMaintainPlanController {

    private final EmMaintainPlanService svc;

    public EmMaintainPlanController(EmMaintainPlanService svc) {
        this.svc = svc;
    }

    @SaCheckPermission(value = {"equipment:maintain-plan:list"}, orRole = "admin")
    @GetMapping("/page")
    public R<IPage<EmMaintainPlan>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                          @RequestParam(defaultValue = "20") Integer pageSize,
                                          @RequestParam(required = false) String equipmentCode,
                                          @RequestParam(required = false) String status) {
        return R.ok(svc.page(equipmentCode, status, pageNum, pageSize));
    }

    @SaCheckPermission(value = {"equipment:maintain-plan:add", "equipment:maintain-plan:edit"}, orRole = "admin")
    @PostMapping
    public R<Void> save(@RequestBody EmMaintainPlan plan) {
        svc.save(plan);
        return R.ok();
    }

    @SaCheckPermission(value = {"equipment:maintain-plan:delete"}, orRole = "admin")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        svc.delete(id);
        return R.ok();
    }
}
