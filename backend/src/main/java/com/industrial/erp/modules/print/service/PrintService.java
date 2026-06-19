package com.industrial.erp.modules.print.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.production.entity.PrdOrder;
import com.industrial.erp.modules.production.mapper.PrdOrderMapper;
import com.industrial.erp.modules.purchase.entity.PurReceipt;
import com.industrial.erp.modules.purchase.entity.PurReceiptDetail;
import com.industrial.erp.modules.purchase.mapper.PurReceiptDetailMapper;
import com.industrial.erp.modules.sales.entity.SalDelivery;
import com.industrial.erp.modules.sales.entity.SalDeliveryDetail;
import com.industrial.erp.modules.sales.mapper.SalDeliveryDetailMapper;
import com.industrial.erp.modules.sales.mapper.SalDeliveryMapper;
import com.industrial.erp.modules.purchase.mapper.PurReceiptMapper;
import com.industrial.erp.modules.system.entity.SysPrintTemplate;
import com.industrial.erp.modules.system.mapper.SysPrintTemplateMapper;
import com.industrial.erp.modules.system.service.SysConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.*;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class PrintService {

    public PrintService(SalDeliveryMapper deliveryMapper, SalDeliveryDetailMapper deliveryDetailMapper,
                        PurReceiptMapper receiptMapper, PurReceiptDetailMapper receiptDetailMapper,
                        PrdOrderMapper prdOrderMapper, SysPrintTemplateMapper printTemplateMapper,
                        DataSource dataSource, SysConfigService configService) {
        this.deliveryMapper = deliveryMapper;
        this.deliveryDetailMapper = deliveryDetailMapper;
        this.receiptMapper = receiptMapper;
        this.receiptDetailMapper = receiptDetailMapper;
        this.prdOrderMapper = prdOrderMapper;
        this.printTemplateMapper = printTemplateMapper;
        this.dataSource = dataSource;
        this.configService = configService;
    }

    private final SalDeliveryMapper deliveryMapper;
    private final SalDeliveryDetailMapper deliveryDetailMapper;
    private final PurReceiptMapper receiptMapper;
    private final PurReceiptDetailMapper receiptDetailMapper;
    private final PrdOrderMapper prdOrderMapper;
    private final SysPrintTemplateMapper printTemplateMapper;
    private final DataSource dataSource;
    private final SysConfigService configService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ========== JDBC 查询 ==========

    private PurReceipt jdbcFindReceipt(Long id) {
        String sql = "SELECT * FROM pur_receipt WHERE id = ?";
        try (PreparedStatement ps = dataSource.getConnection().prepareStatement(sql)) {
            ps.setObject(1, id);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return null;
            return mapRowReceipt(rs);
        } catch (SQLException e) { throw BizException.of("查询入库单失败: " + e.getMessage()); }
    }

    private SalDelivery jdbcFindDelivery(Long id) {
        String sql = "SELECT * FROM sal_delivery WHERE id = ?";
        try (PreparedStatement ps = dataSource.getConnection().prepareStatement(sql)) {
            ps.setObject(1, id);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return null;
            return mapRowDelivery(rs);
        } catch (SQLException e) { throw BizException.of("查询出库单失败: " + e.getMessage()); }
    }

    private List<PurReceiptDetail> jdbcFindReceiptDetails(Long receiptId) {
        String sql = "SELECT * FROM pur_receipt_detail WHERE receipt_id = ? AND deleted = 0 ORDER BY line_no";
        List<PurReceiptDetail> list = new ArrayList<>();
        try (PreparedStatement ps = dataSource.getConnection().prepareStatement(sql)) {
            ps.setObject(1, receiptId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapRowReceiptDetail(rs));
            return list;
        } catch (SQLException e) { throw BizException.of("查询入库明细失败: " + e.getMessage()); }
    }

    private List<SalDeliveryDetail> jdbcFindDeliveryDetails(Long deliveryId) {
        String sql = "SELECT * FROM sal_delivery_detail WHERE delivery_id = ? AND deleted = 0 ORDER BY line_no";
        List<SalDeliveryDetail> list = new ArrayList<>();
        try (PreparedStatement ps = dataSource.getConnection().prepareStatement(sql)) {
            ps.setObject(1, deliveryId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapRowDeliveryDetail(rs));
            return list;
        } catch (SQLException e) { throw BizException.of("查询出库明细失败: " + e.getMessage()); }
    }

    private PurReceipt mapRowReceipt(ResultSet rs) throws SQLException {
        PurReceipt r = new PurReceipt();
        r.setId(rs.getObject("id", Long.class));
        r.setBillNo(rs.getString("bill_no"));
        r.setBillDate(getLocalDate(rs, "bill_date"));
        r.setOrderId(rs.getObject("order_id", Long.class));
        r.setOrderNo(rs.getString("order_no"));
        r.setSupplierId(rs.getObject("supplier_id", Long.class));
        r.setSupplierName(rs.getString("supplier_name"));
        r.setWarehouseId(rs.getObject("warehouse_id", Long.class));
        r.setAreaId(rs.getObject("area_id", Long.class));
        r.setBuyerId(rs.getObject("buyer_id", Long.class));
        r.setBillType(rs.getString("bill_type"));
        r.setTotalQty(getBigDecimal(rs, "total_qty"));
        r.setTotalAmount(getBigDecimal(rs, "total_amount"));
        r.setTaxAmount(getBigDecimal(rs, "tax_amount"));
        r.setTotalAmountTax(getBigDecimal(rs, "total_amount_tax"));
        r.setPaidAmount(getBigDecimal(rs, "paid_amount"));
        r.setPayType(rs.getString("pay_type"));
        r.setBillStatus(rs.getString("bill_status"));
        r.setDeliveryNo(rs.getString("delivery_no"));
        r.setRemark(rs.getString("remark"));
        r.setCreateBy(rs.getObject("create_by", Long.class));
        r.setCreateTime(rs.getObject("create_time", LocalDateTime.class));
        r.setUpdateBy(rs.getObject("update_by", Long.class));
        r.setUpdateTime(rs.getObject("update_time", LocalDateTime.class));
        return r;
    }

    private SalDelivery mapRowDelivery(ResultSet rs) throws SQLException {
        SalDelivery r = new SalDelivery();
        r.setId(rs.getObject("id", Long.class));
        r.setBillNo(rs.getString("bill_no"));
        r.setBillDate(getLocalDate(rs, "bill_date"));
        r.setOrderId(rs.getObject("order_id", Long.class));
        r.setOrderNo(rs.getString("order_no"));
        r.setCustomerId(rs.getObject("customer_id", Long.class));
        r.setCustomerName(rs.getString("customer_name"));
        r.setWarehouseId(rs.getObject("warehouse_id", Long.class));
        r.setAreaId(rs.getObject("area_id", Long.class));
        r.setSalesmanId(rs.getObject("salesman_id", Long.class));
        r.setSalesmanName(rs.getString("salesman_name"));
        r.setBillType(rs.getString("bill_type"));
        r.setTotalQty(getBigDecimal(rs, "total_qty"));
        r.setTotalAmount(getBigDecimal(rs, "total_amount"));
        r.setDiscountAmount(getBigDecimal(rs, "discount_amount"));
        r.setTailAmount(getBigDecimal(rs, "tail_amount"));
        r.setTaxAmount(getBigDecimal(rs, "tax_amount"));
        r.setTotalAmountTax(getBigDecimal(rs, "total_amount_tax"));
        r.setReceivedAmount(getBigDecimal(rs, "received_amount"));
        r.setCostAmount(getBigDecimal(rs, "cost_amount"));
        r.setProfitAmount(getBigDecimal(rs, "profit_amount"));
        r.setDeliveryDate(getLocalDate(rs, "delivery_date"));
        r.setAddress(rs.getString("address"));
        r.setPhone(rs.getString("phone"));
        r.setBillStatus(rs.getString("bill_status"));
        r.setRemark(rs.getString("remark"));
        r.setCreateBy(rs.getObject("create_by", Long.class));
        r.setCreateTime(rs.getObject("create_time", LocalDateTime.class));
        r.setUpdateBy(rs.getObject("update_by", Long.class));
        r.setUpdateTime(rs.getObject("update_time", LocalDateTime.class));
        return r;
    }

    private PurReceiptDetail mapRowReceiptDetail(ResultSet rs) throws SQLException {
        PurReceiptDetail d = new PurReceiptDetail();
        d.setId(rs.getObject("id", Long.class));
        d.setReceiptId(rs.getObject("receipt_id", Long.class));
        d.setLineNo(rs.getObject("line_no", Integer.class));
        d.setOrderDetailId(rs.getObject("order_detail_id", Long.class));
        d.setProductId(rs.getObject("product_id", Long.class));
        d.setProductCode(rs.getString("product_code"));
        d.setProductName(rs.getString("product_name"));
        d.setSpec(rs.getString("spec"));
        d.setUnitId(rs.getObject("unit_id", Long.class));
        d.setUnitName(rs.getString("unit_name"));
        d.setQty(getBigDecimal(rs, "qty"));
        d.setPrice(getBigDecimal(rs, "price"));
        d.setAmount(getBigDecimal(rs, "amount"));
        d.setTaxRate(getBigDecimal(rs, "tax_rate"));
        d.setTaxAmount(getBigDecimal(rs, "tax_amount"));
        d.setAmountTax(getBigDecimal(rs, "amount_tax"));
        d.setBatchNo(rs.getString("batch_no"));
        d.setLocationId(rs.getObject("location_id", Long.class));
        d.setLocationName(rs.getString("location_name"));
        d.setSnNo(rs.getString("sn_no"));
        return d;
    }

    private SalDeliveryDetail mapRowDeliveryDetail(ResultSet rs) throws SQLException {
        SalDeliveryDetail d = new SalDeliveryDetail();
        d.setId(rs.getObject("id", Long.class));
        d.setDeliveryId(rs.getObject("delivery_id", Long.class));
        d.setLineNo(rs.getObject("line_no", Integer.class));
        d.setOrderDetailId(rs.getObject("order_detail_id", Long.class));
        d.setProductId(rs.getObject("product_id", Long.class));
        d.setProductCode(rs.getString("product_code"));
        d.setProductName(rs.getString("product_name"));
        d.setSpec(rs.getString("spec"));
        d.setUnitId(rs.getObject("unit_id", Long.class));
        d.setUnitName(rs.getString("unit_name"));
        d.setQty(getBigDecimal(rs, "qty"));
        d.setPrice(getBigDecimal(rs, "price"));
        d.setAmount(getBigDecimal(rs, "amount"));
        d.setTaxRate(getBigDecimal(rs, "tax_rate"));
        d.setTaxAmount(getBigDecimal(rs, "tax_amount"));
        d.setAmountTax(getBigDecimal(rs, "amount_tax"));
        d.setBatchNo(rs.getString("batch_no"));
        d.setLocationId(rs.getObject("location_id", Long.class));
        d.setLocationName(rs.getString("location_name"));
        d.setSnNo(rs.getString("sn_no"));
        d.setRemark(rs.getString("remark"));
        return d;
    }

    private LocalDate getLocalDate(ResultSet rs, String col) {
        try { return rs.getObject(col, LocalDate.class); } catch (Exception e) { return null; }
    }

    private BigDecimal getBigDecimal(ResultSet rs, String col) {
        try { return rs.getObject(col, BigDecimal.class); } catch (Exception e) { return null; }
    }

    // ========== 渲染入口 ==========

    public String renderSalesDelivery(Long id) {
        SalDelivery bill = jdbcFindDelivery(id);
        if (bill == null) throw BizException.of("出库单不存在");
        List<SalDeliveryDetail> details = jdbcFindDeliveryDetails(id);
        boolean taxSep = "true".equals(configService.getByKey("PRICE_TAX_SEPARATION"));
        SysPrintTemplate tpl = getTemplate("SAL_DELIVERY");
        if (tpl != null && StrUtil.isNotBlank(tpl.getContent()) && tpl.getContent().trim().startsWith("{")) {
            return buildFromConfig(tpl.getContent(), taxSep, bill, details);
        }
        Map<String, Object> model = buildModel(bill, details, taxSep);
        return renderFromString(tpl != null ? tpl.getContent() : readFileTemplate("sales_delivery.ftl"), model);
    }

    public String renderPurchaseReceipt(Long id) {
        PurReceipt bill = jdbcFindReceipt(id);
        if (bill == null) throw BizException.of("入库单不存在");
        List<PurReceiptDetail> details = jdbcFindReceiptDetails(id);
        boolean taxSep = "true".equals(configService.getByKey("PRICE_TAX_SEPARATION"));
        SysPrintTemplate tpl = getTemplate("PUR_RECEIPT");
        if (tpl != null && StrUtil.isNotBlank(tpl.getContent()) && tpl.getContent().trim().startsWith("{")) {
            return buildFromConfig(tpl.getContent(), taxSep, bill, details);
        }
        Map<String, Object> model = buildModel(bill, details, taxSep);
        return renderFromString(tpl != null ? tpl.getContent() : readFileTemplate("purchase_receipt.ftl"), model);
    }

    public String renderPrdOrder(Long id) {
        PrdOrder bill = prdOrderMapper.selectById(id);
        if (bill == null) throw BizException.of("生产单不存在");
        SysPrintTemplate tpl = getTemplate("PRD_ORDER");
        if (tpl != null && StrUtil.isNotBlank(tpl.getContent()) && tpl.getContent().trim().startsWith("{")) {
            try {
                ObjectNode cfg = (ObjectNode) objectMapper.readTree(tpl.getContent());
                String tplStr = cfg.has("template") ? cfg.get("template").asText() : "";
                return buildFromTemplate(tplStr, false, bill, Collections.emptyList(), cfg);
            } catch (Exception e) {
                throw BizException.of("模板渲染失败: " + e.getMessage());
            }
        }
        Map<String, Object> model = new HashMap<>();
        model.put("bill", bill);
        return renderFromString(tpl != null ? tpl.getContent() : readFileTemplate("prd_order.ftl"), model);
    }

    // ========== 模板解析 ==========

    private SysPrintTemplate getTemplate(String templateType) {
        return printTemplateMapper.selectOne(
                new LambdaQueryWrapper<SysPrintTemplate>()
                        .eq(SysPrintTemplate::getTemplateType, templateType)
                        .eq(SysPrintTemplate::getIsDefault, 1)
                        .eq(SysPrintTemplate::getStatus, 1)
                        .eq(SysPrintTemplate::getDeleted, 0)
        );
    }

    private String readFileTemplate(String name) {
        try (InputStream is = this.getClass().getClassLoader().getResourceAsStream("templates/print/" + name);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (IOException e) {
            throw BizException.of("内置模板读取失败: " + e.getMessage());
        }
    }

    private String renderFromString(String templateContent, Map<String, Object> model) {
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

    private Map<String, Object> buildModel(Object bill, List<?> details, boolean taxSeparation) {
        Map<String, Object> m = new HashMap<>();
        m.put("bill", bill);
        m.put("details", details);
        m.put("taxSeparation", taxSeparation);
        return m;
    }

    // ========== JSON模板配置渲染 ==========

    @SuppressWarnings("unchecked")
    private String buildFromConfig(String jsonConfig, boolean taxSep, Object bill, List<?> details) {
        try {
            ObjectNode cfg = (ObjectNode) objectMapper.readTree(jsonConfig);
            if (cfg.has("template")) {
                return buildFromTemplate(cfg.get("template").asText(), taxSep, bill, details, cfg);
            }
            // 旧版勾选配置（兼容）
            String title = cfg.has("title") ? cfg.get("title").asText() : "单据";
            String paperSize = cfg.has("paperSize") ? cfg.get("paperSize").asText() : "P76";
            int width = paperWidth(paperSize);
            StringBuilder html = new StringBuilder();
            html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
                .append("<title>").append(escHtml(title)).append("</title>")
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
                .append("<h1>").append(escHtml(title)).append("</h1>");
            html.append(buildHeader(bill, toStringList(cfg, "headerFields")));
            boolean showTax = taxSep && cfg.has("showTax") && cfg.get("showTax").asBoolean();
            html.append(buildDetailTable(details, toStringList(cfg, "detailColumns"), showTax));
            html.append(buildFooter(bill, toStringList(cfg, "footerFields"), showTax));
            boolean showSig = !cfg.has("showSignature") || cfg.get("showSignature").asBoolean();
            if (showSig) html.append("<div class=\"sign\">仓管签字:_______________</div>");
            html.append("<div class=\"sign\" style=\"margin-top:8px;\">").append(LocalDateTime.now().toString().substring(0, 16).replace("T", " ")).append("</div>");
            html.append("</body></html>");
            return html.toString();
        } catch (Exception e) {
            throw BizException.of("模板渲染失败: " + e.getMessage());
        }
    }

    // ========== 新版 {{field}} + {{#details}} 模板渲染 ==========

    /**
     * 模板格式：
     *   普通行:  key: {{fieldName}}  或  {{fieldName}}
     *   表头行: |列名1|{{field1}}|列名2|{{field2}}
     *   明细:   {{#details}} ...第一行是表头(列用|分隔).. {{/details}}
     */
    @SuppressWarnings("unchecked")
    private String buildFromTemplate(String template, boolean taxSep, Object bill, List<?> details, ObjectNode cfg) {
        String paperSize = cfg.has("paperSize") ? cfg.get("paperSize").asText() : "P76";
        int width = paperWidth(paperSize);
        String title = cfg.has("title") ? cfg.get("title").asText() : "单据";
        boolean showSig = !cfg.has("showSignature") || cfg.get("showSignature").asBoolean();

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
            .append("<title>").append(escHtml(title)).append("</title>")
            .append("<style>")
            .append("body{font-family:SimHei,Microsoft YaHei;font-size:11px;width:").append(width).append("mm;margin:0 auto;padding:2mm;}")
            .append("h1{text-align:center;font-size:14px;margin:4px 0 6px 0;border-bottom:1px solid #000;padding-bottom:2px;}")
            .append(".sec{font-size:10px;line-height:1.7;}")
            .append(".info-row{display:flex;justify-content:space-between;padding:1px 0;}")
            .append("table{width:100%;border-collapse:collapse;margin:3px 0;")
            .append("border:1px solid #333;}")
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
            // details 之前的文本 → 普通行渲染（inDetails=false）
            String head = template.substring(0, ds);
            html.append(renderBlock(head, bill, taxSep, false));
            // details 模板块
            String tpl = template.substring(ds + "{{#details}}".length(), de);
            html.append(buildTableFromTpl(tpl, details, taxSep));
            // /details 之后的文本 → 普通行渲染（inDetails=false）
            String foot = template.substring(de + "{{/details}}".length());
            html.append(renderBlock(foot, bill, taxSep, false));
        } else {
            // 无 details 块时，全部作为普通文本（inDetails=false）
            html.append(renderBlock(template, bill, taxSep, false));
        }

        html.append("</div>");
        if (showSig) html.append("<div class=\"sign\">仓管签字:_______________</div>");
        html.append("<div class=\"sign\">").append(LocalDateTime.now().toString().substring(0, 16).replace("T", " ")).append("</div>");
        html.append("</body></html>");
        return html.toString();
    }

    /**
     * 渲染普通文本块（支持 {{field}} 插值）
     * 每行必须是 | 分隔的表头行，或普通文本行（含 {{field}} 占位符）
     */
    private String renderBlock(String text, Object data, boolean taxSep, boolean inDetails) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        String[] lines = text.split("\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("===")) continue;
            if (inDetails && line.contains("|") && line.contains("{{")) {
                // 在 details 块内的 | 行是明细数据行，由 buildTableFromTpl 处理，跳过
            } else if (line.contains("|") && !line.contains("{{")) {
                // 表头行: |列1|col2| → 渲染为一行 flex
                sb.append("<div class=\"info-row\" style=\"border:1px solid #333;\">");
                String[] cells = line.split("\\|");
                for (String cell : cells) {
                    String content = cell.trim();
                    String display = renderCell(content, data, taxSep);
                    sb.append("<div style=\"flex:1;text-align:center;padding:2px;font-weight:bold;background:#f0f0f0;border-right:1px solid #333;\">").append(display).append("</div>");
                }
                sb.append("</div>");
            } else {
                // 普通行: key: {{field}} 或 {{field}}
                sb.append("<div class=\"info-row\">");
                sb.append("<div>");
                sb.append(renderLine(line, data, taxSep));
                sb.append("</div></div>");
            }
        }
        return sb.toString();
    }

    /**
     * 渲染一行中的 {{field}} 占位符
     */
    private String renderLine(String line, Object data, boolean taxSep) {
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

    /**
     * 渲染一个单元格（可能是 {{field}} 或普通文本或混合内容）
     */
    private String renderCell(String content, Object data, boolean taxSep) {
        if (content == null) return "";
        if (content.startsWith("{{") && content.endsWith("}}")) {
            String field = content.substring(2, content.length() - 2);
            return escHtml(fmtField(field, data, taxSep));
        }
        // 混合内容（如 "车间: {{workshop}}"）→ 用 renderLine 处理
        return renderLine(content, data, taxSep);
    }

    /**
     * 从明细模板构建表格。
     * 第一行（含 | 分隔）= 表头，以后每行（含 | 分隔）= 数据行模板，
     * 数据行会遍历所有 details 项重复输出。
     */
    private String buildTableFromTpl(String tpl, List<?> details, boolean taxSep) {
        List<String> headerCells = new ArrayList<>();
        List<String> dataRowTpls = new ArrayList<>();
        boolean headerRead = false;

        for (String raw : tpl.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (!line.contains("|")) continue;
            // 含 {{field}} 的行 = 数据行模板；不含的 = 表头行
            if (line.contains("{{")) {
                dataRowTpls.add(line);
            } else if (!headerRead) {
                for (String c : line.split("\\|")) headerCells.add(c.trim());
                headerRead = true;
            }
        }

        System.out.println("=== DEBUG buildTableFromTpl ===");
        System.out.println("tpl: [" + tpl + "]");
        System.out.println("details size: " + (details == null ? "null" : details.size()));
        System.out.println("headerCells: " + headerCells);
        System.out.println("dataRowTpls: " + dataRowTpls);

        StringBuilder sb = new StringBuilder();
        sb.append("<table>");

        // 表头
        if (!headerCells.isEmpty()) {
            sb.append("<thead><tr>");
            for (String h : headerCells) {
                sb.append("<th style=\"text-align:center;background:#f0f0f0;\">");
                sb.append(renderCell(h, null, taxSep));
                sb.append("</th>");
            }
            sb.append("</tr></thead>");
        }

        // 表体
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
                    for (String cell : cells) {
                        String content = cell.trim();
                        sb.append("<td style=\"text-align:right;\">");
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

    // ========== 旧版勾选配置辅助方法 ==========

    private String buildHeader(Object bill, List<String> fields) {
        StringBuilder sb = new StringBuilder();
        for (String f : fields) {
            String val = getFieldValue(bill, f);
            if (val != null && !val.isEmpty()) {
                sb.append("<div class=\"row\"><span>").append(escHtml(getFieldLabel(f))).append(": ")
                    .append(escHtml(val)).append("</span></div>");
            }
        }
        return sb.toString();
    }

    private String buildDetailTable(List<?> details, List<String> cols, boolean showTax) {
        StringBuilder sb = new StringBuilder();
        sb.append("<table><thead><tr>");
        for (String c : cols) {
            sb.append("<th class=\"right\">").append(escHtml(getFieldLabel(c))).append("</th>");
        }
        if (showTax) {
            sb.append("<th class=\"right\">税率</th><th class=\"right\">税额</th>");
        }
        sb.append("</tr></thead><tbody>");
        for (Object d : details) {
            sb.append("<tr>");
            for (String c : cols) {
                sb.append("<td class=\"right\">").append(escHtml(fmtCell(c, d))).append("</td>");
            }
            if (showTax) {
                String tr = fmtCell("taxRate", d);
                String ta = fmtCell("taxAmount", d);
                sb.append("<td class=\"right\">").append(escHtml(tr)).append("%</td>");
                sb.append("<td class=\"right\">").append(escHtml(ta)).append("</td>");
            }
            sb.append("</tr>");
        }
        sb.append("</tbody></table>");
        return sb.toString();
    }

    private String buildFooter(Object bill, List<String> fields, boolean showTax) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"hr\"></div>");
        for (String f : fields) {
            if ((f.equals("taxAmount") || f.equals("totalAmountTax")) && !showTax) continue;
            String val = getFieldValue(bill, f);
            if (val != null && !val.isEmpty() && !"0".equals(val)) {
                String label = f.equals("totalAmount") ? "不含税金额" : getFieldLabel(f);
                sb.append("<div class=\"row\"><span>").append(escHtml(label))
                    .append(":</span><span>¥").append(escHtml(fmtNum(val))).append("</span></div>");
            }
        }
        if (showTax) {
            String totalAmt = getFieldValue(bill, "totalAmount");
            sb.append("<div class=\"total\">合计金额: ¥").append(escHtml(fmtNum(totalAmt))).append("</div>");
        }
        return sb.toString();
    }

    // ========== 工具方法 ==========

    private int paperWidth(String size) {
        return switch (size) {
            case "A4" -> 210;
            case "A5" -> 148;
            case "P80" -> 80;
            case "P241" -> 241;
            default -> 76;
        };
    }

    /** 获取字段值 */
    private String getFieldValue(Object obj, String field) {
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

    /** 格式化字段值（用于旧版勾选） */
    private String fmtCell(String field, Object d) {
        String val = getFieldValue(d, field);
        if (field.equals("priceEx")) {
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
        if (field.equals("amountTax")) {
            String qty = getFieldValue(d, "qty");
            String price = getFieldValue(d, "price");
            try {
                return new BigDecimal(qty.isEmpty() ? "0" : qty).multiply(new BigDecimal(price.isEmpty() ? "0" : price)).setScale(4, RoundingMode.HALF_UP).toPlainString();
            } catch (Exception e) { return "0"; }
        }
        return fmtNum(val);
    }

    /** 格式化字段值（用于新版模板） */
    private String fmtField(String field, Object d, boolean taxSep) {
        if (field == null) return "";
        if (!taxSep && (field.equals("taxAmount") || field.equals("taxRate") || field.equals("totalAmountTax"))) return "";
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

    private String fmtNum(String val) {
        if (val == null || val.isEmpty()) return "";
        try { return new BigDecimal(val).setScale(4, RoundingMode.HALF_UP).toPlainString(); }
        catch (Exception e) { return val; }
    }

    private String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private List<String> toStringList(ObjectNode node, String field) {
        List<String> list = new ArrayList<>();
        if (node.has(field) && node.get(field).isArray()) {
            node.get(field).forEach(v -> list.add(v.asText()));
        }
        return list;
    }

    private String getFieldLabel(String field) {
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
}
