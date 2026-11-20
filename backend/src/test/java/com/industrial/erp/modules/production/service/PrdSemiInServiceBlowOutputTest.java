package com.industrial.erp.modules.production.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.industrial.erp.common.Constants;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.industrial.erp.modules.base.mapper.BaseWarehouseMapper;
import com.industrial.erp.modules.inventory.service.StockService;
import com.industrial.erp.modules.mes.service.MesReportService;
import com.industrial.erp.modules.production.entity.PrdSemiIn;
import com.industrial.erp.modules.production.mapper.PrdFinishedInMapper;
import com.industrial.erp.modules.production.mapper.PrdOrderMapper;
import com.industrial.erp.modules.production.mapper.PrdSemiInMapper;
import com.industrial.erp.modules.qms.service.QmsInspectionService;
import com.industrial.erp.security.PermissionService;
import com.industrial.erp.security.SecurityContext;
import com.industrial.erp.utils.BillNoGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * v1.1.204 — 半成品入库审核 = 吹膜工序的产出记录 (用户拍板口径).
 *
 * <p>App 的吹膜链路是「开工 → 首检 → 半成品入库」, 没有报工步骤, 而工序 status=2 的唯一写方是报工
 * ⇒ 吹膜永远未完工 ⇒ 食品级单被成品入库的工序门禁 (v1.1.99 C1) 卡死 (线上 PD202611030002)。
 * 这里把「该单已审核的半成品入库总量」交给 MES 回写吹膜工序。
 *
 * <p>口径要点: 统计必须在<b>本单转 CHECKED 之后</b>发起, 否则本次审核的单据自己不算进去;
 * 只统计 CHECKED (反审核的量自动退出重算)。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("半成品入库审核回写吹膜工序产出")
class PrdSemiInServiceBlowOutputTest {

    @Mock private PrdFinishedInMapper finishedInMapper;
    @Mock private PrdSemiInMapper semiInMapper;
    @Mock private BaseWarehouseMapper warehouseMapper;
    @Mock private BaseProductMapper productMapper;
    @Mock private BillNoGenerator billNoGenerator;
    @Mock private StockService stockService;
    @Mock private PermissionService permService;
    @Mock private MesReportService mesReportService;
    @Mock private QmsInspectionService qmsInspectionService;
    @Mock private PrdOrderMapper prdOrderMapper;
    @Mock private ProductDeriveService deriveService;

    private PrdSemiInService semiInService;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "psi-blow-test"),
                PrdSemiIn.class);
        SecurityContext.setCurrentUserIdForTest(1L);
        semiInService = new PrdSemiInService(semiInMapper, warehouseMapper, billNoGenerator, stockService,
                permService, mesReportService, qmsInspectionService, prdOrderMapper, deriveService, productMapper);
        when(qmsInspectionService.isFoodGradeProduct(any())).thenReturn(false);
    }

    @AfterEach
    void tearDown() { SecurityContext.clearForTest(); }

    private static PrdSemiIn semiIn(Long id, String billNo, BigDecimal qty, String status) {
        PrdSemiIn in = new PrdSemiIn();
        in.setId(id);
        in.setBillNo(billNo);
        in.setBillStatus(status);
        in.setPrdOrderId(10L);
        in.setProductId(200L);
        in.setQty(qty);
        return in;
    }

    @Test
    @DisplayName("审核后按该单全部已审核半成品入库量回写吹膜工序 (含本次审核的单)")
    void checkRecordsBlowOutputAfterBillBecomesChecked() {
        PrdSemiIn current = semiIn(1L, "PSI202611170002", new BigDecimal("2000"), Constants.STATUS_DRAFT);
        when(semiInMapper.selectById(1L)).thenReturn(current);
        when(semiInMapper.selectList(any())).thenReturn(List.of(
                current, semiIn(2L, "PSI202611100001", new BigDecimal("300"), Constants.STATUS_CHECKED)));

        semiInService.check(1L);

        InOrder order = inOrder(semiInMapper, mesReportService);
        order.verify(semiInMapper).update(isNull(), any());
        order.verify(mesReportService).recordBlowOutput(eq(10L), eq(new BigDecimal("2300")),
                eq("PSI202611170002"));
    }

    @Test
    @DisplayName("统计口径只算已审核 (CHECKED) 的半成品入库单")
    void checkAggregatesOnlyCheckedBills() {
        PrdSemiIn current = semiIn(1L, "PSI202611170002", new BigDecimal("2000"), Constants.STATUS_DRAFT);
        when(semiInMapper.selectById(1L)).thenReturn(current);
        when(semiInMapper.selectList(any())).thenReturn(List.of(current));

        semiInService.check(1L);

        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(semiInMapper).selectList(captor.capture());
        assertThat(captor.getValue().getTargetSql()).contains("prd_order_id").contains("bill_status");
        assertThat(captor.getValue().getParamNameValuePairs().values()).contains(Constants.STATUS_CHECKED);
    }

    @Test
    @DisplayName("手工建的无工单半成品入库单不回写工序")
    void checkWithoutOrderRecordsNothing() {
        PrdSemiIn current = semiIn(1L, "PSI202611170002", new BigDecimal("2000"), Constants.STATUS_DRAFT);
        current.setPrdOrderId(null);
        when(semiInMapper.selectById(1L)).thenReturn(current);

        semiInService.check(1L);

        verify(mesReportService, never()).recordBlowOutput(any(), any(), any());
    }
}
