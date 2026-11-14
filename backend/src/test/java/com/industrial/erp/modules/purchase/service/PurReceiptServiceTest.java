package com.industrial.erp.modules.purchase.service;

import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseSupplier;
import com.industrial.erp.modules.base.entity.BaseWarehouse;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.industrial.erp.modules.base.mapper.BaseSupplierMapper;
import com.industrial.erp.modules.base.mapper.BaseWarehouseMapper;
import com.industrial.erp.modules.finance.service.FinArapService;
import com.industrial.erp.modules.inventory.service.StockService;
import com.industrial.erp.modules.mes.service.MesReportService;
import com.industrial.erp.modules.purchase.entity.PurOrder;
import com.industrial.erp.modules.purchase.entity.PurOrderDetail;
import com.industrial.erp.modules.purchase.entity.PurReceipt;
import com.industrial.erp.modules.purchase.entity.PurReceiptDetail;
import com.industrial.erp.modules.purchase.mapper.PurOrderDetailMapper;
import com.industrial.erp.modules.purchase.mapper.PurOrderMapper;
import com.industrial.erp.modules.purchase.mapper.PurReceiptDetailMapper;
import com.industrial.erp.modules.purchase.mapper.PurReceiptMapper;
import com.industrial.erp.modules.qms.service.QmsInspectionService;
import com.industrial.erp.modules.system.aspect.OperLogPublisher;
import com.industrial.erp.security.PermissionService;
import com.industrial.erp.utils.BillNoGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * v1.1.19+: 采购入库新增公式单元测试 (含税单价口径).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PurReceiptService 含税单价口径")
class PurReceiptServiceTest {

    @Mock private PurReceiptMapper receiptMapper;
    @Mock private PurReceiptDetailMapper receiptDetailMapper;
    @Mock private PurOrderMapper orderMapper;
    @Mock private PurOrderDetailMapper orderDetailMapper;
    @Mock private BaseSupplierMapper supplierMapper;
    @Mock private BaseWarehouseMapper warehouseMapper;
    @Mock private BillNoGenerator billNoGenerator;
    @Mock private StockService stockService;
    @Mock private FinArapService arapService;
    @Mock private PermissionService permService;
    @Mock private OperLogPublisher operLogPublisher;
    @Mock private BaseProductMapper productMapper;
    @Mock private QmsInspectionService qmsInspectionService;
    @Mock private MesReportService mesReportService;
    @Mock private PurInboundPlanService inboundPlanService;

    @InjectMocks private PurReceiptService service;

    @Test
    @DisplayName("add 默认税率13%: amount=200, totalAmount=totalAmountTax=200")
    void add_defaultTaxRate13() {
        BaseSupplier s = new BaseSupplier();
        s.setId(60L); s.setSupplierName("供应商A"); s.setTaxRate(new BigDecimal("13.00"));
        lenient().when(supplierMapper.selectById(60L)).thenReturn(s);

        BaseWarehouse w = new BaseWarehouse();
        w.setId(1L); w.setWarehouseName("主仓");
        lenient().when(warehouseMapper.selectById(1L)).thenReturn(w);

        lenient().doAnswer(inv -> {
            PurReceipt arg = inv.getArgument(0);
            arg.setId(System.nanoTime());
            return 1;
        }).when(receiptMapper).insert(any(PurReceipt.class));

        PurReceipt receipt = new PurReceipt();
        receipt.setSupplierId(60L); receipt.setWarehouseId(1L);
        PurReceiptDetail d = new PurReceiptDetail();
        d.setProductId(200L); d.setQty(new BigDecimal("2")); d.setPrice(new BigDecimal("100"));
        receipt.setDetails(Arrays.asList(d));

        service.add(receipt);

        PurReceiptDetail detail = receipt.getDetails().get(0);
        assertThat(detail.getAmount()).isEqualByComparingTo("200.0000");
        assertThat(detail.getTaxAmount()).isEqualByComparingTo("0.0000");
        assertThat(detail.getAmountTax()).isEqualByComparingTo("200.0000");
        assertThat(receipt.getTotalAmount()).isEqualByComparingTo("200.0000");
        assertThat(receipt.getTotalAmountTax()).isEqualByComparingTo("200.0000");
        assertThat(receipt.getTaxAmount()).isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("add 供应商税率0: amount=200, totalAmount=200, taxAmount=0")
    void add_supplierTaxRate0() {
        BaseSupplier s = new BaseSupplier();
        s.setId(60L); s.setSupplierName("供应商A"); s.setTaxRate(BigDecimal.ZERO);
        lenient().when(supplierMapper.selectById(60L)).thenReturn(s);
        BaseWarehouse w = new BaseWarehouse();
        w.setId(1L); w.setWarehouseName("主仓");
        lenient().when(warehouseMapper.selectById(1L)).thenReturn(w);
        lenient().doAnswer(inv -> {
            PurReceipt arg = inv.getArgument(0);
            arg.setId(System.nanoTime());
            return 1;
        }).when(receiptMapper).insert(any(PurReceipt.class));

        PurReceipt receipt = new PurReceipt();
        receipt.setSupplierId(60L); receipt.setWarehouseId(1L);
        PurReceiptDetail d = new PurReceiptDetail();
        d.setProductId(200L); d.setQty(new BigDecimal("2")); d.setPrice(new BigDecimal("100"));
        receipt.setDetails(Arrays.asList(d));

        service.add(receipt);

        assertThat(receipt.getTotalAmount()).isEqualByComparingTo("200.0000");
        assertThat(receipt.getTotalAmountTax()).isEqualByComparingTo("200.0000");
        assertThat(receipt.getTaxAmount()).isEqualByComparingTo("0.0000");
    }

    // ===== v1.1.163 App 采购入库: 现场选仓 + 逐行批号 =====

    private PurOrder stubOrder(Long warehouseId) {
        PurOrder o = new PurOrder();
        o.setId(900L); o.setBillNo("PO202611030001"); o.setSupplierId(60L);
        o.setWarehouseId(warehouseId);
        return o;
    }

    private PurOrderDetail stubOrderDetail(Long id, String name, String qty) {
        PurOrderDetail od = new PurOrderDetail();
        od.setId(id); od.setProductId(id + 1000); od.setProductName(name);
        od.setQty(new BigDecimal(qty));
        return od;
    }

    /** 单号可发 + 供应商在册 + 仓库 1/2 都在; 返回捕获到的入库单头由调用方断言. */
    private PurReceipt runCreateFromOrder(Long warehouseId, java.util.Map<Long, String> lineBatches, String batchNo) {
        BaseSupplier s = new BaseSupplier();
        s.setId(60L); s.setSupplierName("浙江宁杨石化"); s.setStatus(1);
        lenient().when(supplierMapper.selectById(60L)).thenReturn(s);
        BaseWarehouse w1 = new BaseWarehouse();
        w1.setId(1L); w1.setWarehouseName("原料仓");
        BaseWarehouse w2 = new BaseWarehouse();
        w2.setId(2L); w2.setWarehouseName("现场选定的仓");
        lenient().when(warehouseMapper.selectById(1L)).thenReturn(w1);
        lenient().when(warehouseMapper.selectById(2L)).thenReturn(w2);
        lenient().when(billNoGenerator.generate(any())).thenReturn("RKP202611030001");
        lenient().doAnswer(inv -> {
            PurReceipt arg = inv.getArgument(0);
            arg.setId(777L);
            return 1;
        }).when(receiptMapper).insert(any(PurReceipt.class));

        service.createFromOrder(900L, 500L, warehouseId, lineBatches, batchNo);

        ArgumentCaptor<PurReceipt> head = ArgumentCaptor.forClass(PurReceipt.class);
        verify(receiptMapper).insert(head.capture());
        return head.getValue();
    }

    @Test
    @DisplayName("createFromOrder: 传 warehouseId 覆盖订单仓库 (PO 未指定仓库也能入库)")
    void createFromOrder_warehouseOverride() {
        lenient().when(qmsInspectionService.hasPassedIqcForOrder(900L, 500L)).thenReturn(true);
        lenient().when(orderMapper.selectById(900L)).thenReturn(stubOrder(null));
        lenient().when(orderDetailMapper.selectByOrderId(900L))
                .thenReturn(Arrays.asList(stubOrderDetail(501L, "赛科0220kj", "10")));

        PurReceipt receipt = runCreateFromOrder(2L, null, null);

        assertThat(receipt.getWarehouseId()).isEqualTo(2L);
        assertThat(receipt.getWarehouseName()).isEqualTo("现场选定的仓");
    }

    @Test
    @DisplayName("createFromOrder: 不传 warehouseId 沿用订单仓库")
    void createFromOrder_fallsBackToOrderWarehouse() {
        lenient().when(qmsInspectionService.hasPassedIqcForOrder(900L, 500L)).thenReturn(true);
        lenient().when(orderMapper.selectById(900L)).thenReturn(stubOrder(1L));
        lenient().when(orderDetailMapper.selectByOrderId(900L))
                .thenReturn(Arrays.asList(stubOrderDetail(501L, "赛科0220kj", "10")));

        PurReceipt receipt = runCreateFromOrder(null, null, null);

        assertThat(receipt.getWarehouseId()).isEqualTo(1L);
        assertThat(receipt.getWarehouseName()).isEqualTo("原料仓");
    }

    @Test
    @DisplayName("createFromOrder: 订单和本单都没仓库 → 业务异常 (不是 NPE)")
    void createFromOrder_noWarehouseAtAll() {
        BaseSupplier s = new BaseSupplier();
        s.setId(60L); s.setSupplierName("浙江宁杨石化"); s.setStatus(1);
        lenient().when(supplierMapper.selectById(60L)).thenReturn(s);
        lenient().when(qmsInspectionService.hasPassedIqcForOrder(900L, 500L)).thenReturn(true);
        lenient().when(orderMapper.selectById(900L)).thenReturn(stubOrder(null));

        assertThatThrownBy(() -> service.createFromOrder(900L, 500L, null, null, "B1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仓库");
    }

    @Test
    @DisplayName("createFromOrder: 逐行批号各归各, 未填的行回落整单 batchNo")
    void createFromOrder_perLineBatch() {
        lenient().when(qmsInspectionService.hasPassedIqcForOrder(900L, 500L)).thenReturn(true);
        lenient().when(orderMapper.selectById(900L)).thenReturn(stubOrder(1L));
        lenient().when(orderDetailMapper.selectByOrderId(900L)).thenReturn(Arrays.asList(
                stubOrderDetail(501L, "赛科0220kj", "10"),
                stubOrderDetail(502L, "扬巴ldpe2426h", "5")));

        runCreateFromOrder(1L, java.util.Map.of(501L, "20251010A1"), "整单兜底");

        ArgumentCaptor<PurReceiptDetail> cap = ArgumentCaptor.forClass(PurReceiptDetail.class);
        verify(receiptDetailMapper, org.mockito.Mockito.times(2)).insert(cap.capture());
        java.util.List<PurReceiptDetail> lines = cap.getAllValues();
        assertThat(lines.get(0).getProductName()).isEqualTo("赛科0220kj");
        assertThat(lines.get(0).getBatchNo()).isEqualTo("20251010A1");
        assertThat(lines.get(1).getProductName()).isEqualTo("扬巴ldpe2426h");
        assertThat(lines.get(1).getBatchNo()).isEqualTo("整单兜底");
    }

    @Test
    @DisplayName("lineBatch: 行值为空/空白视为未填, 超长截到 64, null map 回落兜底")
    void lineBatch_rules() {
        java.util.Map<Long, String> map = new java.util.HashMap<>();
        map.put(1L, "  L20251012  ");
        map.put(2L, "   ");
        StringBuilder longBatch = new StringBuilder();
        for (int i = 0; i < 70; i++) longBatch.append('A').append(i % 10);

        assertThat(PurReceiptService.lineBatch(map, 1L, "兜底")).isEqualTo("L20251012");
        assertThat(PurReceiptService.lineBatch(map, 2L, "兜底")).isEqualTo("兜底");
        assertThat(PurReceiptService.lineBatch(map, 99L, "兜底")).isEqualTo("兜底");
        assertThat(PurReceiptService.lineBatch(null, 1L, "兜底")).isEqualTo("兜底");
        assertThat(PurReceiptService.lineBatch(map, null, "兜底")).isEqualTo("兜底");
        assertThat(PurReceiptService.lineBatch(java.util.Map.of(1L, longBatch.toString()), 1L, null)).hasSize(64);
    }

    // ===== v1.1.172: 入库审核食品级门禁回落 PUR_ORDER 键 (App 订单版签署的 IQC 挂在 PUR_ORDER) =====

    @Test
    @DisplayName("check: 食品级入库单, IQC 挂 PUR_ORDER 键 (App 订单版签署) → 门禁应回落放行, 不再报「未关联 IQC」")
    void check_foodGrade_iqcOnPurOrderKey_fallsBackAndPasses() {
        Long receiptId = 777L;
        PurReceipt receipt = new PurReceipt();
        receipt.setId(receiptId);
        receipt.setBillNo("RKP202611080001");
        receipt.setBillStatus("DRAFT");
        receipt.setOrderId(900L);
        receipt.setSupplierId(60L);
        receipt.setWarehouseId(1L);
        lenient().when(receiptMapper.selectById(receiptId)).thenReturn(receipt);

        PurReceiptDetail d = new PurReceiptDetail();
        d.setReceiptId(receiptId);
        d.setProductId(200L);
        d.setQty(new BigDecimal("10"));
        d.setPrice(BigDecimal.ZERO);
        lenient().when(receiptDetailMapper.selectByReceiptId(receiptId)).thenReturn(Arrays.asList(d));

        lenient().when(qmsInspectionService.isFoodGradeProduct(200L)).thenReturn(true);

        com.industrial.erp.modules.qms.entity.MesQualityInspection orderIqc = new com.industrial.erp.modules.qms.entity.MesQualityInspection();
        orderIqc.setId(500L);
        orderIqc.setBillNo("QI202611080001");
        orderIqc.setSourceBillType("PUR_ORDER");
        orderIqc.setSourceBillId(900L);
        orderIqc.setStatus("CHECKED");
        orderIqc.setResult("PASS");
        orderIqc.setSampleQty(new BigDecimal("10"));
        orderIqc.setInspectorName("gpssong1");
        lenient().when(qmsInspectionService.findIqcForReceiptGate(receipt)).thenReturn(orderIqc);

        BaseSupplier s = new BaseSupplier();
        s.setId(60L); s.setSupplierName("浙江宁杨石化"); s.setStatus(1); s.setTaxRate(new BigDecimal("13.00"));
        lenient().when(supplierMapper.selectById(60L)).thenReturn(s);
        BaseWarehouse w = new BaseWarehouse();
        w.setId(1L); w.setWarehouseName("原料仓");
        lenient().when(warehouseMapper.selectById(1L)).thenReturn(w);

        service.check(receiptId);

        ArgumentCaptor<PurReceipt> upd = ArgumentCaptor.forClass(PurReceipt.class);
        verify(receiptMapper).updateById(upd.capture());
        assertThat(upd.getValue().getBillStatus()).isEqualTo("CHECKED");
        assertThat(upd.getValue().getInspectorName()).isEqualTo("gpssong1");
    }

    @Test
    @DisplayName("check: 食品级入库单, PUR_RECEIPT 键无 IQC 且 PUR_ORDER 键也无 → 仍报「未关联 IQC」")
    void check_foodGrade_noIqcAnywhere_stillBlocked() {
        Long receiptId = 778L;
        PurReceipt receipt = new PurReceipt();
        receipt.setId(receiptId);
        receipt.setBillNo("RKP202611080002");
        receipt.setBillStatus("DRAFT");
        receipt.setOrderId(901L);
        receipt.setSupplierId(60L);
        receipt.setWarehouseId(1L);
        lenient().when(receiptMapper.selectById(receiptId)).thenReturn(receipt);

        PurReceiptDetail d = new PurReceiptDetail();
        d.setReceiptId(receiptId);
        d.setProductId(200L);
        d.setQty(new BigDecimal("5"));
        d.setPrice(BigDecimal.ZERO);
        lenient().when(receiptDetailMapper.selectByReceiptId(receiptId)).thenReturn(Arrays.asList(d));

        lenient().when(qmsInspectionService.isFoodGradeProduct(200L)).thenReturn(true);
        lenient().when(qmsInspectionService.hasPassedIqc(receiptId)).thenReturn(false);
        lenient().when(qmsInspectionService.findIqcByOrderId(901L)).thenReturn(null);

        BaseSupplier s = new BaseSupplier();
        s.setId(60L); s.setSupplierName("浙江宁杨石化"); s.setStatus(1);
        lenient().when(supplierMapper.selectById(60L)).thenReturn(s);

        assertThatThrownBy(() -> service.check(receiptId))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未关联 IQC");
    }

    // ===== v1.1.188: 入库审核登记 RAW 原料批 (XK 批次溯源「原料批」列表的数据来源) =====

    @Test
    @DisplayName("check: 逐明细行登记原料批; 无批号的行跳过")
    void check_registersRawBatchPerLineWithBatchNo() {
        Long receiptId = 780L;
        PurReceipt receipt = new PurReceipt();
        receipt.setId(receiptId);
        receipt.setBillNo("RKP202611140005");
        receipt.setBillStatus("DRAFT");
        receipt.setSupplierId(60L);
        receipt.setWarehouseId(1L);
        lenient().when(receiptMapper.selectById(receiptId)).thenReturn(receipt);

        PurReceiptDetail withBatch = new PurReceiptDetail();
        withBatch.setReceiptId(receiptId);
        withBatch.setProductId(200L);
        withBatch.setBatchNo("SK216A08C1");
        withBatch.setQty(new BigDecimal("1500"));
        withBatch.setPrice(BigDecimal.ZERO);

        PurReceiptDetail noBatch = new PurReceiptDetail();
        noBatch.setReceiptId(receiptId);
        noBatch.setProductId(201L);
        noBatch.setQty(new BigDecimal("8"));
        noBatch.setPrice(BigDecimal.ZERO);

        lenient().when(receiptDetailMapper.selectByReceiptId(receiptId))
                .thenReturn(Arrays.asList(withBatch, noBatch));
        BaseWarehouse w = new BaseWarehouse();
        w.setId(1L); w.setWarehouseName("原料仓");
        lenient().when(warehouseMapper.selectById(1L)).thenReturn(w);

        service.check(receiptId);

        verify(mesReportService).registerRawOnReceipt(
                "SK216A08C1", 200L, 60L, "RKP202611140005", new BigDecimal("1500"));
        verify(mesReportService, times(1)).registerRawOnReceipt(any(), any(), any(), any(), any());
    }
}