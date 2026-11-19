package com.industrial.erp.modules.production.service;

import com.industrial.erp.common.Constants;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.industrial.erp.modules.base.mapper.BaseWarehouseMapper;
import com.industrial.erp.modules.inventory.service.StockService;
import com.industrial.erp.modules.mes.service.MesReportService;
import com.industrial.erp.modules.production.entity.PrdFinishedIn;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * v1.1.202 — 入库单 (成品/半成品) 新建接口必须把**新建单的 id** 回给客户端.
 *
 * <p>事故: App 的「审核入库」一步完成流程里, ensureDraft() 调 POST /production/finished-in 建草稿后,
 * 只能靠 GET /production/finished-in/page?billNo={工单号} 回捞刚建的草稿 —— 但入库单自己的单号是
 * BILL_PFI/BILL_PSI 另生成的 (PFI2026…/PSI2026…), 与**工单号** (PD2026…) 根本不是一个号,
 * 列表里永远查不到 ⇒ draftId 为空 ⇒ 现场看到「入库单草稿保存失败，请重试」。
 *
 * <p>口径: 单据自己的 id 由服务端新增时回传, 客户端不靠单号反查。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("入库单新建返回单据 id (成品/半成品)")
class PrdInboundAddIdTest {

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

    private PrdFinishedInService finishedInService;
    private PrdSemiInService semiInService;

    @BeforeEach
    void setUp() {
        SecurityContext.setCurrentUserIdForTest(1L);
        ProductDeriveService.InboundFields f = new ProductDeriveService.InboundFields();
        f.productId = 200L;
        f.productCode = "P1";
        f.productName = "成品袋";
        f.spec = "23*29*0.12";
        f.unitId = 300L;
        f.unitName = "只";
        when(deriveService.inboundFields(any(), any(), any(), any(), any(), any(), any())).thenReturn(f);
        finishedInService = new PrdFinishedInService(finishedInMapper, warehouseMapper, billNoGenerator,
                stockService, permService, mesReportService, qmsInspectionService, prdOrderMapper, deriveService);
        semiInService = new PrdSemiInService(semiInMapper, warehouseMapper, billNoGenerator, stockService,
                permService, mesReportService, qmsInspectionService, prdOrderMapper, deriveService, productMapper);
    }

    @AfterEach
    void tearDown() {
        SecurityContext.clearForTest();
    }

    @Test
    @DisplayName("成品入库新建: 返回新建单 id, 且单号是 PFI 前缀 (不是工单号)")
    void finishedInAdd_shouldReturnCreatedId() {
        when(billNoGenerator.generate(eq(Constants.BILL_PFI))).thenReturn("PFI2026-001");
        doAnswer(inv -> { ((PrdFinishedIn) inv.getArgument(0)).setId(9001L); return 1; })
                .when(finishedInMapper).insert(any(PrdFinishedIn.class));

        PrdFinishedIn in = new PrdFinishedIn();
        in.setPrdOrderId(100L);
        in.setProductId(200L);
        in.setQty(new BigDecimal("10"));
        in.setPrice(new BigDecimal("2"));

        Long id = finishedInService.add(in);

        assertThat(id).isEqualTo(9001L);
        assertThat(in.getBillNo()).isEqualTo("PFI2026-001");
    }

    @Test
    @DisplayName("半成品入库新建: 返回新建单 id, 且单号是 PSI 前缀 (不是工单号)")
    void semiInAdd_shouldReturnCreatedId() {
        when(billNoGenerator.generate(eq(Constants.BILL_PSI))).thenReturn("PSI2026-001");
        doAnswer(inv -> { ((PrdSemiIn) inv.getArgument(0)).setId(9002L); return 1; })
                .when(semiInMapper).insert(any(PrdSemiIn.class));

        PrdSemiIn in = new PrdSemiIn();
        in.setPrdOrderId(100L);
        in.setProductId(200L);
        in.setQty(new BigDecimal("10"));
        in.setPrice(new BigDecimal("2"));

        Long id = semiInService.add(in);

        assertThat(id).isEqualTo(9002L);
        assertThat(in.getBillNo()).isEqualTo("PSI2026-001");
    }
}
