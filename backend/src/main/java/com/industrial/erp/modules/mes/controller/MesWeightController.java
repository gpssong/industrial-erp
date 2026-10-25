package com.industrial.erp.modules.mes.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.mes.entity.MesWeightChannel;
import com.industrial.erp.modules.mes.entity.MesWeightDevice;
import com.industrial.erp.modules.mes.entity.MesWeightSample;
import com.industrial.erp.modules.mes.service.MesWeightService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * v1.1.119 MES — 吹膜米重采集 业务查询接口 (PC 端, 需登录态).
 *
 * <p>门禁 {@code mes:weight:list} / {@code mes:weight:edit}, 均带
 * {@code orRole = "admin"} (全项目惯例)。设备上报接口在
 * {@link DeviceWeightReportController}, 两条路径严格分离 ——
 * 这里一律走登录态, 那里一律走设备密钥。
 *
 * <p>⚠️ <b>PC 端响应信封未拆封</b>: 前端读 {@code r.data.records} / {@code r.data.total}。
 * (App 端相反, 拦截器已 resolve(d.data), 不可再读 .data)
 */
@Tag(name = "MES-米重采集")
@RestController
@RequestMapping("/mes/weight")
public class MesWeightController {

    private final MesWeightService svc;

    public MesWeightController(MesWeightService svc) {
        this.svc = svc;
    }

    // ---------------- 采集器 ----------------

    @Operation(summary = "采集器分页列表")
    @SaCheckPermission(value = {"mes:weight:list"}, orRole = "admin")
    @GetMapping("/device/page")
    public R<IPage<MesWeightDevice>> devicePage(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) String workshop,
            @RequestParam(required = false) String keyword) {
        return R.ok(svc.pageDevices(pageNum, pageSize, workshop, keyword));
    }

    @Operation(summary = "某采集器的通道配置")
    @SaCheckPermission(value = {"mes:weight:list"}, orRole = "admin")
    @GetMapping("/channel/list")
    public R<List<MesWeightChannel>> channelList(@RequestParam Long deviceId) {
        return R.ok(svc.listChannels(deviceId));
    }

    @Operation(summary = "保存通道配置 (id 有则改无则新)")
    @SaCheckPermission(value = {"mes:weight:edit"}, orRole = "admin")
    @PostMapping("/channel")
    public R<MesWeightChannel> saveChannel(@RequestBody MesWeightChannel ch) {
        return R.ok(svc.saveChannel(ch));
    }

    // ---------------- 曲线 / 统计 ----------------

    @Operation(summary = "米重曲线数据 (按时间正序)")
    @SaCheckPermission(value = {"mes:weight:list"}, orRole = "admin")
    @GetMapping("/curve")
    public R<List<Map<String, Object>>> curve(
            @RequestParam Long deviceId,
            @RequestParam(required = false) Integer channelNo,
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime to,
            @RequestParam(defaultValue = "500") Integer limit) {
        return R.ok(svc.curve(deviceId, channelNo, from, to, limit));
    }

    @Operation(summary = "米重统计 (每通道均值/极值/超标率)")
    @SaCheckPermission(value = {"mes:weight:list"}, orRole = "admin")
    @GetMapping("/stats")
    public R<List<Map<String, Object>>> stats(
            @RequestParam Long deviceId,
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime to) {
        return R.ok(svc.stats(deviceId, from, to));
    }

    @Operation(summary = "采样明细分页")
    @SaCheckPermission(value = {"mes:weight:list"}, orRole = "admin")
    @GetMapping("/sample/page")
    public R<IPage<MesWeightSample>> samplePage(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) Long deviceId,
            @RequestParam(required = false) Integer channelNo,
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime to,
            @RequestParam(required = false) Integer isOver) {
        return R.ok(svc.pageSamples(pageNum, pageSize, deviceId, channelNo, from, to, isOver));
    }
}