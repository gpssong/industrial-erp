package com.industrial.erp.modules.sales.service;

import com.industrial.erp.modules.base.entity.BaseCustomer;
import com.industrial.erp.modules.base.entity.BaseWarehouse;
import com.industrial.erp.modules.base.mapper.BaseCustomerMapper;
import com.industrial.erp.modules.base.mapper.BaseWarehouseMapper;
import com.industrial.erp.modules.finance.service.FinArapService;
import com.industrial.erp.modules.inventory.service.StockService;
import com.industrial.erp.modules.sales.entity.SalReturn;
import com.industrial.erp.modules.sales.entity.SalReturnDetail;
import com.industrial.erp.modules.sales.mapper.SalReturnDetailMapper;
import com.industrial.erp.modules.sales.mapper.SalReturnMapper;
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
 * v1.1.19+: 销售退货新增公式单元测试.
 *
 * <p>验证含税口径下退货金额 = qty * price (不含税点).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SalReturnService 含税单价口径")
class SalReturnServiceTest {

    @Mock private SalReturnMapper returnMapper;
    @Mock private SalReturnDetailMapper returnDetailMapper;
    @Mock private BaseCustomerMapper customerMapper;
    @Mock private BaseWarehouseMapper warehouseMapper;
    @Mock private BillNoGenerator billNoGenerator;
    @Mock private StockService stockService;
    @Mock private FinArapService arapService;
    @Mock private PermissionService permService;

    @InjectMocks private SalReturnService service;

    @Test
    @DisplayName("add 默认税率13%: amount=200, taxAmount=0, 主表 totalAmount=200")
    void add_defaultTaxRate13() {
        BaseCustomer c = new BaseCustomer();
        c.setId(50L); c.setCustomerName("客户A"); c.setTaxRate(new BigDecimal("13.00"));
        lenient().when(customerMapper.selectById(50L)).thenReturn(c);

        BaseWarehouse w = new BaseWarehouse();
        w.setId(1L); w.setWarehouseName("主仓");
        lenient().when(warehouseMapper.selectById(1L)).thenReturn(w);

        lenient().doAnswer(inv -> {
            SalReturn arg = inv.getArgument(0);
            arg.setId(System.nanoTime());
            return 1;
        }).when(returnMapper).insert(any(SalReturn.class));

        SalReturn ret = new SalReturn();
        ret.setCustomerId(50L); ret.setWarehouseId(1L);
        SalReturnDetail d = new SalReturnDetail();
        d.setProductId(200L); d.setUnitId(1L); d.setUnitName("个");
        d.setQty(new BigDecimal("2")); d.setPrice(new BigDecimal("100"));
        ret.setDetails(Arrays.asList(d));

        service.add(ret);

        SalReturnDetail detail = ret.getDetails().get(0);
        assertThat(detail.getAmount()).isEqualByComparingTo("200.0000");
        assertThat(detail.getTaxAmount()).isEqualByComparingTo("0.0000");
        assertThat(detail.getAmountTax()).isEqualByComparingTo("200.0000");
        assertThat(ret.getTotalAmount()).isEqualByComparingTo("200.0000");
        assertThat(ret.getTotalAmountTax()).isEqualByComparingTo("200.0000");
        assertThat(ret.getTaxAmount()).isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("add 客户税率0: amount=200 不变, taxAmount=0")
    void add_taxRate0() {
        BaseCustomer c = new BaseCustomer();
        c.setId(50L); c.setCustomerName("客户A"); c.setTaxRate(BigDecimal.ZERO);
        lenient().when(customerMapper.selectById(50L)).thenReturn(c);
        BaseWarehouse w = new BaseWarehouse();
        w.setId(1L); w.setWarehouseName("主仓");
        lenient().when(warehouseMapper.selectById(1L)).thenReturn(w);
        lenient().doAnswer(inv -> {
            SalReturn arg = inv.getArgument(0);
            arg.setId(System.nanoTime());
            return 1;
        }).when(returnMapper).insert(any(SalReturn.class));

        SalReturn ret = new SalReturn();
        ret.setCustomerId(50L); ret.setWarehouseId(1L);
        SalReturnDetail d = new SalReturnDetail();
        d.setProductId(200L); d.setQty(new BigDecimal("2")); d.setPrice(new BigDecimal("100"));
        ret.setDetails(Arrays.asList(d));

        service.add(ret);

        assertThat(ret.getTotalAmount()).isEqualByComparingTo("200.0000");
        assertThat(ret.getTotalAmountTax()).isEqualByComparingTo("200.0000");
        assertThat(ret.getTaxAmount()).isEqualByComparingTo("0.0000");
    }
}