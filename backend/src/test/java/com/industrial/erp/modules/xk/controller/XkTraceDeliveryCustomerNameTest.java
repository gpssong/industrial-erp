package com.industrial.erp.modules.xk.controller;

import com.industrial.erp.modules.base.entity.BaseCustomer;
import com.industrial.erp.modules.base.mapper.BaseCustomerMapper;
import com.industrial.erp.modules.em.service.EmMaintainOrderService;
import com.industrial.erp.modules.em.service.EquipmentService;
import com.industrial.erp.modules.mes.service.MesReportService;
import com.industrial.erp.modules.purchase.service.PurReceiptService;
import com.industrial.erp.modules.qms.service.QmsInspectionService;
import com.industrial.erp.modules.report.mapper.ReportMapper;
import com.industrial.erp.modules.sales.entity.SalDelivery;
import com.industrial.erp.modules.sales.mapper.SalDeliveryMapper;
import com.industrial.erp.modules.system.mapper.SysUserMapper;
import com.industrial.erp.modules.system.service.SysConfigService;
import com.industrial.erp.modules.workflow.service.WfDashboardService;
import com.industrial.erp.modules.workflow.service.WfMaintainRecordService;
import com.industrial.erp.modules.xk.service.XkAuditService;
import com.industrial.erp.modules.xk.service.XkBatchViewService;
import com.industrial.erp.modules.xk.service.XkEnvCheckService;
import com.industrial.erp.modules.xk.service.XkSafetyCheckService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v1.1.205 — PC「批次全链路追溯」点原料批要看到销售单 + 客户。
 *
 * <p>v1.1.147 只回填了 {@code FullTrace.customerName} 这一个标量 (取下游第一条命中客户的行)。
 * 一张原料批经工单桥接后可能分批发给多个客户, 于是只有第一行有名字, 其余出库单显示空客户。
 * 客户名必须由服务端按 {@code customer_id} 查 (前端拿不到 base_customer), 所以逐行回填。
 *
 * <p>v1.1.206 同一层再补一样: 出库出口要带回 {@code sal_delivery.id}。现场点「销售出库」这一站
 * 要直达出库单, 而 {@code mes_batch_link} 的 FG_OUT 行只写了 {@code bill_no} ({@code bill_id} 为空),
 * 光靠单号前端定位不到那一张单。
 */
class XkTraceDeliveryCustomerNameTest {

    private static final String RAW = "251/WQ";
    private static final Long JINBO = 2123970981013852162L;
    private static final Long SHENGLONG = 2123970981013852999L;
    private static final String BILL_1 = "CKP202611210001";
    private static final String BILL_2 = "CKP202611210002";
    private static final Long DELIVERY_ID_1 = 2123971182415941633L;

    @BeforeAll
    static void initLambdaCache() {
        // 按 bill_no 反查出库单用了 LambdaQueryWrapper, 单测没有 SqlSessionFactory ⇒ 先建 TableInfo 缓存
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "trace-delivery-test");
        TableInfoHelper.initTableInfo(assistant, SalDelivery.class);
    }

    private XkController controller(MesReportService mes, BaseCustomerMapper customers, SalDeliveryMapper deliveries) {
        return new XkController(
                mock(ReportMapper.class),
                mock(WfDashboardService.class),
                mock(WfMaintainRecordService.class),
                mock(EmMaintainOrderService.class),
                mock(EquipmentService.class),
                mes,
                mock(PurReceiptService.class),
                mock(QmsInspectionService.class),
                mock(XkSafetyCheckService.class),
                mock(XkAuditService.class),
                mock(XkEnvCheckService.class),
                mock(SysConfigService.class),
                mock(SysUserMapper.class),
                customers,
                deliveries,
                mock(XkBatchViewService.class));
    }

    private static MesReportService.DeliveryHop hop(String billNo, Long customerId) {
        MesReportService.DeliveryHop h = new MesReportService.DeliveryHop();
        h.deliveryBillNo = billNo;
        h.customerId = customerId;
        h.qty = new BigDecimal("2000");
        return h;
    }

    private static BaseCustomer customer(String name) {
        BaseCustomer c = new BaseCustomer();
        c.setCustomerName(name);
        return c;
    }

    private static SalDelivery delivery(String billNo, Long id) {
        SalDelivery d = new SalDelivery();
        d.setId(id);
        d.setBillNo(billNo);
        return d;
    }

    @Test
    @DisplayName("每个销售出口都回填自己的客户名 — 分批发给两个客户时不许只有第一行有名字")
    void everyDeliveryHopCarriesItsCustomerName() {
        MesReportService.FullTrace trace = new MesReportService.FullTrace();
        trace.batchNo = RAW;
        trace.deliveries.add(hop(BILL_1, JINBO));
        trace.deliveries.add(hop(BILL_2, SHENGLONG));

        MesReportService mes = mock(MesReportService.class);
        BaseCustomerMapper customers = mock(BaseCustomerMapper.class);
        SalDeliveryMapper deliveries = mock(SalDeliveryMapper.class);
        when(mes.traceFull(RAW)).thenReturn(trace);
        when(customers.selectById(JINBO)).thenReturn(customer("金华金博食品有限公司"));
        when(customers.selectById(SHENGLONG)).thenReturn(customer("临海盛龙包装"));

        MesReportService.FullTrace r = controller(mes, customers, deliveries).traceFullByQuery(RAW).getData();

        assertThat(r.deliveries).extracting(h -> h.deliveryBillNo, h -> h.customerName)
                .containsExactly(tuple(BILL_1, "金华金博食品有限公司"), tuple(BILL_2, "临海盛龙包装"));
    }

    @Test
    @DisplayName("客户已被删档查不到名字时不许整条追溯报 500 — 出口仍要出现")
    void missingCustomerRowKeepsTheDeliveryHopVisible() {
        MesReportService.FullTrace trace = new MesReportService.FullTrace();
        trace.batchNo = RAW;
        trace.deliveries.add(hop(BILL_1, JINBO));

        MesReportService mes = mock(MesReportService.class);
        BaseCustomerMapper customers = mock(BaseCustomerMapper.class);
        SalDeliveryMapper deliveries = mock(SalDeliveryMapper.class);
        when(mes.traceFull(RAW)).thenReturn(trace);
        when(customers.selectById(JINBO)).thenReturn(null);

        MesReportService.FullTrace r = controller(mes, customers, deliveries).traceFullByQuery(RAW).getData();

        assertThat(r.deliveries).singleElement().satisfies(h -> {
            assertThat(h.deliveryBillNo).isEqualTo(BILL_1);
            assertThat(h.customerId).isEqualTo(JINBO);
            assertThat(h.customerName).isNull();
        });
    }

    @Test
    @DisplayName("v1.1.206: 出库出口按单号带回 sal_delivery.id; 单号在库里找不到时留空而不是报错")
    void deliveryHopCarriesItsDeliveryId() {
        MesReportService.FullTrace trace = new MesReportService.FullTrace();
        trace.batchNo = RAW;
        trace.deliveries.add(hop(BILL_1, JINBO));
        trace.deliveries.add(hop(BILL_2, SHENGLONG));

        MesReportService mes = mock(MesReportService.class);
        BaseCustomerMapper customers = mock(BaseCustomerMapper.class);
        SalDeliveryMapper deliveries = mock(SalDeliveryMapper.class);
        when(mes.traceFull(RAW)).thenReturn(trace);
        // BILL_2 那行流向的出库单已被删除 ⇒ 只有 BILL_1 能反查到 id
        when(deliveries.selectList(any())).thenReturn(List.of(delivery(BILL_1, DELIVERY_ID_1)));

        MesReportService.FullTrace r = controller(mes, customers, deliveries).traceFullByQuery(RAW).getData();

        assertThat(r.deliveries).extracting(h -> h.deliveryBillNo, h -> h.deliveryId)
                .containsExactly(tuple(BILL_1, DELIVERY_ID_1), tuple(BILL_2, null));
    }
}
