package com.industrial.erp.modules.mes.service;

import com.industrial.erp.common.Constants;
import com.industrial.erp.modules.mes.entity.MesBatch;
import com.industrial.erp.modules.mes.mapper.MesBatchLinkMapper;
import com.industrial.erp.modules.mes.mapper.MesBatchMapper;
import com.industrial.erp.modules.mes.mapper.MesProcessDefMapper;
import com.industrial.erp.modules.mes.mapper.PrdProcessMapper;
import com.industrial.erp.modules.pdm.mapper.MesProcessParamMapper;
import com.industrial.erp.modules.production.mapper.PrdOrderMapper;
import com.industrial.erp.modules.qms.service.QmsInspectionService;
import com.industrial.erp.modules.system.mapper.SysOperLogMapper;
import com.industrial.erp.security.SecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * v1.1.188 — 采购入库审核登记 RAW 原料批。
 *
 * <p>此前 {@code mes_batch} 的 RAW 行只由「生产领料/开工」写入 ({@link MesReportService#registerRawOnIssue}),
 * 采购入库审核只写 {@code inv_stock} 自由文本批号 ⇒ XK 批次溯源的「原料批」列表恒空
 * (领料明细批号也是空, 两个入口都不登记)。入库审核才是原料批第一次进入公司的时刻。
 */
@ExtendWith(MockitoExtension.class)
class MesBatchReceiptRegisterTest {
    @Mock PrdProcessMapper procMapper;
    @Mock MesProcessDefMapper defMapper;
    @Mock MesBatchMapper batchMapper;
    @Mock MesBatchLinkMapper linkMapper;
    @Mock PrdOrderMapper orderMapper;
    @Mock SysOperLogMapper operLogMapper;
    @Mock MesProcessParamMapper paramMapper;
    @Mock QmsInspectionService qmsInspectionService;
    @InjectMocks MesReportService service;

    @BeforeEach
    void setUp() {
        SecurityContext.setCurrentUserIdForTest(1L);
    }

    @AfterEach
    void cleanUp() {
        SecurityContext.clearForTest();
    }

    private MesBatch captureInsertedBatch() {
        ArgumentCaptor<MesBatch> captor = ArgumentCaptor.forClass(MesBatch.class);
        verify(batchMapper).insert(captor.capture());
        return captor.getValue();
    }

    @Test
    void newRawBatchFromReceiptCarriesSupplierAndReceiptNo() {
        service.registerRawOnReceipt("SK216A08C1", 501L, 9L, "RKP202611140005", new BigDecimal("1500"));

        MesBatch inserted = captureInsertedBatch();
        assertThat(inserted.getBatchNo()).isEqualTo("SK216A08C1");
        assertThat(inserted.getBatchType()).isEqualTo(Constants.BATCH_TYPE_RAW);
        assertThat(inserted.getStatus()).isEqualTo("ACTIVE");
        assertThat(inserted.getProductId()).isEqualTo(501L);
        assertThat(inserted.getSupplierId()).isEqualTo(9L);
        assertThat(inserted.getReceiptBillNo()).isEqualTo("RKP202611140005");
        assertThat(inserted.getQty()).isEqualByComparingTo("1500");
        assertThat(inserted.getCreateBy()).isEqualTo(1L);
        // 供应商的真实生产日期不在入库审核里, 不能兜成"今天"
        assertThat(inserted.getProductionDate()).isNull();
    }

    @Test
    void existingBatchOnlyBackfillsBlankTraceFields() {
        MesBatch exist = new MesBatch();
        exist.setId(9001L);
        exist.setBatchNo("SK216A08C1");
        exist.setStatus("ACTIVE");
        when(batchMapper.selectOne(any())).thenReturn(exist);

        service.registerRawOnReceipt("SK216A08C1", 501L, 9L, "RKP202611140005", new BigDecimal("1500"));

        ArgumentCaptor<MesBatch> captor = ArgumentCaptor.forClass(MesBatch.class);
        verify(batchMapper).updateById(captor.capture());
        MesBatch upd = captor.getValue();
        assertThat(upd.getId()).isEqualTo(9001L);
        assertThat(upd.getBatchType()).isEqualTo(Constants.BATCH_TYPE_RAW);
        assertThat(upd.getProductId()).isEqualTo(501L);
        assertThat(upd.getSupplierId()).isEqualTo(9L);
        assertThat(upd.getReceiptBillNo()).isEqualTo("RKP202611140005");
        verify(batchMapper, never()).insert(any(MesBatch.class));
    }

    @Test
    void existingBatchWithTraceFieldsIsNotOverwritten() {
        MesBatch exist = new MesBatch();
        exist.setId(9002L);
        exist.setBatchNo("SK216A08C1");
        exist.setBatchType(Constants.BATCH_TYPE_RAW);
        exist.setProductId(501L);
        exist.setSupplierId(9L);
        exist.setReceiptBillNo("RKP202611100001");
        when(batchMapper.selectOne(any())).thenReturn(exist);

        service.registerRawOnReceipt("SK216A08C1", 501L, 9L, "RKP202611140005", new BigDecimal("1500"));

        verify(batchMapper, never()).insert(any(MesBatch.class));
        verify(batchMapper, never()).updateById(any(MesBatch.class));
        assertThat(exist.getReceiptBillNo()).isEqualTo("RKP202611100001");
    }

    @Test
    void blankBatchNoIsSkipped() {
        service.registerRawOnReceipt("  ", 501L, 9L, "RKP202611140005", new BigDecimal("1500"));

        verifyNoInteractions(batchMapper, linkMapper);
    }
}
