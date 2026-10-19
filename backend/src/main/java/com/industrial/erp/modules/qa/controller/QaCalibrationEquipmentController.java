package com.industrial.erp.modules.qa.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.qa.entity.QaCalibrationEquipment;
import com.industrial.erp.modules.qa.service.QaCalibrationEquipmentService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "QA 校准设备主数据")
@RestController
@RequestMapping("/qa/calibration-equipment")
public class QaCalibrationEquipmentController {

    private final QaCalibrationEquipmentService svc;

    public QaCalibrationEquipmentController(QaCalibrationEquipmentService svc) {
        this.svc = svc;
    }

    @SaCheckPermission(value = {"qa:calibration-equipment:list"}, orRole = "admin")
    @GetMapping("/page")
    public R<IPage<QaCalibrationEquipment>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                                  @RequestParam(defaultValue = "20") Integer pageSize,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(required = false) String workshop) {
        return R.ok(svc.page(status, workshop, pageNum, pageSize));
    }

    @SaCheckPermission(value = {"qa:calibration-equipment:list"}, orRole = "admin")
    @GetMapping("/list")
    public R<List<QaCalibrationEquipment>> listAll() {
        return R.ok(svc.listAll());
    }

    @SaCheckPermission(value = {"qa:calibration-equipment:list"}, orRole = "admin")
    @GetMapping("/{id}")
    public R<QaCalibrationEquipment> detail(@PathVariable Long id) {
        return R.ok(svc.findById(id));
    }

    @SaCheckPermission(value = {"qa:calibration-equipment:add", "qa:calibration-equipment:edit"}, orRole = "admin")
    @PostMapping
    public R<QaCalibrationEquipment> save(@RequestBody QaCalibrationEquipment eq) {
        return R.ok(svc.save(eq));
    }

    @SaCheckPermission(value = {"qa:calibration-equipment:delete"}, orRole = "admin")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        svc.delete(id);
        return R.ok();
    }
}
