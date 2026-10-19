package com.industrial.erp.modules.qa.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.qa.entity.QaCalibrationRecord;
import com.industrial.erp.modules.qa.service.QaCalibrationRecordService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "QA 校准记录")
@RestController
@RequestMapping("/qa/calibration-record")
public class QaCalibrationRecordController {

    private final QaCalibrationRecordService svc;

    public QaCalibrationRecordController(QaCalibrationRecordService svc) {
        this.svc = svc;
    }

    @SaCheckPermission(value = {"qa:calibration-record:list"}, orRole = "admin")
    @GetMapping("/page")
    public R<IPage<QaCalibrationRecord>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                                @RequestParam(defaultValue = "20") Integer pageSize,
                                                @RequestParam(required = false) Long equipmentId,
                                                @RequestParam(required = false) String result) {
        return R.ok(svc.page(equipmentId, result, pageNum, pageSize));
    }

    @SaCheckPermission(value = {"qa:calibration-record:list"}, orRole = "admin")
    @GetMapping("/{id}")
    public R<QaCalibrationRecord> detail(@PathVariable Long id) {
        return R.ok(svc.findById(id));
    }

    @SaCheckPermission(value = {"qa:calibration-record:add", "qa:calibration-record:edit"}, orRole = "admin")
    @PostMapping
    public R<QaCalibrationRecord> save(@RequestBody QaCalibrationRecord rec) {
        return R.ok(svc.save(rec));
    }

    @SaCheckPermission(value = {"qa:calibration:check"}, orRole = "admin")
    @PostMapping("/{id}/check")
    public R<QaCalibrationRecord> check(@PathVariable Long id, @RequestBody Map<String, String> body) {
        if (body == null) body = Map.of();
        return R.ok(svc.check(id,
                body.get("result"),
                body.get("deviation"),
                body.get("reviewer")));
    }

    @SaCheckPermission(value = {"qa:calibration-record:delete"}, orRole = "admin")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        svc.delete(id);
        return R.ok();
    }
}
