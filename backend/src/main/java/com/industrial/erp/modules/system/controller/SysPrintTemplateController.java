package com.industrial.erp.modules.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.system.dto.PrintTemplateQuery;
import com.industrial.erp.modules.system.entity.SysPrintTemplate;
import com.industrial.erp.modules.system.service.SysPrintTemplateService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/**
 * 打印模板管理 (myprint-design)
 *
 * <p>v1.1.24+ 权限收紧:
 * <ul>
 *   <li>类级 {@code @SaCheckLogin} 保证所有方法都要求登录 (业务单据打印按钮调用)</li>
 *   <li>管理操作 (增删改查) 需额外 {@code system:print:*} 权限, 防止普通用户改模板</li>
 *   <li>业务单据读取 ({@code /biz-type/{bizType}}) 仍只检查登录 — 销售/采购/财务模块都需要</li>
 * </ul>
 */
@Tag(name = "打印模板")
@RestController
@RequestMapping("/system/print-template")
@SaCheckLogin
public class SysPrintTemplateController {

    public SysPrintTemplateController(SysPrintTemplateService service) {
        this.service = service;
    }
    private final SysPrintTemplateService service;

    @SaCheckPermission(value = {"system:print:list"}, orRole = "admin")
    @GetMapping("/page")
    public R<PageResult<SysPrintTemplate>> page(PrintTemplateQuery q) {
        return R.ok(PageResult.of(service.page(q)));
    }

    @SaCheckPermission(value = {"system:print:list"}, orRole = "admin")
    @GetMapping("/{id}")
    public R<SysPrintTemplate> detail(@PathVariable Long id) {
        return R.ok(service.detail(id));
    }

    /**
     * 按 biz_type 取该单据类型当前生效的模板 (业务单据打印按钮调用)
     * <p>类级 {@code @SaCheckLogin} 已保证登录, 业务读取不再额外要求权限.
     */
    @GetMapping("/biz-type/{bizType}")
    public R<SysPrintTemplate> getByBizType(@PathVariable String bizType) {
        return R.ok(service.getActiveByBizType(bizType));
    }

    @SaCheckPermission(value = {"system:print:add"}, orRole = "admin")
    @PostMapping
    public R<Void> add(@RequestBody SysPrintTemplate t) {
        service.add(t);
        return R.ok();
    }

    @SaCheckPermission(value = {"system:print:edit"}, orRole = "admin")
    @PutMapping
    public R<Void> update(@RequestBody SysPrintTemplate t) {
        service.update(t);
        return R.ok();
    }

    @SaCheckPermission(value = {"system:print:delete"}, orRole = "admin")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.ok();
    }
}