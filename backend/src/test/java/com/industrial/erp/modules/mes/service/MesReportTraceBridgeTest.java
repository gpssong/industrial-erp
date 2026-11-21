package com.industrial.erp.modules.mes.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.industrial.erp.modules.mes.entity.MesBatch;
import com.industrial.erp.modules.mes.entity.MesBatchLink;
import com.industrial.erp.modules.mes.mapper.MesBatchLinkMapper;
import com.industrial.erp.modules.mes.mapper.MesBatchMapper;
import com.industrial.erp.modules.mes.mapper.MesProcessDefMapper;
import com.industrial.erp.modules.mes.mapper.PrdProcessMapper;
import com.industrial.erp.modules.pdm.mapper.MesProcessParamMapper;
import com.industrial.erp.modules.production.entity.PrdOrder;
import com.industrial.erp.modules.production.entity.PrdOrderMaterial;
import com.industrial.erp.modules.production.mapper.PrdOrderMapper;
import com.industrial.erp.modules.production.mapper.PrdOrderMaterialMapper;
import com.industrial.erp.modules.qms.service.QmsInspectionService;
import com.industrial.erp.modules.system.mapper.SysOperLogMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * v1.1.205 — PC「批次全链路追溯」点原料批只显示 1 站 (当前批次), 生产工单/成品批/销售单/客户全看不见。
 *
 * <p>根因 (2026-11-21 在 .150 主库只读取证): 开工投料写的 {@code mes_batch_link} 行只有
 * {@code parent_batch_no} + {@code prd_order_id}, {@code child_batch_no} 为空 (投料那一刻还没有下游批次)。
 * 于是 {@link MesReportService#traceFull} 的 parent→child BFS 命中这行却没有孩子可入队,
 * 下游批次为空 ⇒ v1.1.147 的客户出口 (child IN 下游链 AND customer_id 非空) 只扫到批号本身,
 * 扫不到销售出库那行 ⇒ 前端只剩「链路完整」徽章 + 1 站。
 *
 * <p>本测试照抄 .150 真实数据形状: 原料批 251/WQ —投料行 WS202611150001→ 工单 PD202611030002
 * → 成品批 PD202611030002 —出库行 CKP202611210001→ 客户 金华金博食品。
 */
@ExtendWith(MockitoExtension.class)
class MesReportTraceBridgeTest {

    private static final String RAW = "251/WQ";
    private static final String SIBLING = "SK216A08C1";
    private static final String FG = "PD202611030002";
    private static final Long ORDER_ID = 2117496026848870401L;
    private static final Long CUSTOMER_ID = 2123970981013852162L;
    private static final String START_BILL_NO = "WS202611150001";
    private static final String DELIVERY_BILL_NO = "CKP202611210001";

    @Mock PrdProcessMapper procMapper;
    @Mock MesProcessDefMapper defMapper;
    @Mock MesBatchMapper batchMapper;
    @Mock MesBatchLinkMapper linkMapper;
    @Mock PrdOrderMapper orderMapper;
    @Mock SysOperLogMapper operLogMapper;
    @Mock MesProcessParamMapper paramMapper;
    @Mock QmsInspectionService qmsInspectionService;
    @Mock PrdOrderMaterialMapper materialMapper;
    @InjectMocks MesReportService service;

    @BeforeAll
    static void initLambdaCache() {
        // 单测没有 SqlSessionFactory, LambdaQueryWrapper.getTargetSql() 要先有 TableInfo 缓存才拼得出列名
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "trace-bridge-test");
        TableInfoHelper.initTableInfo(assistant, MesBatchLink.class);
        TableInfoHelper.initTableInfo(assistant, MesBatch.class);
        TableInfoHelper.initTableInfo(assistant, PrdOrder.class);
        TableInfoHelper.initTableInfo(assistant, PrdOrderMaterial.class);
    }

    // ---------- fixtures: 照抄库内行 ----------

    /** 开工投料行: child 为空, 只有 parent 原料批 + prd_order_id —— 断链就在这. */
    private MesBatchLink issueLink() {
        MesBatchLink l = new MesBatchLink();
        l.setId(2121931520285933570L);
        l.setParentBatchNo(RAW);
        l.setQty(new BigDecimal("3.65"));
        l.setPrdOrderId(ORDER_ID);
        l.setBillType("ISSUE");
        l.setBillNo(START_BILL_NO);
        l.setCreateTime(LocalDateTime.of(2026, 11, 15, 8, 30));
        return l;
    }

    /** 质量处置行: child=本批、parent 空、无客户 ⇒ 既不是上游来料也不是销售出口. */
    private MesBatchLink dispositionLink() {
        MesBatchLink l = new MesBatchLink();
        l.setId(2121611948815089665L);
        l.setChildBatchNo(RAW);
        l.setBillType("DISPOSITION");
        l.setBillNo("QI202611090001");
        return l;
    }

    /** 销售出库行: child=成品批 + customer_id ⇒ 追溯出口. */
    private MesBatchLink fgOutLink() {
        MesBatchLink l = new MesBatchLink();
        l.setId(2123971182415941633L);
        l.setChildBatchNo(FG);
        l.setQty(new BigDecimal("2000"));
        l.setBillType("FG_OUT");
        l.setBillNo(DELIVERY_BILL_NO);
        l.setCustomerId(CUSTOMER_ID);
        l.setCreateTime(LocalDateTime.of(2026, 11, 21, 10, 46, 6));
        return l;
    }

    private MesBatch rawBatch() {
        MesBatch b = new MesBatch();
        b.setBatchNo(RAW);
        b.setBatchType("RAW");
        b.setQty(new BigDecimal("1500"));
        b.setStatus("ACTIVE");
        b.setReceiptBillNo("RKP202611140005");
        return b;
    }

    /** 成品批: 批号即工单号, source_prd_order_no 同值 (v1.1.80 起入库登记口径). */
    private MesBatch fgBatch() {
        MesBatch b = new MesBatch();
        b.setBatchNo(FG);
        b.setBatchType("FG");
        b.setSourcePrdOrderNo(FG);
        b.setQty(new BigDecimal("2000"));
        b.setStatus("ACTIVE");
        return b;
    }

    private PrdOrder order() {
        PrdOrder o = new PrdOrder();
        o.setId(ORDER_ID);
        o.setBillNo(FG);
        o.setProductName("金博23*29*0.12");
        o.setPlanQty(new BigDecimal("2000"));
        o.setBillStatus("PRODUCING");
        return o;
    }

    private List<PrdOrderMaterial> materialRows() {
        return List.of(material(1, SIBLING, "10.95"), material(2, RAW, "3.65"));
    }

    private PrdOrderMaterial material(int lineNo, String batchNo, String reqQty) {
        PrdOrderMaterial m = new PrdOrderMaterial();
        m.setPrdOrderId(ORDER_ID);
        m.setLineNo(lineNo);
        m.setBatchNo(batchNo);
        m.setReqQty(new BigDecimal(reqQty));
        return m;
    }

    // ---------- 查询分派: 同一 mapper 在追溯里被多种形状打过, 按 SQL 片段 + 参数分流 ----------

    private void stubQueries() {
        lenient().when(linkMapper.selectList(any())).thenAnswer(inv -> links((Wrapper<MesBatchLink>) inv.getArgument(0)));
        lenient().when(batchMapper.selectList(any())).thenAnswer(inv -> madeBatches((Wrapper<MesBatch>) inv.getArgument(0)));
        lenient().when(materialMapper.selectList(any())).thenAnswer(inv -> materials((Wrapper<PrdOrderMaterial>) inv.getArgument(0)));
        lenient().when(orderMapper.selectList(any())).thenAnswer(inv -> orderByBillNo((Wrapper<PrdOrder>) inv.getArgument(0)));
        lenient().when(orderMapper.selectBatchIds(any())).thenAnswer(inv -> {
            List<?> ids = new ArrayList<>((java.util.Collection<?>) inv.getArgument(0));
            return ids.contains(ORDER_ID) ? List.of(order()) : List.of();
        });
    }

    private List<MesBatchLink> links(Wrapper<MesBatchLink> wrapper) {
        String sql = sqlOf(wrapper);
        List<String> ps = paramsOf(wrapper);
        if (sql.contains("customer_id")) return ps.contains(FG) ? List.of(fgOutLink()) : List.of();
        if (sql.contains("prd_order_id")) return ps.contains(ORDER_ID.toString()) ? List.of(issueLink()) : List.of();
        if (sql.contains("parent_batch_no")) return ps.contains(RAW) ? List.of(issueLink()) : List.of();
        return ps.contains(RAW) ? List.of(dispositionLink()) : List.of();
    }

    /** 工单产出的批次: 只认工单号 PD202611030002 (真实数据里批号 = 工单号). */
    private List<MesBatch> madeBatches(Wrapper<MesBatch> wrapper) {
        return paramsOf(wrapper).contains(FG) ? List.of(fgBatch()) : List.of();
    }

    /** 该单原料行: 按批号筛 (正向找消费者) 与按工单筛 (反向找来料) 返回同一批真实行. */
    private List<PrdOrderMaterial> materials(Wrapper<PrdOrderMaterial> wrapper) {
        List<String> ps = paramsOf(wrapper);
        return ps.contains(ORDER_ID.toString()) || ps.contains(RAW) || ps.contains(SIBLING) ? materialRows() : List.of();
    }

    /** 按 bill_no 查工单: 只有成品批号与工单同号, 原料批号查不到单. */
    private List<PrdOrder> orderByBillNo(Wrapper<PrdOrder> wrapper) {
        return paramsOf(wrapper).contains(FG) ? List.of(order()) : List.of();
    }

    private static String sqlOf(Wrapper<?> wrapper) {
        return asLambda(wrapper).getTargetSql();
    }

    private static List<String> paramsOf(Wrapper<?> wrapper) {
        LambdaQueryWrapper<?> w = asLambda(wrapper);
        // MP 把参数登记推迟到 SQL 渲染那一刻 (eq 存的是 () -> formatParam(...)),
        // 所以必须先渲染再取 paramNameValuePairs, 直接取会拿到空 map
        w.getTargetSql();
        List<String> out = new ArrayList<>();
        for (Object v : w.getParamNameValuePairs().values()) {
            out.add(v == null ? "" : String.valueOf(v));
        }
        return out;
    }

    private static LambdaQueryWrapper<?> asLambda(Wrapper<?> wrapper) {
        return (LambdaQueryWrapper<?>) wrapper;
    }

    // ---------- 断言 ----------

    @Test
    @DisplayName("原料批经 child 为空的投料行桥接到工单产出的成品批, 并顺带找到销售出库的客户")
    void rawBatchReachesFinishedBatchAndCustomerThroughIssueLink() {
        when(batchMapper.selectOne(any())).thenReturn(rawBatch());
        stubQueries();

        MesReportService.FullTrace r = service.traceFull(RAW);

        assertThat(r.downstreamBatches).contains(FG);
        assertThat(r.customer).isEqualTo(CUSTOMER_ID);
        // 兄弟原料批不是它的上游, 桥接不许把它塞进上游链
        assertThat(r.upstreamBatches).doesNotContain(SIBLING);
    }

    @Test
    @DisplayName("成品批经「这批是谁生产的」反向桥接到该工单的原料批")
    void finishedBatchReachesItsRawInputsThroughProducingOrder() {
        when(batchMapper.selectOne(any())).thenReturn(fgBatch());
        stubQueries();

        MesReportService.FullTrace r = service.traceFull(FG);

        assertThat(r.upstreamBatches).contains(RAW, SIBLING);
    }

    @Test
    @DisplayName("工单号与销售出库单号作为结构化 hop 交出去 — PC 时间线直接渲染, 不再只有批号")
    void rawBatchExposesOrderAndDeliveryHops() {
        when(batchMapper.selectOne(any())).thenReturn(rawBatch());
        stubQueries();

        MesReportService.FullTrace r = service.traceFull(RAW);

        assertThat(r.orders).singleElement().satisfies(o -> {
            assertThat(o.orderNo).isEqualTo(FG);
            assertThat(o.productName).isEqualTo("金博23*29*0.12");
            assertThat(o.planQty).isEqualByComparingTo("2000");
            assertThat(o.issueBillNo).isEqualTo(START_BILL_NO);
        });
        assertThat(r.deliveries).singleElement().satisfies(d -> {
            assertThat(d.deliveryBillNo).isEqualTo(DELIVERY_BILL_NO);
            assertThat(d.customerId).isEqualTo(CUSTOMER_ID);
            assertThat(d.qty).isEqualByComparingTo("2000");
        });
    }

    @Test
    @DisplayName("反向追溯同一张工单只出现一次, 且领料单号不被反向覆盖成空")
    void finishedBatchListsItsProducingOrderOnce() {
        when(batchMapper.selectOne(any())).thenReturn(fgBatch());
        stubQueries();

        MesReportService.FullTrace r = service.traceFull(FG);

        assertThat(r.orders).singleElement().satisfies(o -> {
            assertThat(o.orderNo).isEqualTo(FG);
            assertThat(o.issueBillNo).isEqualTo(START_BILL_NO);
        });
    }
}
