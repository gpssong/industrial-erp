package com.industrial.erp.modules.base.label;

import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseProduct;
import com.industrial.erp.modules.base.entity.BaseProductUnit;
import com.industrial.erp.modules.base.label.context.GoodsLabelContextLoader;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.industrial.erp.modules.base.mapper.BaseProductUnitMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v1.1.178 商品标 (GOODS_LABEL)：数据源 = 商品主数据 {@code base_product}，一行商品打一张。
 *
 * <p>与合格证相反的两点，都在测试里钉住：
 * ① 这里**没有**「宁可报错」的闸 —— 商品标不承诺质量，停用商品照样有在库的要贴标，所以 status=0 不拦；
 * 唯一拦的是「商品不存在/已软删」（回 404，因为那时连品名都没有）。
 * ② 数量/批号/日期这些**单据派生**的字段一个都不给：主数据上没有，硬塞只会变成永久空白栏。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GoodsLabelContextLoaderTest {

    @Mock private BaseProductMapper productMapper;
    @Mock private BaseProductUnitMapper productUnitMapper;

    private BaseProduct product() {
        BaseProduct p = new BaseProduct();
        p.setId(77L);
        p.setProductCode("FG001");
        p.setProductName("背心袋 30×45cm");
        p.setSpec("30×45cm");
        p.setMaterial("LDPE");
        p.setGramWeight(new BigDecimal("8"));
        p.setWidth(new BigDecimal("300"));
        p.setThickness(new BigDecimal("0.025"));
        p.setColorNo("本色");
        p.setBarcode("6901234567890");
        p.setQrcode("http://trace.example/FG001");
        p.setShelfLifeDays(365);
        p.setBatchNo("不该出现在商品标上");
        p.setStatus(0);   // 停用商品也要能打标
        return p;
    }

    private GoodsLabelContextLoader loader() {
        return new GoodsLabelContextLoader(productMapper, productUnitMapper);
    }

    private BaseProductUnit mainUnit() {
        BaseProductUnit u = new BaseProductUnit();
        u.setUnitName("个");
        return u;
    }

    @Test
    @DisplayName("键序 = 纸面次序；单位来自 base_product_unit 的主单位行")
    void buildsContextFromProductMaster() {
        when(productMapper.selectById(77L)).thenReturn(product());
        when(productUnitMapper.selectMainUnit(77L)).thenReturn(mainUnit());

        Map<String, Object> m = loader().load(77L, null).get(0);

        assertThat(m).containsOnlyKeys("productName", "productCode", "spec", "material", "unitName",
                "gramWeight", "width", "thickness", "colorNo", "barcode", "qrcode", "shelfLifeDays");
        assertThat(m).containsEntry("productName", "背心袋 30×45cm");
        assertThat(m).containsEntry("unitName", "个");
        assertThat(m).containsEntry("gramWeight", new BigDecimal("8"));
        assertThat(m).containsEntry("qrcode", "http://trace.example/FG001");
    }

    @Test
    @DisplayName("幅宽/厚度按实存口径换算成 mm 印上纸面：幅宽 cm×10 ÷10，厚度 丝 ÷1000")
    void widthAndThicknessRenderedAsMillimetre() {
        when(productMapper.selectById(77L)).thenReturn(product());
        when(productUnitMapper.selectMainUnit(77L)).thenReturn(mainUnit());

        Map<String, Object> m = loader().load(77L, null).get(0);

        // 幅宽列存 300（=30.0cm×10）÷10 = 30mm；厚度列存 0.025（丝口径演示值）÷1000 = 0.000025mm
        assertThat(m).containsEntry("width", "30mm");
        assertThat(m).containsEntry("thickness", "0.000025mm");
    }

    @Test
    @DisplayName("幅宽/厚度为空或 0 → 留白（null），不印「0mm」")
    void blankWidthThicknessStayBlank() {
        BaseProduct p = product();
        p.setWidth(null);
        BaseProduct p2 = product();
        p2.setThickness(new BigDecimal("0"));
        when(productMapper.selectById(77L)).thenReturn(p).thenReturn(p2);
        when(productUnitMapper.selectMainUnit(77L)).thenReturn(mainUnit());

        Map<String, Object> m = loader().load(77L, null).get(0);
        assertThat(m).containsEntry("width", null);

        Map<String, Object> m2 = loader().load(77L, null).get(0);
        assertThat(m2).containsEntry("thickness", null);
    }

    @Test
    @DisplayName("商品标不带批号/数量/日期 —— 主数据上没有的字段不许上纸（那会是永久空白）")
    void noBillDerivedFields() {
        when(productMapper.selectById(77L)).thenReturn(product());
        when(productUnitMapper.selectMainUnit(77L)).thenReturn(mainUnit());

        assertThat(loader().load(77L, null).get(0))
                .doesNotContainKeys("batchNo", "qty", "productionDate", "orderNo", "inspectionBillNo");
    }

    @Test
    @DisplayName("停用商品（status=0）照样出标 —— 只拦「查不到」，不拦「不用了」")
    void disabledProductStillPrints() {
        when(productMapper.selectById(77L)).thenReturn(product());

        assertThat(loader().load(77L, null)).hasSize(1);
    }

    @Test
    @DisplayName("商品不存在或已删除 → 404（连品名都没有，不是一行空白标）")
    void missingProductIs404() {
        when(productMapper.selectById(9L)).thenReturn(null);

        assertThatThrownBy(() -> loader().load(9L, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("商品不存在或已删除: id=9");
    }

    @Test
    @DisplayName("没配主单位 → unitName 留空、不查别的表、不抛（一个单位不该挡住整张标）")
    void toleratesMissingMainUnit() {
        when(productMapper.selectById(77L)).thenReturn(product());
        when(productUnitMapper.selectMainUnit(77L)).thenReturn(null);

        assertThat(loader().load(77L, null).get(0)).containsEntry("unitName", null);
    }

    @Test
    @DisplayName("lineIds 对商品标无意义 —— 不因为多传了就崩，也不因此多出一张")
    void ignoresLineIds() {
        when(productMapper.selectById(77L)).thenReturn(product());

        assertThat(loader().load(77L, List.of(1L, 2L))).hasSize(1);
    }

    @Test
    @DisplayName("示例数据与 load 同一份键清单、不含主数据键，且不碰库")
    void sampleMatchesLoadContract() {
        List<Map<String, Object>> rows = loader().sample();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsOnlyKeys("productName", "productCode", "spec", "material", "unitName",
                "gramWeight", "width", "thickness", "colorNo", "barcode", "qrcode", "shelfLifeDays");
        verify(productMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("bizType() = GOODS_LABEL")
    void declaresGoodsLabelBizType() {
        assertThat(loader().bizType()).isEqualTo(LabelBizType.GOODS_LABEL);
    }
}
