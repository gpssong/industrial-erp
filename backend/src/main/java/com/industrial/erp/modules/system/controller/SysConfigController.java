package com.industrial.erp.modules.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.system.entity.SysConfig;
import com.industrial.erp.modules.system.service.SysConfigService;
import com.industrial.erp.security.PermissionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

@Tag(name = "系统配置")
@RestController
@RequestMapping("/system/config")
public class SysConfigController {

    public SysConfigController(SysConfigService service, PermissionService permService) {
        this.service = service;
        this.permService = permService;
    }
    private final SysConfigService service;
    private final PermissionService permService;

    @SaCheckPermission(value = {"system:config:list"}, orRole = "admin")
    @GetMapping("/page")
    public R<PageResult<SysConfig>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                          @RequestParam(defaultValue = "20") Integer pageSize,
                                          @RequestParam(required = false) String configName,
                                          @RequestParam(required = false) Integer configType) {
        return R.ok(PageResult.of(service.page(pageNum, pageSize, configName, configType)));
    }

    @SaCheckPermission(value = {"system:config:list"}, orRole = "admin")
    @GetMapping("/{id}")
    public R<SysConfig> detail(@PathVariable Long id) { return R.ok(service.detail(id)); }

    @SaCheckPermission(value = {"system:config:add"}, orRole = "admin")
    @PostMapping
    public R<Void> add(@RequestBody SysConfig c) { service.add(c); return R.ok(); }

    @SaCheckPermission(value = {"system:config:edit"}, orRole = "admin")
    @PutMapping
    public R<Void> update(@RequestBody SysConfig c) { service.update(c); return R.ok(); }

    @SaCheckPermission(value = {"system:config:delete"}, orRole = "admin")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) { service.delete(id); return R.ok(); }

    /**
     * 客户端可读配置白名单。
     * <p>AI_INTEGRATION 等含密钥的配置不在此列，仅管理员可读，避免登录用户按 key 遍历泄露。
     */
    private static final Set<String> CLIENT_READABLE_KEYS = Set.of(
            "APP_MODULE_ENABLED",
            "APP_DASHBOARD_CONFIG",
            "DASHBOARD_CONFIG",
            "PRICE_TAX_SEPARATION",
            "SYS_FACTORY",
            "SYS_NAME",
            "SYSTEM_VERSION_INFO",
            "XK_AUDIT_RETENTION_YEARS",
            "AI_PROVIDER_ORDER"
    );

    /** 查配置: 登录后仅可读客户端白名单; 其余（含密钥类）要求管理员或配置查询权限 */
    @SaCheckLogin
    @GetMapping("/key/{key}")
    public R<String> getByKey(@PathVariable String key) {
        if (!CLIENT_READABLE_KEYS.contains(key)
                && !StpUtil.hasRole("admin")
                && !StpUtil.hasPermission("system:config:list")) {
            throw BizException.of(403, "无权读取该配置");
        }
        return R.ok(service.getByKey(key));
    }

    @SaCheckPermission(value = {"system:config:edit"}, orRole = "admin")
    @PutMapping("/value")
    public R<Void> updateValue(@RequestBody java.util.Map<String, String> body) {
        service.updateValue(body.get("key"), body.get("value"));
        return R.ok();
    }
}
