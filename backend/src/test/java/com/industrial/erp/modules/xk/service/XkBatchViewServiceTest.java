package com.industrial.erp.modules.xk.service;

import com.industrial.erp.modules.base.entity.BaseProduct;
import com.industrial.erp.modules.base.entity.BaseSupplier;
import com.industrial.erp.modules.base.entity.BaseUnit;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.industrial.erp.modules.base.mapper.BaseSupplierMapper;
import com.industrial.erp.modules.base.mapper.BaseUnitMapper;
import com.industrial.erp.modules.mes.entity.MesBatch;
import com.industrial.erp.modules.mes.mapper.MesBatchMapper;
import com.industrial.erp.modules.production.entity.PrdOrder;
import com.industrial.erp.modules.production.mapper.PrdOrderMapper;
import com.industrial.erp.modules.xk.dto.XkBatchView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v1.1.189 — 批次台账要能看到品名 (现场只看到 product_id 一串数字).
 */
class XkBatchViewServiceTest {

    private final MesBatchMapper batchMapper = mock(MesBatchMapper.class);
    private final BaseProductMapper productMapper = mock(BaseProductMapper.class);
    private final BaseSupplierMapper supplierMapper = mock(BaseSupplierMapper.class);
    private final BaseUnitMapper unitMapper = mock(BaseUnitMapper.class);
    private final PrdOrderMapper orderMapper = mock(PrdOrderMapper.class);

    private XkBatchViewService service() {
        return new XkBatchViewService(batchMapper, productMapper, supplierMapper, unitMapper, orderMapper);
    }

    private static MesBatch batch(String no, String type, Long productId, Long supplierId, String orderNo) {
        MesBatch b = new MesBatch();
        b.setBatchNo(no);
        b.setBatchType(type);
        b.setProductId(productId);
        b.setSupplierId(supplierId);
        b.setSourcePrdOrderNo(orderNo);
        b.setQty(new BigDecimal("1500"));
        b.setStatus("ACTIVE");
        return b;
    }

    @Test
    void rawBatchResolvesProductAndSupplierName() {
        MesBatch raw = batch("251/WQ", "RAW", 100L, 200L, null);
        when(batchMapper.selectList(any())).thenReturn(List.of(raw));

        BaseProduct p = new BaseProduct();
        p.setId(100L);
        p.setProductName("ldpe");
        when(productMapper.selectBatchIds(anyCollection())).thenReturn(List.of(p));

        BaseSupplier s = new BaseSupplier();
        s.setId(200L);
        s.setSupplierName("某某石化");
        when(supplierMapper.selectBatchIds(anyCollection())).thenReturn(List.of(s));

        List<XkBatchView> views = service().listViews("RAW", null);

        assertThat(views).hasSize(1);
        XkBatchView v = views.get(0);
        assertThat(v.getBatchNo()).isEqualTo("251/WQ");
        assertThat(v.getProductName()).isEqualTo("ldpe");
        assertThat(v.getSupplierName()).isEqualTo("某某石化");
    }

    @Test
    void finishedBatchWithoutProductIdFallsBackToSourceOrderName() {
        MesBatch fg = batch("PD202609170002", "FG", null, null, "PD202609170002");
        when(batchMapper.selectList(any())).thenReturn(List.of(fg));

        PrdOrder o = new PrdOrder();
        o.setBillNo("PD202609170002");
        o.setProductName("吹膜成品膜");
        o.setUnitName("kg");
        when(orderMapper.selectList(any())).thenReturn(List.of(o));

        List<XkBatchView> views = service().listViews("FG", null);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).getProductName()).isEqualTo("吹膜成品膜");
        assertThat(views.get(0).getUnitName()).isEqualTo("kg");
    }

    @Test
    void unknownIdsGiveEmptyNamesInsteadOfFailing() {
        MesBatch raw = batch("X-1", "RAW", 999L, 888L, null);
        when(batchMapper.selectList(any())).thenReturn(List.of(raw));
        when(productMapper.selectBatchIds(anyCollection())).thenReturn(List.of());
        when(supplierMapper.selectBatchIds(anyCollection())).thenReturn(List.of());

        List<XkBatchView> views = service().listViews("RAW", null);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).getProductName()).isEmpty();
        assertThat(views.get(0).getSupplierName()).isEmpty();
    }

    @Test
    void unitNameComesFromBaseUnit() {
        MesBatch raw = batch("U-1", "RAW", null, null, null);
        raw.setUnitId(300L);
        when(batchMapper.selectList(any())).thenReturn(List.of(raw));

        BaseUnit u = new BaseUnit();
        u.setId(300L);
        u.setUnitName("公斤");
        when(unitMapper.selectBatchIds(anyCollection())).thenReturn(List.of(u));

        List<XkBatchView> views = service().listViews("RAW", null);

        assertThat(views.get(0).getUnitName()).isEqualTo("公斤");
    }
}
