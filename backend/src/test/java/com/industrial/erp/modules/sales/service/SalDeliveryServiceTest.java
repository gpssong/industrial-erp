package com.industrial.erp.modules.sales.service;

import com.industrial.erp.modules.base.entity.BaseCustomer;
import com.industrial.erp.modules.base.entity.BaseWarehouse;
import com.industrial.erp.modules.base.mapper.BaseCustomerMapper;
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
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v1.1.19+: 销售出库新增公式单元测试.
 *
 * <p>覆盖含税单价口径:
 * <ul>
 *   <li>默认税率 13%: amount = qty*price, taxAmount=0, amountTax=amount</li>
 *   <li>带整单折扣: totalAmount = totalAmountTax = sum(amount) - discount</li>
 *   <li>带抹零: totalAmount = totalAmountTax = sum(amount) - discount - tail</li>
 *   <li>客户税率为 0: taxAmount=0, amount 不变</li>
 *   <li>check 流程: creditUsed 预占用 totalAmountTax (= 开单金额)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SalDeliveryService 含税单价口径")
class SalDeliveryServiceTest {

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

    @InjectMocks private SalDeliveryService service;

    private static final Long CUSTOMER_ID = 50L;
    private static final Long WAREHOUSE_ID = 1L;
    private static final Long PRODUCT_ID = 200L;

    private SalDelivery buildDelivery(BigDecimal discount, BigDecimal tail, BigDecimal customerTaxRate) {
        BaseCustomer c = new BaseCustomer();
        c.setId(CUSTOMER_ID);
        c.setCustomerName("客户A");
        c.setTaxRate(customerTaxRate);
        lenient().when(customerMapper.selectById(CUSTOMER_ID)).thenReturn(c);

        BaseWarehouse w = new BaseWarehouse();
        w.setId(WAREHOUSE_ID);
        w.setWarehouseName("主仓");
        lenient().when(warehouseMapper.selectById(WAREHOUSE_ID)).thenReturn(w);

        SalDelivery delivery = new SalDelivery();
        delivery.setCustomerId(CUSTOMER_ID);
        delivery.setWarehouseId(WAREHOUSE_ID);
        delivery.setDiscountAmount(discount);
        delivery.setTailAmount(tail);

        SalDeliveryDetail d = new SalDeliveryDetail();
        d.setProductId(PRODUCT_ID);
        d.setUnitId(1L);
        d.setUnitName("个");
        d.setQty(new BigDecimal("2"));
        d.setPrice(new BigDecimal("100"));   // 含税单价
        delivery.setDetails(Arrays.asList(d));
        return delivery;
    }

    private void captureInserted(SalDelivery d) {
        // deliveryMapper.insert 后给个 id
        lenient().doAnswer(inv -> {
            SalDelivery arg = inv.getArgument(0);
            arg.setId(System.nanoTime());
            return 1;
        }).when(deliveryMapper).insert(any(SalDelivery.class));
    }

    @Test
    @DisplayName("add 默认税率13%: amount=200, taxAmount=0, amountTax=200, 主表 totalAmount=totalAmountTax=200")
    void add_defaultTaxRate13() {
        SalDelivery delivery = buildDelivery(BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("13.00"));
        captureInserted(delivery);
        doNothing();

        service.add(delivery);

        // 验证明细字段
        SalDeliveryDetail detail = delivery.getDetails().get(0);
        assertThat(detail.getAmount()).isEqualByComparingTo("200.0000");
        assertThat(detail.getTaxAmount()).isEqualByComparingTo("0.0000");
        assertThat(detail.getAmountTax()).isEqualByComparingTo("200.0000");

        // 验证主表
        assertThat(delivery.getTotalAmount()).isEqualByComparingTo("200.0000");
        assertThat(delivery.getTaxAmount()).isEqualByComparingTo("0.0000");
        assertThat(delivery.getTotalAmountTax()).isEqualByComparingTo("200.0000");
    }

    @Test
    @DisplayName("add 带折扣10: totalAmount=190, totalAmountTax=190 (开单金额)")
    void add_withDiscount() {
        SalDelivery delivery = buildDelivery(new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("13.00"));
        captureInserted(delivery);

        service.add(delivery);

        assertThat(delivery.getTotalAmount()).isEqualByComparingTo("190.0000");
        assertThat(delivery.getTotalAmountTax()).isEqualByComparingTo("190.0000");
        assertThat(delivery.getTaxAmount()).isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("add 带抹零0.5: totalAmount=189.5, totalAmountTax=189.5")
    void add_withTail() {
        SalDelivery delivery = buildDelivery(new BigDecimal("10"), new BigDecimal("0.5"), new BigDecimal("13.00"));
        captureInserted(delivery);

        service.add(delivery);

        assertThat(delivery.getTotalAmount()).isEqualByComparingTo("189.5000");
        assertThat(delivery.getTotalAmountTax()).isEqualByComparingTo("189.5000");
    }

    @Test
    @DisplayName("add 客户税率0%: taxAmount=0, amount 不变")
    void add_customerTaxRate0() {
        SalDelivery delivery = buildDelivery(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        captureInserted(delivery);

        service.add(delivery);

        SalDeliveryDetail detail = delivery.getDetails().get(0);
        assertThat(detail.getAmount()).isEqualByComparingTo("200.0000");
        assertThat(detail.getTaxAmount()).isEqualByComparingTo("0.0000");
        assertThat(detail.getAmountTax()).isEqualByComparingTo("200.0000");
        assertThat(delivery.getTotalAmountTax()).isEqualByComparingTo("200.0000");
    }

    @Test
    @DisplayName("check creditUsed 预占用 totalAmountTax (= 开单金额, 不是 1.13×)")
    void check_creditUsesTotalAmountTax() {
        SalDelivery d = new SalDelivery();
        d.setId(100L);
        d.setBillNo("CKP001");
        d.setBillStatus("DRAFT");
        d.setCustomerId(CUSTOMER_ID);
        d.setWarehouseId(WAREHOUSE_ID);
        d.setTotalAmount(new BigDecimal("200"));
        d.setTotalAmountTax(new BigDecimal("200"));  // v1.1.19+: 已与 totalAmount 相等

        BaseCustomer c = new BaseCustomer();
        c.setId(CUSTOMER_ID);
        c.setCreditLimit(new BigDecimal("5000"));
        c.setCreditUsed(new BigDecimal("0"));
        when(customerMapper.selectById(CUSTOMER_ID)).thenReturn(c);
        when(deliveryMapper.selectById(100L)).thenReturn(d);
        when(detailMapper.selectByDeliveryId(100L)).thenReturn(Collections.emptyList());
        BaseWarehouse wh = new BaseWarehouse();
        wh.setId(WAREHOUSE_ID);
        wh.setWarehouseName("主仓");
        when(warehouseMapper.selectById(WAREHOUSE_ID)).thenReturn(wh);

        service.check(100L);

        // 验证预占的是 200, 不是 226
        ArgumentCaptor<BigDecimal> cap = ArgumentCaptor.forClass(BigDecimal.class);
        verify(customerMapper).incrCreditUsed(any(), cap.capture());
        assertThat(cap.getValue()).isEqualByComparingTo("200");
    }

    private void doNothing() {
        lenient().doNothing().when(permService).requirePerm(any());
        lenient().when(billNoGenerator.generate(any())).thenReturn("CKP20260820001");
    }
}