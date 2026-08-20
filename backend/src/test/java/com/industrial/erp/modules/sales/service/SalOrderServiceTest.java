package com.industrial.erp.modules.sales.service;

import com.industrial.erp.modules.base.entity.BaseCustomer;
import com.industrial.erp.modules.base.mapper.BaseCustomerMapper;
import com.industrial.erp.modules.sales.entity.SalOrder;
import com.industrial.erp.modules.sales.entity.SalOrderDetail;
import com.industrial.erp.modules.sales.mapper.SalOrderDetailMapper;
import com.industrial.erp.modules.sales.mapper.SalOrderMapper;
import com.industrial.erp.modules.sales.mapper.SalDeliveryDetailMapper;
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
 * v1.1.19+: 销售订单公式单元测试 (含税单价口径, 订单不写 AR).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SalOrderService 含税单价口径")
class SalOrderServiceTest {

    @Mock private SalOrderMapper orderMapper;
    @Mock private SalOrderDetailMapper detailMapper;
    @Mock private SalDeliveryDetailMapper deliveryDetailMapper;
    @Mock private BaseCustomerMapper customerMapper;
    @Mock private BillNoGenerator billNoGenerator;
    @Mock private PermissionService permService;
    @Mock private OperLogPublisher operLogPublisher;

    @InjectMocks private SalOrderService service;

    @Test
    @DisplayName("add 默认税率13%: amount=200, totalAmount=totalAmountTax=200")
    void add_defaultTaxRate13() {
        BaseCustomer c = new BaseCustomer();
        c.setId(50L); c.setCustomerName("客户A"); c.setTaxRate(new BigDecimal("13.00"));
        lenient().when(customerMapper.selectById(50L)).thenReturn(c);

        lenient().doAnswer(inv -> {
            SalOrder arg = inv.getArgument(0);
            arg.setId(System.nanoTime());
            return 1;
        }).when(orderMapper).insert(any(SalOrder.class));

        SalOrder order = new SalOrder();
        order.setCustomerId(50L);
        SalOrderDetail d = new SalOrderDetail();
        d.setProductId(200L); d.setQty(new BigDecimal("2")); d.setPrice(new BigDecimal("100"));
        order.setDetails(Arrays.asList(d));

        service.add(order);

        SalOrderDetail detail = order.getDetails().get(0);
        assertThat(detail.getAmount()).isEqualByComparingTo("200.0000");
        assertThat(detail.getTaxAmount()).isEqualByComparingTo("0.0000");
        assertThat(detail.getAmountTax()).isEqualByComparingTo("200.0000");
        assertThat(order.getTotalAmount()).isEqualByComparingTo("200.0000");
        assertThat(order.getTotalAmountTax()).isEqualByComparingTo("200.0000");
        assertThat(order.getTaxAmount()).isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("add 客户税率为0: amount=200 不变, taxAmount=0")
    void add_customerTaxRate0() {
        BaseCustomer c = new BaseCustomer();
        c.setId(50L); c.setCustomerName("客户A"); c.setTaxRate(BigDecimal.ZERO);
        lenient().when(customerMapper.selectById(50L)).thenReturn(c);
        lenient().doAnswer(inv -> {
            SalOrder arg = inv.getArgument(0);
            arg.setId(System.nanoTime());
            return 1;
        }).when(orderMapper).insert(any(SalOrder.class));

        SalOrder order = new SalOrder();
        order.setCustomerId(50L);
        SalOrderDetail d = new SalOrderDetail();
        d.setProductId(200L); d.setQty(new BigDecimal("2")); d.setPrice(new BigDecimal("100"));
        order.setDetails(Arrays.asList(d));

        service.add(order);

        assertThat(order.getTotalAmount()).isEqualByComparingTo("200.0000");
        assertThat(order.getTotalAmountTax()).isEqualByComparingTo("200.0000");
        assertThat(order.getTaxAmount()).isEqualByComparingTo("0.0000");
    }
}