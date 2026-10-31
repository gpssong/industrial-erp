package com.industrial.erp.modules.production.service;

import com.industrial.erp.modules.base.entity.BaseProduct;
import com.industrial.erp.modules.base.entity.BaseProductUnit;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.industrial.erp.modules.base.mapper.BaseProductUnitMapper;
import com.industrial.erp.modules.production.entity.PrdOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * v1.1.142 工单/入库单「规格 · 单位」派生口径.
 *
 * <p>覆盖:
 * <ol>
 *   <li>规格优先级: 单据快照 → base_product.spec → base_product.model → 工单快照</li>
 *   <li>单位优先级: 单据快照 → base_product_unit 主单位 → 工单快照; unit_id=0 脏行不可当主键回填</li>
 *   <li>已有快照一律不覆盖</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ProductDeriveService 规格/单位派生口径")
class ProductDeriveServiceTest {

    @Mock private BaseProductMapper productMapper;
    @Mock private BaseProductUnitMapper productUnitMapper;

    @InjectMocks private ProductDeriveService deriveService;

    private static final Long PRODUCT_ID = 2104473647277748226L;
    private static final Long MAIN_UNIT_ID = 2089154538889621509L;

    // ===== 规格 =====

    @Test
    @DisplayName("规格: 商品 spec 空时回落到 model (带鱼袋 dyd975007 实况)")
    void spec_shouldFallBackToModelWhenProductSpecBlank() {
        BaseProduct p = product("", "9*75*0.07");

        assertThat(deriveService.spec(null, p)).isEqualTo("9*75*0.07");
        assertThat(deriveService.spec("", p)).isEqualTo("9*75*0.07");
    }

    @Test
    @DisplayName("规格: 商品 spec 有值时优先于 model, 单据快照有值时谁都不覆盖")
    void spec_shouldNotOverrideExistingSnapshot() {
        BaseProduct p = product("真实规格", "9*75*0.07");

        assertThat(deriveService.spec("快照规格", p)).isEqualTo("快照规格");
        assertThat(deriveService.spec(null, p)).isEqualTo("真实规格");
    }

    @Test
    @DisplayName("规格: 单据/商品全空时回落到工单快照")
    void spec_shouldFallBackToOrderSnapshot() {
        BaseProduct p = product("", "");
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(p);
        when(productUnitMapper.selectMainUnit(PRODUCT_ID)).thenReturn(null);

        PrdOrder order = new PrdOrder();
        order.setSpec("9*75*0.07");

        ProductDeriveService.InboundFields f =
                deriveService.inboundFields(PRODUCT_ID, null, null, null, null, null, order);

        assertThat(f.spec).isEqualTo("9*75*0.07");
    }

    // ===== 单位 =====

    @Test
    @DisplayName("单位: 只认 unit_name, unit_id=0 的历史脏行与 null 同等对待")
    void hasUnit_shouldJudgeNameOnly() {
        assertThat(deriveService.hasUnit("只")).isTrue();
        assertThat(deriveService.hasUnit(null)).isFalse();
        assertThat(deriveService.hasUnit("  ")).isFalse();
    }

    @Test
    @DisplayName("主单位: unit_name 为空的行视为没配单位")
    void mainUnit_shouldRejectBlankName() {
        when(productUnitMapper.selectMainUnit(PRODUCT_ID)).thenReturn(unit(MAIN_UNIT_ID, ""));

        assertThat(deriveService.mainUnit(PRODUCT_ID)).isNull();
        assertThat(deriveService.mainUnit(null)).isNull();
    }

    @Test
    @DisplayName("批量主单位: 一次查询注入分页, 空名与 null 行被丢掉")
    void mainUnits_shouldBatchAndDropBlankNames() {
        BaseProductUnit u1 = unit(MAIN_UNIT_ID, "只");
        u1.setProductId(1L);
        BaseProductUnit blank = unit(9L, "");
        blank.setProductId(2L);
        when(productUnitMapper.selectList(any())).thenReturn(Arrays.asList(u1, blank, null));

        Map<Long, BaseProductUnit> result = deriveService.mainUnits(Arrays.asList(1L, 2L, null));

        assertThat(result).containsOnlyKeys(1L);
        assertThat(deriveService.mainUnits(Collections.emptyList())).isEmpty();
    }

    @Test
    @DisplayName("单位: 商品没配主单位时回落到工单快照, 且 unit_id=0 脏行不回填")
    void inboundFields_shouldFallBackToOrderUnitAndRejectZeroUnitId() {
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(product("9*75*0.07", "9*75*0.07"));
        when(productUnitMapper.selectMainUnit(PRODUCT_ID)).thenReturn(null);

        PrdOrder order = new PrdOrder();
        order.setProductId(PRODUCT_ID);
        order.setProductCode("dyd975007");
        order.setProductName("带鱼袋");
        order.setSpec("9*75*0.07");
        order.setUnitId(0L);
        order.setUnitName("只");

        ProductDeriveService.InboundFields f =
                deriveService.inboundFields(null, null, null, null, null, null, order);

        assertThat(f.productId).isEqualTo(PRODUCT_ID);
        assertThat(f.productCode).isEqualTo("dyd975007");
        assertThat(f.unitName).isEqualTo("只");
        assertThat(f.unitId).isNull();
    }

    @Test
    @DisplayName("入库单: 瘦客户端全空 → 按商品主数据补齐商品/规格/单位")
    void inboundFields_shouldFillEverythingFromProductMaster() {
        BaseProduct p = product("", "9*75*0.07");
        p.setProductCode("dyd975007");
        p.setProductName("带鱼袋9*75*0.07");
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(p);
        when(productUnitMapper.selectMainUnit(PRODUCT_ID)).thenReturn(unit(MAIN_UNIT_ID, "只"));

        ProductDeriveService.InboundFields f =
                deriveService.inboundFields(PRODUCT_ID, null, null, null, null, null, null);

        assertThat(f.productCode).isEqualTo("dyd975007");
        assertThat(f.productName).isEqualTo("带鱼袋9*75*0.07");
        assertThat(f.spec).isEqualTo("9*75*0.07");
        assertThat(f.unitName).isEqualTo("只");
        assertThat(f.unitId).isEqualTo(MAIN_UNIT_ID);
    }

    @Test
    @DisplayName("入库单: 客户端已送的快照原样保留")
    void inboundFields_shouldKeepClientSnapshot() {
        BaseProduct p = product("别的规格", "别的型号");
        p.setProductCode("other");
        p.setProductName("其它商品");
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(p);
        when(productUnitMapper.selectMainUnit(anyLong())).thenReturn(unit(999L, "箱"));

        ProductDeriveService.InboundFields f = deriveService.inboundFields(
                PRODUCT_ID, "dyd975007", "带鱼袋", "9*75*0.07", MAIN_UNIT_ID, "只", null);

        assertThat(f.productCode).isEqualTo("dyd975007");
        assertThat(f.productName).isEqualTo("带鱼袋");
        assertThat(f.spec).isEqualTo("9*75*0.07");
        assertThat(f.unitName).isEqualTo("只");
        assertThat(f.unitId).isEqualTo(MAIN_UNIT_ID);
    }

    // ===== helpers =====

    private static BaseProduct product(String spec, String model) {
        BaseProduct p = new BaseProduct();
        p.setId(PRODUCT_ID);
        p.setSpec(spec);
        p.setModel(model);
        return p;
    }

    private static BaseProductUnit unit(Long unitId, String unitName) {
        BaseProductUnit u = new BaseProductUnit();
        u.setProductId(PRODUCT_ID);
        u.setUnitId(unitId);
        u.setUnitName(unitName);
        u.setIsMain(1);
        return u;
    }
}
