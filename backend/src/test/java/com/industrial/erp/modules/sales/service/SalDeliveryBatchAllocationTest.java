package com.industrial.erp.modules.sales.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.industrial.erp.modules.base.entity.BaseCustomer;
import com.industrial.erp.modules.base.entity.BaseWarehouse;
import com.industrial.erp.modules.base.mapper.BaseCustomerMapper;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.industrial.erp.modules.base.mapper.BaseWarehouseMapper;
import com.industrial.erp.modules.finance.service.FinArapService;
import com.industrial.erp.modules.inventory.entity.InvStock;
import com.industrial.erp.modules.inventory.mapper.InvLedgerMapper;
import com.industrial.erp.modules.inventory.mapper.InvStockMapper;
import com.industrial.erp.modules.inventory.service.StockService;
import com.industrial.erp.modules.mes.service.MesReportService;
import com.industrial.erp.modules.sales.dto.SalDeliveryCheckDTO;
import com.industrial.erp.modules.sales.entity.SalDelivery;
import com.industrial.erp.modules.sales.entity.SalDeliveryDetail;
import com.industrial.erp.modules.sales.mapper.SalDeliveryDetailMapper;
import com.industrial.erp.modules.sales.mapper.SalDeliveryMapper;
import com.industrial.erp.modules.sales.mapper.SalOrderDetailMapper;
import com.industrial.erp.modules.sales.mapper.SalOrderMapper;
import com.industrial.erp.modules.system.aspect.OperLogPublisher;
import com.industrial.erp.security.PermissionService;
import com.industrial.erp.utils.BillNoGenerator;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v1.1.152: 销售出库审核「按指定批次 + 数量出库」.
 * <p>不传分配时走旧的按 batch_no 自动拆分 (零回归); 传了则严格按分配, 且校验
 * 合计 = 行数量 / 批次存在 / 逐批可用库存.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SalDeliveryService 审核批次分配")
class SalDeliveryBatchAllocationTest {

    @Mock private SalDeliveryMapper deliveryMapper;
    @Mock private SalDeliveryDetailMapper detailMapper;
    @Mock private BaseCustomerMapper customerMapper;
    @Mock private BaseWarehouseMapper warehouseMapper;
    @Mock private BillNoGenerator billNoGenerator;
    @Mock private StockService stockService;
    @Mock private FinArapService arapService;
    @Mock private PermissionService permService;
    @Mock private SalOrderDetailMapper orderDetailMapper;
    @Mock private OperLogPublisher operLogPublisher;
    @Mock private BaseProductMapper productMapper;
    @Mock private SalOrderMapper orderMapper;
    @Mock private MesReportService mesReportService;
    @Mock private InvStockMapper inventoryStockMapper;
    @Mock private InvLedgerMapper ledgerMapper;

    @InjectMocks private SalDeliveryService service;

    private static final Long DELIVERY_ID = 100L;
    private static final Long DETAIL_ID = 300L;
    private static final Long CUSTOMER_ID = 50L;
    private static final Long WAREHOUSE_ID = 1L;
    private static final Long PRODUCT_ID = 200L;

    /** outStock 调用记录: "batchNo:qty" */
    private final List<String> outCalls = new ArrayList<>();

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new Configuration(), "test");
        TableInfoHelper.initTableInfo(assistant, SalDelivery.class);
        TableInfoHelper.initTableInfo(assistant, SalDeliveryDetail.class);
    }

    @BeforeEach
    void setUp() {
        outCalls.clear();
        // 每次出库成本 = qty × 2, 便于断言整行成本
        lenient().when(stockService.outStock(any(), any(), any(), any(), any(), any(), any(), any(),
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    String batchNo = inv.getArgument(11);
                    BigDecimal qty = inv.getArgument(12);
                    outCalls.add((batchNo == null ? "" : batchNo) + ":" + qty.stripTrailingZeros().toPlainString());
                    return qty.multiply(new BigDecimal("2")).setScale(4);
                });
    }

    private SalDelivery stubDelivery(String detailBatchNo, String qty) {
        SalDelivery d = new SalDelivery();
        d.setId(DELIVERY_ID);
        d.setBillNo("CKP152");
        d.setBillStatus("DRAFT");
        d.setCustomerId(CUSTOMER_ID);
        d.setWarehouseId(WAREHOUSE_ID);
        d.setTotalAmount(new BigDecimal(qty).multiply(new BigDecimal("10")));
        d.setTotalAmountTax(d.getTotalAmount());
        when(deliveryMapper.selectById(DELIVERY_ID)).thenReturn(d);
        when(deliveryMapper.update(any(), any())).thenReturn(1);
        when(customerMapper.selectById(CUSTOMER_ID)).thenReturn(new BaseCustomer());

        BaseWarehouse w = new BaseWarehouse();
        w.setId(WAREHOUSE_ID);
        w.setWarehouseName("成品仓");
        when(warehouseMapper.selectById(WAREHOUSE_ID)).thenReturn(w);

        SalDeliveryDetail det = new SalDeliveryDetail();
        det.setId(DETAIL_ID);
        det.setDeliveryId(DELIVERY_ID);
        det.setProductId(PRODUCT_ID);
        det.setProductName("PE 膜");
        det.setUnitId(1L);
        det.setUnitName("kg");
        det.setQty(new BigDecimal(qty));
        det.setPrice(new BigDecimal("10"));
        det.setBatchNo(detailBatchNo);
        when(detailMapper.selectByDeliveryId(DELIVERY_ID)).thenReturn(Collections.singletonList(det));
        return d;
    }

    private SalDeliveryCheckDTO req(SalDeliveryCheckDTO.BatchQty... allocs) {
        SalDeliveryCheckDTO dto = new SalDeliveryCheckDTO();
        SalDeliveryCheckDTO.Line line = new SalDeliveryCheckDTO.Line();
        line.setDetailId(DETAIL_ID);
        line.setAllocations(Arrays.asList(allocs));
        dto.setDetails(Collections.singletonList(line));
        return dto;
    }

    private static SalDeliveryCheckDTO.BatchQty alloc(String batchNo, String qty) {
        SalDeliveryCheckDTO.BatchQty b = new SalDeliveryCheckDTO.BatchQty();
        b.setBatchNo(batchNo);
        b.setQty(new BigDecimal(qty));
        return b;
    }

    @Test
    @DisplayName("显式分配: 按指定批次数量逐批出库, 成本累加, 明细回写实际批次")
    void check_explicitAllocation() {
        SalDelivery d = stubDelivery("A", "10");
        when(inventoryStockMapper.listByWarehouseAndProduct(WAREHOUSE_ID, PRODUCT_ID)).thenReturn(Arrays.asList(
                stock("A", "4"), stock("B", "6")));

        service.check(DELIVERY_ID, req(alloc("A", "4"), alloc("B", "6")));

        assertThat(outCalls).containsExactly("A:4", "B:6");
        ArgumentCaptor<SalDeliveryDetail> cap = ArgumentCaptor.forClass(SalDeliveryDetail.class);
        verify(detailMapper).updateById(cap.capture());
        assertThat(cap.getValue().getBatchNo()).isEqualTo("A|B");
        assertThat(cap.getValue().getCostAmount()).isEqualByComparingTo("20");   // (4+6)*2
        assertThat(d.getProfitAmount()).isNull(); // 主表更新走 upd 对象, 原对象不变
    }

    @Test
    @DisplayName("显式分配合计 != 行数量: 报错且不扣库存")
    void check_sumMismatch() {
        stubDelivery("A", "10");
        when(inventoryStockMapper.listByWarehouseAndProduct(WAREHOUSE_ID, PRODUCT_ID))
                .thenReturn(Collections.singletonList(stock("A", "100")));

        assertThatThrownBy(() -> service.check(DELIVERY_ID, req(alloc("A", "4"))))
                .hasMessageContaining("不一致");
        assertThat(outCalls).isEmpty();
    }

    @Test
    @DisplayName("显式分配超过该批可用: 报批次库存不足且不扣库存")
    void check_batchInsufficient() {
        stubDelivery("A", "10");
        when(inventoryStockMapper.listByWarehouseAndProduct(WAREHOUSE_ID, PRODUCT_ID))
                .thenReturn(Collections.singletonList(stock("A", "4")));

        assertThatThrownBy(() -> service.check(DELIVERY_ID, req(alloc("A", "10"))))
                .hasMessageContaining("批次 A 库存不足")
                .hasMessageContaining("可用=4");
        assertThat(outCalls).isEmpty();
    }

    @Test
    @DisplayName("显式分配指定了仓库里没有的批次: 报错且不扣库存")
    void check_unknownBatch() {
        stubDelivery("A", "10");
        when(inventoryStockMapper.listByWarehouseAndProduct(WAREHOUSE_ID, PRODUCT_ID))
                .thenReturn(Collections.singletonList(stock("A", "10")));

        assertThatThrownBy(() -> service.check(DELIVERY_ID, req(alloc("Z", "10"))))
                .hasMessageContaining("批次 Z 在该仓库没有库存记录");
        assertThat(outCalls).isEmpty();
    }

    @Test
    @DisplayName("不传分配 (App/旧客户端): 沿用按 batch_no 自动拆分")
    void check_legacyAutoSplit() {
        stubDelivery("A|B", "10");
        when(inventoryStockMapper.listByWarehouseAndProduct(WAREHOUSE_ID, PRODUCT_ID)).thenReturn(Arrays.asList(
                stock("A", "4"), stock("B", "6")));

        service.check(DELIVERY_ID);

        assertThat(outCalls).containsExactly("A:4", "B:6");
    }

    @Test
    @DisplayName("浮点尾差 (0.1+0.2) 并入最后一笔, 不误判合计不一致")
    void check_residualAbsorbed() {
        stubDelivery("A", "0.3");
        when(inventoryStockMapper.listByWarehouseAndProduct(WAREHOUSE_ID, PRODUCT_ID))
                .thenReturn(Collections.singletonList(stock("A", "10")));

        service.check(DELIVERY_ID, req(alloc("A", "0.1"), alloc("A", "0.2")));

        assertThat(outCalls).hasSize(2);
        BigDecimal total = new BigDecimal(outCalls.get(0).split(":")[1]).add(new BigDecimal(outCalls.get(1).split(":")[1]));
        assertThat(total).isEqualByComparingTo("0.3");
    }

    @Test
    @DisplayName("明细回写批次串: 无批次段按习惯放末尾 (A|), 不出现 |A")
    void check_batchNoDisplayPutsNoBatchLast() {
        stubDelivery("", "10");
        when(inventoryStockMapper.listByWarehouseAndProduct(WAREHOUSE_ID, PRODUCT_ID)).thenReturn(Arrays.asList(
                stock(null, "4"), stock("A", "6")));

        service.check(DELIVERY_ID, req(alloc("", "4"), alloc("A", "6")));

        ArgumentCaptor<SalDeliveryDetail> cap = ArgumentCaptor.forClass(SalDeliveryDetail.class);
        verify(detailMapper).updateById(cap.capture());
        assertThat(cap.getValue().getBatchNo()).isEqualTo("A|");
    }

    private static InvStock stock(String batchNo, String qty) {
        InvStock s = new InvStock();
        s.setBatchNo(batchNo);
        s.setQty(new BigDecimal(qty));
        return s;
    }
}
