package com.industrial.erp.modules.base.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.base.label.LabelMaster;
import com.industrial.erp.modules.base.label.LabelMasterService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "标签打印")
@RestController
@RequestMapping("/base/label")
public class BaseLabelController {

    public BaseLabelController(LabelMasterService masterService) { this.masterService = masterService; }
    private final LabelMasterService masterService;

    /** 标签主数据（生产者名称/地址/XK 证号/执行标准/贮存条件/检验员代号）。
     *  App 打印中心也要读它，不能叫只有业务权限的人去调 /system/config/*，所以单独开一个只读口。 */
    @SaCheckPermission(value = {"base:label:list", "base:product:list"}, mode = SaMode.OR, orRole = "admin")
    @GetMapping("/master")
    public R<LabelMaster> master() { return R.ok(masterService.load()); }
}
