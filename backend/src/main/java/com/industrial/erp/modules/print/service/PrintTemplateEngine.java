package com.industrial.erp.modules.print.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.system.entity.SysPrintTemplate;
import com.industrial.erp.modules.system.mapper.SysPrintTemplateMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 打印模板引擎。
 *
 * <p>职责: 解析模板内容 (内置 .ftl 资源或数据库 sys_print_template.content),
 * 提供字段插值 (`{{field}}`)、明细循环 (`{{#details}} ... {{/details}}`)、价税分离格式化等。
 *
 * <p>不直接产出最终 HTML (那是 {@link PrintRenderer} 的事), 引擎只把模板解析成纯字符串块。
 */
@Component
public class PrintTemplateEngine {

    private static final Logger log = LoggerFactory.getLogger(PrintTemplateEngine.class);

    private final SysPrintTemplateMapper printTemplateMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PrintTemplateEngine(SysPrintTemplateMapper printTemplateMapper) {
        this.printTemplateMapper = printTemplateMapper;
    }

    // ========== 模板获取 ==========

    /** 取数据库中默认启用的模板 */
    public SysPrintTemplate getDefaultTemplate(String templateType) {
        return printTemplateMapper.selectOne(
                new LambdaQueryWrapper<SysPrintTemplate>()
                        .eq(SysPrintTemplate::getTemplateType, templateType)
                        .eq(SysPrintTemplate::getIsDefault, 1)
                        .eq(SysPrintTemplate::getStatus, 1)
                        .eq(SysPrintTemplate::getDeleted, 0)
        );
    }

    /** 读取 classpath 内置 .ftl 模板 */
    public String readBuiltinTemplate(String name) {
        try (InputStream is = this.getClass().getClassLoader().getResourceAsStream("templates/print/" + name);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (IOException e) {
            throw BizException.of("内置模板读取失败: " + e.getMessage());
        }
    }

    /** 判断模板内容是否为新版 JSON 配置 (以 { 开头) */
    public boolean isJsonTemplate(String content) {
        return StrUtil.isNotBlank(content) && content.trim().startsWith("{");
    }

    /** 解析 JSON 配置为 ObjectNode */
    public ObjectNode parseJsonConfig(String json) {
        try {
            return (ObjectNode) objectMapper.readTree(json);
        } catch (Exception e) {
            throw BizException.of("模板 JSON 解析失败: " + e.getMessage());
        }
    }

    // ========== 字段格式化 ==========

    /** 通过反射取字段值 */
    public String getFieldValue(Object obj, String field) {
        if (obj == null || field == null) return "";
        try {
            String getter = "get" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
            java.lang.reflect.Method m = obj.getClass().getMethod(getter);
            Object val = m.invoke(obj);
            if (val == null) return "";
            if (val instanceof LocalDate || val instanceof LocalDateTime) return val.toString();
            return val.toString();
        } catch (Exception e) { return ""; }
    }

    /** 模板中 `{{field}}` 的格式化 (含 priceEx 不含税单价 / amountTax 含税金额计算) */
    public String fmtField(String field, Object d, boolean taxSep) {
        if (field == null) return "";
        // 修复: totalAmountTax (价税合计 = 不含税 + 税额) 是客户实付金额, 即使价税分离开关关闭也应显示.
        // 仅 taxAmount (税额) 和 taxRate (税率) 在关闭时隐藏 (因为它们没意义).
        if (!taxSep && (field.equals("taxAmount") || field.equals("taxRate"))) return "";
        if (field.equals("priceEx") && d != null) {
            String price = getFieldValue(d, "price");
            String taxR = getFieldValue(d, "taxRate");
            try {
                BigDecimal p = new BigDecimal(price.isEmpty() ? "0" : price);
                BigDecimal tr = new BigDecimal(taxR.isEmpty() ? "0" : taxR);
                if (tr.compareTo(BigDecimal.ZERO) > 0) {
                    return p.divide(BigDecimal.ONE.add(tr.divide(new BigDecimal(100), 6, RoundingMode.HALF_UP)), 4, RoundingMode.HALF_UP).toPlainString();
                }
                return p.setScale(4, RoundingMode.HALF_UP).toPlainString();
            } catch (Exception e) { return "0"; }
        }
        if (field.equals("amountTax") && d != null) {
            String qty = getFieldValue(d, "qty");
            String price = getFieldValue(d, "price");
            try {
                return new BigDecimal(qty.isEmpty() ? "0" : qty).multiply(new BigDecimal(price.isEmpty() ? "0" : price)).setScale(4, RoundingMode.HALF_UP).toPlainString();
            } catch (Exception e) { return "0"; }
        }
        String val = d != null ? getFieldValue(d, field) : "";
        return fmtNum(val);
    }

    /** 把字符串格式化为 4 位小数的纯数字串 */
    public String fmtNum(String val) {
        if (val == null || val.isEmpty()) return "";
        try { return new BigDecimal(val).setScale(4, RoundingMode.HALF_UP).toPlainString(); }
        catch (Exception e) { return val; }
    }

    /** HTML 转义 */
    public String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** 把 JSON 节点中的字符串数组字段转为 List<String> */
    public List<String> toStringList(ObjectNode node, String field) {
        List<String> list = new ArrayList<>();
        if (node.has(field) && node.get(field).isArray()) {
            node.get(field).forEach(v -> list.add(v.asText()));
        }
        return list;
    }

    /** 字段名 → 中文标签 */
    public String getFieldLabel(String field) {
        return switch (field) {
            case "billNo" -> "单号"; case "billDate" -> "日期"; case "deliveryDate" -> "交货日期";
            case "supplierName" -> "供应商"; case "supplierCode" -> "供应商编码";
            case "customerName" -> "客户"; case "customerCode" -> "客户编码";
            case "warehouseName" -> "仓库"; case "remark" -> "备注"; case "address" -> "地址";
            case "phone" -> "电话"; case "salesmanName" -> "业务员"; case "buyerName" -> "采购员";
            case "orderNo" -> "采购订单号"; case "payType" -> "付款方式";
            case "discountAmount" -> "整单折扣"; case "tailAmount" -> "抹零";
            case "productName" -> "商品"; case "productCode" -> "编码"; case "spec" -> "规格";
            case "unitName" -> "单位"; case "qty" -> "数量"; case "price" -> "单价";
            case "priceEx" -> "不含税单价"; case "amount" -> "金额"; case "amountTax" -> "含税金额";
            case "taxRate" -> "税率%"; case "taxAmount" -> "税额"; case "totalAmountTax" -> "价税合计";
            case "totalQty" -> "合计数量"; case "totalAmount" -> "不含税金额";
            case "lineNo" -> "行号"; case "batchNo" -> "批次"; case "locationName" -> "库位";
            case "snNo" -> "序列号"; case "costAmount" -> "成本金额"; case "profitAmount" -> "毛利";
            case "bomNo" -> "BOM编号"; case "planQty" -> "计划数量";
            case "actualQty" -> "实际数量"; case "goodQty" -> "合格数量";
            case "lossQty" -> "损耗数量"; case "lossRate" -> "损耗率";
            case "workshop" -> "车间"; case "leader" -> "负责人";
            case "startDate" -> "开工日期"; case "endDate" -> "完工日期";
            case "billStatus" -> "状态"; default -> field;
        };
    }

    /** 纸张代码 → 宽度 (mm) */
    public int paperWidth(String size) {
        return switch (size) {
            case "A4" -> 210;
            case "A5" -> 148;
            case "P80" -> 80;
            case "P241" -> 241;
            default -> 76;
        };
    }

    /** 渲染一行中的 {{field}} 占位符 */
    public String renderLine(String line, Object data, boolean taxSep) {
        if (line == null) return "";
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < line.length()) {
            int o = line.indexOf("{{", i);
            if (o < 0) {
                sb.append(escHtml(line.substring(i)));
                break;
            }
            sb.append(escHtml(line.substring(i, o)));
            int c = line.indexOf("}}", o);
            if (c < 0) {
                sb.append(escHtml(line.substring(o)));
                break;
            }
            String field = line.substring(o + 2, c);
            String val = fmtField(field, data, taxSep);
            sb.append("<b>").append(escHtml(val)).append("</b>");
            i = c + 2;
        }
        return sb.toString();
    }

    /** 渲染一个单元格 (可能是 {{field}} / 普通文本 / 混合) */
    public String renderCell(String content, Object data, boolean taxSep) {
        if (content == null) return "";
        if (content.startsWith("{{") && content.endsWith("}}")) {
            String field = content.substring(2, content.length() - 2);
            return escHtml(fmtField(field, data, taxSep));
        }
        return renderLine(content, data, taxSep);
    }

    /**
     * 解析明细模板: 第一行 (含 |) = 表头, 之后每行 (含 | 且含 {{}}) = 数据行模板。
     * 数据行会遍历所有 details 项重复输出。
     *
     * <p>列对齐: 根据 `{{fieldName}}` 中的 fieldName 推断——
     * 数值类字段 (qty/price/amount/...等) 右对齐, 文本类字段 (productName/spec/...) 左对齐.
     */
    public String buildTableFromTpl(String tpl, List<?> details, boolean taxSep) {
        List<String> headerCells = new ArrayList<>();
        List<String> dataRowTpls = new ArrayList<>();
        boolean headerRead = false;

        for (String raw : tpl.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (!line.contains("|")) continue;
            if (line.contains("{{")) {
                dataRowTpls.add(line);
            } else if (!headerRead) {
                for (String c : line.split("\\|")) headerCells.add(c.trim());
                headerRead = true;
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("buildTableFromTpl: tpl={}, details={}, headerCells={}, dataRowTpls={}",
                    tpl, details == null ? "null" : details.size(), headerCells, dataRowTpls);
        }

        // 推断每列的对齐: 数值列右对齐, 其他左对齐
        List<String> alignments = new ArrayList<>();
        if (!dataRowTpls.isEmpty()) {
            String[] firstRowCells = dataRowTpls.get(0).split("\\|");
            for (String cell : firstRowCells) {
                String field = extractFieldName(cell.trim());
                alignments.add(isNumericField(field) ? "right" : "left");
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<table>");
        if (!headerCells.isEmpty()) {
            sb.append("<thead><tr>");
            for (int i = 0; i < headerCells.size(); i++) {
                String align = i < alignments.size() ? alignments.get(i) : "center";
                sb.append("<th style=\"text-align:").append(align).append(";background:#f0f0f0;\">");
                sb.append(renderCell(headerCells.get(i), null, taxSep));
                sb.append("</th>");
            }
            sb.append("</tr></thead>");
        }
        sb.append("<tbody>");
        if (details.isEmpty()) {
            if (!headerCells.isEmpty()) {
                sb.append("<tr>");
                for (int i = 0; i < headerCells.size(); i++) sb.append("<td style=\"height:20px;\">&nbsp;</td>");
                sb.append("</tr>");
            }
        } else {
            for (Object row : details) {
                for (String rowTpl : dataRowTpls) {
                    if (rowTpl.trim().isEmpty()) continue;
                    sb.append("<tr>");
                    String[] cells = rowTpl.split("\\|");
                    for (int i = 0; i < cells.length; i++) {
                        String content = cells[i].trim();
                        String align = i < alignments.size() ? alignments.get(i) : "left";
                        sb.append("<td style=\"text-align:").append(align).append(";\">");
                        sb.append(renderCell(content, row, taxSep));
                        sb.append("</td>");
                    }
                    sb.append("</tr>");
                }
            }
        }
        sb.append("</tbody></table>");
        return sb.toString();
    }

    /** 从单元格内容中提取 `{{xxx}}` 里的字段名, 没有则返回 null */
    private String extractFieldName(String cell) {
        if (cell == null || !cell.contains("{{")) return null;
        int s = cell.indexOf("{{");
        int e = cell.indexOf("}}", s);
        if (e < 0) return null;
        String f = cell.substring(s + 2, e).trim();
        // 处理 {{field:text}} / {{field:align=left}} 这类带后缀的写法, 只取 field
        int colon = f.indexOf(':');
        return colon >= 0 ? f.substring(0, colon).trim() : f;
    }

    /** 数值类字段: 数量/单价/金额/价税/税率/序号/行号 等需要右对齐 */
    private boolean isNumericField(String field) {
        if (field == null || field.isEmpty()) return false;
        String f = field.toLowerCase();
        return f.contains("qty") || f.contains("quantity") || f.contains("price") || f.contains("amount")
                || f.contains("tax") || f.contains("rate") || f.contains("cost") || f.contains("profit")
                || f.contains("total") || f.contains("discount") || f.contains("tail")
                || f.contains("loss") || f.contains("lineno") || f.contains("line_no") || f.equals("sn")
                || f.contains("snno") || f.contains("sn_no") || f.contains("received") || f.contains("paid");
    }

    /**
     * 渲染普通文本块 (支持 {{field}} 插值)。
     * 每行要么是 | 分隔的表头行, 要么是普通文本行 (含 {{field}} 占位符)。
     */
    public String renderBlock(String text, Object data, boolean taxSep, boolean inDetails) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("===")) continue;
            if (inDetails && line.contains("|") && line.contains("{{")) {
                // details 块内的 | 行由 buildTableFromTpl 处理
            } else if (line.contains("|") && !line.contains("{{")) {
                sb.append("<div class=\"info-row\" style=\"border:1px solid #333;\">");
                for (String cell : line.split("\\|")) {
                    String display = renderCell(cell.trim(), data, taxSep);
                    sb.append("<div style=\"flex:1;text-align:center;padding:2px;font-weight:bold;background:#f0f0f0;border-right:1px solid #333;\">")
                      .append(display).append("</div>");
                }
                sb.append("</div>");
            } else {
                sb.append("<div class=\"info-row\"><div>").append(renderLine(line, data, taxSep)).append("</div></div>");
            }
        }
        return sb.toString();
    }

    /**
     * HTML 模式: 模板字符串按 HTML 直接渲染, 仅对 {{field}} 占位符做值替换并转义.
     * 不强制每行包 div, 用户的 &lt;table&gt;/&lt;div&gt;/&lt;style&gt; 等原样输出.
     */
    public String renderHtmlBlock(String text, Object data, boolean taxSep) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        String[] lines = text.split("\n", -1); // -1 保留末尾空行
        for (String raw : lines) {
            String line = raw; // 不 trim, 保留用户原始格式
            if (line.isEmpty()) { sb.append('\n'); continue; }
            // 用 renderHtmlLine: 保留 HTML 标签原样, 只替换 {{field}} 为转义后的值
            sb.append(renderHtmlLine(line, data, taxSep));
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * 模板格式:
     *   文本模式 (默认):
     *     普通行:  key: {{fieldName}}  或  {{fieldName}}
     *     表头行: |列名1|{{field1}}|列名2|{{field2}}
     *     明细:   {{#details}} ...第一行是表头(列用|分隔).. {{/details}}
     *   HTML 模式 (cfg.mode = "html"):
     *     模板字符串按 HTML 原样输出, 仅 {{field}} 替换; {{#details}} 仍可用循环.
     *     用户可写任意 HTML (table/div/style/script).
     */
    public String buildFromTemplate(String template, boolean taxSep, Object bill, List<?> details, ObjectNode cfg) {
        String paperSize = cfg.has("paperSize") ? cfg.get("paperSize").asText() : "P76";
        int width = paperWidth(paperSize);
        String title = cfg.has("title") ? cfg.get("title").asText() : "单据";
        boolean showSig = !cfg.has("showSignature") || cfg.get("showSignature").asBoolean();
        boolean htmlMode = "html".equalsIgnoreCase(cfg.has("mode") ? cfg.get("mode").asText() : "");

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
            .append("<title>").append(escHtml(title)).append("</title>")
            .append("<style>")
            .append("body{font-family:SimHei,Microsoft YaHei;font-size:11px;width:").append(width).append("mm;margin:0 auto;padding:2mm;}")
            .append("h1{text-align:center;font-size:14px;margin:4px 0 6px 0;border-bottom:1px solid #000;padding-bottom:2px;}")
            .append(".sec{font-size:10px;line-height:1.7;}")
            .append(".info-row{display:flex;justify-content:space-between;padding:1px 0;}")
            .append("table{width:100%;border-collapse:collapse;margin:3px 0;border:1px solid #333;}")
            .append("th,td{border:1px solid #333;padding:2px 4px;font-size:10px;}")
            .append("th{background:#f0f0f0;text-align:center;font-weight:bold;}")
            .append("td{text-align:right;}")
            .append("td.left,th.left{text-align:left;}")
            .append(".bold{font-weight:bold;}")
            .append(".total{font-weight:bold;font-size:11px;margin-top:4px;}")
            .append(".sign{text-align:right;margin-top:8px;font-size:10px;}")
            .append("</style></head><body>")
            .append("<h1>").append(escHtml(title)).append("</h1>")
            .append("<div class=\"sec\">");

        int ds = template.indexOf("{{#details}}");
        int de = template.indexOf("{{/details}}");

        if (ds >= 0 && de > ds) {
            String head = template.substring(0, ds);
            String foot = template.substring(de + "{{/details}}".length());
            String detailTpl = template.substring(ds + "{{#details}}".length(), de);
            // head/foot 部分: HTML 模式原样输出, 文本模式走 renderBlock
            html.append(htmlMode ? renderHtmlBlock(head, bill, taxSep) : renderBlock(head, bill, taxSep, false));
            // 明细部分: HTML 模式保持简单表格生成, 但允许用户在 detailTpl 中写 HTML 单元格
            html.append(htmlMode ? buildTableFromTplHtml(detailTpl, details, taxSep, head) : buildTableFromTpl(detailTpl, details, taxSep));
            html.append(htmlMode ? renderHtmlBlock(foot, bill, taxSep) : renderBlock(foot, bill, taxSep, false));
        } else {
            html.append(htmlMode ? renderHtmlBlock(template, bill, taxSep) : renderBlock(template, bill, taxSep, false));
        }

        html.append("</div>");
        if (showSig) html.append("<div class=\"sign\">仓管签字:_______________</div>");
        html.append("<div class=\"sign\">").append(LocalDateTime.now().toString().substring(0, 16).replace("T", " ")).append("</div>");
        html.append("</body></html>");
        return html.toString();
    }

    /**
     * HTML 模式下的明细表: 支持两种格式.
     * <ul>
     *   <li>HTML 格式: 模板中直接写 &lt;tr&gt;&lt;td&gt;{{field}}&lt;/td&gt;&lt;/tr&gt;, 每行原样复制并替换字段</li>
     *   <li>管道格式: 第一行 (含 |) = 表头, 之后每行 = 数据行模板</li>
     * </ul>
     *
     * @param head 模板中 {{#details}} 之前的部分 (用于判断是否已在 &lt;tbody&gt; 内)
     */
    private String buildTableFromTplHtml(String tpl, List<?> details, boolean taxSep, String head) {
        boolean hasHtmlRows = tpl.contains("<tr") || tpl.contains("<td");

        if (hasHtmlRows) {
            return buildHtmlDetailRows(tpl, details, taxSep, head);
        }
        // 管道格式: 回退到旧逻辑
        return buildTableFromTpl(tpl, details, taxSep);
    }

    /**
     * HTML 行格式明细: 模板中直接写 HTML &lt;tr&gt; 行, 对每条 detail 数据重复输出并替换 {{field}}.
     * <p>如果外层 head 已含 &lt;tbody&gt; (用户把 {{#details}} 放在 &lt;tbody&gt; 内), 则只输出 &lt;tr&gt; 行;
     * 否则包裹 &lt;table&gt; (独立明细表场景).
     *
     * @param head 模板中 {{#details}} 之前的部分 (用于检测是否已在 &lt;tbody&gt; 内)
     */
    private String buildHtmlDetailRows(String tpl, List<?> details, boolean taxSep, String head) {
        boolean insideTbody = head != null && head.contains("<tbody");
        if (details == null || details.isEmpty()) {
            String empty = tpl.replaceAll("\\{\\{[^}]+\\}\\}", "&nbsp;");
            return insideTbody ? empty : "<table style=\"width:100%;border-collapse:collapse;\">" + empty + "</table>";
        }
        StringBuilder sb = new StringBuilder();
        if (!insideTbody) sb.append("<table style=\"width:100%;border-collapse:collapse;\">");
        for (Object row : details) {
            String[] lines = tpl.split("\n");
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty()) continue;
                sb.append(renderHtmlLine(line, row, taxSep)).append("\n");
            }
        }
        if (!insideTbody) sb.append("</table>");
        return sb.toString();
    }

    /**
     * 渲染一行 HTML 中的 {{field}} 占位符, 保留 HTML 标签原样, 只替换字段值.
     * 字段值做 HTML 转义以防止 XSS.
     */
    private String renderHtmlLine(String line, Object data, boolean taxSep) {
        if (line == null) return "";
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < line.length()) {
            int o = line.indexOf("{{", i);
            if (o < 0) {
                sb.append(line.substring(i));
                break;
            }
            sb.append(line, i, o);
            int c = line.indexOf("}}", o);
            if (c < 0) {
                sb.append(line.substring(o));
                break;
            }
            String field = line.substring(o + 2, c).trim();
            String val = fmtField(field, data, taxSep);
            sb.append(escHtml(val));
            i = c + 2;
        }
        return sb.toString();
    }
}
