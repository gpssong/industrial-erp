package com.industrial.erp;

import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.inventory.entity.InvStock;
import com.industrial.erp.modules.inventory.mapper.InvStockMapper;
import com.industrial.erp.modules.inventory.service.StockService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 库存服务核心测试:
 *  1. 入库 -> 移动加权平均成本计算
 *  2. 出库 -> 严格禁止负库存
 *  3. 正常出库 -> 扣减库存与成本
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StockServiceTest {

    @Autowired StockService stockService;
    @Autowired InvStockMapper stockMapper;

    static final Long WAREHOUSE_ID = 1L;
    static final Long PRODUCT_ID = 1L;
    static final String BATCH = "TEST_BATCH_001";

    @Test @Order(1)
    @Transactional @Rollback
    void testInStock_MovingWeightedAvg() {
        stockService.inStock("PUR_RECEIPT", 1L, "RKP001", 1L,
                WAREHOUSE_ID, "原料一库", null, null,
                PRODUCT_ID, 1L, "公斤", BATCH,
                new BigDecimal("100"), new BigDecimal("10.0000"),
                "RKP001", 1L, null, "test1");
        InvStock s = stockMapper.selectForUpdate(WAREHOUSE_ID, null, PRODUCT_ID, BATCH);
        assertNotNull(s);
        assertEquals(0, s.getQty().compareTo(new BigDecimal("100.0000")));
        assertEquals(0, s.getAvgCost().compareTo(new BigDecimal("10.0000")));
    }

    @Test @Order(2)
    @Transactional @Rollback
    void testOutStock_StrictlyProhibitNegative() {
        stockService.inStock("PUR_RECEIPT", 100L, "RKP100", 1L,
                WAREHOUSE_ID, "原料一库", null, null,
                PRODUCT_ID, 1L, "公斤", BATCH,
                new BigDecimal("10"), new BigDecimal("10.0000"),
                "RKP100", 1L, null, "test");
        BizException ex = assertThrows(BizException.class, () -> {
            stockService.outStock("SAL_DELIVERY", 200L, "CKP200", 1L,
                    WAREHOUSE_ID, "原料一库", null, null,
                    PRODUCT_ID, 1L, "公斤", BATCH,
                    new BigDecimal("20"), new BigDecimal("15.0000"),
                    "CKP200", null, 1L, "test");
        });
        assertTrue(ex.getMessage().contains("库存不足"));
    }

    @Test @Order(3)
    @Transactional @Rollback
    void testOutStock_NormalFlow() {
        stockService.inStock("PUR_RECEIPT", 300L, "RKP300", 1L,
                WAREHOUSE_ID, "原料一库", null, null,
                PRODUCT_ID, 1L, "公斤", BATCH,
                new BigDecimal("100"), new BigDecimal("10.0000"),
                "RKP300", 1L, null, "test");
        BigDecimal cost = stockService.outStock("SAL_DELIVERY", 301L, "CKP301", 1L,
                WAREHOUSE_ID, "原料一库", null, null,
                PRODUCT_ID, 1L, "公斤", BATCH,
                new BigDecimal("30"), new BigDecimal("20.0000"),
                "CKP301", null, 1L, "test");
        assertEquals(0, cost.compareTo(new BigDecimal("300.0000")));
        InvStock s = stockMapper.selectForUpdate(WAREHOUSE_ID, null, PRODUCT_ID, BATCH);
        assertEquals(0, s.getQty().compareTo(new BigDecimal("70.0000")));
    }
}
