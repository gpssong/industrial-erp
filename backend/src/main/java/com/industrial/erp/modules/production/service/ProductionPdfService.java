package com.industrial.erp.modules.production.service;

import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.production.bill.PrdOrderBillLoader;
import com.lowagie.text.pdf.BaseFont;
import freemarker.template.Configuration;
import freemarker.template.Template;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.xhtmlrenderer.pdf.ITextRenderer;

import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.util.Map;

/**
 * 生产加工单 PDF 分享服务 (v1.1.11+)
 *
 * <p>复用 FreeMarker 模板 + OpenPDF 渲染 A4 PDF, 给 App 端分享用.
 * 数据源复用 {@link PrdOrderBillLoader} (与飞鹅同一份 PrdOrder + 规格/BOM/领料).
 *
 * <p>中文字体: bundle 在 classpath fonts/wqy-microhei.ttc (5MB), 显式注册到 OpenPDF,
 * 模板 CSS font-family 'WenQuanYi Micro Hei' 与之匹配.
 */
@Service
public class ProductionPdfService {

    private static final Logger log = LoggerFactory.getLogger(ProductionPdfService.class);

    /** FreeMarker 模板路径 (与飞鹅同源, 但不混用 — 文件名区分) */
    private static final String TEMPLATE_PATH = "print/prd_order_share.ftl";

    /** 中文字体 (classpath: resources/fonts/wqy-microhei.ttc) */
    private static final String FONT_PATH = "fonts/wqy-microhei.ttc";

    private final PrdOrderBillLoader billLoader;
    private final Configuration freemarkerConfig;

    public ProductionPdfService(PrdOrderBillLoader billLoader,
                                @Qualifier("feieFreemarkerConfig") Configuration freemarkerConfig) {
        this.billLoader = billLoader;
        this.freemarkerConfig = freemarkerConfig;
    }

    /**
     * 渲染生产单 PDF 字节数组
     *
     * @param orderId 生产加工单 ID
     * @return PDF 字节流 (3-10KB, 字体子集化)
     */
    public byte[] renderPdf(Long orderId) {
        // 1. 加载数据 (复用 BillLoader, 与飞鹅打印同一份 PrdOrder + transient 字段)
        Map<String, Object> model = billLoader.load(orderId);
        if (model == null) {
            throw BizException.of("生产单不存在: id=" + orderId);
        }

        // 2. FreeMarker 渲染 HTML
        String html;
        try (StringWriter sw = new StringWriter()) {
            Template template = freemarkerConfig.getTemplate(TEMPLATE_PATH, "UTF-8");
            template.process(model, sw);
            html = sw.toString();
        } catch (Exception e) {
            log.error("[pdf-share] 模板渲染失败: {}", e.getMessage(), e);
            throw BizException.of("PDF 模板渲染失败: " + e.getMessage());
        }

        // 3. OpenPDF + flying-saucer HTML→PDF (显式注册中文字体)
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ITextRenderer renderer = new ITextRenderer();
            // wqy-microhei.ttc 包含 2 个 TTF, 注册时 TTC 必须传 index 0
            String fontUrl = new ClassPathResource(FONT_PATH).getURL().toString();
            // IDENTITY_H 横向编码支持中文 (CJK)
            renderer.getFontResolver().addFont(fontUrl, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            log.info("[pdf-share] 中文字体注册: {}", fontUrl);

            renderer.setDocumentFromString(html);
            renderer.layout();
            renderer.createPDF(baos);
            renderer.finishPDF();
            log.info("[pdf-share] PDF 渲染成功: orderId={} size={}B", orderId, baos.size());
            return baos.toByteArray();
        } catch (Exception e) {
            log.error("[pdf-share] PDF 渲染失败: {}", e.getMessage(), e);
            throw BizException.of("PDF 渲染失败: " + e.getMessage());
        }
    }
}
