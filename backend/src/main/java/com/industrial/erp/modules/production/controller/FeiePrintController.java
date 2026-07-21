package com.industrial.erp.modules.production.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.industrial.erp.common.R;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.production.service.FeiePrintService;
import com.industrial.erp.modules.system.annotation.OperLog;
import com.industrial.erp.modules.system.entity.SysFeiePrintLog;
import com.industrial.erp.modules.system.entity.SysFeiePrinterConfig;
import com.industrial.erp.modules.system.entity.SysFeiePrintTemplate;
import com.industrial.erp.modules.system.mapper.SysFeiePrinterConfigMapper;
import com.industrial.erp.modules.system.service.SysFeiePrintLogService;
import com.industrial.erp.modules.system.service.SysFeiePrintTemplateService;
import freemarker.template.Configuration;
import freemarker.template.Template;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.io.StringReader;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private final SysFeiePrintTemplateService templateService;
    private final Configuration freemarkerConfig;

    public FeiePrintController(FeiePrintService feiePrintService,
                               SysFeiePrinterConfigMapper configMapper,
                               SysFeiePrintLogService logService,
                               SysFeiePrintTemplateService templateService,
                               @Qualifier("feieFreemarkerConfig") Configuration freemarkerConfig) {
        this.feiePrintService = feiePrintService;
        this.configMapper = configMapper;
        this.logService = logService;
        this.templateService = templateService;
        this.freemarkerConfig = freemarkerConfig;
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
    public R<String> testPrinter(@RequestParam(required = false) String user,
                                 @RequestParam String ukey,
                                 @RequestParam(required = false) String deviceSn) {
        return R.ok("ok", feiePrintService.testConnection(user, ukey, deviceSn));
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

    // ==================== 飞鹅打印模板 CRUD ====================

    @Operation(summary = "飞鹅打印模板分页查询")
    @GetMapping("/templates/page")
    @SaCheckPermission("system:feie:template")
    public R<IPage<SysFeiePrintTemplate>> pageTemplates(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String bizType,
            @RequestParam(required = false) Long printerConfigId) {
        return R.ok(templateService.page(pageNum, pageSize, bizType, printerConfigId));
    }

    @Operation(summary = "飞鹅打印模板详情")
    @GetMapping("/templates/{id}")
    @SaCheckPermission("system:feie:template")
    public R<SysFeiePrintTemplate> getTemplate(@PathVariable Long id) {
        return R.ok(templateService.get(id));
    }

    @Operation(summary = "飞鹅打印模板新增")
    @PostMapping("/templates")
    @SaCheckPermission("system:feie:template")
    @OperLog(module = "飞鹅打印模板", businessType = "ADD")
    public R<Void> addTemplate(@org.springframework.web.bind.annotation.RequestBody java.util.Map<String, Object> body) {
        java.util.Map<String, Object> map = body == null ? new java.util.HashMap<>() : body;
        SysFeiePrintTemplate t = new SysFeiePrintTemplate();
        Object name = map.get("name");
        if (name != null) t.setName(String.valueOf(name));
        Object bizType = map.get("bizType");
        if (bizType != null) t.setBizType(String.valueOf(bizType));
        Object printerConfigId = map.get("printerConfigId");
        if (printerConfigId != null) t.setPrinterConfigId(Long.valueOf(printerConfigId.toString()));
        Object content = map.get("content");
        if (content != null) t.setContent(String.valueOf(content));
        Object paperWidth = map.get("paperWidth");
        if (paperWidth != null) t.setPaperWidth(Integer.valueOf(paperWidth.toString()));
        Object status = map.get("status");
        if (status != null) t.setStatus(Integer.valueOf(status.toString()));
        else t.setStatus(1);
        Object isDefault = map.get("isDefault");
        if (isDefault != null) t.setIsDefault(Integer.valueOf(isDefault.toString()));
        Object remark = map.get("remark");
        if (remark != null) t.setRemark(String.valueOf(remark));
        templateService.save(t);
        return R.ok();
    }

    @Operation(summary = "飞鹅打印模板编辑")
    @PutMapping("/templates/{id}")
    @SaCheckPermission("system:feie:template")
    @OperLog(module = "飞鹅打印模板", businessType = "EDIT")
    public R<Void> updateTemplate(@PathVariable Long id, @org.springframework.web.bind.annotation.RequestBody java.util.Map<String, Object> body) {
        java.util.Map<String, Object> map = body == null ? new java.util.HashMap<>() : body;
        SysFeiePrintTemplate t = new SysFeiePrintTemplate();
        Object name = map.get("name");
        if (name != null) t.setName(String.valueOf(name));
        Object user = map.get("user");
        // user ignored (no field in entity)
        Object bizType = map.get("bizType");
        if (bizType != null) t.setBizType(String.valueOf(bizType));
        Object printerConfigId = map.get("printerConfigId");
        if (printerConfigId != null) t.setPrinterConfigId(Long.valueOf(printerConfigId.toString()));
        Object content = map.get("content");
        if (content != null) t.setContent(String.valueOf(content));
        Object paperWidth = map.get("paperWidth");
        if (paperWidth != null) t.setPaperWidth(Integer.valueOf(paperWidth.toString()));
        Object status = map.get("status");
        if (status != null) t.setStatus(Integer.valueOf(status.toString()));
        Object isDefault = map.get("isDefault");
        if (isDefault != null) t.setIsDefault(Integer.valueOf(isDefault.toString()));
        Object remark = map.get("remark");
        if (remark != null) t.setRemark(String.valueOf(remark));
        t.setId(id);
        templateService.update(t);
        return R.ok();
    }

    @Operation(summary = "飞鹅打印模板删除")
    @DeleteMapping("/templates/{id}")
    @SaCheckPermission("system:feie:template")
    @OperLog(module = "飞鹅打印模板", businessType = "DELETE")
    public R<Void> deleteTemplate(@PathVariable Long id) {
        templateService.delete(id);
        return R.ok();
    }

    @Operation(summary = "飞鹅打印模板预览 (用示例数据渲染)")
    @PostMapping("/templates/{id}/preview")
    @SaCheckPermission("system:feie:template")
    public R<String> previewTemplate(@PathVariable Long id) {
        SysFeiePrintTemplate tpl = templateService.get(id);
        // 用模拟数据渲染模板
        Map<String, Object> model = new HashMap<>();
        model.put("bill", Map.of(
            "billNo", "SO202607180001",
            "billDate", "2026-07-18",
            "customerName", "测试客户",
            "customerPhone", "13800138000",
            "warehouseName", "主仓库",
            "totalQty", "100",
            "totalAmount", "500.00",
            "totalAmountTax", "565.00",
            "remark", "测试备注"
        ));
        model.put("details", List.of(
            Map.of("productName", "LDPE 2426H", "qty", "50", "price", "10.00", "amount", "500.00"),
            Map.of("productName", "LLDPE 7050", "qty", "10", "price", "15.00", "amount", "150.00")
        ));
        try {
            Template t = new Template("preview", new StringReader(tpl.getContent()), freemarkerConfig);
            StringWriter w = new StringWriter();
            t.process(model, w);
            return R.ok(w.toString());
        } catch (Exception e) {
            throw BizException.of("预览渲染失败: " + e.getMessage());
        }
    }
}