package com.industrial.erp.modules.purchase.service;

import com.industrial.erp.modules.base.entity.BaseSupplier;
import com.industrial.erp.modules.base.entity.BaseWarehouse;
import com.industrial.erp.modules.base.mapper.BaseSupplierMapper;
import com.industrial.erp.modules.base.mapper.BaseWarehouseMapper;
import com.industrial.erp.modules.finance.service.FinArapService;
import com.industrial.erp.modules.inventory.service.StockService;
import com.industrial.erp.modules.purchase.entity.PurReturn;
import com.industrial.erp.modules.purchase.entity.PurReturnDetail;
import com.industrial.erp.modules.purchase.mapper.PurReturnDetailMapper;
import com.industrial.erp.modules.purchase.mapper.PurReturnMapper;
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
 * v1.1.19+: 采购退货新增公式单元测试 (含税单价口径).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PurReturnService 含税单价口径")
class PurReturnServiceTest {

    @Mock private PurReturnMapper returnMapper;
    @Mock private PurReturnDetailMapper returnDetailMapper;
    @Mock private BaseSupplierMapper supplierMapper;
    @Mock private BaseWarehouseMapper warehouseMapper;
    @Mock private BillNoGenerator billNoGenerator;
    @Mock private StockService stockService;
    @Mock private FinArapService arapService;
    @Mock private PermissionService permService;

    @InjectMocks private PurReturnService service;

    @Test
    @DisplayName("add 默认税率13%: amount=200, taxAmount=0, totalAmount=200")
    void add_defaultTaxRate13() {
        BaseSupplier s = new BaseSupplier();
        s.setId(60L); s.setSupplierName("供应商A"); s.setTaxRate(new BigDecimal("13.00"));
        lenient().when(supplierMapper.selectById(60L)).thenReturn(s);

        BaseWarehouse w = new BaseWarehouse();
        w.setId(1L); w.setWarehouseName("主仓");
        lenient().when(warehouseMapper.selectById(1L)).thenReturn(w);

        lenient().doAnswer(inv -> {
            PurReturn arg = inv.getArgument(0);
            arg.setId(System.nanoTime());
            return 1;
        }).when(returnMapper).insert(any(PurReturn.class));

        PurReturn ret = new PurReturn();
        ret.setSupplierId(60L); ret.setWarehouseId(1L);
        PurReturnDetail d = new PurReturnDetail();
        d.setProductId(200L); d.setQty(new BigDecimal("2")); d.setPrice(new BigDecimal("100"));
        ret.setDetails(Arrays.asList(d));

        service.add(ret);

        PurReturnDetail detail = ret.getDetails().get(0);
        assertThat(detail.getAmount()).isEqualByComparingTo("200.0000");
        assertThat(detail.getTaxAmount()).isEqualByComparingTo("0.0000");
        assertThat(detail.getAmountTax()).isEqualByComparingTo("200.0000");
        assertThat(ret.getTotalAmount()).isEqualByComparingTo("200.0000");
        assertThat(ret.getTotalAmountTax()).isEqualByComparingTo("200.0000");
        assertThat(ret.getTaxAmount()).isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("add 供应商税率0: amount=200 不变, taxAmount=0")
    void add_supplierTaxRate0() {
        BaseSupplier s = new BaseSupplier();
        s.setId(60L); s.setSupplierName("供应商A"); s.setTaxRate(BigDecimal.ZERO);
        lenient().when(supplierMapper.selectById(60L)).thenReturn(s);
        BaseWarehouse w = new BaseWarehouse();
        w.setId(1L); w.setWarehouseName("主仓");
        lenient().when(warehouseMapper.selectById(1L)).thenReturn(w);
        lenient().doAnswer(inv -> {
            PurReturn arg = inv.getArgument(0);
            arg.setId(System.nanoTime());
            return 1;
        }).when(returnMapper).insert(any(PurReturn.class));

        PurReturn ret = new PurReturn();
        ret.setSupplierId(60L); ret.setWarehouseId(1L);
        PurReturnDetail d = new PurReturnDetail();
        d.setProductId(200L); d.setQty(new BigDecimal("2")); d.setPrice(new BigDecimal("100"));
        ret.setDetails(Arrays.asList(d));

        service.add(ret);

        assertThat(ret.getTotalAmount()).isEqualByComparingTo("200.0000");
        assertThat(ret.getTotalAmountTax()).isEqualByComparingTo("200.0000");
        assertThat(ret.getTaxAmount()).isEqualByComparingTo("0.0000");
    }
}