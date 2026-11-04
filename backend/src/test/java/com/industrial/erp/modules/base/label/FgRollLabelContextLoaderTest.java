package com.industrial.erp.modules.base.label;

import com.industrial.erp.modules.base.label.context.FgRollLabelContextLoader;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.industrial.erp.modules.mes.entity.MesBatch;
import com.industrial.erp.modules.mes.mapper.MesBatchMapper;
import com.industrial.erp.modules.production.entity.PrdFinishedIn;
import com.industrial.erp.modules.production.mapper.PrdFinishedInMapper;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FgRollLabelContextLoaderTest {

    @Mock private PrdFinishedInMapper finishedInMapper;
    @Mock private BaseProductMapper productMapper;
    @Mock private MesBatchMapper batchMapper;

    @Test
    @DisplayName("生产日期取 mes_batch.production_date，不是 bill_date（docs/32 §3.2 已核对）")
    void productionDateComesFromBatch() {
        PrdFinishedIn in = new PrdFinishedIn();
        in.setId(1L);
        in.setBillNo("PFI202611040001");
        in.setBillDate(LocalDate.of(2026, 11, 4));
        in.setProductName("PE 平口袋");
        in.setSpec("200×300mm");
        in.setUnitName("个");
        in.setQty(new BigDecimal("5000"));
        in.setBatchNo("B20261104");
        in.setPrdOrderNo("PD202611030001");
        in.setProductId(77L);
        when(finishedInMapper.selectById(1L)).thenReturn(in);

        MesBatch b = new MesBatch();
        b.setBatchNo("B20261104");
        b.setProductionDate(LocalDate.of(2026, 10, 28));
        when(batchMapper.selectOne(any())).thenReturn(b);

        com.industrial.erp.modules.base.entity.BaseProduct p = new com.industrial.erp.modules.base.entity.BaseProduct();
        p.setGramWeight(new BigDecimal("12.5"));
        p.setWidth(new BigDecimal("200"));
        p.setThickness(new BigDecimal("0.06"));
        p.setColorNo("透明");
        when(productMapper.selectById(77L)).thenReturn(p);

        List<Map<String, Object>> ctx = new FgRollLabelContextLoader(finishedInMapper, productMapper, batchMapper)
                .load(1L, List.of());

        assertThat(ctx).hasSize(1);
        Map<String, Object> m = ctx.get(0);
        assertThat(m).containsEntry("productionDate", LocalDate.of(2026, 10, 28));
        assertThat(m).containsEntry("billNo", "PFI202611040001");
        assertThat(m).containsEntry("orderNo", "PD202611030001");
        assertThat(m).containsEntry("productName", "PE 平口袋");
        assertThat(m).containsEntry("gramWeight", new BigDecimal("12.5"));
        assertThat(m).containsEntry("colorNo", "透明");
    }

    @Test
    @DisplayName("批次查不到（主库 mes_batch 普遍无批号）→ productionDate 为空，不抛错")
    void toleratesMissingBatch() {
        PrdFinishedIn in = new PrdFinishedIn();
        in.setId(2L);
        in.setBillNo("PFI2");
        in.setBatchNo(null);
        when(finishedInMapper.selectById(2L)).thenReturn(in);

        List<Map<String, Object>> ctx = new FgRollLabelContextLoader(finishedInMapper, productMapper, batchMapper)
                .load(2L, null);
        assertThat(ctx).hasSize(1);
        assertThat(ctx.get(0).get("productionDate")).isNull();
    }
}
