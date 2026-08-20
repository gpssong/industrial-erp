package com.industrial.erp.modules.purchase.service;

import com.industrial.erp.modules.base.entity.BaseSupplier;
import com.industrial.erp.modules.base.entity.BaseWarehouse;
import com.industrial.erp.modules.base.mapper.BaseSupplierMapper;
import com.industrial.erp.modules.base.mapper.BaseWarehouseMapper;
import com.industrial.erp.modules.finance.service.FinArapService;
import com.industrial.erp.modules.inventory.service.StockService;
import com.industrial.erp.modules.purchase.entity.PurReceipt;
import com.industrial.erp.modules.purchase.entity.PurReceiptDetail;
import com.industrial.erp.modules.purchase.mapper.PurOrderDetailMapper;
import com.industrial.erp.modules.purchase.mapper.PurOrderMapper;
import com.industrial.erp.modules.purchase.mapper.PurReceiptDetailMapper;
import com.industrial.erp.modules.purchase.mapper.PurReceiptMapper;
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
}