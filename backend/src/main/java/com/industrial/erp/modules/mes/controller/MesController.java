package com.industrial.erp.modules.mes.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.mes.entity.MesProcessDef;
import com.industrial.erp.modules.mes.entity.PrdProcess;
import com.industrial.erp.modules.mes.service.MesReportService;
import com.industrial.erp.modules.mes.service.MesScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * v1.1.80 MES 第一阶段 — 扫码报工 + 工序进度 + 批次追溯 + 工序定义。
 *
 * <p>权限沿用 production 族 (orRole=admin 兜底, 6 内置角色 DB 已授权
 * production:report:list/add via sql/53)。报工 App 端 + PC 端共用本端点。
 */
@Tag(name = "MES 工单/报工/批次")
@RestController
@RequestMapping("/mes")
public class MesController {

    private final MesReportService mes;
    /** v1.1.99 C4: 生产排程 + 设备 OEE. */
    private final MesScheduleService scheduleService;

    public MesController(MesReportService mes, MesScheduleService scheduleService) {
        this.mes = mes;
        this.scheduleService = scheduleService;
    }

    /** 报工 DTO (App/PC 提交)。 */
    public static class ReportDTO {
        public Long orderId;
        public Long processId;
        public BigDecimal goodQty;
        public BigDecimal badQty;
        public String operator;
        /** v1.1.99 C3: 工艺参数实测值 (可空, key=paramKey, 关键参数越限则工序不自动完工). */
        public java.util.Map<String, BigDecimal> paramValues;

        public Long getOrderId() { return orderId; }
        public Long getProcessId() { return processId; }
        public BigDecimal getGoodQty() { return goodQty; }
        public BigDecimal getBadQty() { return badQty; }
        public String getOperator() { return operator; }
        public java.util.Map<String, BigDecimal> getParamValues() { return paramValues; }
    }

    @Operation(summary = "扫码报工")
    @SaCheckPermission(value = {"production:report:add"}, orRole = "admin")
    @PostMapping("/report")
    public R<PrdProcess> report(@RequestBody ReportDTO dto) {
        if (dto == null || dto.getOrderId() == null || dto.getProcessId() == null) {
            return R.fail("orderId 和 processId 必填");
        }
        PrdProcess p = mes.reportWork(dto.getOrderId(), dto.getProcessId(),
                dto.getGoodQty(), dto.getBadQty(), dto.getOperator(), dto.getParamValues());
        return R.ok(p);
    }

    @Operation(summary = "订单工序进度 (实时看板)")
    @SaCheckPermission(value = {"production:report:list"}, orRole = "admin")
    @GetMapping("/order/{orderId}/progress")
    public R<List<PrdProcess>> progress(@PathVariable Long orderId) {
        return R.ok(mes.orderProgress(orderId));
    }

    @Operation(summary = "批次追溯 (沿 料→半成品→成品→客户 递归展开, 前端逐层调)")
    // v1.1.130: 批次溯源从 production:report:list 拆出独立 perm production:report:trace
    // (扫码报工与 XK 批次溯源在 App 端分开授权). OR 模式兼容旧授权: 已有 report:list 的角色不受影响.
    @SaCheckPermission(value = {"production:report:trace", "production:report:list"}, mode = SaMode.OR, orRole = "admin")
    @GetMapping("/batch/trace/{batchNo}")
    public R<MesReportService.TraceResult> trace(@PathVariable String batchNo,
                                                 @RequestParam(defaultValue = "0") int depth) {
        return R.ok(mes.batchTrace(batchNo, depth));
    }

    @Operation(summary = "批次全链追溯 (服务端双向 BFS 展开 料→半成品→成品→客户, 一次返回整链)")
    @SaCheckPermission(value = {"production:report:trace", "production:report:list"}, mode = SaMode.OR, orRole = "admin")
    @GetMapping("/batch/trace-full/{batchNo}")
    public R<MesReportService.FullTrace> traceFull(@PathVariable String batchNo) {
        return R.ok(mes.traceFull(batchNo));
    }

    @Operation(summary = "批次列表 (按类型/产品筛)")
    @SaCheckPermission(value = {"production:report:trace", "production:report:list"}, mode = SaMode.OR, orRole = "admin")
    @GetMapping("/batch/page")
    public R<List<com.industrial.erp.modules.mes.entity.MesBatch>> batches(
            @RequestParam(required = false) String batchType,
            @RequestParam(required = false) Long productId) {
        return R.ok(mes.listBatches(batchType, productId));
    }

    // ===== 工序定义 CRUD (PC 端配工序链) =====

    @Operation(summary = "产品默认工序链")
    @SaCheckPermission(value = {"production:report:list"}, orRole = "admin")
    @GetMapping("/process-def/list")
    public R<List<MesProcessDef>> processDefList(@RequestParam(required = false) Long productId) {
        return R.ok(mes.listProcessDef(productId));
    }

    @Operation(summary = "保存工序定义 (新增/更新)")
    @SaCheckPermission(value = {"production:report:add"}, orRole = "admin")
    @PostMapping("/process-def")
    public R<Long> saveProcessDef(@RequestBody MesProcessDef def) {
        return R.ok(mes.saveProcessDef(def));
    }

    @Operation(summary = "删除工序定义")
    @SaCheckPermission(value = {"production:report:add"}, orRole = "admin")
    @DeleteMapping("/process-def/{id}")
    public R<Void> deleteProcessDef(@PathVariable Long id) {
        mes.deleteProcessDef(id);
        return R.ok();
    }

    // ==================================================================
    // v1.1.99 C4 — 生产排程 + 设备 OEE
    // ==================================================================

    @Operation(summary = "排程分页")
    // v1.1.130: OEE 页只读引用排程分页 → 放开 production:schedule:oee (OR), 与生产排程分开授权
    @SaCheckPermission(value = {"production:schedule:list", "production:schedule:oee"}, mode = SaMode.OR, orRole = "admin")
    @GetMapping("/schedule/page")
    public R<com.baomidou.mybatisplus.core.metadata.IPage<com.industrial.erp.modules.mes.entity.MesSchedule>> schedulePage(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) Long equipmentId,
            @RequestParam(required = false) Long prdOrderId,
            @RequestParam(required = false) String status) {
        return R.ok(scheduleService.page(pageNum, pageSize, equipmentId, prdOrderId, status));
    }

    @Operation(summary = "某设备甘特排程 (按时间轴)")
    @SaCheckPermission(value = {"production:schedule:list"}, orRole = "admin")
    @GetMapping("/schedule/gantt/{equipmentId}")
    public R<java.util.List<com.industrial.erp.modules.mes.entity.MesSchedule>> scheduleGantt(@PathVariable Long equipmentId) {
        return R.ok(scheduleService.ganttByEquipment(equipmentId));
    }

    @Operation(summary = "保存排程 (新增/更新, 冲突检测 + 设备承接提示)")
    @SaCheckPermission(value = {"production:schedule:edit"}, orRole = "admin")
    @PostMapping("/schedule")
    public R<Long> scheduleSave(@RequestBody com.industrial.erp.modules.mes.entity.MesSchedule s) {
        return R.ok(scheduleService.save(s));
    }

    @Operation(summary = "取消排程")
    @SaCheckPermission(value = {"production:schedule:edit"}, orRole = "admin")
    @PostMapping("/schedule/{id}/cancel")
    public R<Void> scheduleCancel(@PathVariable Long id) {
        scheduleService.cancel(id);
        return R.ok();
    }

    @Operation(summary = "设备 OEE (稼动率×性能率×良品率)")
    // v1.1.130: OEE 独立 perm production:schedule:oee (与生产排程分开授权); OR 兼容旧授权
    @SaCheckPermission(value = {"production:schedule:oee", "production:schedule:list"}, mode = SaMode.OR, orRole = "admin")
    @GetMapping("/oee/{equipmentId}")
    public R<MesScheduleService.OeeResult> oee(@PathVariable Long equipmentId) {
        return R.ok(scheduleService.oee(equipmentId));
    }

    @Operation(summary = "记录一段工序实际耗时 (OEE 数据源)")
    @SaCheckPermission(value = {"production:schedule:list"}, orRole = "admin")
    @PostMapping("/process-time")
    public R<Long> processTime(@RequestBody com.industrial.erp.modules.mes.entity.MesProcessTime t) {
        return R.ok(scheduleService.recordProcessTime(t));
    }
}
