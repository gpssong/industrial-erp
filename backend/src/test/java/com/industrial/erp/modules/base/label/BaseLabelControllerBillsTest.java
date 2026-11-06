package com.industrial.erp.modules.base.label;

import com.industrial.erp.common.R;
import com.industrial.erp.modules.base.controller.BaseLabelController;
import com.industrial.erp.modules.base.service.BaseLabelTemplateService;
import com.industrial.erp.modules.production.client.FeiePrintClient;
import com.industrial.erp.modules.production.mapper.PrdFinishedInMapper;
import com.industrial.erp.modules.purchase.mapper.PurReceiptMapper;
import com.industrial.erp.modules.sales.entity.SalDelivery;
import com.industrial.erp.modules.sales.mapper.SalDeliveryMapper;
import com.industrial.erp.modules.system.mapper.SysFeiePrinterConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * `/base/label/bills` 选单列表的日期列。
 *
 * <p>存在理由：箱标的「出库日期」在库里**从来没有值**（`sal_delivery.delivery_date` 全库 165/165 NULL，
 * 建单与审核链路都不写它），纸面侧已按 `bill_date` 回落；这里不跟着回落的话，
 * 操作工在打印中心看到的日期列是空的，按下打印出来的标签上却有一个日期 —— 屏幕和纸两个口径。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BaseLabelControllerBillsTest {

    @Mock private LabelPreviewService previewService;
    @Mock private LabelContextService contextService;
    @Mock private LabelPrintService printService;
    @Mock private PrdFinishedInMapper finishedInMapper;
    @Mock private PurReceiptMapper purReceiptMapper;
    @Mock private SalDeliveryMapper salDeliveryMapper;
    @Mock private BaseLabelTemplateService templateService;
    @Mock private SysFeiePrinterConfigMapper printerMapper;
    @Mock private FeiePrintClient feieClient;

    private BaseLabelController controller;

    @BeforeEach
    void setUp() {
        controller = new BaseLabelController(null, templateService, previewService, contextService,
                printService, null, null, feieClient,
                finishedInMapper, purReceiptMapper, salDeliveryMapper, null, null, printerMapper);
    }

    private SalDelivery delivery(LocalDate deliveryDate, LocalDate billDate) {
        SalDelivery s = new SalDelivery();
        s.setId(30L);
        s.setBillNo("CKP202611060001");
        s.setCustomerName("宁波某包装");
        s.setDeliveryDate(deliveryDate);
        s.setBillDate(billDate);
        s.setBillStatus("CHECKED");
        return s;
    }

    @Test
    @DisplayName("箱标选单：delivery_date 为空时日期列回落 bill_date（与纸面同一口径）")
    void cartonDateFallsBackToBillDate() {
        when(salDeliveryMapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(delivery(null, LocalDate.of(2026, 11, 6))));

        List<Map<String, Object>> rows = controller.bills("CARTON", null, 30).getData();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("billDate", "2026-11-06");
    }

    @Test
    @DisplayName("出库日期有值时以它为准 —— 回落不能反过来盖掉真实出库日")
    void cartonDateKeepsDeliveryDateWhenPresent() {
        when(salDeliveryMapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(delivery(LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 6))));

        List<Map<String, Object>> rows = controller.bills("CARTON", null, 30).getData();

        assertThat(rows.get(0)).containsEntry("billDate", "2026-11-03");
    }

    @Test
    @DisplayName("R.ok 的包装不变：bills 回的是 R，不是裸 List")
    void billsWrapsInR() {
        when(salDeliveryMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        assertThat(controller.bills("CARTON", "", 30)).isInstanceOf(R.class);
    }
}
