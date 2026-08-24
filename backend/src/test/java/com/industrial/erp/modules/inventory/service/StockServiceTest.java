package com.industrial.erp.modules.inventory.service;

import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseProduct;
import com.industrial.erp.modules.base.entity.BaseProductUnit;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.industrial.erp.modules.base.mapper.BaseProductUnitMapper;
import com.industrial.erp.modules.inventory.entity.InvLedger;
import com.industrial.erp.modules.inventory.entity.InvStock;
import com.industrial.erp.modules.inventory.mapper.InvLedgerMapper;
import com.industrial.erp.modules.inventory.mapper.InvStockMapper;
import com.industrial.erp.security.SecurityContext;
import com.industrial.erp.utils.RedisLock;
import org.junit.jupiter.api.AfterEach;
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
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 库存核心单元测试。
 *
 * <p>覆盖两个核心场景:
 * <ol>
 *   <li>移动加权平均成本 (入库)</li>
 *   <li>严格禁止负库存 (出库)</li>
 * </ol>
 *
 * <p>使用 Mockito 直接替换 Mapper / RedisLock 依赖, 不依赖 MySQL / Redis, 单测可在
 * 几毫秒内完成, 适合 CI。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("StockService 库存核心")
class StockServiceTest {

    @Mock private InvStockMapper stockMapper;
    @Mock private InvLedgerMapper ledgerMapper;
    @Mock private BaseProductMapper productMapper;
    @Mock private BaseProductUnitMapper unitMapper;
    @Mock private RedisLock redisLock;

    @InjectMocks private StockService stockService;

    private static final Long WAREHOUSE_ID = 1L;
    private static final Long PRODUCT_ID = 100L;
    private static final String BATCH_NO = "B202601";

    @BeforeEach
    void setUpSecurityContext() {
        // StockService 通过 SecurityContext 取 createBy, 单测无 Sa-Token 会 NPE
        SecurityContext.setCurrentUserIdForTest(1L);
        // RedisLock 直接执行 action, 跳过真实 Redis 交互
        lenient().when(redisLock.executeWithLock(anyString(), any(Integer.class), any(Integer.class), any()))
                .thenAnswer(inv -> {
                    Supplier<?> action = inv.getArgument(3);
                    return action.get();
                });
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContext.clearForTest();
    }

    private BaseProduct mockProduct() {
        BaseProduct p = new BaseProduct();
        p.setId(PRODUCT_ID);
        p.setProductCode("P001");
        p.setProductName("薄膜-A");
        p.setSpec("厚0.1mm");
        p.setModel("FM-A-2026");  // v1.1.20+ 型号字段
        p.setCostPrice(BigDecimal.ZERO);
        return p;
    }

    // ============================ 入库 ============================

    @Test
    @DisplayName("入库 - 新建库存: avgCost = 入库单价")
    void inStock_createsNew_whenAbsent() {
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(mockProduct());
        when(stockMapper.selectForUpdate(WAREHOUSE_ID, PRODUCT_ID, BATCH_NO)).thenReturn(null);

        InvStock result = stockService.inStock(
                "PUR_RECEIPT", 1L, "RKP001", 10L,
                WAREHOUSE_ID, "主仓", null, null,
                PRODUCT_ID, 1L, "KG", BATCH_NO,
                new BigDecimal("100"), new BigDecimal("12.50"), "PO001",
                1L, null, "首次入库");

        assertThat(result.getQty()).isEqualByComparingTo("100");
        assertThat(result.getAvailableQty()).isEqualByComparingTo("100");
        assertThat(result.getAvgCost()).isEqualByComparingTo("12.50");
        assertThat(result.getTotalCost()).isEqualByComparingTo("1250.0000");
        verify(stockMapper, times(1)).insert(any(InvStock.class));
        verify(ledgerMapper, times(1)).insert((InvLedger) any());
    }


    @Test
    @DisplayName("入库 - 已有库存: 按移动加权平均重算")
    void inStock_recomputesMovingAvg_whenExists() {
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(mockProduct());

        // 已有库存: 100 件, avg=10, total=1000
        InvStock existing = new InvStock();
        existing.setId(1L);
        existing.setWarehouseId(WAREHOUSE_ID);
        existing.setProductId(PRODUCT_ID);
        existing.setProductName("薄膜-A");
        existing.setProductCode("P001");
        existing.setQty(new BigDecimal("100"));
        existing.setAvailableQty(new BigDecimal("100"));
        existing.setAvgCost(new BigDecimal("10"));
        existing.setTotalCost(new BigDecimal("1000"));
        when(stockMapper.selectForUpdate(WAREHOUSE_ID, PRODUCT_ID, BATCH_NO)).thenReturn(existing);

        // 新入库: 50 件 @ 14 元 = 700
        stockService.inStock(
                "PUR_RECEIPT", 2L, "RKP002", 11L,
                WAREHOUSE_ID, "主仓", null, null,
                PRODUCT_ID, 1L, "KG", BATCH_NO,
                new BigDecimal("50"), new BigDecimal("14"), "PO002",
                1L, null, "二批入库");

        // 加权平均: (1000 + 700) / (100 + 50) = 1700 / 150 = 11.3333
        ArgumentCaptor<InvStock> captor = ArgumentCaptor.forClass(InvStock.class);
        verify(stockMapper).updateById(captor.capture());
        InvStock updated = captor.getValue();
        assertThat(updated.getQty()).isEqualByComparingTo("150");
        assertThat(updated.getTotalCost()).isEqualByComparingTo("1700.0000");
        assertThat(updated.getAvgCost()).isEqualByComparingTo("11.3333");

        // 验证台账的 before/after 字段. 修复前因为直接读 cur.getQty() 而读到新值, 此断言会失败.
        ArgumentCaptor<InvLedger> ledgerCaptor = ArgumentCaptor.forClass(InvLedger.class);
        verify(ledgerMapper).insert(ledgerCaptor.capture());
        InvLedger ledger = ledgerCaptor.getValue();
        assertThat(ledger.getBeforeQty()).isEqualByComparingTo("100");   // 操作前 = 100
        assertThat(ledger.getAfterQty()).isEqualByComparingTo("150");    // 操作后 = 150
        assertThat(ledger.getBeforeAvgCost()).isEqualByComparingTo("10");  // 操作前单价
        assertThat(ledger.getAfterAvgCost()).isEqualByComparingTo("11.3333"); // 操作后单价
    }

    @Test
    @DisplayName("入库 - 新建库存: 台账 before 全为 0")
    void inStock_ledgerBeforeZeros_whenNewStock() {
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(mockProduct());
        when(stockMapper.selectForUpdate(WAREHOUSE_ID, PRODUCT_ID, BATCH_NO)).thenReturn(null);

        stockService.inStock(
                "PUR_RECEIPT", 3L, "RKP003", 12L,
                WAREHOUSE_ID, "主仓", null, null,
                PRODUCT_ID, 1L, "KG", BATCH_NO,
                new BigDecimal("20"), new BigDecimal("8"), "PO003",
                1L, null, "首次入库");

        ArgumentCaptor<InvLedger> ledgerCaptor = ArgumentCaptor.forClass(InvLedger.class);
        verify(ledgerMapper).insert(ledgerCaptor.capture());
        InvLedger ledger = ledgerCaptor.getValue();
        assertThat(ledger.getBeforeQty()).isEqualByComparingTo("0");
        assertThat(ledger.getAfterQty()).isEqualByComparingTo("20");
        assertThat(ledger.getBeforeAvgCost()).isEqualByComparingTo("0");
        assertThat(ledger.getAfterAvgCost()).isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("入库 - 数量为 0 应抛业务异常")
    void inStock_rejectsNonPositiveQty() {
        assertThatThrownBy(() -> stockService.inStock(
                "PUR_RECEIPT", 1L, "X", 1L,
                WAREHOUSE_ID, "主仓", null, null,
                PRODUCT_ID, 1L, "KG", BATCH_NO,
                BigDecimal.ZERO, new BigDecimal("1"), null, 1L, null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("入库数量必须大于 0");
        verify(stockMapper, never()).selectForUpdate(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("入库 - 商品不存在应抛业务异常")
    void inStock_rejectsUnknownProduct() {
        when(productMapper.selectById(999L)).thenReturn(null);
        assertThatThrownBy(() -> stockService.inStock(
                "PUR_RECEIPT", 1L, "X", 1L,
                WAREHOUSE_ID, "主仓", null, null,
                999L, 1L, "KG", BATCH_NO,
                new BigDecimal("1"), new BigDecimal("1"), null, 1L, null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("商品不存在");
    }

    // ============================ 出库 ============================

    @Test
    @DisplayName("出库 - 正常扣减: qty 减少, totalCost 按 avgCost 重算")
    void outStock_reducesQty() {
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(mockProduct());
        InvStock existing = new InvStock();
        existing.setId(1L);
        existing.setWarehouseId(WAREHOUSE_ID);
        existing.setProductId(PRODUCT_ID);
        existing.setProductName("薄膜-A");
        existing.setProductCode("P001");
        existing.setQty(new BigDecimal("100"));
        existing.setAvailableQty(new BigDecimal("100"));
        existing.setAvgCost(new BigDecimal("10"));
        existing.setTotalCost(new BigDecimal("1000"));
        when(stockMapper.selectForUpdate(WAREHOUSE_ID, PRODUCT_ID, BATCH_NO)).thenReturn(existing);

        BigDecimal outCost = stockService.outStock(
                "SAL_DELIVERY", 1L, "CKP001", 10L,
                WAREHOUSE_ID, "主仓", null, null,
                PRODUCT_ID, 1L, "KG", BATCH_NO,
                new BigDecimal("30"), null, "SO001",
                null, 1L, "正常出库");

        // 出库成本 = 30 * 10 = 300
        assertThat(outCost).isEqualByComparingTo("300.0000");

        ArgumentCaptor<InvStock> captor = ArgumentCaptor.forClass(InvStock.class);
        verify(stockMapper).updateById(captor.capture());
        InvStock updated = captor.getValue();
        assertThat(updated.getQty()).isEqualByComparingTo("70");
        // totalCost = 70 * 10 = 700
        assertThat(updated.getTotalCost()).isEqualByComparingTo("700.0000");

        // 台账应记录出库
        ArgumentCaptor<InvLedger> ledgerCap = ArgumentCaptor.forClass(InvLedger.class);
        verify(ledgerMapper).insert(ledgerCap.capture());
        assertThat(ledgerCap.getValue().getQty()).isEqualByComparingTo("30");
        assertThat(ledgerCap.getValue().getBeforeQty()).isEqualByComparingTo("100");
        assertThat(ledgerCap.getValue().getAfterQty()).isEqualByComparingTo("70");
    }

    @Test
    @DisplayName("出库 - 库存为零应抛业务异常")
    void outStock_rejectsWhenStockMissing() {
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(mockProduct());
        when(stockMapper.selectForUpdate(WAREHOUSE_ID, PRODUCT_ID, BATCH_NO)).thenReturn(null);

        assertThatThrownBy(() -> stockService.outStock(
                "SAL_DELIVERY", 1L, "X", 1L,
                WAREHOUSE_ID, "主仓", null, null,
                PRODUCT_ID, 1L, "KG", BATCH_NO,
                new BigDecimal("1"), null, null,
                null, 1L, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("库存不存在");
        verify(stockMapper, never()).updateById((InvStock) any());
        verify(ledgerMapper, never()).insert(any(InvLedger.class));
    }

    @Test
    @DisplayName("出库 - 库存不足应抛业务异常, 严禁负库存")
    void outStock_rejectsInsufficientStock() {
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(mockProduct());
        InvStock existing = new InvStock();
        existing.setId(1L);
        existing.setWarehouseId(WAREHOUSE_ID);
        existing.setProductId(PRODUCT_ID);
        existing.setProductName("薄膜-A");
        existing.setProductCode("P001");
        existing.setQty(new BigDecimal("5"));
        existing.setAvailableQty(new BigDecimal("5"));
        existing.setAvgCost(new BigDecimal("10"));
        existing.setTotalCost(new BigDecimal("50"));
        when(stockMapper.selectForUpdate(WAREHOUSE_ID, PRODUCT_ID, BATCH_NO)).thenReturn(existing);

        // 请求 10 件, 库存仅 5 件
        assertThatThrownBy(() -> stockService.outStock(
                "SAL_DELIVERY", 1L, "X", 1L,
                WAREHOUSE_ID, "主仓", null, null,
                PRODUCT_ID, 1L, "KG", BATCH_NO,
                new BigDecimal("10"), null, null,
                null, 1L, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("库存不足");
        verify(stockMapper, never()).updateById((InvStock) any());
    }

    @Test
    @DisplayName("出库 - 刚好等于库存, 允许但台账记录 afterQty=0")
    void outStock_allowsExactBalance() {
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(mockProduct());
        InvStock existing = new InvStock();
        existing.setId(1L);
        existing.setWarehouseId(WAREHOUSE_ID);
        existing.setProductId(PRODUCT_ID);
        existing.setProductName("薄膜-A");
        existing.setProductCode("P001");
        existing.setQty(new BigDecimal("10"));
        existing.setAvailableQty(new BigDecimal("10"));
        existing.setAvgCost(new BigDecimal("5"));
        existing.setTotalCost(new BigDecimal("50"));
        when(stockMapper.selectForUpdate(WAREHOUSE_ID, PRODUCT_ID, BATCH_NO)).thenReturn(existing);

        BigDecimal outCost = stockService.outStock(
                "SAL_DELIVERY", 1L, "X", 1L,
                WAREHOUSE_ID, "主仓", null, null,
                PRODUCT_ID, 1L, "KG", BATCH_NO,
                new BigDecimal("10"), null, null,
                null, 1L, null);

        assertThat(outCost).isEqualByComparingTo("50.0000");
        ArgumentCaptor<InvStock> captor = ArgumentCaptor.forClass(InvStock.class);
        verify(stockMapper).updateById(captor.capture());
        assertThat(captor.getValue().getQty()).isEqualByComparingTo("0");
        assertThat(captor.getValue().getAvailableQty()).isEqualByComparingTo("0");
    }

    // ==================== v1.1.17+ 副单位 → 主单位折算测试 ====================

    /** 主单位 = 卷 (unitId=1, conversionRate=1), 副单位 = 箱 (unitId=2, conversionRate=60) */
    private void mockUnitsForConversion(Long mainUnitId, String mainName, Long subUnitId, BigDecimal subRate) {
        BaseProductUnit mainUnit = new BaseProductUnit();
        mainUnit.setUnitId(mainUnitId);
        mainUnit.setUnitName(mainName);
        mainUnit.setIsMain(1);
        mainUnit.setConversionRate(BigDecimal.ONE);
        lenient().when(unitMapper.selectMainUnit(PRODUCT_ID)).thenReturn(mainUnit);
        BaseProductUnit subUnit = new BaseProductUnit();
        subUnit.setUnitId(subUnitId);
        subUnit.setUnitName("箱");
        subUnit.setIsMain(0);
        subUnit.setConversionRate(subRate);
        lenient().when(unitMapper.selectByProductId(PRODUCT_ID))
                .thenReturn(java.util.Arrays.asList(mainUnit, subUnit));
    }

    @Test
    @DisplayName("入库 - 副单位录入1箱(conv=60) → 库存按主单位加60")
    void inStock_convertsSubUnitToMain() {
        mockUnitsForConversion(1L, "卷", 2L, new BigDecimal("60"));
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(mockProduct());
        when(stockMapper.selectForUpdate(WAREHOUSE_ID, PRODUCT_ID, BATCH_NO)).thenReturn(null);

        stockService.inStock(
                "PUR_RECEIPT", 1L, "R", 1L,
                WAREHOUSE_ID, "主仓", null, null,
                PRODUCT_ID, 2L, "箱", BATCH_NO,
                new BigDecimal("1"), new BigDecimal("2.5"), null,
                1L, null, "test");

        ArgumentCaptor<InvStock> stockCap = ArgumentCaptor.forClass(InvStock.class);
        verify(stockMapper).insert(stockCap.capture());
        assertThat(stockCap.getValue().getQty()).isEqualByComparingTo("60");
        assertThat(stockCap.getValue().getUnitName()).isEqualTo("卷");
        assertThat(stockCap.getValue().getUnitId()).isEqualTo(1L);
        // amount = price * mainQty = 2.5 * 60 = 150
        assertThat(stockCap.getValue().getTotalCost()).isEqualByComparingTo("150.0000");

        ArgumentCaptor<InvLedger> ledgerCap = ArgumentCaptor.forClass(InvLedger.class);
        verify(ledgerMapper).insert(ledgerCap.capture());
        assertThat(ledgerCap.getValue().getQty()).isEqualByComparingTo("60");
        assertThat(ledgerCap.getValue().getUnitName()).isEqualTo("卷");
    }

    @Test
    @DisplayName("入库 - 主单位录入60卷 → 库存加60 (与副单位录1箱等价)")
    void inStock_mainUnitNoConversion() {
        mockUnitsForConversion(1L, "卷", 2L, new BigDecimal("60"));
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(mockProduct());
        when(stockMapper.selectForUpdate(WAREHOUSE_ID, PRODUCT_ID, BATCH_NO)).thenReturn(null);

        stockService.inStock(
                "PUR_RECEIPT", 1L, "R", 1L,
                WAREHOUSE_ID, "主仓", null, null,
                PRODUCT_ID, 1L, "卷", BATCH_NO,
                new BigDecimal("60"), new BigDecimal("2.5"), null,
                1L, null, "test");

        ArgumentCaptor<InvStock> stockCap = ArgumentCaptor.forClass(InvStock.class);
        verify(stockMapper).insert(stockCap.capture());
        assertThat(stockCap.getValue().getQty()).isEqualByComparingTo("60");
    }

    @Test
    @DisplayName("出库 - 副单位录入1箱(conv=60) → 库存按主单位减60")
    void outStock_convertsSubUnitToMain() {
        mockUnitsForConversion(1L, "卷", 2L, new BigDecimal("60"));
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(mockProduct());
        InvStock existing = new InvStock();
        existing.setId(1L);
        existing.setWarehouseId(WAREHOUSE_ID);
        existing.setProductId(PRODUCT_ID);
        existing.setQty(new BigDecimal("200"));
        existing.setAvailableQty(new BigDecimal("200"));
        existing.setAvgCost(new BigDecimal("5"));
        existing.setTotalCost(new BigDecimal("1000"));
        when(stockMapper.selectForUpdate(WAREHOUSE_ID, PRODUCT_ID, BATCH_NO)).thenReturn(existing);

        // 录 1箱 (=60卷), 库存 200 → 减 60 → 剩 140
        BigDecimal outCost = stockService.outStock(
                "SAL_DELIVERY", 1L, "X", 1L,
                WAREHOUSE_ID, "主仓", null, null,
                PRODUCT_ID, 2L, "箱", BATCH_NO,
                new BigDecimal("1"), null, null,
                null, 1L, null);

        // outCost = avgCost(5) * 60 = 300
        assertThat(outCost).isEqualByComparingTo("300.0000");
        ArgumentCaptor<InvStock> stockCap = ArgumentCaptor.forClass(InvStock.class);
        verify(stockMapper).updateById(stockCap.capture());
        assertThat(stockCap.getValue().getQty()).isEqualByComparingTo("140");
        // 注: outStock 不修改 stock 的 unitName/unitId (保留原库存快照), 仅台账写主单位

        ArgumentCaptor<InvLedger> ledgerCap = ArgumentCaptor.forClass(InvLedger.class);
        verify(ledgerMapper).insert(ledgerCap.capture());
        assertThat(ledgerCap.getValue().getQty()).isEqualByComparingTo("60");
        assertThat(ledgerCap.getValue().getUnitName()).isEqualTo("卷");
    }

    @Test
    @DisplayName("出库 - 副单位录1箱但库存不足60卷 → 抛业务异常")
    void outStock_subUnitInsufficientStock() {
        mockUnitsForConversion(1L, "卷", 2L, new BigDecimal("60"));
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(mockProduct());
        InvStock existing = new InvStock();
        existing.setId(1L);
        existing.setWarehouseId(WAREHOUSE_ID);
        existing.setProductId(PRODUCT_ID);
        existing.setQty(new BigDecimal("30"));  // 只有30卷, 不够60卷
        existing.setAvailableQty(new BigDecimal("30"));
        existing.setAvgCost(new BigDecimal("5"));
        existing.setTotalCost(new BigDecimal("150"));
        when(stockMapper.selectForUpdate(WAREHOUSE_ID, PRODUCT_ID, BATCH_NO)).thenReturn(existing);

        assertThatThrownBy(() -> stockService.outStock(
                "SAL_DELIVERY", 1L, "X", 1L,
                WAREHOUSE_ID, "主仓", null, null,
                PRODUCT_ID, 2L, "箱", BATCH_NO,
                new BigDecimal("1"), null, null,
                null, 1L, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("库存不足")
                .hasMessageContaining("需要=60");  // 错误信息显示折算后的需求
    }

    @Test
    @DisplayName("入库 - 找不到单位(脏数据兜底) → 不抛, 按 qty 直入")
    void inStock_missingUnitFallback() {
        // 不调 mockUnitsForConversion, selectMainUnit 返回 null → 走兜底
        lenient().when(unitMapper.selectMainUnit(PRODUCT_ID)).thenReturn(null);
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(mockProduct());
        when(stockMapper.selectForUpdate(WAREHOUSE_ID, PRODUCT_ID, BATCH_NO)).thenReturn(null);

        stockService.inStock(
                "PUR_RECEIPT", 1L, "R", 1L,
                WAREHOUSE_ID, "主仓", null, null,
                PRODUCT_ID, 99L, "未知单位", BATCH_NO,
                new BigDecimal("1"), new BigDecimal("10"), null,
                1L, null, "test");

        ArgumentCaptor<InvStock> stockCap = ArgumentCaptor.forClass(InvStock.class);
        verify(stockMapper).insert(stockCap.capture());
        // 兜底: qty 原值
        assertThat(stockCap.getValue().getQty()).isEqualByComparingTo("1");
    }

    // ============================ v1.1.20+ spec/model 写入测试 ============================

    @Test
    @DisplayName("入库 - 台账应写入商品的 spec 和 model (v1.1.20+)")
    void inStock_writesProductSpecModel_toLedger() {
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(mockProduct());
        when(stockMapper.selectForUpdate(WAREHOUSE_ID, PRODUCT_ID, BATCH_NO)).thenReturn(null);

        stockService.inStock(
                "PUR_RECEIPT", 1L, "RKP001", 10L,
                WAREHOUSE_ID, "主仓", null, null,
                PRODUCT_ID, 1L, "KG", BATCH_NO,
                new BigDecimal("100"), new BigDecimal("12.50"), "PO001",
                1L, null, "首次入库");

        ArgumentCaptor<InvLedger> ledgerCap = ArgumentCaptor.forClass(InvLedger.class);
        verify(ledgerMapper).insert(ledgerCap.capture());
        InvLedger ledger = ledgerCap.getValue();
        assertThat(ledger.getSpec()).isEqualTo("厚0.1mm");
        assertThat(ledger.getModel()).isEqualTo("FM-A-2026");
    }

    @Test
    @DisplayName("出库 - 台账应写入商品的 spec 和 model (v1.1.20+)")
    void outStock_writesProductSpecModel_toLedger() {
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(mockProduct());
        InvStock existing = new InvStock();
        existing.setId(1L);
        existing.setWarehouseId(WAREHOUSE_ID);
        existing.setProductId(PRODUCT_ID);
        existing.setProductName("薄膜-A");
        existing.setQty(new BigDecimal("100"));
        existing.setAvailableQty(new BigDecimal("100"));
        existing.setAvgCost(new BigDecimal("10"));
        existing.setTotalCost(new BigDecimal("1000"));
        when(stockMapper.selectForUpdate(WAREHOUSE_ID, PRODUCT_ID, BATCH_NO)).thenReturn(existing);

        stockService.outStock(
                "SAL_DELIVERY", 1L, "CKP001", 10L,
                WAREHOUSE_ID, "主仓", null, null,
                PRODUCT_ID, 1L, "KG", BATCH_NO,
                new BigDecimal("30"), null, null,
                null, 1L, "正常出库");

        ArgumentCaptor<InvLedger> ledgerCap = ArgumentCaptor.forClass(InvLedger.class);
        verify(ledgerMapper).insert(ledgerCap.capture());
        InvLedger ledger = ledgerCap.getValue();
        assertThat(ledger.getSpec()).isEqualTo("厚0.1mm");
        assertThat(ledger.getModel()).isEqualTo("FM-A-2026");
    }
}
