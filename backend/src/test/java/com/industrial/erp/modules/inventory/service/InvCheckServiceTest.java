package com.industrial.erp.modules.inventory.service;

import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseProduct;
import com.industrial.erp.modules.base.entity.BaseWarehouse;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.industrial.erp.modules.base.mapper.BaseWarehouseMapper;
import com.industrial.erp.modules.inventory.dto.AppCheckSubmitDTO;
import com.industrial.erp.modules.inventory.entity.InvCheck;
import com.industrial.erp.modules.inventory.entity.InvCheckDetail;
import com.industrial.erp.modules.inventory.mapper.InvCheckDetailMapper;
import com.industrial.erp.modules.inventory.mapper.InvCheckMapper;
import com.industrial.erp.modules.inventory.mapper.InvStockMapper;
import com.industrial.erp.modules.inventory.vo.AppCheckSubmitVO;
import com.industrial.erp.security.PermissionService;
import com.industrial.erp.utils.BillNoGenerator;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v1.0.8+ InvCheckService App 外勤盘点单元测试
 *
 * <p>覆盖三个核心场景:
 * <ol>
 *   <li>正常提交: 生成 DRAFT 盘点单 + 明细, 账面/实盘/差异/差异类型正确</li>
 *   <li>无效 warehouseId: 抛 BizException</li>
 *   <li>部分商品 ID 不存在: 抛 BizException</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InvCheckService App 盘点")
class InvCheckServiceTest {

    @Mock private InvCheckMapper checkMapper;
    @Mock private InvCheckDetailMapper checkDetailMapper;
    @Mock private BaseWarehouseMapper warehouseMapper;
    @Mock private BaseProductMapper productMapper;
    @Mock private InvStockMapper stockMapper;
    @Mock private BillNoGenerator billNoGenerator;
    @Mock private StockService stockService;
    @Mock private PermissionService permService;

    @InjectMocks private InvCheckService service;

    private static final Long WH_ID = 10L;
    private static final Long PROD_A = 100L;
    private static final Long PROD_B = 200L;

    @Test
    @DisplayName("submitFromApp 正常路径: 生成 DRAFT 单 + 计算 diff")
    void submitFromApp_generatesDraftCheck() {
        // given
        BaseWarehouse wh = new BaseWarehouse();
        wh.setId(WH_ID);
        wh.setWarehouseName("原料一库");
        when(warehouseMapper.selectById(WH_ID)).thenReturn(wh);

        BaseProduct pA = new BaseProduct();
        pA.setId(PROD_A);
        pA.setProductCode("P-A");
        pA.setProductName("商品A");
        pA.setCostPrice(new BigDecimal("10.0000"));
        pA.setMainUnitId(1L);
        BaseProduct pB = new BaseProduct();
        pB.setId(PROD_B);
        pB.setProductCode("P-B");
        pB.setProductName("商品B");
        pB.setCostPrice(new BigDecimal("5.0000"));
        pB.setMainUnitId(1L);
        when(productMapper.selectBatchIds(anyList())).thenReturn(List.of(pA, pB));

        when(stockMapper.sumQtyByWarehouseAndProduct(WH_ID, PROD_A)).thenReturn(new BigDecimal("100.0000"));
        when(stockMapper.sumQtyByWarehouseAndProduct(WH_ID, PROD_B)).thenReturn(new BigDecimal("50.0000"));

        // 模拟自增 ID 回填
        org.mockito.Mockito.doAnswer(inv -> {
            InvCheck arg = inv.getArgument(0);
            arg.setId(999L);
            return 1;
        }).when(checkMapper).insert(any(InvCheck.class));

        AppCheckSubmitDTO dto = new AppCheckSubmitDTO();
        dto.setWarehouseId(WH_ID);
        AppCheckSubmitDTO.Item i1 = new AppCheckSubmitDTO.Item();
        i1.setProductId(PROD_A);
        i1.setActualQty(new BigDecimal("95"));   // 亏 5
        AppCheckSubmitDTO.Item i2 = new AppCheckSubmitDTO.Item();
        i2.setProductId(PROD_B);
        i2.setActualQty(new BigDecimal("55"));   // 盈 5
        dto.setItems(List.of(i1, i2));

        // when
        AppCheckSubmitVO vo = service.submitFromApp(dto);

        // then
        assertThat(vo).isNotNull();
        assertThat(vo.getId()).isEqualTo(999L);
        assertThat(vo.getBillNo()).startsWith("CK-APP-");
        assertThat(vo.getBillStatus()).isEqualTo("DRAFT");
        assertThat(vo.getItemCount()).isEqualTo(2);
        // totalDiffQty = -5 + 5 = 0; totalDiffAmount = (-5*10) + (5*5) = -25
        assertThat(vo.getTotalDiffQty().compareTo(BigDecimal.ZERO)).isEqualTo(0);
        assertThat(vo.getTotalDiffAmount().compareTo(new BigDecimal("-25.0000"))).isEqualTo(0);
        verify(checkMapper, times(1)).insert(any(InvCheck.class));
        verify(checkDetailMapper, times(2)).insert(any(InvCheckDetail.class));
    }

    @Test
    @DisplayName("submitFromApp 仓库不存在: 抛 BizException, 不插入任何数据")
    void submitFromApp_invalidWarehouse_throws() {
        // given
        when(warehouseMapper.selectById(WH_ID)).thenReturn(null);

        AppCheckSubmitDTO dto = new AppCheckSubmitDTO();
        dto.setWarehouseId(WH_ID);
        dto.setItems(Collections.emptyList());

        // when & then
        assertThatThrownBy(() -> service.submitFromApp(dto))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仓库不存在");
        verify(checkMapper, never()).insert(any(InvCheck.class));
    }

    @Test
    @DisplayName("submitFromApp 部分商品 ID 缺失: 抛 BizException")
    void submitFromApp_invalidProduct_throws() {
        // given
        BaseWarehouse wh = new BaseWarehouse();
        wh.setId(WH_ID);
        when(warehouseMapper.selectById(WH_ID)).thenReturn(wh);
        // 只返回 1 个商品, 但 dto 有 2 个 productId (一个不存在)
        BaseProduct pA = new BaseProduct();
        pA.setId(PROD_A);
        when(productMapper.selectBatchIds(anyList())).thenReturn(List.of(pA));

        AppCheckSubmitDTO dto = new AppCheckSubmitDTO();
        dto.setWarehouseId(WH_ID);
        AppCheckSubmitDTO.Item i1 = new AppCheckSubmitDTO.Item();
        i1.setProductId(PROD_A);
        i1.setActualQty(BigDecimal.ONE);
        AppCheckSubmitDTO.Item i2 = new AppCheckSubmitDTO.Item();
        i2.setProductId(999L);  // 不存在
        i2.setActualQty(BigDecimal.ONE);
        dto.setItems(List.of(i1, i2));

        // when & then
        assertThatThrownBy(() -> service.submitFromApp(dto))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("商品不存在");
        verify(checkMapper, never()).insert(any(InvCheck.class));
    }

    @Test
    @DisplayName("submitFromApp 全部 NORMAL (实盘=账面): diffType=NORMAL, 不生成盈亏")
    void submitFromApp_allNormal_noProfitOrLoss() {
        // given
        BaseWarehouse wh = new BaseWarehouse();
        wh.setId(WH_ID);
        wh.setWarehouseName("原料一库");
        when(warehouseMapper.selectById(WH_ID)).thenReturn(wh);

        BaseProduct pA = new BaseProduct();
        pA.setId(PROD_A);
        pA.setProductCode("P-A");
        pA.setProductName("商品A");
        pA.setCostPrice(new BigDecimal("10"));
        when(productMapper.selectBatchIds(anyList())).thenReturn(List.of(pA));
        when(stockMapper.sumQtyByWarehouseAndProduct(WH_ID, PROD_A)).thenReturn(new BigDecimal("100.0000"));

        org.mockito.Mockito.doAnswer(inv -> {
            ((InvCheck) inv.getArgument(0)).setId(888L);
            return 1;
        }).when(checkMapper).insert(any(InvCheck.class));

        AppCheckSubmitDTO dto = new AppCheckSubmitDTO();
        dto.setWarehouseId(WH_ID);
        AppCheckSubmitDTO.Item i1 = new AppCheckSubmitDTO.Item();
        i1.setProductId(PROD_A);
        i1.setActualQty(new BigDecimal("100"));  // = 账面
        dto.setItems(List.of(i1));

        // when
        AppCheckSubmitVO vo = service.submitFromApp(dto);

        // then
        assertThat(vo.getTotalDiffQty().compareTo(BigDecimal.ZERO)).isEqualTo(0);
        assertThat(vo.getTotalDiffAmount().compareTo(BigDecimal.ZERO)).isEqualTo(0);
        assertThat(vo.getBillStatus()).isEqualTo("DRAFT");
    }
}
