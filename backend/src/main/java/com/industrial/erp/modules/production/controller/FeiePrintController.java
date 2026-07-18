package com.industrial.erp.modules.production.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.production.service.FeiePrintService;
import com.industrial.erp.modules.system.entity.SysFeiePrinterConfig;
import com.industrial.erp.modules.system.mapper.SysFeiePrinterConfigMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 飞鹅云打印机接口
 * 生产单飞鹅打印 + 打印机配置管理
 */
@Tag(name = "飞鹅云打印")
@RestController
@RequestMapping("/api/feie")
@SaCheckLogin
public class FeiePrintController {

    private final FeiePrintService feiePrintService;
    private final SysFeiePrinterConfigMapper configMapper;

    public FeiePrintController(FeiePrintService feiePrintService,
                               SysFeiePrinterConfigMapper configMapper) {
        this.feiePrintService = feiePrintService;
        this.configMapper = configMapper;
    }

    // ==================== 生产单打印 ====================

    @Operation(summary = "生产单打印预览 (返回渲染后的 HTML)")
    @GetMapping("/print/prd-order/{id}/preview")
    public R<String> previewPrdOrder(@PathVariable Long id) {
        String html = feiePrintService.renderHtml(id);
        return R.ok(html);
    }

    @Operation(summary = "生产单发送到飞鹅云打印")
    @PostMapping("/print/prd-order/{id}")
    public R<Void> printPrdOrder(@PathVariable Long id) {
        String msg = feiePrintService.print(id);
        return R.ok("打印成功: " + msg);
    }

    @Operation(summary = "生产单发送到指定飞鹅打印机")
    @PostMapping("/print/prd-order/{id}/config/{configId}")
    public R<Void> printPrdOrderWithConfig(@PathVariable Long id,
                                           @PathVariable Long configId) {
        String msg = feiePrintService.printWithConfig(id, configId);
        return R.ok("打印成功: " + msg);
    }

    // ==================== 打印机配置管理 ====================

    @Operation(summary = "获取所有打印机配置")
    @GetMapping("/printers")
    public R<List<SysFeiePrinterConfig>> listPrinters() {
        List<SysFeiePrinterConfig> list = configMapper.selectList(
                new LambdaQueryWrapper<SysFeiePrinterConfig>()
                        .orderByDesc(SysFeiePrinterConfig::getCreateTime)
        );
        return R.ok(list);
    }

    @Operation(summary = "新增打印机配置")
    @PostMapping("/printers")
    public R<Void> addPrinter(@RequestBody SysFeiePrinterConfig config) {
        config.setStatus(1);
        configMapper.insert(config);
        return R.ok();
    }

    @Operation(summary = "更新打印机配置")
    @PutMapping("/printers")
    public R<Void> updatePrinter(@RequestBody SysFeiePrinterConfig config) {
        SysFeiePrinterConfig existing = configMapper.selectById(config.getId());
        if (existing == null) {
            return R.fail("打印机配置不存在");
        }
        configMapper.updateById(config);
        return R.ok();
    }

    @Operation(summary = "删除打印机配置")
    @DeleteMapping("/printers/{id}")
    public R<Void> deletePrinter(@PathVariable Long id) {
        configMapper.update(null, new LambdaUpdateWrapper<SysFeiePrinterConfig>()
                .eq(SysFeiePrinterConfig::getId, id)
                .set(SysFeiePrinterConfig::getDeleted, 1));
        return R.ok();
    }

    @Operation(summary = "测试打印机连接")
    @PostMapping("/printers/test")
    public R<String> testPrinter(@RequestParam String ukey,
                                 @RequestParam(required = false) String deviceSn) {
        String result = feiePrintService.testConnection(ukey, deviceSn);
        return R.ok("测试成功", result);
    }
}
