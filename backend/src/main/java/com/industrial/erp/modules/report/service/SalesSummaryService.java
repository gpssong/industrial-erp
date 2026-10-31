package com.industrial.erp.modules.report.service;

import cn.hutool.json.JSONUtil;
import com.industrial.erp.modules.report.mapper.ReportMapper;
import com.industrial.erp.security.PermissionService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 销售汇总 / 明细的<b>唯一口径出口</b> —— v1.1.144 先给 AI 助手, v1.1.145 起报表中心销售报表也走这里.
 *
 * <p>为什么只留一个 service: 同一份口径要喂四个消费者 —— ① 只读工具 (模型据此答题)、
 * ② AI 对话里的表格、③ 报表中心的明细/汇总表格、④ CSV 下载。列定义与数字格式只在这里写一次,
 * 这四个地方才不会长出四个不同的「销售额」。
 *
 * <p>口径 (与 {@code ReportMapper.salesRanking} / 工作台 KPI 完全一致):
 * 只算 {@code sal_delivery} 里 {@code bill_status IN ('CHECKED','FINISHED') AND deleted = 0}
 * 的销售出库单, 金额取<b>含税销售额</b> {@code total_amount_tax}, 时间窗走 {@code bill_date}。
 * 草稿 (DRAFT) 不算 —— 那是还没出库的量。明细按 {@code sal_delivery_detail} 出行,
 * 与汇总在 XML 里共用同一段过滤 ({@code salesDetailWhere}), 所以明细行加总 = 汇总里的销售额。
 *
 * <p>权限: 缺销售数据读权限时返回错误而不是空表 —— 空表会被模型或用户读成「这客户没卖过」,
 * 那是会拿去做生意决定的假结论。
 *
 * <p>类历史: v1.1.144 时叫 {@code modules.ai.agent.AiSalesSummaryService}; 报表中心不应反向依赖
 * AI 模块, v1.1.145 移到 report 包并改现名, AI 侧调用行为与返回 JSON 结构不变。
 */
@Service
public class SalesSummaryService {

    /** 销售数据的读权限候选 (任一即可): 出库单列表 / 报表中心 / 工作台销售排行. */
    public static final String[] DATA_PERMS = {"sales:delivery:list", "report:view", "dashboard:sales-ranking"};

    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    /** 模型常直接给「2026-09」这种月份, 展开成整月首末日 (开始月取月初, 结束月取月末). */
    private static final Pattern MONTH = Pattern.compile("^\\d{4}-\\d{2}$");
    private static final Pattern DATE_PREFIX = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern FILE_NAME_UNSAFE = Pattern.compile("[\\\\/:*?\"<>|%&#+]");

    /** public: AI 工具层 (跨包) 的默认条数从这里取, 别再自己写一份 100。 */
    public static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 300;

    /** 明细分页: 报表页默认 20 行/页, 翻页上限防一次拉穿。 */
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 200;
    /** 导出一次最多带走的明细行数 (本厂建库至今全部出库行远小于此)。 */
    static final int MAX_EXPORT_LINES = 20000;

    /** Excel 打开 UTF-8 CSV 需要 BOM, 否则中文列头乱码. */
    private static final String CSV_BOM = String.valueOf((char) 0xFEFF);

    private final ReportMapper reportMapper;
    private final PermissionService permService;

    public SalesSummaryService(ReportMapper reportMapper, PermissionService permService) {
        this.reportMapper = reportMapper;
        this.permService = permService;
    }

    /** AI 助手用: 只认客户名关键字, 没有客户 id 概念。 */
    public Table query(String rawStart, String rawEnd, String customer, String groupBy, Integer rawLimit) {
        return query(rawStart, rawEnd, null, customer, groupBy, rawLimit);
    }

    /**
     * 汇总查询 + 组装表格。参数非法/无权限不抛异常, 统一放进 {@link Table#error}。
     *
     * @param customerId 客户 ID 精确过滤 (报表页下拉); 可与客户名关键字同时给 (取交集)
     */
    public Table query(String rawStart, String rawEnd, Long customerId, String customer,
                       String groupBy, Integer rawLimit) {
        String start = normalizeDate(rawStart, true);
        String end = normalizeDate(rawEnd, false);
        if (start == null || end == null) {
            return Table.fail("startDate 和 endDate 必填且格式为 YYYY-MM-DD (也接受 YYYY-MM, 会展开成整月), "
                    + "收到的值: startDate=" + rawStart + ", endDate=" + rawEnd);
        }
        if (start.compareTo(end) > 0) {
            return Table.fail("开始日期 " + start + " 晚于结束日期 " + end + ", 请修正后重新查询");
        }
        int limit = rawLimit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(rawLimit, MAX_LIMIT));
        String kw = trimToEmpty(customer);
        boolean byMonth = "month".equalsIgnoreCase(trimToEmpty(groupBy));

        if (!permService.hasAnyPerm(DATA_PERMS)) {
            return Table.fail(noPermMsg());
        }

        List<Map<String, Object>> raw = reportMapper.aiSalesSummary(
                start, end, customerId, kw.isEmpty() ? null : kw, byMonth ? "month" : "customer", limit);
        if (raw == null) raw = new ArrayList<>();

        Table t = new Table();
        t.byMonth = byMonth;
        t.title = buildTitle(kw, start, end, byMonth ? "销售汇总 (按月)" : "销售汇总");
        t.fileName = buildFileName("销售汇总", kw, start, end);
        t.columns = buildSummaryColumns(byMonth);
        t.rows = new ArrayList<>(raw.size());
        long billCount = 0;
        BigDecimal qty = BigDecimal.ZERO;
        BigDecimal amount = BigDecimal.ZERO;
        for (Map<String, Object> r : raw) {
            Map<String, Object> row = new LinkedHashMap<>();
            if (byMonth) row.put("period", str(r.get("period")));
            row.put("customerName", str(r.get("customerName")));
            row.put("billCount", toLong(r.get("billCount")));
            row.put("totalQty", plainQty(toDecimal(r.get("totalQty"))));
            row.put("totalAmount", money(r.get("totalAmount")));
            t.rows.add(row);
            billCount += toLong(r.get("billCount"));
            qty = qty.add(toDecimal(r.get("totalQty")));
            amount = amount.add(toDecimal(r.get("totalAmount")));
        }
        t.count = t.rows.size();
        t.truncated = t.rows.size() >= limit;
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("customerName", "合计");
        totals.put("billCount", billCount);
        totals.put("totalQty", plainQty(qty));
        totals.put("totalAmount", amount.setScale(2, RoundingMode.HALF_UP).toPlainString());
        t.totals = totals;
        t.note = "口径: 已审核/完成的销售出库单, 金额=单据头的含税销售额 (与销售看板/客户排行同一口径), 不含草稿单。";
        t.params = baseParams(start, end, customerId, kw);
        t.params.put("groupBy", byMonth ? "month" : "customer");
        t.params.put("limit", String.valueOf(limit));
        if (t.rows.isEmpty()) {
            t.hint = "没查到出库记录。可能是: 1) 客户名关键字不对 (可先调 listCustomers 核对); "
                    + "2) 该时间段只有草稿单 (草稿不计入汇总); 3) 时间段选错。已查范围 "
                    + start + " ~ " + end + (kw.isEmpty() ? "" : ", 客户含「" + kw + "」");
        }
        return t;
    }

    /** 报表中心「销售明细」一页 + 全查询范围的合计行。 */
    public Table detail(String rawStart, String rawEnd, Long customerId, String customer,
                        String product, Integer page, Integer pageSize) {
        int size = pageSize == null ? DEFAULT_PAGE_SIZE : Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
        return doDetail(rawStart, rawEnd, customerId, customer, product,
                page == null ? 1 : Math.max(1, page), size);
    }

    /**
     * 报表中心「导出全部明细」: 一次取满 {@link #MAX_EXPORT_LINES} 行, 不分页。
     * 导出必须回服务端重查 —— 把屏幕上那一页导回去, 用户拿到的是一份缺数据的报表。
     */
    public Table detailForExport(String rawStart, String rawEnd, Long customerId, String customer, String product) {
        return doDetail(rawStart, rawEnd, customerId, customer, product, 1, MAX_EXPORT_LINES);
    }

    private Table doDetail(String rawStart, String rawEnd, Long customerId, String customer,
                           String product, int page, int size) {
        String start = normalizeDate(rawStart, true);
        String end = normalizeDate(rawEnd, false);
        if (start == null || end == null) {
            return Table.fail("startDate 和 endDate 必填且格式为 YYYY-MM-DD (也接受 YYYY-MM, 会展开成整月), "
                    + "收到的值: startDate=" + rawStart + ", endDate=" + rawEnd);
        }
        if (start.compareTo(end) > 0) {
            return Table.fail("开始日期 " + start + " 晚于结束日期 " + end + ", 请修正后重新查询");
        }
        String kw = trimToEmpty(customer);
        String pd = trimToEmpty(product);

        if (!permService.hasAnyPerm(DATA_PERMS)) {
            return Table.fail(noPermMsg());
        }

        Map<String, Object> agg = reportMapper.salesDetailTotals(
                start, end, customerId, kw.isEmpty() ? null : kw, pd.isEmpty() ? null : pd);
        long totalLines = toLong(agg == null ? null : agg.get("lineCount"));
        // 先数行数再取页: 越界页不查库 (offset 为负会让 SQL 直接报错)
        List<Map<String, Object>> raw = totalLines == 0L || (long) (page - 1) * size >= totalLines
                ? new ArrayList<>()
                : reportMapper.salesDetailLines(start, end, customerId, kw.isEmpty() ? null : kw,
                        pd.isEmpty() ? null : pd, (int) ((long) (page - 1) * size), size);
        if (raw == null) raw = new ArrayList<>();

        Table t = new Table();
        t.title = buildTitle(kw, start, end, "销售明细");
        t.fileName = buildFileName("销售明细", kw, start, end);
        t.columns = buildDetailColumns();
        t.rows = new ArrayList<>(raw.size());
        for (Map<String, Object> r : raw) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("billDate", str(r.get("billDate")));
            row.put("billNo", str(r.get("billNo")));
            row.put("customerName", str(r.get("customerName")));
            row.put("salesmanName", str(r.get("salesmanName")));
            row.put("productCode", str(r.get("productCode")));
            row.put("productName", str(r.get("productName")));
            row.put("spec", str(r.get("spec")));
            row.put("unitName", str(r.get("unitName")));
            row.put("qty", plainQty(toDecimal(r.get("qty"))));
            row.put("price", money(r.get("price")));
            row.put("amount", money(r.get("amount")));
            row.put("taxAmount", money(r.get("taxAmount")));
            row.put("amountTax", money(r.get("amountTax")));
            row.put("costAmount", money(r.get("costAmount")));
            row.put("batchNo", str(r.get("batchNo")));
            t.rows.add(row);
        }
        t.count = t.rows.size();
        t.page = page;
        t.pageSize = size;
        t.totalRows = totalLines;
        t.truncated = totalLines > t.rows.size();

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("customerName", "合计");
        totals.put("billNo", agg == null ? "" : str(agg.get("billCount")) + " 张单");
        totals.put("qty", plainQty(toDecimal(agg == null ? null : agg.get("totalQty"))));
        totals.put("amount", money(agg == null ? null : agg.get("totalAmount")));
        totals.put("taxAmount", money(agg == null ? null : agg.get("totalTaxAmount")));
        totals.put("amountTax", money(agg == null ? null : agg.get("totalAmountTax")));
        totals.put("costAmount", money(agg == null ? null : agg.get("totalCost")));
        t.totals = totals;
        t.note = "口径: 已审核/完成的销售出库单明细行, 金额=行价税合计(含税)加总, 不含草稿单; 合计行是整个查询范围的数, 不是当前页。"
                + "明细合计与下方销售汇总可能不一致 —— 汇总取单据头金额, 明细按行加总; 对不上说明有出库单的单据头金额与明细行不符, 需核对该单。";
        t.params = baseParams(start, end, customerId, kw);
        t.params.put("product", pd);
        t.params.put("page", String.valueOf(page));
        t.params.put("pageSize", String.valueOf(size));
        if (totalLines == 0L) {
            t.hint = "没查到出库明细。可能是: 1) 时间段选错; 2) 该时间段只有草稿单; 3) 客户/商品关键字不匹配。已查范围 "
                    + start + " ~ " + end + (kw.isEmpty() ? "" : ", 客户含「" + kw + "」")
                    + (pd.isEmpty() ? "" : ", 商品含「" + pd + "」");
        }
        return t;
    }

    /** 生成喂给模型的 JSON。有 error 就只回错误, 免得模型把空表当"真没卖". */
    public String toToolJson(Table t) {
        if (t == null) return "{\"error\":\"查询失败\"}";
        if (t.error != null) return new cn.hutool.json.JSONObject().set("error", t.error).toString();
        return JSONUtil.toJsonStr(t);
    }

    /**
     * CSV (Excel 可直接打开)。前置 BOM, 否则中文列头在 Excel 里是乱码 ——
     * 与 PC 端 Hub/Maintenance 导出同款处理。
     */
    public String toCsv(Table t) {
        StringBuilder sb = new StringBuilder(CSV_BOM);
        List<Column> cols = t.columns == null ? new ArrayList<>() : t.columns;
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(csvCell(cols.get(i).label));
        }
        sb.append('\n');
        if (t.rows != null) {
            for (Map<String, Object> row : t.rows) sb.append(csvRow(cols, row)).append('\n');
        }
        if (t.totals != null && t.rows != null && !t.rows.isEmpty()) {
            sb.append(csvRow(cols, t.totals)).append('\n');
        }
        if (t.note != null) sb.append(csvCell(t.note)).append('\n');
        return sb.toString();
    }

    private static String csvRow(List<Column> cols, Map<String, Object> row) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(csvCell(row.get(cols.get(i).key)));
        }
        return sb.toString();
    }

    /** 逗号/引号/换行要包起来, 内部引号翻倍 (RFC 4180). */
    public static String csvCell(Object v) {
        String s = v == null ? "" : String.valueOf(v);
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    private static String noPermMsg() {
        return "当前账号没有销售数据的查看权限 (需要 sales:delivery:list / report:view / "
                + "dashboard:sales-ranking 任一), 请让管理员在角色权限里勾选后重试";
    }

    private static List<Column> buildSummaryColumns(boolean byMonth) {
        List<Column> cols = new ArrayList<>();
        if (byMonth) cols.add(new Column("period", "月份"));
        cols.add(new Column("customerName", "客户"));
        cols.add(new Column("billCount", "出库单数"));
        cols.add(new Column("totalQty", "数量"));
        cols.add(new Column("totalAmount", "销售额(元)"));
        return cols;
    }

    private static List<Column> buildDetailColumns() {
        List<Column> cols = new ArrayList<>();
        cols.add(new Column("billDate", "日期"));
        cols.add(new Column("billNo", "出库单号"));
        cols.add(new Column("customerName", "客户"));
        cols.add(new Column("productName", "商品名称"));
        cols.add(new Column("spec", "规格"));
        cols.add(new Column("unitName", "单位"));
        cols.add(new Column("qty", "数量"));
        cols.add(new Column("price", "单价(元)"));
        cols.add(new Column("amount", "金额(未税)"));
        cols.add(new Column("taxAmount", "税额"));
        cols.add(new Column("amountTax", "金额(含税)"));
        cols.add(new Column("costAmount", "成本"));
        cols.add(new Column("productCode", "商品编码"));
        cols.add(new Column("salesmanName", "业务员"));
        cols.add(new Column("batchNo", "批次号"));
        return cols;
    }

    private static String buildTitle(String kw, String start, String end, String what) {
        return (kw.isEmpty() ? "各客户" : "客户「" + kw + "」") + " " + start + " ~ " + end + " " + what;
    }

    private static String buildFileName(String what, String kw, String start, String end) {
        String name = what + (kw.isEmpty() ? "" : "_" + kw)
                + "_" + start.replace("-", "") + "-" + end.replace("-", "");
        return FILE_NAME_UNSAFE.matcher(name).replaceAll("_");
    }

    private static Map<String, String> baseParams(String start, String end, Long customerId, String kw) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("startDate", start);
        p.put("endDate", end);
        if (customerId != null) p.put("customerId", String.valueOf(customerId));
        p.put("customer", kw);
        return p;
    }

    /**
     * YYYY-MM-DD 原样; YYYY-MM 按 {@code monthStart} 取月初或月末; 带时间的取前 10 位。
     * 其余返回 null 由调用方报错。
     */
    public static String normalizeDate(String raw, boolean monthStart) {
        if (raw == null) return null;
        String v = raw.trim();
        if (DATE.matcher(v).matches()) return v;
        if (MONTH.matcher(v).matches()) {
            try {
                YearMonth ym = YearMonth.parse(v);
                return (monthStart ? ym.atDay(1) : ym.atEndOfMonth()).toString();
            } catch (DateTimeParseException e) {
                return null;
            }
        }
        if (DATE_PREFIX.matcher(v).find()) return v.substring(0, 10);
        return null;
    }

    private static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static long toLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static BigDecimal toDecimal(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal) return (BigDecimal) v;
        try {
            return new BigDecimal(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /** 数量: 去掉库里补的 0 尾数 (193800.0000 → 193800). */
    private static String plainQty(BigDecimal qty) {
        BigDecimal b = qty.stripTrailingZeros();
        if (b.scale() < 0) b = b.setScale(0);
        return b.toPlainString();
    }

    private static String money(Object v) {
        return toDecimal(v).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    // ===== 结构 =====

    /**
     * 一张可在前端展示 / 下载成 CSV 的表。字段即 JSON 键, 全给 getter 让 hutool 与 Jackson 都能序列化。
     * {@code page/pageSize/totalRows} 只有明细用得到, 汇总时为 null。
     */
    public static class Table {
        public String error;
        public String title;
        public String fileName;
        public boolean byMonth;
        public List<Column> columns = new ArrayList<>();
        public List<Map<String, Object>> rows = new ArrayList<>();
        public Map<String, Object> totals = new LinkedHashMap<>();
        public Map<String, String> params = new LinkedHashMap<>();
        public int count;
        public boolean truncated;
        public String note;
        public String hint;
        public Integer page;
        public Integer pageSize;
        public Long totalRows;

        static Table fail(String message) {
            Table t = new Table();
            t.error = message;
            return t;
        }

        public String getError() { return error; }
        public String getTitle() { return title; }
        public String getFileName() { return fileName; }
        public boolean isByMonth() { return byMonth; }
        public List<Column> getColumns() { return columns; }
        public List<Map<String, Object>> getRows() { return rows; }
        public Map<String, Object> getTotals() { return totals; }
        public Map<String, String> getParams() { return params; }
        public int getCount() { return count; }
        public boolean isTruncated() { return truncated; }
        public String getNote() { return note; }
        public String getHint() { return hint; }
        public Integer getPage() { return page; }
        public Integer getPageSize() { return pageSize; }
        public Long getTotalRows() { return totalRows; }
    }

    public static class Column {
        public String key;
        public String label;

        Column(String key, String label) {
            this.key = key;
            this.label = label;
        }

        public String getKey() { return key; }
        public String getLabel() { return label; }
    }
}
