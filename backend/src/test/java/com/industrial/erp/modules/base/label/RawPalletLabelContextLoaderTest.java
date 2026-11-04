package com.industrial.erp.modules.base.label;

import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.label.context.RawPalletLabelContextLoader;
import com.industrial.erp.modules.purchase.entity.PurReceipt;
import com.industrial.erp.modules.purchase.entity.PurReceiptDetail;
import com.industrial.erp.modules.purchase.mapper.PurReceiptDetailMapper;
import com.industrial.erp.modules.purchase.mapper.PurReceiptMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RawPalletLabelContextLoaderTest {

    @Mock private PurReceiptMapper receiptMapper;
    @Mock private PurReceiptDetailMapper detailMapper;

    private PurReceipt receipt() {
        PurReceipt r = new PurReceipt();
        r.setId(10L);
        r.setBillNo("PRI202611030001");
        r.setSupplierName("金波塑业");
        r.setInspectionReportNo("BG-YL-20261103-001");
        r.setBillDate(LocalDate.of(2026, 11, 3));
        return r;
    }

    private PurReceiptDetail detail(long id, String productName, String batchNo, String qty) {
        PurReceiptDetail d = new PurReceiptDetail();
        d.setId(id);
        d.setProductName(productName);
        d.setProductCode("P" + id);
        d.setSpec("500×800mm");
        d.setUnitName("kg");
        d.setQty(new BigDecimal(qty));
        d.setBatchNo(batchNo);
        return d;
    }

    @Test
    @DisplayName("品名/批号/数量在明细行上 ⇒ 两行明细展开成 2 项，每项都带表头的供应商与报告号")
    void expandsOneMapPerDetailLine() {
        when(receiptMapper.selectById(10L)).thenReturn(receipt());
        when(detailMapper.selectByReceiptId(10L)).thenReturn(List.of(
                detail(101L, "PE 低压原料", "YL20261101", "2000"),
                detail(102L, "PP 拉丝原料", "YL20261102", "1500")));

        List<Map<String, Object>> ctx = new RawPalletLabelContextLoader(receiptMapper, detailMapper)
                .load(10L, null);

        assertThat(ctx).hasSize(2);
        Map<String, Object> first = ctx.get(0);
        assertThat(first).containsEntry("supplierName", "金波塑业")
                .containsEntry("inspectionReportNo", "BG-YL-20261103-001")
                .containsEntry("receiptBillNo", "PRI202611030001")
                .containsEntry("receiptDate", LocalDate.of(2026, 11, 3))
                .containsEntry("productName", "PE 低压原料")
                .containsEntry("batchNo", "YL20261101")
                .containsEntry("qty", new BigDecimal("2000"))
                .containsEntry("unitName", "kg");
        // key 集合钉桩：字面量清单（docs/32 §3.2 RAW_PALLET 字段，有意不含 spec），不从 LabelFieldDict 反推
        assertThat(first).containsOnlyKeys("productName", "productCode", "supplierName", "batchNo",
                "qty", "unitName", "receiptBillNo", "receiptDate", "inspectionReportNo");
        Map<String, Object> second = ctx.get(1);
        assertThat(second).containsEntry("supplierName", "金波塑业")
                .containsEntry("inspectionReportNo", "BG-YL-20261103-001")
                .containsEntry("productName", "PP 拉丝原料")
                .containsEntry("batchNo", "YL20261102")
                .containsEntry("qty", new BigDecimal("1500"));
    }

    @Test
    @DisplayName("lineIds 只选第一行 ⇒ 只返回 1 项")
    void filtersByLineIds() {
        when(receiptMapper.selectById(10L)).thenReturn(receipt());
        when(detailMapper.selectByReceiptId(10L)).thenReturn(List.of(
                detail(101L, "PE 低压原料", "YL20261101", "2000"),
                detail(102L, "PP 拉丝原料", "YL20261102", "1500")));

        List<Map<String, Object>> ctx = new RawPalletLabelContextLoader(receiptMapper, detailMapper)
                .load(10L, List.of(101L));

        assertThat(ctx).hasSize(1);
        assertThat(ctx.get(0)).containsEntry("productName", "PE 低压原料");
    }

    @Test
    @DisplayName("单据查不到 → 404")
    void missingReceiptThrows404() {
        when(receiptMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> new RawPalletLabelContextLoader(receiptMapper, detailMapper).load(99L, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("采购入库单不存在");
    }

    @Test
    @DisplayName("lineIds 里夹了 null（请求体 [101,null]）→ 400，不是 Set.copyOf 的 NPE 500")
    void nullLineIdIsClientError() {
        when(receiptMapper.selectById(10L)).thenReturn(receipt());
        when(detailMapper.selectByReceiptId(10L)).thenReturn(List.of(detail(101L, "PE 低压原料", "YL20261101", "2000")));

        assertThatThrownBy(() -> new RawPalletLabelContextLoader(receiptMapper, detailMapper)
                .load(10L, Arrays.asList(101L, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("明细行 id 不能为空");
    }
}
