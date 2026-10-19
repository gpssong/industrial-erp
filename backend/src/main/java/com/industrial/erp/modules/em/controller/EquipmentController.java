package com.industrial.erp.modules.em.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.em.entity.BaseEquipment;
import com.industrial.erp.modules.em.service.EquipmentService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "设备主数据")
@RestController
@RequestMapping("/base/equipment")
public class EquipmentController {

    private final EquipmentService svc;

    public EquipmentController(EquipmentService svc) {
        this.svc = svc;
    }

    @SaCheckPermission(value = {"equipment:master:list"}, orRole = "admin")
    @GetMapping("/page")
    public R<IPage<BaseEquipment>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                        @RequestParam(defaultValue = "20") Integer pageSize,
                                        @RequestParam(required = false) String workshop,
                                        @RequestParam(required = false) String type) {
        return R.ok(svc.page(workshop, type, pageNum, pageSize));
    }

    @SaCheckPermission(value = {"equipment:master:add", "equipment:master:edit"}, orRole = "admin")
    @PostMapping
    public R<Void> save(@RequestBody BaseEquipment eq) {
        svc.save(eq);
        return R.ok();
    }

    @SaCheckPermission(value = {"equipment:master:delete"}, orRole = "admin")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        svc.delete(id);
        return R.ok();
    }

    @SaCheckPermission(value = {"equipment:master:list"}, orRole = "admin")
    @GetMapping("/list")
    public R<List<BaseEquipment>> listAll(@RequestParam(required = false) String workshop,
                                          @RequestParam(required = false) String type) {
        return R.ok(svc.listByWorkshop(workshop));
    }

    @SaCheckPermission(value = {"equipment:master:list"}, orRole = "admin")
    @GetMapping("/{id}")
    public R<BaseEquipment> detail(@PathVariable Long id) {
        return R.ok(svc.findById(id));
    }
}