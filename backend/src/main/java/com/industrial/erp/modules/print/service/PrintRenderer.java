package com.industrial.erp.modules.print.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.industrial.erp.exception.BizException;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

/**
 * 打印渲染器 (Freemarker + 旧版勾选配置 HTML 拼装)。
 *
 * <p>职责单一: 把模板引擎解析后的内容渲染成最终 HTML 文档。
 * - 新版 `{{field}}` 模板: 走 {@link PrintTemplateEngine#buildFromTemplate} 已直接产出 HTML
 * - 旧版 .ftl (Freemarker): 走本类的 {@link #renderFromString}
 * - 旧版勾选 JSON 配置: 走本类的 {@link #renderLegacyConfig}
 */
@Component
public class PrintRenderer {

    private final PrintTemplateEngine engine;

    public PrintRenderer(PrintTemplateEngine engine) {
        this.engine = engine;
    }

    /**
     * 用 Freemarker 渲染 .ftl 模板内容
     */
    public String renderFromString(String templateContent, Map<String, Object> model) {
        try {
            Configuration cfg = new Configuration(Configuration.VERSION_2_3_32);
            cfg.setDefaultEncoding("UTF-8");
            Template t = new Template("dynamic", new StringReader(templateContent), cfg);
            StringWriter sw = new StringWriter();
            t.process(model, sw);
            return sw.toString();
        } catch (TemplateException | IOException e) {
            throw BizException.of("模板渲染失败: " + e.getMessage());
        }
    }

    public Map<String, Object> buildModel(Object bill, Object details, boolean taxSeparation) {
        Map<String, Object> m = new HashMap<>();
        m.put("bill", bill);
        m.put("details", details);
        m.put("taxSeparation", taxSeparation);
        return m;
    }

    /**
     * 旧版"勾选字段" JSON 配置渲染 (headerFields + detailColumns + footerFields)
     */
    public String renderLegacyConfig(ObjectNode cfg, boolean taxSep, Object bill, java.util.List<?> details) {
        String title = cfg.has("title") ? cfg.get("title").asText() : "单据";
        String paperSize = cfg.has("paperSize") ? cfg.get("paperSize").asText() : "P76";
        int width = engine.paperWidth(paperSize);

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
            .append("<title>").append(engine.escHtml(title)).append("</title>")
            .append("<style>")
            .append("body{font-family:SimHei,Microsoft YaHei;font-size:11px;width:").append(width).append("mm;margin:0 auto;padding:2mm;}")
            .append("h1{text-align:center;font-size:14px;margin:4px 0 6px 0;border-bottom:1px solid #000;padding-bottom:2px;}")
            .append(".row{display:flex;justify-content:space-between;font-size:10px;line-height:1.6;}")
            .append("table{width:100%;border-collapse:collapse;margin:4px 0;}")
            .append("th{background:#f0f0f0;font-size:10px;text-align:left;padding:2px;border-bottom:1px solid #000;}")
            .append("td{font-size:10px;padding:2px 1px;border-bottom:1px dashed #999;}")
            .append(".total{text-align:right;font-weight:bold;font-size:11px;margin-top:4px;}")
            .append(".hr{border-top:1px dashed #000;margin:4px 0;}")
            .append(".sign{text-align:right;margin-top:6px;font-size:10px;}")
            .append(".right{text-align:right;}")
            .append("</style></head><body>")
            .append("<h1>").append(engine.escHtml(title)).append("</h1>");

        html.append(buildHeader(bill, engine.toStringList(cfg, "headerFields")));
        boolean showTax = taxSep && cfg.has("showTax") && cfg.get("showTax").asBoolean();
        html.append(buildDetailTable(details, engine.toStringList(cfg, "detailColumns"), showTax));
        html.append(buildFooter(bill, engine.toStringList(cfg, "footerFields"), showTax));
        boolean showSig = !cfg.has("showSignature") || cfg.get("showSignature").asBoolean();
        if (showSig) html.append("<div class=\"sign\">仓管签字:_______________</div>");
        html.append("<div class=\"sign\" style=\"margin-top:8px;\">")
            .append(java.time.LocalDateTime.now().toString().substring(0, 16).replace("T", " "))
            .append("</div>");
        html.append("</body></html>");
        return html.toString();
    }

    // ========== 旧版勾选配置辅助方法 ==========

    private String buildHeader(Object bill, java.util.List<String> fields) {
        StringBuilder sb = new StringBuilder();
        for (String f : fields) {
            String val = engine.getFieldValue(bill, f);
            if (val != null && !val.isEmpty()) {
                sb.append("<div class=\"row\"><span>").append(engine.escHtml(engine.getFieldLabel(f))).append(": ")
                  .append(engine.escHtml(val)).append("</span></div>");
            }
        }
        return sb.toString();
    }

    private String buildDetailTable(java.util.List<?> details, java.util.List<String> cols, boolean showTax) {
        StringBuilder sb = new StringBuilder();
        sb.append("<table><thead><tr>");
        for (String c : cols) {
            sb.append("<th class=\"right\">").append(engine.escHtml(engine.getFieldLabel(c))).append("</th>");
        }
        if (showTax) {
            sb.append("<th class=\"right\">税率</th><th class=\"right\">税额</th>");
        }
        sb.append("</tr></thead><tbody>");
        for (Object d : details) {
            sb.append("<tr>");
            for (String c : cols) {
                sb.append("<td class=\"right\">").append(engine.escHtml(fmtCell(c, d))).append("</td>");
            }
            if (showTax) {
                sb.append("<td class=\"right\">").append(engine.escHtml(fmtCell("taxRate", d))).append("%</td>")
                  .append("<td class=\"right\">").append(engine.escHtml(fmtCell("taxAmount", d))).append("</td>");
            }
            sb.append("</tr>");
        }
        sb.append("</tbody></table>");
        return sb.toString();
    }

    private String buildFooter(Object bill, java.util.List<String> fields, boolean showTax) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"hr\"></div>");
        for (String f : fields) {
            if ((f.equals("taxAmount") || f.equals("totalAmountTax")) && !showTax) continue;
            String val = engine.getFieldValue(bill, f);
            if (val != null && !val.isEmpty() && !"0".equals(val)) {
                String label = f.equals("totalAmount") ? "不含税金额" : engine.getFieldLabel(f);
                sb.append("<div class=\"row\"><span>").append(engine.escHtml(label))
                  .append(":</span><span>¥").append(engine.escHtml(engine.fmtNum(val))).append("</span></div>");
            }
        }
        if (showTax) {
            String totalAmt = engine.getFieldValue(bill, "totalAmount");
            sb.append("<div class=\"total\">合计金额: ¥").append(engine.escHtml(engine.fmtNum(totalAmt))).append("</div>");
        }
        return sb.toString();
    }

    private String fmtCell(String field, Object d) {
        String val = engine.getFieldValue(d, field);
        if (field.equals("priceEx")) {
            String price = engine.getFieldValue(d, "price");
            String taxR = engine.getFieldValue(d, "taxRate");
            try {
                java.math.BigDecimal p = new java.math.BigDecimal(price.isEmpty() ? "0" : price);
                java.math.BigDecimal tr = new java.math.BigDecimal(taxR.isEmpty() ? "0" : taxR);
                if (tr.compareTo(java.math.BigDecimal.ZERO) > 0) {
                    return p.divide(java.math.BigDecimal.ONE.add(tr.divide(new java.math.BigDecimal(100), 6, java.math.RoundingMode.HALF_UP)), 4, java.math.RoundingMode.HALF_UP).toPlainString();
                }
                return p.setScale(4, java.math.RoundingMode.HALF_UP).toPlainString();
            } catch (Exception e) { return "0"; }
        }
        if (field.equals("amountTax")) {
            String qty = engine.getFieldValue(d, "qty");
            String price = engine.getFieldValue(d, "price");
            try {
                return new java.math.BigDecimal(qty.isEmpty() ? "0" : qty).multiply(new java.math.BigDecimal(price.isEmpty() ? "0" : price)).setScale(4, java.math.RoundingMode.HALF_UP).toPlainString();
            } catch (Exception e) { return "0"; }
        }
        return engine.fmtNum(val);
    }
}
