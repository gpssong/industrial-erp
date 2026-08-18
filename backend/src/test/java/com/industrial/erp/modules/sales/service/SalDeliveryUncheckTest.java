package com.industrial.erp.modules.sales.service;

import com.industrial.erp.common.Constants;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseWarehouse;
import com.industrial.erp.modules.base.mapper.BaseCustomerMapper;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.industrial.erp.modules.base.mapper.BaseWarehouseMapper;
import com.industrial.erp.modules.finance.service.FinArapService;
import com.industrial.erp.modules.inventory.service.StockService;
import com.industrial.erp.modules.sales.entity.SalDelivery;
import com.industrial.erp.modules.sales.entity.SalDeliveryDetail;
import com.industrial.erp.modules.sales.mapper.SalDeliveryDetailMapper;
import com.industrial.erp.modules.sales.mapper.SalDeliveryMapper;
import com.industrial.erp.modules.sales.mapper.SalOrderDetailMapper;
import com.industrial.erp.modules.system.aspect.OperLogPublisher;
import com.industrial.erp.security.PermissionService;
import com.industrial.erp.utils.BillNoGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v1.1.18+: 销售出库反审核单元测试.
 *
 * <p>覆盖:
 * <ol>
 *   <li>正常路径: inStock + decrCreditUsed + AR 删除</li>
 *   <li>状态错 (非 CHECKED) → 抛 BizException</li>
 *   <li>AR 已核销 (paidAmount>0) → 抛 BizException, 库存/信用 不动</li>
 *   <li>明细为空 (极端) → 不调 inStock, 但仍调 decrCreditUsed + AR 删除</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SalDeliveryService 反审核")
class SalDeliveryUncheckTest {

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

    @InjectMocks private SalDeliveryService service;

    private static final Long DELIVERY_ID = 100L;
    private static final Long CUSTOMER_ID = 50L;
    private static final Long WAREHOUSE_ID = 1L;
    private static final Long PRODUCT_ID = 200L;
    private static final String BATCH_NO = "B202608";
    private static final BigDecimal TOTAL_AMOUNT = new BigDecimal("500.00");

    @BeforeEach
    void setUp() {
        lenient().doNothing().when(permService).requirePerm(anyString());
    }

    @AfterEach
    void tearDown() {}

    private SalDelivery mockDelivery() {
        SalDelivery d = new SalDelivery();
        d.setId(DELIVERY_ID);
        d.setBillNo("OUT202608-001");
        d.setBillStatus(Constants.STATUS_CHECKED);
        d.setWarehouseId(WAREHOUSE_ID);
        d.setCustomerId(CUSTOMER_ID);
        d.setTotalAmountTax(TOTAL_AMOUNT);
        d.setCostAmount(new BigDecimal("300"));
        d.setProfitAmount(new BigDecimal("200"));
        return d;
    }

    private SalDeliveryDetail mockDetail() {
        SalDeliveryDetail det = new SalDeliveryDetail();
        det.setId(1L);
        det.setDeliveryId(DELIVERY_ID);
        det.setProductId(PRODUCT_ID);
        det.setUnitId(2L);  // 副单位 "箱"
        det.setUnitName("箱");
        det.setBatchNo(BATCH_NO);
        det.setQty(new BigDecimal("1"));
        det.setPrice(new BigDecimal("168"));
        det.setCostPrice(new BigDecimal("100"));
        return det;
    }

    private BaseWarehouse mockWarehouse() {
        BaseWarehouse wh = new BaseWarehouse();
        wh.setId(WAREHOUSE_ID);
        wh.setWarehouseName("主仓");
        return wh;
    }

    @Test
    @DisplayName("正常路径 - inStock + decrCreditUsed + AR 删除")
    void uncheck_success() {
        when(deliveryMapper.selectById(DELIVERY_ID)).thenReturn(mockDelivery());
        when(detailMapper.selectByDeliveryId(DELIVERY_ID)).thenReturn(Arrays.asList(mockDetail()));
        when(warehouseMapper.selectById(WAREHOUSE_ID)).thenReturn(mockWarehouse());

        assertThatCode(() -> service.uncheck(DELIVERY_ID)).doesNotThrowAnyException();

        // 1. inStock: 1箱 (=60卷主单位), price=100 (costPrice)
        verify(stockService, times(1)).inStock(
                eq(Constants.LEDGER_SAL_DELIVERY), eq(DELIVERY_ID), eq("OUT202608-001"), eq(1L),
                eq(WAREHOUSE_ID), eq("主仓"), eq(null), eq(null),
                eq(PRODUCT_ID), eq(2L), eq("箱"), eq(BATCH_NO),
                eq(new BigDecimal("1")), eq(new BigDecimal("100")), anyString(),
                eq(null), eq(CUSTOMER_ID), anyString()
        );
        // 2. decrCreditUsed: customerId=50, amount=500 (totalAmountTax)
        verify(customerMapper, times(1)).decrCreditUsed(CUSTOMER_ID, TOTAL_AMOUNT);
        // 3. requireCancelableAndDelete: 按 sourceBillType+sourceBillId
        verify(arapService, times(1)).requireCancelableAndDelete(Constants.LEDGER_SAL_DELIVERY, DELIVERY_ID);
        // 4. 主表更新: 状态=DRAFT, 清零成本/毛利
        verify(deliveryMapper, times(1)).updateById(any(SalDelivery.class));
    }

    @Test
    @DisplayName("状态非 CHECKED - 抛 BizException, 不调任何回退")
    void uncheck_wrongStatus_throws() {
        SalDelivery d = mockDelivery();
        d.setBillStatus(Constants.STATUS_DRAFT);  // 不是 CHECKED
        when(deliveryMapper.selectById(DELIVERY_ID)).thenReturn(d);

        assertThatThrownBy(() -> service.uncheck(DELIVERY_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只有已审核状态可反审核");

        verify(stockService, never()).inStock(anyString(), anyLong(), anyString(), anyLong(),
                anyLong(), anyString(), any(), any(), anyLong(), anyLong(), anyString(), any(),
                any(), any(), anyString(), any(), anyLong(), anyString());
        verify(customerMapper, never()).decrCreditUsed(anyLong(), any());
        verify(arapService, never()).requireCancelableAndDelete(anyString(), anyLong());
    }

    @Test
    @DisplayName("AR 已核销 - 抛 BizException, 库存/信用/AR 都抛错且前面动作都已执行 (事务回滚)")
    void uncheck_arAlreadyWriteoff_throws() {
        when(deliveryMapper.selectById(DELIVERY_ID)).thenReturn(mockDelivery());
        when(detailMapper.selectByDeliveryId(DELIVERY_ID)).thenReturn(Arrays.asList(mockDetail()));
        when(warehouseMapper.selectById(WAREHOUSE_ID)).thenReturn(mockWarehouse());
        doThrow(new BizException("AR 已核销")).when(arapService)
                .requireCancelableAndDelete(Constants.LEDGER_SAL_DELIVERY, DELIVERY_ID);

        assertThatThrownBy(() -> service.uncheck(DELIVERY_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("AR 已核销");

        // 库存和信用都已经调 (后续 AR 抛错, 事务整体回滚, DB 无副作用)
        verify(stockService, times(1)).inStock(anyString(), anyLong(), anyString(), anyLong(),
                anyLong(), anyString(), any(), any(), anyLong(), anyLong(), anyString(), any(),
                any(), any(), anyString(), any(), anyLong(), anyString());
        verify(customerMapper, times(1)).decrCreditUsed(CUSTOMER_ID, TOTAL_AMOUNT);
    }

    @Test
    @DisplayName("明细为空 - 仍走 decrCreditUsed + AR 删除 (无库存操作)")
    void uncheck_emptyDetails() {
        when(deliveryMapper.selectById(DELIVERY_ID)).thenReturn(mockDelivery());
        when(detailMapper.selectByDeliveryId(DELIVERY_ID)).thenReturn(Collections.emptyList());
        when(warehouseMapper.selectById(WAREHOUSE_ID)).thenReturn(mockWarehouse());

        assertThatCode(() -> service.uncheck(DELIVERY_ID)).doesNotThrowAnyException();

        verify(stockService, never()).inStock(anyString(), anyLong(), anyString(), anyLong(),
                anyLong(), anyString(), any(), any(), anyLong(), anyLong(), anyString(), any(),
                any(), any(), anyString(), any(), anyLong(), anyString());
        verify(customerMapper, times(1)).decrCreditUsed(CUSTOMER_ID, TOTAL_AMOUNT);
        verify(arapService, times(1)).requireCancelableAndDelete(Constants.LEDGER_SAL_DELIVERY, DELIVERY_ID);
    }

    @Test
    @DisplayName("总金额为 0 - 跳过 decrCreditUsed (防负信用)")
    void uncheck_zeroAmount_skipsCredit() {
        SalDelivery d = mockDelivery();
        d.setTotalAmountTax(BigDecimal.ZERO);
        when(deliveryMapper.selectById(DELIVERY_ID)).thenReturn(d);
        when(detailMapper.selectByDeliveryId(DELIVERY_ID)).thenReturn(Collections.emptyList());

        assertThatCode(() -> service.uncheck(DELIVERY_ID)).doesNotThrowAnyException();

        verify(customerMapper, never()).decrCreditUsed(anyLong(), any());
        verify(arapService, times(1)).requireCancelableAndDelete(Constants.LEDGER_SAL_DELIVERY, DELIVERY_ID);
    }
}