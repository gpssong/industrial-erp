package com.industrial.erp.modules.base.service;

import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseProduct;
import com.industrial.erp.modules.base.entity.BaseProductUnit;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.industrial.erp.modules.base.mapper.BaseProductUnitMapper;
import com.industrial.erp.modules.system.aspect.OperLogPublisher;
import com.industrial.erp.security.PermissionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v1.1.166 App 端「新增商品」提交口径.
 *
 * <p>覆盖:
 * <ol>
 *   <li>条码撞在用商品 → 报「商品条码已被…使用」, 且绝不物理删除那条在用商品
 *       (selectAnyByBarcode 不带 deleted 过滤, 少了这道闸就是硬删他人档案)</li>
 *   <li>条码只撞软删墓碑 → 物理清墓碑后放行 (uniq_base_product_barcode 唯一索引)</li>
 *   <li>主单位 isMain=1 → 价格与 mainUnitId 回填到商品 (漏了就是下游「该商品未配置单位」)</li>
 *   <li>空条码归一化为 null</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BaseProductService 新增商品 (App 端提交口径)")
class BaseProductServiceAddTest {

    @Mock private BaseProductMapper productMapper;
    @Mock private BaseProductUnitMapper unitMapper;
    @Mock private PermissionService permService;
    @Mock private OperLogPublisher operLogPublisher;

    @InjectMocks private BaseProductService service;

    private static final Long LIVE_ID = 2104473647277748226L;
    private static final Long TOMB_ID = 2104473647277748999L;
    private static final Long UNIT_ID = 2089154538889621509L;

    private static BaseProduct product(String code, String barcode) {
        BaseProduct p = new BaseProduct();
        p.setProductCode(code);
        p.setProductName("背心袋 32*50");
        p.setBarcode(barcode);
        return p;
    }

    private static BaseProduct existing(Long id, String name, String code, int deleted) {
        BaseProduct p = new BaseProduct();
        p.setId(id);
        p.setProductName(name);
        p.setProductCode(code);
        p.setBarcode("6901234567890");
        p.setDeleted(deleted);
        return p;
    }

    @Test
    @DisplayName("条码撞在用商品: 抛业务异常, 且不物理删除那条在用商品")
    void add_shouldRejectWhenBarcodeUsedByLiveProduct() {
        when(productMapper.selectByCode("BD-001")).thenReturn(null);
        when(productMapper.selectAnyByCode("BD-001")).thenReturn(null);
        when(productMapper.selectByBarcode("6901234567890"))
                .thenReturn(existing(LIVE_ID, "旧背心袋", "BD-000", 0));

        assertThatThrownBy(() -> service.add(product("BD-001", "6901234567890"), Collections.emptyList()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("商品条码已被")
                .hasMessageContaining("旧背心袋")
                .hasMessageContaining("BD-000");

        verify(productMapper, never()).physicalDeleteById(anyLong());
        verify(productMapper, never()).insert(any(BaseProduct.class));
    }

    @Test
    @DisplayName("条码只撞软删墓碑: 清墓碑后正常入库")
    void add_shouldCleanBarcodeTombstoneAndInsert() {
        when(productMapper.selectByCode("BD-001")).thenReturn(null);
        when(productMapper.selectAnyByCode("BD-001")).thenReturn(null);
        when(productMapper.selectByBarcode("6901234567890")).thenReturn(null);
        when(productMapper.selectAnyByBarcode("6901234567890"))
                .thenReturn(existing(TOMB_ID, "已删的背心袋", "BD-000", 1));

        service.add(product("BD-001", "6901234567890"), Collections.emptyList());

        verify(productMapper).physicalDeleteById(TOMB_ID);
        verify(productMapper).insert(any(BaseProduct.class));
    }

    @Test
    @DisplayName("主单位 isMain=1: 价格与 mainUnitId 回填到商品")
    void add_shouldSyncPriceAndMainUnitFromMainUnit() {
        when(productMapper.selectByCode("BD-001")).thenReturn(null);
        when(productMapper.selectAnyByCode("BD-001")).thenReturn(null);

        BaseProductUnit main = new BaseProductUnit();
        main.setUnitId(UNIT_ID);
        main.setUnitName("只");
        main.setIsMain(1);
        main.setConversionRate(BigDecimal.ONE);
        main.setSalesPrice(new BigDecimal("0.35"));
        main.setPurchasePrice(new BigDecimal("0.21"));
        main.setCostPrice(new BigDecimal("0.20"));
        List<BaseProductUnit> units = Collections.singletonList(main);

        BaseProduct p = product("BD-001", null);
        service.add(p, units);

        assertThat(p.getMainUnitId()).isEqualTo(UNIT_ID);
        assertThat(p.getSalesPrice()).isEqualByComparingTo("0.35");
        assertThat(p.getPurchasePrice()).isEqualByComparingTo("0.21");
        assertThat(p.getCostPrice()).isEqualByComparingTo("0.20");
        assertThat(p.getStatus()).isEqualTo(1);
        verify(unitMapper).insert(any(BaseProductUnit.class));
    }

    @Test
    @DisplayName("空条码归一化为 null (避免撞 uniq_base_product_barcode 空串槽位)")
    void add_shouldNormalizeBlankBarcodeToNull() {
        when(productMapper.selectByCode(anyString())).thenReturn(null);
        when(productMapper.selectAnyByCode(anyString())).thenReturn(null);

        BaseProduct p = product("BD-002", "   ");
        service.add(p, Collections.emptyList());

        assertThat(p.getBarcode()).isNull();
        verify(productMapper, never()).selectByBarcode(anyString());
        verify(productMapper, never()).selectAnyByBarcode(anyString());
    }
}
