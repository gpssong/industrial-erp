package com.industrial.erp.modules.base.label;

import com.industrial.erp.exception.BizException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * `/base/label/bills` 选单端点的契约。
 *
 * <p>存在理由：箱标的「出库日期」在库里**从来没有值**（`sal_delivery.delivery_date` 全库 165/165 NULL，
 * 建单与审核链路都不写它），纸面侧按 `bill_date` 回落。这里跟着回落是为了让屏幕和纸是同一个口径 ——
 * 但如实说：`billDate` 这一列**今天没有任何界面在渲染**（PC 下拉只拼单号+客户/品名+批号，
 * App 只显示单号），所以这几条钉的是**端点契约**，不是一个看得见的空列。纸面上那一栏空白才是真问题，
 * 那一条由 {@code CartonLabelContextLoaderTest} 钉。
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
    @Mock private LabelResolveService resolveService;

    private BaseLabelController controller;

    @BeforeEach
    void setUp() {
        controller = new BaseLabelController(null, templateService, previewService, contextService,
                printService, null, null, feieClient,
                finishedInMapper, purReceiptMapper, salDeliveryMapper, null, null, printerMapper, resolveService);
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
    @DisplayName("CERT 在选单这一步就明确 400 —— 不是回一个空列表（A9 裁定 R2：switch 必须是穷尽表达式）")
    void certBillsIsRejectedNotSilentlyEmpty() {
        // 空列表在界面上的表现是「这个类型没单」，和权限不足、数据真空一模一样；显式 400 才让人知道是本期没做。
        assertThatThrownBy(() -> controller.bills("CERT", null, 30))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("合格证本期不出");
    }

    @Test
    @DisplayName("选单行的键名是前端契约：PC 下拉拼 billNo+mainName+batchNo，改名等于下拉凭空变空")
    void cartonRowKeysMatchWhatThePickerReads() {
        when(salDeliveryMapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(delivery(null, LocalDate.of(2026, 11, 6))));

        Map<String, Object> row = controller.bills("CARTON", null, 30).getData().get(0);

        assertThat(row).containsKeys("id", "billNo", "mainName", "batchNo", "billDate", "billStatus");
        assertThat(row).containsEntry("mainName", "宁波某包装");
    }
}
