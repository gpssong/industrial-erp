package com.industrial.erp.modules.em.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.em.entity.EmMaintainRecord;
import com.industrial.erp.modules.em.service.EmMaintainRecordService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "设备维保记录")
@RestController
@RequestMapping("/em/maintain-record")
public class EmMaintainRecordController {

    private final EmMaintainRecordService svc;

    public EmMaintainRecordController(EmMaintainRecordService svc) {
        this.svc = svc;
    }

    @SaCheckPermission(value = {"equipment:maintain-record:list"}, orRole = "admin")
    @GetMapping("/page")
    public R<IPage<EmMaintainRecord>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                            @RequestParam(defaultValue = "20") Integer pageSize,
                                            @RequestParam(required = false) String equipmentCode) {
        return R.ok(svc.page(equipmentCode, pageNum, pageSize));
    }

    @SaCheckPermission(value = {"equipment:maintain-record:list"}, orRole = "admin")
    @GetMapping("/{id}")
    public R<EmMaintainRecord> detail(@PathVariable Long id) {
        return R.ok(svc.findById(id));
    }

    @SaCheckPermission(value = {"equipment:maintain-record:check"}, orRole = "admin")
    @PostMapping("/{id}/check")
    public R<EmMaintainRecord> check(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        String reviewer = body == null ? null : body.get("reviewer");
        return R.ok(svc.check(id, reviewer));
    }
}
