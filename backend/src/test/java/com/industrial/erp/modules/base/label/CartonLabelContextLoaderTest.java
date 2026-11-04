package com.industrial.erp.modules.base.label;

import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.label.context.CartonLabelContextLoader;
import com.industrial.erp.modules.sales.entity.SalDelivery;
import com.industrial.erp.modules.sales.entity.SalDeliveryDetail;
import com.industrial.erp.modules.sales.mapper.SalDeliveryDetailMapper;
import com.industrial.erp.modules.sales.mapper.SalDeliveryMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CartonLabelContextLoaderTest {

    @Mock private SalDeliveryMapper deliveryMapper;
    @Mock private SalDeliveryDetailMapper detailMapper;

    private SalDelivery delivery() {
        SalDelivery s = new SalDelivery();
        s.setId(20L);
        s.setBillNo("SAL202611040001");
        s.setCustomerName("华塑包装");
        s.setAddress("杭州市萧山区工业路 88 号");
        s.setPhone("0571-88888888");
        s.setOrderNo("XS202611030002");
        s.setDeliveryDate(LocalDate.of(2026, 11, 4));
        return s;
    }

    private SalDeliveryDetail detail(long id, String productName, String batchNo, String qty) {
        SalDeliveryDetail d = new SalDeliveryDetail();
        d.setId(id);
        d.setProductName(productName);
        d.setSpec("300×400mm");
        d.setUnitName("个");
        d.setQty(new BigDecimal(qty));
        d.setBatchNo(batchNo);
        return d;
    }

    @Test
    @DisplayName("两行明细 → 2 项；表头客户/地址/电话/单号进每一项，行字段各归各的")
    void expandsOneMapPerDetailLine() {
        when(deliveryMapper.selectById(20L)).thenReturn(delivery());
        when(detailMapper.selectByDeliveryId(20L)).thenReturn(List.of(
                detail(201L, "PE 平口袋", "B1|B2|", "500"),
                detail(202L, "PP 背心袋", "B3", "800")));

        List<Map<String, Object>> ctx = new CartonLabelContextLoader(deliveryMapper, detailMapper)
                .load(20L, null);

        assertThat(ctx).hasSize(2);
        Map<String, Object> first = ctx.get(0);
        assertThat(first).containsEntry("customerName", "华塑包装")
                .containsEntry("deliveryAddress", "杭州市萧山区工业路 88 号")
                .containsEntry("deliveryPhone", "0571-88888888")
                .containsEntry("orderNo", "XS202611030002")
                .containsEntry("deliveryBillNo", "SAL202611040001")
                .containsEntry("deliveryDate", LocalDate.of(2026, 11, 4))
                .containsEntry("productName", "PE 平口袋")
                .containsEntry("spec", "300×400mm")
                .containsEntry("unitName", "个")
                .containsEntry("qty", new BigDecimal("500"));
        assertThat(ctx.get(1)).containsEntry("productName", "PP 背心袋")
                .containsEntry("batchNo", "B3");
    }

    @Test
    @DisplayName("批号 | 串（含行尾裸 |）原样透传，不做二次加工（docs/32 §3.2）")
    void passesBatchNoThroughUnparsed() {
        when(deliveryMapper.selectById(20L)).thenReturn(delivery());
        when(detailMapper.selectByDeliveryId(20L)).thenReturn(List.of(
                detail(201L, "PE 平口袋", "B1|B2|", "500")));

        List<Map<String, Object>> ctx = new CartonLabelContextLoader(deliveryMapper, detailMapper)
                .load(20L, List.of());

        assertThat(ctx).hasSize(1);
        assertThat(ctx.get(0)).containsEntry("batchNo", "B1|B2|");
    }

    @Test
    @DisplayName("lineIds 只选第二行 ⇒ 只返回 1 项")
    void filtersByLineIds() {
        when(deliveryMapper.selectById(20L)).thenReturn(delivery());
        when(detailMapper.selectByDeliveryId(20L)).thenReturn(List.of(
                detail(201L, "PE 平口袋", "B1|B2|", "500"),
                detail(202L, "PP 背心袋", "B3", "800")));

        List<Map<String, Object>> ctx = new CartonLabelContextLoader(deliveryMapper, detailMapper)
                .load(20L, List.of(202L));

        assertThat(ctx).hasSize(1);
        assertThat(ctx.get(0)).containsEntry("productName", "PP 背心袋");
    }

    @Test
    @DisplayName("单据查不到 → 404")
    void missingDeliveryThrows404() {
        when(deliveryMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> new CartonLabelContextLoader(deliveryMapper, detailMapper).load(99L, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("销售出库单不存在");
    }
}
