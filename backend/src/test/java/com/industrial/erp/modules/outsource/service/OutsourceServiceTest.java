package com.industrial.erp.modules.outsource.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.industrial.erp.common.Constants;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseSupplier;
import com.industrial.erp.modules.base.entity.BaseWarehouse;
import com.industrial.erp.modules.base.mapper.BaseSupplierMapper;
import com.industrial.erp.modules.base.mapper.BaseWarehouseMapper;
import com.industrial.erp.modules.inventory.service.StockService;
import com.industrial.erp.modules.outsource.entity.OutIssue;
import com.industrial.erp.modules.outsource.entity.OutIssueDetail;
import com.industrial.erp.modules.outsource.entity.OutProcessingIn;
import com.industrial.erp.modules.outsource.entity.OutProcessingInDetail;
import com.industrial.erp.modules.outsource.mapper.OutIssueDetailMapper;
import com.industrial.erp.modules.outsource.mapper.OutIssueMapper;
import com.industrial.erp.modules.outsource.mapper.OutProcessingInDetailMapper;
import com.industrial.erp.modules.outsource.mapper.OutProcessingInMapper;
import com.industrial.erp.security.PermissionService;
import com.industrial.erp.utils.BillNoGenerator;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutsourceServiceTest {

    private final OutIssueMapper issueMapper = mock(OutIssueMapper.class);
    private final OutIssueDetailMapper issueDetailMapper = mock(OutIssueDetailMapper.class);
    private final OutProcessingInMapper piMapper = mock(OutProcessingInMapper.class);
    private final OutProcessingInDetailMapper piDetailMapper = mock(OutProcessingInDetailMapper.class);
    private final BaseSupplierMapper supplierMapper = mock(BaseSupplierMapper.class);
    private final BaseWarehouseMapper warehouseMapper = mock(BaseWarehouseMapper.class);
    private final BillNoGenerator billNoGenerator = mock(BillNoGenerator.class);
    private final StockService stockService = mock(StockService.class);
    private final PermissionService permService = mock(PermissionService.class);

    private final OutsourceService service = new OutsourceService(issueMapper, issueDetailMapper, piMapper,
            piDetailMapper, supplierMapper, warehouseMapper, billNoGenerator, stockService, permService);

    private BaseWarehouse warehouse;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "out-issue-test"), OutIssue.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "out-pi-test"), OutProcessingIn.class);

        BaseSupplier supplier = new BaseSupplier();
        supplier.setId(8L);
        supplier.setSupplierName("宁波外协厂");
        when(supplierMapper.selectById(8L)).thenReturn(supplier);

        warehouse = new BaseWarehouse();
        warehouse.setId(3L);
        warehouse.setWarehouseName("委外仓");
        when(warehouseMapper.selectById(3L)).thenReturn(warehouse);

        when(billNoGenerator.generate(Constants.BILL_OI)).thenReturn("OI202611120001");
        when(billNoGenerator.generate(Constants.BILL_OPI)).thenReturn("OPI202611120001");
        doAnswer(inv -> {
            ((OutIssue) inv.getArgument(0)).setId(100L);
            return 1;
        }).when(issueMapper).insert(any(OutIssue.class));
        doAnswer(inv -> {
            ((OutProcessingIn) inv.getArgument(0)).setId(200L);
            return 1;
        }).when(piMapper).insert(any(OutProcessingIn.class));
    }

    private OutIssueDetail detail(String batchNo, String qty, String price) {
        OutIssueDetail d = new OutIssueDetail();
        d.setProductId(66L);
        d.setUnitId(1L);
        d.setUnitName("kg");
        d.setBatchNo(batchNo);
        d.setQty(new BigDecimal(qty));
        d.setPrice(new BigDecimal(price));
        return d;
    }

    private OutIssue issue(OutIssueDetail... details) {
        OutIssue o = new OutIssue();
        o.setSupplierId(8L);
        o.setWarehouseId(3L);
        o.setDetails(List.of(details));
        return o;
    }

    @Test
    void addIssueComputesLineAmountsAndDefaults() {
        OutIssueDetail d1 = detail("B1", "10", "2.5");
        OutIssueDetail d2 = detail("B2", "3", "1.25");
        OutIssue o = issue(d1, d2);

        service.addIssue(o);

        assertThat(o.getBillNo()).isEqualTo("OI202611120001");
        assertThat(o.getBillStatus()).isEqualTo(Constants.STATUS_DRAFT);
        assertThat(o.getSupplierName()).isEqualTo("宁波外协厂");
        assertThat(o.getBillDate()).isNotNull();
        assertThat(o.getTotalQty()).isEqualByComparingTo("13");
        assertThat(o.getTotalCost()).isEqualByComparingTo("28.75");
        assertThat(d1.getAmount()).isEqualByComparingTo("25.0000");
        assertThat(d2.getAmount()).isEqualByComparingTo("3.7500");
        assertThat(d1.getLineNo()).isEqualTo(1);
        assertThat(d2.getLineNo()).isEqualTo(2);
        assertThat(d1.getIssueId()).isEqualTo(100L);
        assertThat(d2.getIssueId()).isEqualTo(100L);
        verify(issueMapper).insert(o);
        verify(issueDetailMapper, times(2)).insert(any(OutIssueDetail.class));
    }

    @Test
    void addIssueRejectsUnknownSupplier() {
        when(supplierMapper.selectById(8L)).thenReturn(null);
        OutIssue o = issue(detail("B1", "1", "1"));

        assertThatThrownBy(() -> service.addIssue(o))
                .isInstanceOf(BizException.class).hasMessageContaining("外协厂不存在");
        verify(issueMapper, never()).insert(any(OutIssue.class));
    }

    @Test
    void checkIssuePostsStockPerDetailAndMarksChecked() {
        OutIssue o = new OutIssue();
        o.setId(100L);
        o.setBillNo("OI202611120001");
        o.setBillStatus(Constants.STATUS_DRAFT);
        o.setWarehouseId(3L);
        o.setSupplierId(8L);
        when(issueMapper.selectById(100L)).thenReturn(o);
        when(issueDetailMapper.selectByIssueId(100L)).thenReturn(List.of(
                detail("B1", "10", "2.5"), detail("B2", "3", "1.25")));

        service.checkIssue(100L);

        verify(stockService, times(2)).outStock(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        ArgumentCaptor<LambdaUpdateWrapper<OutIssue>> w = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(issueMapper).update(isNull(), w.capture());
        assertThat(w.getValue().getSqlSet()).contains("bill_status");
    }

    @Test
    void checkIssueRejectsNonDraft() {
        OutIssue o = new OutIssue();
        o.setId(100L);
        o.setBillStatus(Constants.STATUS_CHECKED);
        when(issueMapper.selectById(100L)).thenReturn(o);

        assertThatThrownBy(() -> service.checkIssue(100L))
                .isInstanceOf(BizException.class).hasMessageContaining("状态不正确");
        verify(stockService, never()).outStock(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void checkIssueRejectsMissingBill() {
        when(issueMapper.selectById(100L)).thenReturn(null);
        assertThatThrownBy(() -> service.checkIssue(100L))
                .isInstanceOf(BizException.class).hasMessageContaining("发料单不存在");
    }

    @Test
    void addProcessingInSumsMaterialAndProcessFees() {
        OutProcessingInDetail d = new OutProcessingInDetail();
        d.setProductId(66L);
        d.setQty(new BigDecimal("4"));
        d.setPrice(new BigDecimal("10"));
        d.setProcessPrice(new BigDecimal("2.5"));
        OutProcessingIn in = new OutProcessingIn();
        in.setSupplierId(8L);
        in.setWarehouseId(3L);
        in.setDetails(List.of(d));

        service.addProcessingIn(in);

        assertThat(in.getBillNo()).isEqualTo("OPI202611120001");
        assertThat(in.getBillStatus()).isEqualTo(Constants.STATUS_DRAFT);
        assertThat(in.getSupplierName()).isEqualTo("宁波外协厂");
        assertThat(d.getAmount()).isEqualByComparingTo("40.0000");
        assertThat(d.getProcessAmount()).isEqualByComparingTo("10.0000");
        assertThat(in.getMaterialFee()).isEqualByComparingTo("40");
        assertThat(in.getProcessFee()).isEqualByComparingTo("10");
        assertThat(in.getTotalAmount()).isEqualByComparingTo("50");
        assertThat(d.getPiId()).isEqualTo(200L);
        verify(piDetailMapper).insert(d);
    }

    @Test
    void addProcessingInRejectsUnknownSupplier() {
        when(supplierMapper.selectById(8L)).thenReturn(null);
        OutProcessingIn in = new OutProcessingIn();
        in.setSupplierId(8L);
        in.setDetails(List.of(new OutProcessingInDetail()));

        assertThatThrownBy(() -> service.addProcessingIn(in))
                .isInstanceOf(BizException.class).hasMessageContaining("外协厂不存在");
        verify(piMapper, never()).insert(any(OutProcessingIn.class));
    }

    @Test
    void checkProcessingInPostsStockPerDetailAndMarksChecked() {
        OutProcessingIn in = new OutProcessingIn();
        in.setId(200L);
        in.setBillNo("OPI202611120001");
        in.setBillStatus(Constants.STATUS_DRAFT);
        in.setWarehouseId(3L);
        in.setSupplierId(8L);
        when(piMapper.selectById(200L)).thenReturn(in);
        OutProcessingInDetail d = new OutProcessingInDetail();
        d.setProductId(66L);
        d.setQty(new BigDecimal("4"));
        d.setPrice(new BigDecimal("10"));
        d.setProcessPrice(new BigDecimal("2.5"));
        when(piDetailMapper.selectByPiId(200L)).thenReturn(List.of(d));

        service.checkProcessingIn(200L);

        verify(stockService).inStock(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        ArgumentCaptor<LambdaUpdateWrapper<OutProcessingIn>> w = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(piMapper).update(isNull(), w.capture());
        assertThat(w.getValue().getSqlSet()).contains("bill_status");
    }

    @Test
    void checkProcessingInRejectsNonDraft() {
        OutProcessingIn in = new OutProcessingIn();
        in.setId(200L);
        in.setBillStatus(Constants.STATUS_CHECKED);
        when(piMapper.selectById(200L)).thenReturn(in);

        assertThatThrownBy(() -> service.checkProcessingIn(200L))
                .isInstanceOf(BizException.class).hasMessageContaining("状态不正确");
        verify(stockService, never()).inStock(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }
}
