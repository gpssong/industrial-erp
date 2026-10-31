package com.industrial.erp.modules.ai.agent;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.modules.ai.client.LlmClient;
import com.industrial.erp.modules.base.entity.BaseWarehouse;
import com.industrial.erp.modules.base.mapper.BaseCustomerMapper;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.industrial.erp.modules.base.mapper.BaseSupplierMapper;
import com.industrial.erp.modules.base.mapper.BaseWarehouseMapper;
import com.industrial.erp.modules.inventory.mapper.InvStockPageQueryMapper;
import com.industrial.erp.modules.production.entity.PrdFinishedIn;
import com.industrial.erp.modules.production.service.PrdFinishedInService;
import com.industrial.erp.modules.report.service.SalesSummaryService;
import com.industrial.erp.modules.sales.service.SalDeliveryService;
import com.industrial.erp.modules.sales.service.SalOrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v1.1.143 — AI 助手「成品入账」写提议所依赖的两个只读工具.
 *
 * <p>模型必须先拿到真实 warehouseId / 入库单 id 才能提 addPrdFinishedIn / checkPrdFinishedIn,
 * 所以这两个工具既要在 {@code tools()} 里, 也要在按能力组裁剪的 {@code toolsEnabled()} 里,
 * 否则开了写能力的站点会拿不到 id 只能臆造。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ErpToolRegistry 成品入库只读工具")
class ErpToolRegistryTest {

    @Mock private InvStockPageQueryMapper stockPageQueryMapper;
    @Mock private BaseCustomerMapper customerMapper;
    @Mock private BaseProductMapper productMapper;
    @Mock private BaseSupplierMapper supplierMapper;
    @Mock private BaseWarehouseMapper warehouseMapper;
    @Mock private SalDeliveryService salDeliveryService;
    @Mock private SalOrderService salOrderService;
    @Mock private PrdFinishedInService finishedInService;
    @Mock private SalesSummaryService salesSummaryService;
    @Mock private com.industrial.erp.modules.ai.rag.RagService ragService;
    @Mock private com.industrial.erp.modules.ai.replenish.ReplenishService replenishService;

    @InjectMocks private ErpToolRegistry registry;

    private static List<String> names(JSONArray arr) {
        return arr.toList(JSONObject.class).stream()
                .map(t -> t.getJSONObject("function").getStr("name"))
                .collect(java.util.stream.Collectors.toList());
    }

    private static LlmClient.ToolCall call(String name, String argsRaw) {
        LlmClient.ToolCall tc = new LlmClient.ToolCall();
        tc.id = "call_1";
        tc.name = name;
        tc.argumentsRaw = argsRaw;
        return tc;
    }

    @Test
    @DisplayName("tools() 注册 listWarehouses / listFinishedInBills")
    void tools_shouldRegisterFinishedInReadTools() {
        assertThat(names(registry.tools())).contains("listWarehouses", "listFinishedInBills");
    }

    @Test
    @DisplayName("toolsEnabled(business) 同样带出这两个工具; 全关则一个都没有")
    void toolsEnabled_shouldGateThemUnderBusinessGroup() {
        assertThat(names(registry.toolsEnabled(Collections.singletonList(ErpToolRegistry.GROUP_BUSINESS))))
                .contains("listWarehouses", "listFinishedInBills");
        assertThat(names(registry.toolsEnabled(Collections.singletonList(ErpToolRegistry.GROUP_QUALITY))))
                .doesNotContain("listWarehouses", "listFinishedInBills");
        assertThat(registry.toolsEnabled(Collections.emptyList())).isEmpty();
    }

    @Test
    @DisplayName("listWarehouses 只查启用中的仓库并回真实 id")
    void listWarehouses_shouldReturnEnabledRows() {
        BaseWarehouse wh = new BaseWarehouse();
        wh.setId(7001L);
        wh.setWarehouseName("成品仓");
        wh.setIsDefault(1);
        when(warehouseMapper.selectList(any())).thenReturn(Collections.singletonList(wh));

        String out = registry.execute(call("listWarehouses", "{\"keyword\":\"成品\"}"));

        assertThat(out).contains("成品仓").contains("7001");
    }

    @Test
    @DisplayName("listFinishedInBills 走 service 分页 (关键字空→不过滤, limit 默认 10) 并带出单号与状态")
    void listFinishedInBills_shouldPageThroughService() {
        PrdFinishedIn bill = new PrdFinishedIn();
        bill.setId(8100L);
        bill.setBillNo("PFI202610310001");
        bill.setBillStatus("DRAFT");
        bill.setProductName("带鱼袋9*75*0.07");
        Page<PrdFinishedIn> p = new Page<>(1, 10);
        p.setRecords(Collections.singletonList(bill));
        when(finishedInService.page(eq(1), anyInt(), isNull())).thenReturn(p);

        String out = registry.execute(call("listFinishedInBills", "{}"));

        assertThat(out).contains("PFI202610310001").contains("DRAFT");
        verify(finishedInService).page(1, 10, null);
    }

    @Test
    @DisplayName("limit 超上限被截到 30, 防止模型一次拉爆上下文")
    void listFinishedInBills_shouldCapLimit() {
        Page<PrdFinishedIn> p = new Page<>(1, 30);
        p.setRecords(Collections.emptyList());
        when(finishedInService.page(anyInt(), anyInt(), any())).thenReturn(p);

        registry.execute(call("listFinishedInBills", "{\"keyword\":\"PFI\",\"limit\":500}"));

        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(finishedInService).page(eq(1), limit.capture(), eq("PFI"));
        assertThat(limit.getValue()).isEqualTo(30);
    }

    @Test
    @DisplayName("未知工具回结构化错误 (不抛异常)")
    void execute_shouldNotThrowOnUnknownTool() {
        assertThat(registry.execute(call("dropTable", "{}"))).contains("未知工具");
    }

    /**
     * v1.1.144 — getSalesSummary 既要在 schema 里 (模型才想得起调), 又要把 service 的返回体原样吐回去:
     * 那份 JSON 同时是喂模型的料和前端渲染表格 + 下载 CSV 的料, 少一层透传界面上就只剩模型抄的文字。
     */
    @Test
    @DisplayName("getSalesSummary 在 tools()/business 组里注册, quality 组不带; 返回体原样透传")
    void salesSummaryTool_shouldBeRegisteredAndPassServiceJsonThrough() {
        assertThat(names(registry.tools())).contains("getSalesSummary");
        assertThat(names(registry.toolsEnabled(Collections.singletonList(ErpToolRegistry.GROUP_BUSINESS))))
                .contains("getSalesSummary");
        assertThat(names(registry.toolsEnabled(Collections.singletonList(ErpToolRegistry.GROUP_QUALITY))))
                .doesNotContain("getSalesSummary");
        assertThat(ErpToolRegistry.TABLE_TOOLS).contains("getSalesSummary");

        SalesSummaryService.Table table = new SalesSummaryService.Table();
        table.title = "客户「长晟」 2026-09-01 ~ 2026-09-30 销售汇总";
        when(salesSummaryService.query(any(), any(), any(), any(), anyInt())).thenReturn(table);
        when(salesSummaryService.toToolJson(table)).thenReturn("{\"title\":\"" + table.title + "\",\"rows\":[{}]}");

        String out = registry.execute(call("getSalesSummary",
                "{\"startDate\":\"2026-09\",\"endDate\":\"2026-09\",\"customer\":\"长晟\",\"limit\":50}"));

        assertThat(out).contains("销售汇总").contains("rows");
        verify(salesSummaryService).query("2026-09", "2026-09", "长晟", "", 50);
    }
}
