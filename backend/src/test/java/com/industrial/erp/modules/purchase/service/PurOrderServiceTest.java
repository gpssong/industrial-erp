package com.industrial.erp.modules.purchase.service;

import com.industrial.erp.modules.base.entity.BaseSupplier;
import com.industrial.erp.modules.base.mapper.BaseSupplierMapper;
import com.industrial.erp.modules.purchase.entity.PurOrder;
import com.industrial.erp.modules.purchase.entity.PurOrderDetail;
import com.industrial.erp.modules.purchase.mapper.PurOrderDetailMapper;
import com.industrial.erp.modules.purchase.mapper.PurOrderMapper;
import com.industrial.erp.modules.purchase.mapper.PurReceiptDetailMapper;
import com.industrial.erp.modules.system.aspect.OperLogPublisher;
import com.industrial.erp.security.PermissionService;
import com.industrial.erp.utils.BillNoGenerator;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;

/**
 * v1.1.19+: 采购订单公式单元测试 (含税单价口径, 订单不写 AP).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PurOrderService 含税单价口径")
class PurOrderServiceTest {

    @Mock private PurOrderMapper orderMapper;
    @Mock private PurOrderDetailMapper detailMapper;
    @Mock private PurReceiptDetailMapper receiptDetailMapper;
    @Mock private BaseSupplierMapper supplierMapper;
    @Mock private BillNoGenerator billNoGenerator;
    @Mock private PermissionService permService;
    @Mock private OperLogPublisher operLogPublisher;

    @InjectMocks private PurOrderService service;

    @Test
    @DisplayName("add 默认税率13%: amount=200, totalAmount=totalAmountTax=200")
    void add_defaultTaxRate13() {
        BaseSupplier s = new BaseSupplier();
        s.setId(60L); s.setSupplierName("供应商A"); s.setTaxRate(new BigDecimal("13.00"));
        lenient().when(supplierMapper.selectById(60L)).thenReturn(s);

        lenient().doAnswer(inv -> {
            PurOrder arg = inv.getArgument(0);
            arg.setId(System.nanoTime());
            return 1;
        }).when(orderMapper).insert(any(PurOrder.class));

        PurOrder order = new PurOrder();
        order.setSupplierId(60L);
        PurOrderDetail d = new PurOrderDetail();
        d.setProductId(200L); d.setQty(new BigDecimal("2")); d.setPrice(new BigDecimal("100"));
        order.setDetails(Arrays.asList(d));

        service.add(order);

        PurOrderDetail detail = order.getDetails().get(0);
        assertThat(detail.getAmount()).isEqualByComparingTo("200.0000");
        assertThat(detail.getTaxAmount()).isEqualByComparingTo("0.0000");
        assertThat(detail.getAmountTax()).isEqualByComparingTo("200.0000");
        assertThat(order.getTotalAmount()).isEqualByComparingTo("200.0000");
        assertThat(order.getTotalAmountTax()).isEqualByComparingTo("200.0000");
        assertThat(order.getTaxAmount()).isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("add 供应商税率为0: amount=200 不变, taxAmount=0")
    void add_supplierTaxRate0() {
        BaseSupplier s = new BaseSupplier();
        s.setId(60L); s.setSupplierName("供应商A"); s.setTaxRate(BigDecimal.ZERO);
        lenient().when(supplierMapper.selectById(60L)).thenReturn(s);
        lenient().doAnswer(inv -> {
            PurOrder arg = inv.getArgument(0);
            arg.setId(System.nanoTime());
            return 1;
        }).when(orderMapper).insert(any(PurOrder.class));

        PurOrder order = new PurOrder();
        order.setSupplierId(60L);
        PurOrderDetail d = new PurOrderDetail();
        d.setProductId(200L); d.setQty(new BigDecimal("2")); d.setPrice(new BigDecimal("100"));
        order.setDetails(Arrays.asList(d));

        service.add(order);

        assertThat(order.getTotalAmount()).isEqualByComparingTo("200.0000");
        assertThat(order.getTotalAmountTax()).isEqualByComparingTo("200.0000");
        assertThat(order.getTaxAmount()).isEqualByComparingTo("0.0000");
    }
}