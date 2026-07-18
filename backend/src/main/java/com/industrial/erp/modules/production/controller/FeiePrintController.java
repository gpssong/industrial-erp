package com.industrial.erp.modules.production.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.production.service.FeiePrintService;
import com.industrial.erp.modules.system.annotation.OperLog;
import com.industrial.erp.modules.system.entity.SysFeiePrintLog;
import com.industrial.erp.modules.system.entity.SysFeiePrinterConfig;
import com.industrial.erp.modules.system.mapper.SysFeiePrinterConfigMapper;
import com.industrial.erp.modules.system.service.SysFeiePrintLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 飞鹅云打印机接口 (通用)
 *
 * <p>支持单据: PRD_ORDER / SAL_DELIVERY / SAL_RETURN / PUR_RECEIPT / PUR_RETURN / INV_CHECK
 * <p>路径统一: {@code /api/feie/print/{bizType}/{id}}
 */
@Tag(name = "飞鹅云打印")
@RestController
@RequestMapping("/feie")
@SaCheckLogin
public class FeiePrintController {

    private final FeiePrintService feiePrintService;
    private final SysFeiePrinterConfigMapper configMapper;
    private final SysFeiePrintLogService logService;

    public FeiePrintController(FeiePrintService feiePrintService,
                               SysFeiePrinterConfigMapper configMapper,
                               SysFeiePrintLogService logService) {
        this.feiePrintService = feiePrintService;
        this.configMapper = configMapper;
        this.logService = logService;
    }

    // ==================== 通用单据打印 ====================

    @Operation(summary = "单据打印预览 (返回渲染后的 HTML, 用于 iframe)")
    @GetMapping("/print/{bizType}/{id}/preview")
    @OperLog(module = "飞鹅云打印", businessType = "PRINT_PREVIEW")
    public R<String> previewBill(@PathVariable String bizType, @PathVariable Long id) {
        return R.ok(feiePrintService.renderText(bizType, id));
    }

    @Operation(summary = "单据发送到飞鹅云打印")
    @PostMapping("/print/{bizType}/{id}")
    @SaCheckPermission(value = {"production:order:feie-print"}, orRole = "admin")
    @OperLog(module = "飞鹅云打印", businessType = "PRINT")
    public R<String> printBill(@PathVariable String bizType, @PathVariable Long id) {
        return R.ok("ok", feiePrintService.print(bizType, id));
    }

    @Operation(summary = "单据发送到指定飞鹅打印机")
    @PostMapping("/print/{bizType}/{id}/config/{configId}")
    @SaCheckPermission(value = {"production:order:feie-print"}, orRole = "admin")
    @OperLog(module = "飞鹅云打印", businessType = "PRINT")
    public R<String> printBillWithConfig(@PathVariable String bizType,
                                          @PathVariable Long id,
                                          @PathVariable Long configId) {
        return R.ok("ok", feiePrintService.printWithConfig(bizType, id, configId));
    }

    // ==================== 打印机配置管理 ====================

    @Operation(summary = "获取所有打印机配置")
    @GetMapping("/printers")
    @SaCheckPermission("system:feie:list")
    public R<List<SysFeiePrinterConfig>> listPrinters() {
        List<SysFeiePrinterConfig> list = configMapper.selectList(
                new LambdaQueryWrapper<SysFeiePrinterConfig>()
                        .orderByDesc(SysFeiePrinterConfig::getCreateTime)
        );
        return R.ok(list);
    }

    @Operation(summary = "新增打印机配置")
    @PostMapping("/printers")
    @SaCheckPermission("system:feie:add")
    @OperLog(module = "飞鹅云打印", businessType = "ADD")
    public R<Void> addPrinter(@RequestBody SysFeiePrinterConfig config) {
        config.setStatus(1);
        configMapper.insert(config);
        return R.ok();
    }

    @Operation(summary = "更新打印机配置")
    @PutMapping("/printers")
    @SaCheckPermission("system:feie:edit")
    @OperLog(module = "飞鹅云打印", businessType = "EDIT")
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
    @SaCheckPermission("system:feie:delete")
    @OperLog(module = "飞鹅云打印", businessType = "DELETE")
    public R<Void> deletePrinter(@PathVariable Long id) {
        configMapper.update(null, new LambdaUpdateWrapper<SysFeiePrinterConfig>()
                .eq(SysFeiePrinterConfig::getId, id)
                .set(SysFeiePrinterConfig::getDeleted, 1));
        return R.ok();
    }

    @Operation(summary = "测试打印机连接")
    @PostMapping("/printers/test")
    @SaCheckPermission("system:feie:test")
    @OperLog(module = "飞鹅云打印", businessType = "TEST")
    public R<String> testPrinter(@RequestParam String ukey,
                                 @RequestParam(required = false) String deviceSn) {
        return R.ok("ok", feiePrintService.testConnection(ukey, deviceSn));
    }

    // ==================== 打印日志 ====================

    @Operation(summary = "飞鹅打印日志分页查询")
    @GetMapping("/log/page")
    @SaCheckPermission("system:feie:log")
    public R<IPage<SysFeiePrintLog>> pageLog(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String bizType,
            @RequestParam(required = false) Long billId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return R.ok(logService.page(pageNum, pageSize, bizType, billId, status, startTime, endTime));
    }
}