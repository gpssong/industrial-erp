package com.industrial.erp.modules.report.service;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.industrial.erp.modules.report.mapper.ReportMapper;
import com.industrial.erp.security.PermissionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v1.1.144 — AI 助手「按客户/时间段查销售汇总」的口径与产出.
 *
 * <p>这份 service 是工具 JSON、对话里那张表、下载 CSV 三处的唯一算法出口, 所以测三件事:
 * ① 参数怎么展开 (模型常直接丢「2026-09」); ② 数字怎么格式化 (合计必须和明细对得上);
 * ③ CSV 在 Excel 里打开不能乱码、含逗号的商品/客户名不能把列冲开。
 * fixture 用飞牛库里的真数 (长晟塑业 2026-09 = 3 单 / 193800 / 14622.00), 与 canary 端到端实测对齐。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SalesSummaryService 销售汇总口径")
class SalesSummaryServiceTest {

    @Mock private ReportMapper reportMapper;
    @Mock private PermissionService permService;

    @InjectMocks private SalesSummaryService service;

    private void allow() {
        when(permService.hasAnyPerm(SalesSummaryService.DATA_PERMS)).thenReturn(true);
    }

    private static Map<String, Object> row(String customer, int bills, String qty, String amount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("customerName", customer);
        m.put("billCount", bills);
        m.put("totalQty", new BigDecimal(qty));
        m.put("totalAmount", new BigDecimal(amount));
        return m;
    }

    // ===== 日期展开 =====

    @Test
    @DisplayName("YYYY-MM 按起止各展开成月初/月末; 带时间的取前 10 位; 非法一律 null")
    void normalizeDate_shouldExpandMonthAndTrimTime() {
        assertThat(SalesSummaryService.normalizeDate("2026-09", true)).isEqualTo("2026-09-01");
        assertThat(SalesSummaryService.normalizeDate("2026-09", false)).isEqualTo("2026-09-30");
        assertThat(SalesSummaryService.normalizeDate("2026-02", false)).isEqualTo("2026-02-28");
        assertThat(SalesSummaryService.normalizeDate("2026-09-15 08:30:00", true)).isEqualTo("2026-09-15");
        assertThat(SalesSummaryService.normalizeDate("2026-09-01", true)).isEqualTo("2026-09-01");
        assertThat(SalesSummaryService.normalizeDate("9月", true)).isNull();
        assertThat(SalesSummaryService.normalizeDate("2026-13", true)).isNull();
        assertThat(SalesSummaryService.normalizeDate(null, true)).isNull();
    }

    @Test
    @DisplayName("日期非法 / 起止写反 → 只回错误, 不打库")
    void query_shouldFailFastOnBadDates() {
        SalesSummaryService.Table a = service.query("", "2026-09-30", "长晟", null, null);
        assertThat(a.error).contains("必填");

        SalesSummaryService.Table b = service.query("2026-09-30", "2026-09-01", "长晟", null, null);
        assertThat(b.error).contains("晚于");

        verify(reportMapper, never()).aiSalesSummary(any(), any(), any(), any(), any(), anyInt());
    }

    // ===== 权限 =====

    @Test
    @DisplayName("没有销售数据读权限 → 明确报错, 不能回空表 (空表会被模型当成「没卖过」)")
    void query_shouldRejectWithoutSalesPerm() {
        when(permService.hasAnyPerm(SalesSummaryService.DATA_PERMS)).thenReturn(false);

        SalesSummaryService.Table t = service.query("2026-09-01", "2026-09-30", "长晟", null, null);

        assertThat(t.error).contains("权限");
        verify(reportMapper, never()).aiSalesSummary(any(), any(), any(), any(), any(), anyInt());
    }

    // ===== 参数 → SQL =====

    @Test
    @DisplayName("模型传「2026-09」也能落成整月区间; 空客户名传 null (不参与 LIKE); limit 缺省 100")
    void query_shouldNormalizeParamsForMapper() {
        allow();
        when(reportMapper.aiSalesSummary(any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(Collections.emptyList());

        service.query("2026-09", "2026-09", "  ", null, null);

        verify(reportMapper).aiSalesSummary(eq("2026-09-01"), eq("2026-09-30"), isNull(), isNull(), eq("customer"), eq(100));
    }

    @Test
    @DisplayName("limit 钳进 [1, 300], groupBy=month 才走按月分组")
    void query_shouldClampLimitAndMapGroupBy() {
        allow();
        when(reportMapper.aiSalesSummary(any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(Collections.emptyList());

        service.query("2026-01-01", "2026-12-31", null, "MONTH", 5000);
        ArgumentCaptor<Integer> big = ArgumentCaptor.forClass(Integer.class);
        verify(reportMapper).aiSalesSummary(any(), any(), any(), any(), eq("month"), big.capture());
        assertThat(big.getValue()).isEqualTo(300);

        service.query("2026-01-01", "2026-12-31", null, null, 0);
        ArgumentCaptor<Integer> zero = ArgumentCaptor.forClass(Integer.class);
        verify(reportMapper).aiSalesSummary(any(), any(), any(), any(), eq("customer"), zero.capture());
        assertThat(zero.getValue()).isEqualTo(1);
    }

    // ===== 表格组装 =====

    @Test
    @DisplayName("明细 + 合计: 数量去掉补的 0 尾数, 金额固定两位, 合计等于明细之和")
    void query_shouldBuildRowsAndTotals() {
        allow();
        List<Map<String, Object>> raw = new ArrayList<>();
        raw.add(row("长晟塑业", 3, "193800.0000", "14622"));
        raw.add(row("舟山鹏程", 1, "500.50", "800.005"));
        when(reportMapper.aiSalesSummary(any(), any(), any(), any(), any(), anyInt())).thenReturn(raw);

        SalesSummaryService.Table t = service.query("2026-09-01", "2026-09-30", "长晟", null, null);

        assertThat(t.error).isNull();
        assertThat(t.count).isEqualTo(2);
        assertThat(t.truncated).isFalse();
        Map<String, Object> first = t.rows.get(0);
        assertThat(first.get("customerName")).isEqualTo("长晟塑业");
        assertThat(first.get("billCount")).isEqualTo(3L);
        assertThat(first.get("totalQty")).isEqualTo("193800");
        assertThat(first.get("totalAmount")).isEqualTo("14622.00");
        assertThat(t.totals.get("totalQty")).isEqualTo("194300.5");
        assertThat(t.totals.get("totalAmount")).isEqualTo("15422.01");
        assertThat(t.title).contains("客户「长晟」").contains("2026-09-01 ~ 2026-09-30");
        assertThat(t.fileName).startsWith("销售汇总_长晟_");
        assertThat(t.note).contains("含税");
        assertThat(t.hint).isNull();
    }

    @Test
    @DisplayName("按月分组时首列是「月份」, 标题带 (按月)")
    void query_shouldPrependPeriodColumnWhenGroupByMonth() {
        allow();
        Map<String, Object> m = row("长晟塑业", 3, "193800", "14622");
        m.put("period", "2026-09");
        when(reportMapper.aiSalesSummary(any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(Collections.singletonList(m));

        SalesSummaryService.Table t = service.query("2026-01-01", "2026-12-31", "长晟", "month", null);

        assertThat(t.byMonth).isTrue();
        assertThat(t.columns.get(0).key).isEqualTo("period");
        assertThat(t.columns.get(0).label).isEqualTo("月份");
        assertThat(t.title).contains("(按月)");
        assertThat(t.rows.get(0).get("period")).isEqualTo("2026-09");
    }

    @Test
    @DisplayName("行数顶到 limit → truncated=true, 提示用户可缩小范围")
    void query_shouldFlagTruncated() {
        allow();
        List<Map<String, Object>> raw = new ArrayList<>();
        for (int i = 0; i < 2; i++) raw.add(row("客户" + i, 1, "1", "1"));
        when(reportMapper.aiSalesSummary(any(), any(), any(), any(), any(), anyInt())).thenReturn(raw);

        SalesSummaryService.Table t = service.query("2026-09-01", "2026-09-30", null, null, 2);

        assertThat(t.truncated).isTrue();
    }

    @Test
    @DisplayName("空结果给下一步提示 (核对客户名 / 草稿单不计入 / 时间段), 而不是一张空表")
    void query_shouldGiveHintWhenEmpty() {
        allow();
        when(reportMapper.aiSalesSummary(any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(Collections.emptyList());

        SalesSummaryService.Table t = service.query("2026-09-01", "2026-09-30", "长晟", null, null);

        assertThat(t.rows).isEmpty();
        assertThat(t.hint).contains("listCustomers").contains("草稿").contains("长晟");
    }

    // ===== 喂模型的 JSON =====

    @Test
    @DisplayName("有 error 时工具 JSON 只带 error, 不带空 rows (免得模型把「查不到」当「没卖过」)")
    void toToolJson_shouldOnlyCarryError() {
        when(permService.hasAnyPerm(SalesSummaryService.DATA_PERMS)).thenReturn(false);
        SalesSummaryService.Table t = service.query("2026-09-01", "2026-09-30", null, null, null);

        String json = service.toToolJson(t);

        assertThat(JSONUtil.parseObj(json).getStr("error")).contains("权限");
        assertThat(json).doesNotContain("rows");
    }

    @Test
    @DisplayName("正常表格的 JSON 里 rows/totals/columns/params 齐全, 前端直接渲染")
    void toToolJson_shouldSerializeTable() {
        allow();
        when(reportMapper.aiSalesSummary(any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(Collections.singletonList(row("长晟塑业", 3, "193800", "14622")));

        SalesSummaryService.Table t = service.query("2026-09", "2026-09", "长晟", null, null);
        JSONObject o = JSONUtil.parseObj(service.toToolJson(t));

        assertThat(o.getStr("title")).contains("销售汇总");
        assertThat(o.getJSONArray("rows")).hasSize(1);
        assertThat(o.getJSONObject("totals").getStr("totalAmount")).isEqualTo("14622.00");
        assertThat(o.getJSONObject("params").getStr("startDate")).isEqualTo("2026-09-01");
    }

    // ===== CSV =====

    @Test
    @DisplayName("CSV: BOM 开头 (Excel 不乱码) + 表头 + 明细 + 合计 + 口径说明")
    void toCsv_shouldHaveBomHeaderRowsTotalsNote() {
        allow();
        List<Map<String, Object>> raw = new ArrayList<>();
        raw.add(row("长晟塑业", 3, "193800", "14622"));
        raw.add(row("其它客户", 1, "10", "20"));
        when(reportMapper.aiSalesSummary(any(), any(), any(), any(), any(), anyInt())).thenReturn(raw);
        SalesSummaryService.Table t = service.query("2026-09-01", "2026-09-30", null, null, null);

        String csv = service.toCsv(t);
        // BOM 是 toCsv 自己加的 (Excel 认中文列头), 拆行时先去掉, 否则第一个表头带不上干净字符串
        assertThat(csv.charAt(0)).isEqualTo((char) 0xFEFF);
        String[] lines = csv.substring(1).split("\n");
        assertThat(lines[0]).isEqualTo("客户,出库单数,数量,销售额(元)");
        assertThat(lines[1]).isEqualTo("长晟塑业,3,193800,14622.00");
        assertThat(lines[3]).isEqualTo("合计,4,193810,14642.00");
        assertThat(lines[4]).contains("含税");
    }

    @Test
    @DisplayName("客户名里的逗号/引号按 RFC4180 包起来, 不能把列冲开")
    void toCsv_shouldQuoteUnsafeCells() {
        allow();
        when(reportMapper.aiSalesSummary(any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(Collections.singletonList(row("A,B \"C\"", 1, "1", "1")));
        SalesSummaryService.Table t = service.query("2026-09-01", "2026-09-30", null, null, null);

        String csv = service.toCsv(t);

        assertThat(csv).contains("\"A,B \"\"C\"\"\",1,1,1.00");
    }

    @Test
    @DisplayName("csvCell: 只有含逗号/引号/换行才加引号, 普通数字保持裸值 (Excel 里才是数字列)")
    void csvCell_shouldLeavePlainValues() {
        assertThat(SalesSummaryService.csvCell("14622.00")).isEqualTo("14622.00");
        assertThat(SalesSummaryService.csvCell("a,b")).isEqualTo("\"a,b\"");
        assertThat(SalesSummaryService.csvCell(null)).isEmpty();
    }

    @Test
    @DisplayName("客户名做进文件名前先洗掉路径/查询危险字符")
    void query_shouldSanitizeFileName() {
        allow();
        when(reportMapper.aiSalesSummary(any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(Collections.singletonList(row("长晟", 1, "1", "1")));

        SalesSummaryService.Table t = service.query("2026-09-01", "2026-09-30", "长晟/..?*|", null, null);

        assertThat(t.fileName).doesNotContain("/").doesNotContain("*").doesNotContain("?");
    }

    // ===================== v1.1.145 报表中心「销售明细」 =====================

    private static Map<String, Object> agg(long lineCount, int billCount, String qty, String amountTax) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lineCount", lineCount);
        m.put("billCount", billCount);
        m.put("customerCount", 1);
        m.put("totalQty", new BigDecimal(qty));
        m.put("totalAmount", new BigDecimal(amountTax));
        m.put("totalTaxAmount", BigDecimal.ZERO);
        m.put("totalAmountTax", new BigDecimal(amountTax));
        m.put("totalCost", BigDecimal.ZERO);
        return m;
    }

    private static Map<String, Object> line(String billNo, String product, String qty, String amountTax) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("billDate", "2026-09-15");
        m.put("billNo", billNo);
        m.put("customerName", "长晟塑业");
        m.put("productName", product);
        m.put("spec", "30*40");
        m.put("unitName", "只");
        m.put("qty", new BigDecimal(qty));
        m.put("price", new BigDecimal("0.08"));
        m.put("amount", new BigDecimal(amountTax));
        m.put("taxAmount", BigDecimal.ZERO);
        m.put("amountTax", new BigDecimal(amountTax));
        m.put("costAmount", new BigDecimal("100.00"));
        m.put("productCode", "P001");
        m.put("salesmanName", "宋");
        m.put("batchNo", "B20260915");
        return m;
    }

    @Test
    @DisplayName("明细: 第 3 页 × 20 行 → SQL offset=40 (页码不能只靠前端切片)")
    void detail_shouldOffsetByPage() {
        allow();
        when(reportMapper.salesDetailTotals(any(), any(), any(), any(), any())).thenReturn(agg(50L, 12, "0", "0"));
        when(reportMapper.salesDetailLines(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());

        service.detail("2026-09-01", "2026-09-30", null, null, null, 3, 20);

        verify(reportMapper).salesDetailLines(eq("2026-09-01"), eq("2026-09-30"), isNull(), isNull(), isNull(),
                eq(40), eq(20));
    }

    @Test
    @DisplayName("明细: 客户 ID 精确过滤 + 商品关键字一起透传给 SQL")
    void detail_shouldPassCustomerIdAndProduct() {
        allow();
        when(reportMapper.salesDetailTotals(any(), any(), any(), any(), any())).thenReturn(agg(0L, 0, "0", "0"));

        service.detail("2026-09", "2026-09", 123L, "长晟", "胶带", 1, 50);

        verify(reportMapper).salesDetailTotals(eq("2026-09-01"), eq("2026-09-30"), eq(123L), eq("长晟"), eq("胶带"));
        verify(reportMapper, never()).salesDetailLines(any(), any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("明细: 合计取整范围聚合, 不是当前页 rows 累加")
    void detail_totalsComeFromAggregateNotPageRows() {
        allow();
        when(reportMapper.salesDetailTotals(any(), any(), any(), any(), any())).thenReturn(agg(200L, 30, "999999", "88888.88"));
        when(reportMapper.salesDetailLines(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Collections.singletonList(line("XS001", "透明胶带", "100", "8.00")));

        SalesSummaryService.Table t = service.detail("2026-09-01", "2026-09-30", null, null, null, 1, 20);

        assertThat(t.totals.get("amountTax")).isEqualTo("88888.88");
        assertThat(t.totals.get("qty")).isEqualTo("999999");
        assertThat(t.totalRows).isEqualTo(200L);
        assertThat(t.rows).hasSize(1);
        assertThat(t.truncated).isTrue();
    }

    @Test
    @DisplayName("明细: 越界页不查库 (offset 超行数直接空页, 合计仍然带回来)")
    void detail_shouldSkipQueryWhenPageBeyondRange() {
        allow();
        when(reportMapper.salesDetailTotals(any(), any(), any(), any(), any())).thenReturn(agg(5L, 2, "10", "10"));

        SalesSummaryService.Table t = service.detail("2026-09-01", "2026-09-30", null, null, null, 9, 20);

        verify(reportMapper, never()).salesDetailLines(any(), any(), any(), any(), any(), anyInt(), anyInt());
        assertThat(t.rows).isEmpty();
        assertThat(t.totals.get("amountTax")).isEqualTo("10.00");
        assertThat(t.page).isEqualTo(9);
    }

    @Test
    @DisplayName("明细: pageSize 夹在 [1,200], 缺省 20 (防前端传 99999 拖库)")
    void detail_shouldClampPageSize() {
        allow();
        when(reportMapper.salesDetailTotals(any(), any(), any(), any(), any())).thenReturn(agg(10L, 1, "1", "1"));
        when(reportMapper.salesDetailLines(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());

        service.detail("2026-09-01", "2026-09-30", null, null, null, 1, 99999);
        ArgumentCaptor<Integer> big = ArgumentCaptor.forClass(Integer.class);
        verify(reportMapper).salesDetailLines(any(), any(), any(), any(), any(), anyInt(), big.capture());
        assertThat(big.getValue()).isEqualTo(SalesSummaryService.MAX_PAGE_SIZE);

        Mockito.clearInvocations(reportMapper);
        service.detail("2026-09-01", "2026-09-30", null, null, null, null, null);
        verify(reportMapper).salesDetailLines(any(), any(), any(), any(), any(), eq(0),
                eq(SalesSummaryService.DEFAULT_PAGE_SIZE));
    }

    @Test
    @DisplayName("明细导出: 一次取满上限行, 不分页 (导出一份缺数据的报表比不导更糟)")
    void detailForExport_shouldTakeWholeRange() {
        allow();
        when(reportMapper.salesDetailTotals(any(), any(), any(), any(), any())).thenReturn(agg(3L, 2, "30", "30"));
        when(reportMapper.salesDetailLines(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Collections.singletonList(line("XS001", "透明胶带", "10", "10")));

        SalesSummaryService.Table t = service.detailForExport("2026-09-01", "2026-09-30", null, "长晟", null);

        verify(reportMapper).salesDetailLines(eq("2026-09-01"), eq("2026-09-30"), isNull(), eq("长晟"), isNull(),
                eq(0), eq(SalesSummaryService.MAX_EXPORT_LINES));
        assertThat(t.fileName).startsWith("销售明细_长晟_");
    }

    @Test
    @DisplayName("明细: 没有销售数据读权限 → 报错且不碰数据库 (空表会被当成「没卖过」)")
    void detail_shouldFailFastWithoutPerm() {
        when(permService.hasAnyPerm(SalesSummaryService.DATA_PERMS)).thenReturn(false);

        SalesSummaryService.Table t = service.detail("2026-09-01", "2026-09-30", null, null, null, 1, 20);

        assertThat(t.error).contains("权限");
        verify(reportMapper, never()).salesDetailTotals(any(), any(), any(), any(), any());
        verify(reportMapper, never()).salesDetailLines(any(), any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("明细: 日期非法 / 开始晚于结束 → 报错且不查库")
    void detail_shouldRejectBadDates() {
        allow();
        assertThat(service.detail("", "2026-09-30", null, null, null, 1, 20).error).contains("startDate");
        assertThat(service.detail("2026-09-30", "2026-09-01", null, null, null, 1, 20).error).contains("晚于");
        verify(reportMapper, never()).salesDetailTotals(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("明细 CSV: 表头取列 label, 数字用表格里的字符串不再二次格式化")
    void detailCsv_shouldUseLabels() {
        allow();
        when(reportMapper.salesDetailTotals(any(), any(), any(), any(), any())).thenReturn(agg(1L, 1, "10", "10"));
        when(reportMapper.salesDetailLines(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Collections.singletonList(line("XS001", "透明胶带, 500米", "10", "9.5")));

        SalesSummaryService.Table t = service.detail("2026-09-01", "2026-09-30", null, null, null, 1, 20);
        String csv = service.toCsv(t);

        assertThat(csv.charAt(0)).isEqualTo((char) 0xFEFF);
        String[] rows = csv.substring(1).split("\n");
        assertThat(rows[0]).startsWith("日期,出库单号,客户,商品名称,规格,单位,数量,单价(元),金额(未税),税额,金额(含税)");
        assertThat(rows[1]).contains("\"透明胶带, 500米\"");
        assertThat(rows[1]).contains("9.50");
        assertThat(rows[2]).contains("合计");
        assertThat(rows[2]).contains("10.00");
    }
}
