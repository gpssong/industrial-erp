package com.industrial.erp.modules.production.service;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.exceptions.ExceptionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.industrial.erp.common.Constants;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.production.bill.BillLoader;
import com.industrial.erp.modules.production.client.FeiePrintClient;
import com.industrial.erp.modules.system.entity.SysFeiePrintLog;
import com.industrial.erp.modules.system.entity.SysFeiePrintTemplate;
import com.industrial.erp.modules.system.entity.SysFeiePrinterConfig;
import com.industrial.erp.modules.system.mapper.SysFeiePrinterConfigMapper;
import com.industrial.erp.modules.system.service.SysFeiePrintLogService;
import com.industrial.erp.modules.system.service.SysFeiePrintTemplateService;
import freemarker.core.TemplateClassResolver;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 飞鹅云打印服务 (通用)
 *
 * <p>负责:
 * <ol>
 *   <li>通过 {@link BillLoader} 加载任意 bizType 的单据</li>
 *   <li>Freemarker 渲染飞鹅标签格式文本</li>
 *   <li>调用飞鹅 API {@code Open_printMsg}</li>
 *   <li>写 {@code sys_feie_print_log} 日志 (含失败回查)</li>
 * </ol>
 */
@Service
public class FeiePrintService {

    private static final Logger log = LoggerFactory.getLogger(FeiePrintService.class);

    private final List<BillLoader> billLoaders;
    private final SysFeiePrinterConfigMapper configMapper;
    private final SysFeiePrintLogService logService;
    private final SysFeiePrintTemplateService templateService;
    private final FeiePrintClient feiePrintClient;
    private final Configuration freemarkerConfig;

    public FeiePrintService(List<BillLoader> billLoaders,
                            SysFeiePrinterConfigMapper configMapper,
                            SysFeiePrintLogService logService,
                            SysFeiePrintTemplateService templateService,
                            FeiePrintClient feiePrintClient,
                            @Qualifier("feieFreemarkerConfig") Configuration freemarkerConfig) {
        this.billLoaders = billLoaders;
        this.configMapper = configMapper;
        this.logService = logService;
        this.templateService = templateService;
        this.feiePrintClient = feiePrintClient;
        this.freemarkerConfig = freemarkerConfig;
        this.freemarkerConfig.setNewBuiltinClassResolver(TemplateClassResolver.ALLOWS_NOTHING_RESOLVER);
    }

    /**
     * 按 bizType 找 Loader
     */
    public BillLoader resolveLoader(String bizType) {
        for (BillLoader l : billLoaders) {
            if (l.bizType().equalsIgnoreCase(bizType)) return l;
        }
        throw BizException.of(400, "不支持的单据类型: " + bizType
                + ", 已支持: " + billLoaders.stream().map(BillLoader::bizType).reduce((a, b) -> a + "," + b).orElse(""));
    }

    /**
     * 渲染单据文本 (供预览/iframe)
     *
     * <p>优先使用用户自定义模板 (与 print 一致)
     */
    public String renderText(String bizType, Long billId) {
        BillLoader loader = resolveLoader(bizType);
        Map<String, Object> model = loader.load(billId);
        // 取当前启用的打印机配置 (优先第一个启用的)
        SysFeiePrinterConfig cfg = getActiveConfig();
        String content = renderCustomContent(cfg.getId(), bizType, model);
        if (content == null) {
            content = renderTemplate(loader.templatePath(), model);
        }
        return content;
    }

    /**
     * 发送到飞鹅云 (使用默认启用打印机)
     */
    public String print(String bizType, Long billId) {
        SysFeiePrinterConfig config = getActiveConfig();
        return doPrint(bizType, billId, config, config.getId());
    }

    /**
     * 发送到飞鹅云 (指定打印机)
     */
    public String printWithConfig(String bizType, Long billId, Long configId) {
        SysFeiePrinterConfig config = configMapper.selectById(configId);
        if (config == null) {
            throw BizException.of("打印机配置不存在: id=" + configId);
        }
        return doPrint(bizType, billId, config, configId);
    }

    /**
     * 核心打印逻辑 + 日志写入
     * <p>优先使用用户自定义模板 (sys_feie_print_template), 不存在则使用内置 ftl 模板
     */
    private String doPrint(String bizType, Long billId, SysFeiePrinterConfig config, Long configId) {
        BillLoader loader = resolveLoader(bizType);
        Map<String, Object> model = loader.load(billId);

        // 优先加载用户自定义模板
        String content = renderCustomContent(configId, bizType, model);
        if (content == null) {
            content = renderTemplate(loader.templatePath(), model);
        }
        String contentHash = md5(content);
        String billNo = loader.billNo(billId);

        SysFeiePrintLog row = newLog(bizType, billId, billNo, config, configId, contentHash);
        long start = System.currentTimeMillis();
        try {
            JSONObject result = feiePrintClient.printMsg(config.getUser(), config.getUkey(), config.getDeviceSn(), content, 1);
            Integer ret = result.getInt("ret");
            String msg = result.getStr("msg", "");
            row.setRespCode(ret);
            row.setRespMsg(truncate(msg, 500));
            row.setCostMs((int) (System.currentTimeMillis() - start));
            if (ret != null && ret == 0) {
                row.setStatus(1); // 已下发
                logService.save(row);
                return "打印成功: " + msg;
            }
            row.setStatus(0); // 失败
            logService.save(row);
            throw BizException.of("飞鹅返回失败: ret=" + ret + ", msg=" + msg);
        } catch (BizException e) {
            row.setCostMs((int) (System.currentTimeMillis() - start));
            row.setStatus(0);
            row.setRespMsg(truncate(e.getMessage(), 500));
            try { logService.save(row); } catch (Exception ignore) {}
            throw e;
        } catch (RuntimeException e) {
            row.setCostMs((int) (System.currentTimeMillis() - start));
            row.setStatus(0);
            row.setRespMsg(truncate(ExceptionUtil.getRootCauseMessage(e), 500));
            try { logService.save(row); } catch (Exception ignore) {}
            throw BizException.of("飞鹅打印异常: " + e.getMessage());
        }
    }

    /**
     * 测试打印机连接
     */
    public String testConnection(String user, String ukey, String deviceSn) {
        if (user == null || user.isEmpty()) user = "gpssong@163.com";
        String now = LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String testContent = "<CB>测试打印</CB><BR>"
                + "飞鹅云打印机工作正常<BR>"
                + "时间: " + now + "<BR>";
        try {
            var result = feiePrintClient.printMsg(user, ukey, deviceSn, testContent, 1);
            return "测试成功: " + result.getStr("msg", "");
        } catch (RuntimeException e) {
            throw BizException.of("测试打印失败: " + e.getMessage());
        }
    }

    /**
     * 获取当前启用的飞鹅打印机配置
     */
    public SysFeiePrinterConfig getActiveConfig() {
        SysFeiePrinterConfig config = configMapper.selectOne(new LambdaQueryWrapper<SysFeiePrinterConfig>()
                .eq(SysFeiePrinterConfig::getStatus, 1)
                .last("LIMIT 1"));
        if (config == null) {
            throw BizException.of("未配置启用的飞鹅打印机, 请先在系统设置中配置");
        }
        return config;
    }

    // ==================== 私有工具 ====================

    private SysFeiePrintLog newLog(String bizType, Long billId, String billNo,
                                   SysFeiePrinterConfig config, Long configId, String contentHash) {
        SysFeiePrintLog row = new SysFeiePrintLog();
        row.setBizType(bizType);
        row.setBillId(billId);
        row.setBillNo(billNo);
        row.setConfigId(configId != null ? configId : config.getId());
        row.setDeviceSn(config.getDeviceSn());
        row.setContentHash(contentHash);
        row.setCreateTime(LocalDateTime.now());
        try {
            row.setUserId(StpUtil.getLoginId() == null ? null : Long.valueOf(StpUtil.getLoginId().toString()));
            row.setUserName(StpUtil.getSession() == null ? null
                    : (String) StpUtil.getSession().get(Constants.CURRENT_USER));
        } catch (Exception ignore) {}
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest req = attrs.getRequest();
                row.setClientIp(clientIp(req));
            }
        } catch (Exception ignore) {}
        return row;
    }

    private String clientIp(HttpServletRequest req) {
        if (req == null) return null;
        String ip = req.getHeader("X-Forwarded-For");
        if (StrUtil.isBlank(ip) || "unknown".equalsIgnoreCase(ip)) ip = req.getHeader("X-Real-IP");
        if (StrUtil.isBlank(ip) || "unknown".equalsIgnoreCase(ip)) ip = req.getRemoteAddr();
        return ip;
    }

    private String renderTemplate(String templateName, Map<String, Object> data) {
        try {
            Template tpl = freemarkerConfig.getTemplate(templateName, "UTF-8");
            StringWriter writer = new StringWriter();
            tpl.process(data, writer);
            return writer.toString();
        } catch (IOException | TemplateException e) {
            throw BizException.of("模板渲染失败 [" + templateName + "]: " + e.getMessage());
        }
    }

    /**
     * 渲染用户自定义模板内容
     *
     * @param configId 打印机配置ID (传 null 时查所有打印机)
     * @param bizType  单据类型
     * @param data     Freemarker 数据模型
     * @return 渲染后的飞鹅标签文本, 无自定义模板时返回 null
     */
    private String renderCustomContent(Long configId, String bizType, Map<String, Object> data) {
        try {
            SysFeiePrintTemplate tpl = templateService.findDefault(bizType, configId);
            if (tpl == null) return null;
            Template t = new Template("custom", new java.io.StringReader(tpl.getContent()), freemarkerConfig);
            StringWriter w = new StringWriter();
            t.process(data, w);
            return w.toString();
        } catch (IOException | TemplateException e) {
            throw BizException.of("自定义模板渲染失败: " + e.getMessage());
        }
    }

    private String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}