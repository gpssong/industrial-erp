package com.industrial.erp.modules.mes.controller;

import cn.hutool.core.util.StrUtil;
import com.industrial.erp.common.R;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.mes.entity.MesWeightSample;
import com.industrial.erp.modules.mes.service.MesWeightService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

/**
 * v1.1.119 MES — ESP32 米克重采集器上报入口 (免登录态).
 *
 * <p><b>鉴权模型: 固定设备密钥自校验, 不用 Sa-Token 登录态.</b>
 * 理由: Sa-Token token 有 30 天 timeout 且并发共享, 现场 ESP32 无人值守
 * 刷新不了, 会静默变成 11012 未登录。所以本路径在 {@code SaTokenConfig}
 * 白名单里<b>按精确路径</b>豁免登录态, 密钥由 Controller 自己比。
 *
 * <p>⚠️ 白名单里<b>只能写 {@code /device/weight/report} 这条精确路径</b>,
 * 绝不能写 {@code /device/**} —— 那个 notMatch 是全项目唯一的登录态豁免口,
 * 写通配等于把设备端所有端点裸奔到公网。
 *
 * <p>密钥走环境变量 {@code ERP_DEVICE_KEY} 注入 ({@code @Value}),
 * <b>不硬编码不落库</b>; 表里只存后 6 位掩码。
 *
 * <p>⚠️ 不需要配 CORS: CORS 是浏览器概念, ESP32 不发 Origin 头。
 */
@Tag(name = "MES-设备上报")
@RestController
@RequestMapping("/device/weight")
public class DeviceWeightReportController {

    private static final Logger log = LoggerFactory.getLogger(DeviceWeightReportController.class);

    private final MesWeightService svc;

    /** 设备密钥, 环境变量 ERP_DEVICE_KEY 注入; 未配置则拒绝一切上报 (fail-closed) */
    @Value("${erp.device.key:}")
    private String deviceKey;

    public DeviceWeightReportController(MesWeightService svc) {
        this.svc = svc;
    }

    @Operation(summary = "ESP32 上报米克重采样 (设备密钥鉴权)")
    @PostMapping("/report")
    public R<Map<String, Object>> report(
            @RequestHeader(value = "X-Device-Key", required = false) String key,
            @RequestBody DeviceReportDTO dto,
            HttpServletRequest request) {

        // 密钥未配置 = 服务端没准备好, fail-closed 拒收而不是放行
        if (StrUtil.isBlank(deviceKey)) {
            log.error("[MES-Weight] erp.device.key 未配置, 拒绝设备上报");
            throw new BizException(500, "服务端未配置设备密钥");
        }
        if (!deviceKey.equals(key)) {
            log.warn("[MES-Weight] 设备密钥无效, 来源 IP={}", request.getRemoteAddr());
            throw new BizException(401, "设备密钥无效");
        }

        int n = svc.reportSamples(dto.getDeviceCode(),
                                  dto.getSamples(),
                                  dto.getFwVersion(),
                                  request.getRemoteAddr());

        Map<String, Object> resp = new java.util.HashMap<>();
        resp.put("accepted", n);
        return R.ok(resp);
    }

    /**
     * 上报报文. 一次可带多通道 (称重1/2/3)。
     *
     * <p>字段名用 camelCase, 与 {@link MesWeightSample} 实体字段一致 —
     * ESP32 端手搓 JSON 时最容易错的就是大小写, 这里保持直白。
     */
    public static class DeviceReportDTO {
        /** 设备编码, 对应 mes_weight_device.device_code */
        private String deviceCode;
        /** 固件版本 (可空) */
        private String fwVersion;
        /** 各通道采样 */
        private List<MesWeightSample> samples;

        public String getDeviceCode() { return deviceCode; }
        public void setDeviceCode(String deviceCode) { this.deviceCode = deviceCode; }
        public String getFwVersion() { return fwVersion; }
        public void setFwVersion(String fwVersion) { this.fwVersion = fwVersion; }
        public List<MesWeightSample> getSamples() { return samples; }
        public void setSamples(List<MesWeightSample> samples) { this.samples = samples; }
    }
}