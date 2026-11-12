package com.industrial.erp.modules.finance.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.industrial.erp.common.Constants;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.finance.entity.FinArap;
import com.industrial.erp.modules.finance.mapper.FinArapMapper;
import com.industrial.erp.modules.purchase.entity.PurReceipt;
import com.industrial.erp.modules.purchase.entity.PurReturn;
import com.industrial.erp.modules.sales.entity.SalDelivery;
import com.industrial.erp.modules.sales.entity.SalReturn;
import org.apache.ibatis.builder.MapperBuilderAssistant;
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
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 应收应付台账口径: 同一来源单只入账一次 / 退货取负 / 核销余额与状态.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FinArapService 台账口径")
class FinArapServiceTest {

    @Mock private FinArapMapper arapMapper;
    @InjectMocks private FinArapService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "fin-arap-test"), FinArap.class);
        when(arapMapper.selectCount(any())).thenReturn(0L);
    }

    private PurReceipt receipt() {
        PurReceipt r = new PurReceipt();
        r.setId(11L);
        r.setBillNo("RKP202611120001");
        r.setSupplierId(60L);
        r.setSupplierName("供应商A");
        r.setBillDate(LocalDate.of(2026, 11, 12));
        r.setTotalAmountTax(new BigDecimal("1130.00"));
        return r;
    }

    private FinArap captured() {
        ArgumentCaptor<FinArap> c = ArgumentCaptor.forClass(FinArap.class);
        verify(arapMapper).insert(c.capture());
        return c.getValue();
    }

    @Test
    @DisplayName("采购入库生成 AP: 未付/未开票, 余额=含税额")
    void createApForPurchase_initialLedger() {
        service.createApForPurchase(receipt(), List.of());
        FinArap ap = captured();
        assertThat(ap.getBillType()).isEqualTo("AP");
        assertThat(ap.getSourceBillType()).isEqualTo(Constants.LEDGER_PUR_RECEIPT);
        assertThat(ap.getSourceBillId()).isEqualTo(11L);
        assertThat(ap.getAmount()).isEqualByComparingTo("1130.00");
        assertThat(ap.getPaidAmount()).isEqualByComparingTo("0");
        assertThat(ap.getBalance()).isEqualByComparingTo("1130.00");
        assertThat(ap.getBillStatus()).isEqualTo(Constants.STATUS_UNPAID);
        assertThat(ap.getInvoiceStatus()).isEqualTo("UNINVOICED");
        assertThat(ap.getUninvoicedAmount()).isEqualByComparingTo("1130.00");
    }

    @Test
    @DisplayName("同一采购入库单重复审核: 拒绝入账")
    void createApForPurchase_duplicateRejected() {
        when(arapMapper.selectCount(any())).thenReturn(1L);
        assertThatThrownBy(() -> service.createApForPurchase(receipt(), List.of()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已生成应收/应付记录");
        verify(arapMapper, never()).insert(any(FinArap.class));
    }

    @Test
    @DisplayName("销售出库生成 AR: 客户名与应收金额落库")
    void createArForSales_initialLedger() {
        SalDelivery d = new SalDelivery();
        d.setId(21L);
        d.setBillNo("XSCK202611120001");
        d.setCustomerId(88L);
        d.setCustomerName("客户B");
        d.setBillDate(LocalDate.of(2026, 11, 12));
        d.setTotalAmountTax(new BigDecimal("500.00"));

        service.createArForSales(d, List.of());

        FinArap ar = captured();
        assertThat(ar.getBillType()).isEqualTo("AR");
        assertThat(ar.getSourceBillType()).isEqualTo(Constants.LEDGER_SAL_DELIVERY);
        assertThat(ar.getCustomerName()).isEqualTo("客户B");
        assertThat(ar.getAmount()).isEqualByComparingTo("500.00");
        assertThat(ar.getBalance()).isEqualByComparingTo("500.00");
        assertThat(ar.getBillStatus()).isEqualTo(Constants.STATUS_UNPAID);
    }

    @Test
    @DisplayName("采购退货: AP 取负, 负金额直接记已付")
    void reverseApForReturn_negativeAmount() {
        PurReturn ret = new PurReturn();
        ret.setId(31L);
        ret.setBillNo("CJTH202611120001");
        ret.setTotalAmountTax(new BigDecimal("200.00"));

        service.reverseApForReturn(ret);

        FinArap ap = captured();
        assertThat(ap.getAmount()).isEqualByComparingTo("-200.00");
        assertThat(ap.getBalance()).isEqualByComparingTo("-200.00");
        assertThat(ap.getBillStatus()).isEqualTo(Constants.STATUS_PAID);
        assertThat(ap.getRemark()).contains("采购退货自动冲账");
    }

    @Test
    @DisplayName("销售退货金额为空: 按 0 冲账, 状态未付")
    void reverseArForReturn_nullAmountTreatedAsZero() {
        SalReturn ret = new SalReturn();
        ret.setId(32L);
        ret.setBillNo("XSTH202611120001");
        ret.setTotalAmountTax(null);

        service.reverseArForReturn(ret);

        FinArap ar = captured();
        assertThat(ar.getAmount()).isEqualByComparingTo("0");
        assertThat(ar.getBillStatus()).isEqualTo(Constants.STATUS_UNPAID);
    }

    private FinArap existing(String amount, String paid) {
        FinArap ori = new FinArap();
        ori.setId(41L);
        ori.setAmount(new BigDecimal(amount));
        ori.setPaidAmount(new BigDecimal(paid));
        return ori;
    }

    @Test
    @DisplayName("部分核销: 余额递减, 状态 PARTIAL")
    void writeoff_partial() {
        when(arapMapper.selectById(41L)).thenReturn(existing("1000.00", "0"));

        service.writeoff(41L, new BigDecimal("300.00"));

        ArgumentCaptor<FinArap> c = ArgumentCaptor.forClass(FinArap.class);
        verify(arapMapper).updateById(c.capture());
        assertThat(c.getValue().getPaidAmount()).isEqualByComparingTo("300.00");
        assertThat(c.getValue().getBalance()).isEqualByComparingTo("700.00");
        assertThat(c.getValue().getBillStatus()).isEqualTo(Constants.STATUS_PARTIAL);
    }

    @Test
    @DisplayName("核销足额: 余额 0, 状态 PAID")
    void writeoff_finishesLedger() {
        when(arapMapper.selectById(41L)).thenReturn(existing("1000.00", "700.00"));

        service.writeoff(41L, new BigDecimal("300.00"));

        ArgumentCaptor<FinArap> c = ArgumentCaptor.forClass(FinArap.class);
        verify(arapMapper).updateById(c.capture());
        assertThat(c.getValue().getBalance()).isEqualByComparingTo("0");
        assertThat(c.getValue().getBillStatus()).isEqualTo(Constants.STATUS_PAID);
    }

    @Test
    @DisplayName("核销不存在的台账: 报错")
    void writeoff_missingLedger() {
        when(arapMapper.selectById(99L)).thenReturn(null);
        assertThatThrownBy(() -> service.writeoff(99L, BigDecimal.TEN))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("反审核: 已核销的台账拒绝删除")
    void requireCancelableAndDelete_rejectsPaid() {
        FinArap a = existing("1000.00", "100.00");
        a.setInvoicedAmount(BigDecimal.ZERO);
        when(arapMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(a));

        assertThatThrownBy(() -> service.requireCancelableAndDelete(Constants.LEDGER_PUR_RECEIPT, 11L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已被核销");
        verify(arapMapper, never()).deleteById(any(Long.class));
    }

    @Test
    @DisplayName("反审核: 已开票的台账拒绝删除")
    void requireCancelableAndDelete_rejectsInvoiced() {
        FinArap a = existing("1000.00", "0");
        a.setInvoicedAmount(new BigDecimal("500.00"));
        when(arapMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(a));

        assertThatThrownBy(() -> service.requireCancelableAndDelete(Constants.LEDGER_SAL_DELIVERY, 21L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已开票");
    }

    @Test
    @DisplayName("反审核: 干净台账直接删除")
    void requireCancelableAndDelete_deletesClean() {
        FinArap a = existing("1000.00", "0");
        a.setInvoicedAmount(BigDecimal.ZERO);
        when(arapMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(a));

        service.requireCancelableAndDelete(Constants.LEDGER_SAL_DELIVERY, 21L);

        verify(arapMapper).deleteById(41L);
    }

    @Test
    @DisplayName("反审核: 历史单据没有台账时静默跳过")
    void requireCancelableAndDelete_noopWhenNoLedger() {
        when(arapMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        service.requireCancelableAndDelete(Constants.LEDGER_PUR_RECEIPT, 11L);

        verify(arapMapper, never()).deleteById(any(Long.class));
    }
}
