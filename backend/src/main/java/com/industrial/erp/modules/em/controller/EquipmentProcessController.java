package com.industrial.erp.modules.em.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.em.entity.BaseEquipmentProcess;
import com.industrial.erp.modules.em.service.EquipmentService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "设备工序绑定")
@RestController
@RequestMapping("/base/equipment/process")
public class EquipmentProcessController {

    private final EquipmentService svc;

    public EquipmentProcessController(EquipmentService svc) {
        this.svc = svc;
    }

    @SaCheckPermission(value = {"equipment:master:list"}, orRole = "admin")
    @GetMapping("/list")
    public R<List<BaseEquipmentProcess>> list(@RequestParam Long equipmentId) {
        return R.ok(svc.listProcesses(equipmentId));
    }

    @SaCheckPermission(value = {"equipment:master:edit"}, orRole = "admin")
    @PostMapping("/bind")
    public R<Void> bind(@RequestBody BindDTO dto) {
        svc.bindProcesses(dto.getEquipmentId(), dto.getProcessNames());
        return R.ok();
    }

    /** 绑定提交载体. */
    public static class BindDTO {
        private Long equipmentId;
        private List<String> processNames;

        public Long getEquipmentId() { return equipmentId; }
        public void setEquipmentId(Long equipmentId) { this.equipmentId = equipmentId; }
        public List<String> getProcessNames() { return processNames; }
        public void setProcessNames(List<String> processNames) { this.processNames = processNames; }
    }
}