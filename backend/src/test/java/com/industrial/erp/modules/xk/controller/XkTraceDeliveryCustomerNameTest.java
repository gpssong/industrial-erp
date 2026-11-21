package com.industrial.erp.modules.xk.controller;

import com.industrial.erp.modules.base.entity.BaseCustomer;
import com.industrial.erp.modules.base.mapper.BaseCustomerMapper;
import com.industrial.erp.modules.em.service.EmMaintainOrderService;
import com.industrial.erp.modules.em.service.EquipmentService;
import com.industrial.erp.modules.mes.service.MesReportService;
import com.industrial.erp.modules.purchase.service.PurReceiptService;
import com.industrial.erp.modules.qms.service.QmsInspectionService;
import com.industrial.erp.modules.report.mapper.ReportMapper;
import com.industrial.erp.modules.system.mapper.SysUserMapper;
import com.industrial.erp.modules.system.service.SysConfigService;
import com.industrial.erp.modules.workflow.service.WfDashboardService;
import com.industrial.erp.modules.workflow.service.WfMaintainRecordService;
import com.industrial.erp.modules.xk.service.XkAuditService;
import com.industrial.erp.modules.xk.service.XkBatchViewService;
import com.industrial.erp.modules.xk.service.XkEnvCheckService;
import com.industrial.erp.modules.xk.service.XkSafetyCheckService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v1.1.205 — PC「批次全链路追溯」点原料批要看到销售单 + 客户。
 *
 * <p>v1.1.147 只回填了 {@code FullTrace.customerName} 这一个标量 (取下游第一条命中客户的行)。
 * 一张原料批经工单桥接后可能分批发给多个客户, 于是只有第一行有名字, 其余出库单显示空客户。
 * 客户名必须由服务端按 {@code customer_id} 查 (前端拿不到 base_customer), 所以逐行回填。
 */
class XkTraceDeliveryCustomerNameTest {

    private static final String RAW = "251/WQ";
    private static final Long JINBO = 2123970981013852162L;
    private static final Long SHENGLONG = 2123970981013852999L;

    private XkController controller(MesReportService mes, BaseCustomerMapper customers) {
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

    @Test
    @DisplayName("每个销售出口都回填自己的客户名 — 分批发给两个客户时不许只有第一行有名字")
    void everyDeliveryHopCarriesItsCustomerName() {
        MesReportService.FullTrace trace = new MesReportService.FullTrace();
        trace.batchNo = RAW;
        trace.deliveries.add(hop("CKP202611210001", JINBO));
        trace.deliveries.add(hop("CKP202611210002", SHENGLONG));

        MesReportService mes = mock(MesReportService.class);
        BaseCustomerMapper customers = mock(BaseCustomerMapper.class);
        when(mes.traceFull(RAW)).thenReturn(trace);
        when(customers.selectById(JINBO)).thenReturn(customer("金华金博食品有限公司"));
        when(customers.selectById(SHENGLONG)).thenReturn(customer("临海盛龙包装"));

        MesReportService.FullTrace r = controller(mes, customers).traceFullByQuery(RAW).getData();

        assertThat(r.deliveries).extracting(h -> h.deliveryBillNo, h -> h.customerName)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("CKP202611210001", "金华金博食品有限公司"),
                        org.assertj.core.groups.Tuple.tuple("CKP202611210002", "临海盛龙包装"));
    }

    @Test
    @DisplayName("客户已被删档查不到名字时不许整条追溯报 500 — 出口仍要出现")
    void missingCustomerRowKeepsTheDeliveryHopVisible() {
        MesReportService.FullTrace trace = new MesReportService.FullTrace();
        trace.batchNo = RAW;
        trace.deliveries.add(hop("CKP202611210001", JINBO));

        MesReportService mes = mock(MesReportService.class);
        BaseCustomerMapper customers = mock(BaseCustomerMapper.class);
        when(mes.traceFull(RAW)).thenReturn(trace);
        when(customers.selectById(JINBO)).thenReturn(null);

        MesReportService.FullTrace r = controller(mes, customers).traceFullByQuery(RAW).getData();

        assertThat(r.deliveries).singleElement().satisfies(h -> {
            assertThat(h.deliveryBillNo).isEqualTo("CKP202611210001");
            assertThat(h.customerId).isEqualTo(JINBO);
            assertThat(h.customerName).isNull();
        });
    }
}
