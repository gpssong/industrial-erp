package com.industrial.erp.modules.production.service;

import com.industrial.erp.common.Constants;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseProduct;
import com.industrial.erp.modules.base.entity.BaseWarehouse;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.industrial.erp.modules.base.mapper.BaseWarehouseMapper;
import com.industrial.erp.modules.production.entity.PrdBom;
import com.industrial.erp.modules.production.entity.PrdBomDetail;
import com.industrial.erp.modules.production.entity.PrdFinishedIn;
import com.industrial.erp.modules.production.entity.PrdOrder;
import com.industrial.erp.modules.production.entity.PrdRequisition;
import com.industrial.erp.modules.production.entity.PrdRequisitionDetail;
import com.industrial.erp.modules.production.mapper.PrdFinishedInMapper;
import com.industrial.erp.modules.production.mapper.PrdOrderMapper;
import com.industrial.erp.modules.production.mapper.PrdRequisitionDetailMapper;
import com.industrial.erp.modules.production.mapper.PrdRequisitionMapper;
import com.industrial.erp.security.SecurityContext;
import com.industrial.erp.security.PermissionService;
import com.industrial.erp.utils.BillNoGenerator;
import com.industrial.erp.modules.inventory.service.StockService;
import com.industrial.erp.modules.system.aspect.OperLogPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 生产加工单核心业务单元测试.
 *
 * <p>覆盖:
 * <ol>
 *   <li>开工 (release) — BOM 展开生成领料单, 按 planQty 缩放用量</li>
 *   <li>完工 (finish) — 写入成品入库单 + 库存入库</li>
 *   <li>详情 (detail) — 注入色号字段 (N+1 优化验证)</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PrdOrderService 生产加工单")
class PrdOrderServiceTest {

    @Mock private PrdOrderMapper orderMapper;
    @Mock private PrdRequisitionMapper reqMapper;
    @Mock private PrdRequisitionDetailMapper reqDetailMapper;
    @Mock private PrdFinishedInMapper finishedInMapper;
    @Mock private PrdBomService bomService;
    @Mock private BaseProductMapper productMapper;
    @Mock private BaseWarehouseMapper warehouseMapper;
    @Mock private BillNoGenerator billNoGenerator;
    @Mock private StockService stockService;
    @Mock private PermissionService permService;
    @Mock private OperLogPublisher operLogPublisher;

    @InjectMocks private PrdOrderService prdOrderService;

    private static final Long ORDER_ID = 100L;
    private static final Long PRODUCT_ID = 200L;
    private static final Long WAREHOUSE_ID = 300L;
    private static final Long BOM_ID = 400L;

    @BeforeEach
    void setUp() {
        SecurityContext.setCurrentUserIdForTest(1L);
    }

    // ===== release (开工) =====

    @Test
    @DisplayName("开工: 按 BOM 展开领料单, 用量按 planQty 缩放")
    void release_shouldExpandBomAndCreateRequisition() {
        // 准备生产单
        PrdOrder order = buildDraftOrder();
        order.setPlanQty(new BigDecimal("100"));
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order);

        // 准备 BOM
        PrdBom bom = new PrdBom();
        bom.setId(BOM_ID);
        bom.setBaseQty(new BigDecimal("10"));
        bom.setLossRate(new BigDecimal("5")); // 5% 损耗
        List<PrdBomDetail> bomDetails = List.of(
                buildBomDetail(1L, "LDPE", new BigDecimal("2.0")),
                buildBomDetail(2L, "LLDPE", new BigDecimal("0.5"))
        );
        bom.setDetails(bomDetails);
        when(bomService.detail(BOM_ID)).thenReturn(bom);

        // 生成编号
        when(billNoGenerator.generate(eq(Constants.BILL_RQ))).thenReturn("RQ2026-001");

        // 仓库回退: 生产单填了仓库
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order);

        // 执行
        Long reqId = prdOrderService.release(ORDER_ID);

        // 验证: 插入领料单
        verify(reqMapper, times(1)).insert(any(PrdRequisition.class));
        // 验证: LDPE 用量 = 2.0 * (100/10) * 1.05 = 21.0
        // 验证: LLDPE 用量 = 0.5 * (100/10) * 1.05 = 5.25
        verify(reqDetailMapper, times(2)).insert(any(PrdRequisitionDetail.class));
        // 验证: 生产单状态改为 RELEASED
        verify(orderMapper, times(1)).updateById(any(PrdOrder.class));
    }

    @Test
    @DisplayName("开工: 生产单没填仓库时, 回退到默认仓库")
    void release_shouldFallbackToDefaultWarehouse() {
        PrdOrder order = buildDraftOrder();
        order.setPlanQty(new BigDecimal("100"));
        order.setWarehouseId(null); // 没填仓库
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order);

        PrdBom bom = new PrdBom();
        bom.setId(BOM_ID);
        bom.setBaseQty(new BigDecimal("10"));
        bom.setLossRate(BigDecimal.ZERO);
        bom.setDetails(Collections.emptyList());
        when(bomService.detail(BOM_ID)).thenReturn(bom);

        when(billNoGenerator.generate(eq(Constants.BILL_RQ))).thenReturn("RQ2026-001");

        BaseWarehouse defaultWh = new BaseWarehouse();
        defaultWh.setId(WAREHOUSE_ID);
        defaultWh.setWarehouseName("默认仓库");
        when(warehouseMapper.selectOne(any())).thenReturn(defaultWh);

        Long reqId = prdOrderService.release(ORDER_ID);

        // 验证: 领料单使用了默认仓库
        ArgumentCaptor<PrdRequisition> captor = ArgumentCaptor.forClass(PrdRequisition.class);
        verify(reqMapper, times(1)).insert(captor.capture());
        assertThat(captor.getValue().getWarehouseId()).isEqualTo(WAREHOUSE_ID);
    }

    @Test
    @DisplayName("开工: 无 BOM 时报错")
    void release_shouldFailWhenNoBom() {
        PrdOrder order = buildDraftOrder();
        order.setBomId(null); // 触发"未配置BOM"分支
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order);

        assertThatThrownBy(() -> prdOrderService.release(ORDER_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未配置BOM");
    }

    // ===== finish (完工) =====

    @Test
    @DisplayName("完工: 写入成品入库单 + 库存入库")
    void finish_shouldCreateFinishedInAndStockIn() {
        PrdOrder order = buildReleasedOrder();
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order);
        when(billNoGenerator.generate(eq(Constants.BILL_PFI))).thenReturn("PFI2026-001");

        // 模拟仓库
        BaseWarehouse wh = new BaseWarehouse();
        wh.setId(WAREHOUSE_ID);
        wh.setWarehouseName("成品仓");
        when(warehouseMapper.selectById(WAREHOUSE_ID)).thenReturn(wh);

        // 执行
        prdOrderService.finish(ORDER_ID, new BigDecimal("95"), new BigDecimal("5"), WAREHOUSE_ID);

        // 验证: 插入成品入库单
        verify(finishedInMapper, times(1)).insert(any(PrdFinishedIn.class));
        // 验证: 生产单状态改为 FINISHED
        verify(orderMapper, times(1)).updateById(any(PrdOrder.class));
        // 验证: 调用库存入库
        verify(stockService, times(1)).inStock(
                eq(Constants.LEDGER_PROD_IN), isNull(), eq("PFI2026-001"), isNull(),
                eq(WAREHOUSE_ID), eq("成品仓"), isNull(), isNull(),
                eq(PRODUCT_ID), isNull(), isNull(), eq("PDPRD-2026-001"),
                eq(new BigDecimal("95")), any(BigDecimal.class), eq("PRD-2026-001"),
                isNull(), isNull(), eq("成品入库 PFI2026-001"));
    }

    // ===== detail (详情) =====

    @Test
    @DisplayName("详情: 注入商品色号 (验证 N+1 优化)")
    void detail_shouldInjectColorNo() {
        PrdOrder order = buildReleasedOrder();
        order.setProductId(PRODUCT_ID);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order);

        BaseProduct product = new BaseProduct();
        product.setId(PRODUCT_ID);
        product.setColorNo("红色");
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(product);

        PrdOrder result = prdOrderService.detail(ORDER_ID);

        assertThat(result).isNotNull();
        assertThat(result.getColorNo()).isEqualTo("红色");
        // 验证: 只调用了一次 selectById (N+1 优化生效)
        verify(productMapper, times(1)).selectById(PRODUCT_ID);
    }

    // ===== helpers =====

    private PrdOrder buildDraftOrder() {
        PrdOrder o = new PrdOrder();
        o.setId(ORDER_ID);
        o.setBillNo("PRD-2026-001");
        o.setBillStatus(Constants.STATUS_DRAFT);
        o.setProductId(PRODUCT_ID);
        o.setPlanQty(new BigDecimal("100"));
        o.setBomId(BOM_ID);
        o.setWarehouseId(WAREHOUSE_ID);
        return o;
    }

    private PrdOrder buildReleasedOrder() {
        PrdOrder o = buildDraftOrder();
        o.setBillStatus(Constants.STATUS_RELEASED);
        return o;
    }

    private PrdBomDetail buildBomDetail(Long productId, String name, BigDecimal baseQty) {
        PrdBomDetail d = new PrdBomDetail();
        d.setProductId(productId);
        d.setProductName(name);
        d.setBaseQty(baseQty);
        return d;
    }
}
