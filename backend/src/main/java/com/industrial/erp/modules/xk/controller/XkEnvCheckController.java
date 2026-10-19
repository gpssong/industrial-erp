package com.industrial.erp.modules.xk.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.xk.entity.XkEnvCheck;
import com.industrial.erp.modules.xk.service.XkEnvCheckService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "XK 生产环境检查")
@RestController
@RequestMapping("/xk/env-check")
public class XkEnvCheckController {

    private final XkEnvCheckService svc;

    public XkEnvCheckController(XkEnvCheckService svc) {
        this.svc = svc;
    }

    @SaCheckPermission(value = {"xk:env:list"}, orRole = "admin")
    @GetMapping("/page")
    public R<IPage<XkEnvCheck>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                      @RequestParam(defaultValue = "20") Integer pageSize,
                                      @RequestParam(required = false) String workshop,
                                      @RequestParam(required = false) String envType,
                                      @RequestParam(required = false) String status) {
        return R.ok(svc.page(workshop, envType, status, pageNum, pageSize));
    }

    @SaCheckPermission(value = {"xk:env:list"}, orRole = "admin")
    @GetMapping("/{id}")
    public R<XkEnvCheck> detail(@PathVariable Long id) {
        return R.ok(svc.findById(id));
    }

    @SaCheckPermission(value = {"xk:env:add", "xk:env:edit"}, orRole = "admin")
    @PostMapping
    public R<XkEnvCheck> save(@RequestBody XkEnvCheck env) {
        return R.ok(svc.save(env));
    }

    @SaCheckPermission(value = {"xk:env:check"}, orRole = "admin")
    @PostMapping("/{id}/check")
    public R<XkEnvCheck> check(@PathVariable Long id, @RequestBody Map<String, String> body) {
        if (body == null) body = Map.of();
        return R.ok(svc.check(id,
                body.get("level"),
                body.get("owner"),
                body.get("deadline"),
                body.get("rectifyDesc"),
                body.get("beforePhotos"),
                body.get("afterPhotos"),
                body.get("rectifyStatus")));
    }

    @SaCheckPermission(value = {"xk:env:edit"}, orRole = "admin")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        svc.delete(id);
        return R.ok();
    }
}
