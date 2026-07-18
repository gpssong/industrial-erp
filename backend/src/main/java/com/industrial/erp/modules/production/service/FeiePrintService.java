package com.industrial.erp.modules.production.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.production.client.FeiePrintClient;
import com.industrial.erp.modules.production.entity.PrdOrder;
import com.industrial.erp.modules.production.entity.PrdRequisitionDetail;
import com.industrial.erp.modules.production.mapper.PrdOrderMapper;
import com.industrial.erp.modules.production.mapper.PrdRequisitionDetailMapper;
import com.industrial.erp.modules.system.entity.SysFeiePrinterConfig;
import com.industrial.erp.modules.system.mapper.SysFeiePrinterConfigMapper;
import freemarker.core.TemplateClassResolver;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 飞鹅云打印服务
 * 负责: 聚合生产单数据 -> Freemarker 渲染 HTML -> 调用飞鹅 API 打印
 */
@Service
public class FeiePrintService {

    private final PrdOrderMapper orderMapper;
    private final PrdRequisitionDetailMapper reqDetailMapper;
    private final SysFeiePrinterConfigMapper configMapper;
    private final FeiePrintClient feiePrintClient;
    private final Configuration freemarkerConfig;

    public FeiePrintService(PrdOrderMapper orderMapper,
                            PrdRequisitionDetailMapper reqDetailMapper,
                            SysFeiePrinterConfigMapper configMapper,
                            FeiePrintClient feiePrintClient,
                            Configuration freemarkerConfig) {
        this.orderMapper = orderMapper;
        this.reqDetailMapper = reqDetailMapper;
        this.configMapper = configMapper;
        this.feiePrintClient = feiePrintClient;
        this.freemarkerConfig = freemarkerConfig;
        this.freemarkerConfig.setNewBuiltinClassResolver(TemplateClassResolver.ALLOWS_NOTHING_RESOLVER);
    }

    /**
     * 获取当前启用的飞鹅打印机配置
     */
    public SysFeiePrinterConfig getActiveConfig() {
        LambdaQueryWrapper<SysFeiePrinterConfig> w = new LambdaQueryWrapper<>();
        w.eq(SysFeiePrinterConfig::getStatus, 1)
         .last("LIMIT 1");
        SysFeiePrinterConfig config = configMapper.selectOne(w);
        if (config == null) {
            throw BizException.of("未配置启用的飞鹅打印机, 请先在系统设置中配置");
        }
        return config;
    }

    /**
     * 获取生产单详情 (含领料明细)
     */
    public PrdOrder getOrderWithDetails(Long orderId) {
        PrdOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw BizException.of("生产单不存在");
        }
        // 查询领料明细 (通过领料单ID, 可能为 null 如果尚未开工)
        List<PrdRequisitionDetail> details = new ArrayList<>();
        if (order.getSourceBillId() != null) {
            LambdaQueryWrapper<PrdRequisitionDetail> w = new LambdaQueryWrapper<>();
            w.eq(PrdRequisitionDetail::getRequisitionId, order.getSourceBillId());
            details = reqDetailMapper.selectList(w);
        }
        order.setRequisitionDetails(details);
        return order;
    }

    /**
     * 渲染生产单 HTML (用于预览)
     */
    public String renderHtml(Long orderId) {
        PrdOrder order = getOrderWithDetails(orderId);
        Map<String, Object> data = buildModel(order);
        return renderTemplate("print/prd_order_feie.ftl", data);
    }

    /**
     * 渲染并发送到飞鹅云打印
     */
    public String print(Long orderId) {
        SysFeiePrinterConfig config = getActiveConfig();
        String html = renderHtml(orderId);
        return printHtml(config, html);
    }

    /**
     * 用指定配置打印
     */
    public String printWithConfig(Long orderId, Long configId) {
        SysFeiePrinterConfig config = configMapper.selectById(configId);
        if (config == null) {
            throw BizException.of("打印机配置不存在");
        }
        String html = renderHtml(orderId);
        return printHtml(config, html);
    }

    /**
     * 测试飞鹅打印机连接
     */
    public String testConnection(String ukey, String deviceSn) {
        // 先获取设备列表验证 UKey 是否有效
        JSONArray devices = feiePrintClient.listDevices(ukey);
        if (devices == null || devices.isEmpty()) {
            throw BizException.of("未找到已绑定的飞鹅设备, 请确认 UKey 是否正确且设备已在飞鹅云平台绑定");
        }
        // 如果有指定设备 SN, 验证设备在线
        if (StrUtil.isNotBlank(deviceSn)) {
            boolean found = false;
            for (int i = 0; i < devices.size(); i++) {
                JSONObject dev = devices.getJSONObject(i);
                if (deviceSn.equals(dev.getStr("deviceSn"))) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw BizException.of("指定设备未找到, 请确认设备 SN 是否正确");
            }
        }
        // 发送一条测试打印
        String now = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String testHtml = "<html><body style=\"font-family:simsun;font-size:14px;padding:10px;\">"
                + "<div style=\"text-align:center;font-size:18px;font-weight:bold;\">测试打印</div>"
                + "<div style=\"text-align:center;margin-top:20px;\">飞鹅云打印机工作正常</div>"
                + "<div style=\"text-align:center;margin-top:10px;\">时间: " + now + "</div>"
                + "</body></html>";
        SysFeiePrinterConfig cfg = new SysFeiePrinterConfig();
        cfg.setUkey(ukey);
        cfg.setDeviceSn(deviceSn);
        return printHtml(cfg, testHtml);
    }

    private String printHtml(SysFeiePrinterConfig config, String html) {
        try {
            var result = feiePrintClient.printHtml(config.getUkey(), config.getDeviceSn(), html);
            return result.getStr("msg", "打印成功");
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw BizException.of("飞鹅打印失败: " + e.getMessage());
        }
    }

    /**
     * 构建 FreeMarker 数据模型
     */
    private Map<String, Object> buildModel(PrdOrder order) {
        Map<String, Object> model = new HashMap<>();
        model.put("order", order);
        return model;
    }

    /**
     * 渲染 FreeMarker 模板
     */
    private String renderTemplate(String templateName, Map<String, Object> data) {
        try {
            Template tpl = freemarkerConfig.getTemplate(templateName, "UTF-8");
            StringWriter writer = new StringWriter();
            tpl.process(data, writer);
            return writer.toString();
        } catch (IOException | TemplateException e) {
            throw BizException.of("模板渲染失败: " + e.getMessage());
        }
    }
}
