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
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * v1.1.167 #296 — 成品卷标的「生产日期」元素取 {@code mes_batch.production_date}, 而该列
 * 此前全库没有写入方, 印出来永远是空。这里锁住新建批次时的登记口径。
 *
 * <p>口径: FG/SEMI 是我们自己那天生产的 → 登记当天; RAW 的真实生产日期属于供应商
 * ({@code pur_receipt_detail.production_date}), 在食品接触材料的追溯记录上写「今天」是编造。
 */
@ExtendWith(MockitoExtension.class)
class MesBatchProductionDateTest {
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

    /** 抓 batchMapper.insert(...) 的实体 (只发生一次插入时用)。 */
    private MesBatch captureInsertedBatch() {
        ArgumentCaptor<MesBatch> captor = ArgumentCaptor.forClass(MesBatch.class);
        verify(batchMapper).insert(captor.capture());
        return captor.getValue();
    }

    @Test
    void fgBatchCreatedOnFinishedInGetsTodayAsProductionDate() {
        service.registerFgOnFinish("FG-2611-001", 10L, "SC2611001",
                "XS2611001", 7L, null, new BigDecimal("100"));

        MesBatch inserted = captureInsertedBatch();
        assertThat(inserted.getBatchType()).isEqualTo(Constants.BATCH_TYPE_FG);
        assertThat(inserted.getProductionDate()).isEqualTo(LocalDate.now());
    }

    @Test
    void semiBatchCreatedOnSemiInGetsTodayAsProductionDate() {
        service.registerSemiOnFinish("SEMI-2611-001", 10L, "SC2611001",
                "BC2611001", null, new BigDecimal("50"));

        MesBatch inserted = captureInsertedBatch();
        assertThat(inserted.getBatchType()).isEqualTo(Constants.BATCH_TYPE_SEMI);
        assertThat(inserted.getProductionDate()).isEqualTo(LocalDate.now());
    }

    /**
     * 守门用例: 防止后人把 RAW 也「顺手补全」。原料批在领料时才登记, 那天只是领用日,
     * 原料的真实生产日期在供应商侧 (pur_receipt_detail.production_date); 给食品接触材料的
     * 追溯记录写一个编造的日期, XK 检查时是要命的问题 → 必须是 null。
     */
    @Test
    void rawBatchCreatedOnIssueStaysWithoutProductionDate() {
        service.registerRawOnIssue("RAW-2611-001", null,
                new BigDecimal("20"), 10L, 3L, "LL2611001");

        MesBatch inserted = captureInsertedBatch();
        assertThat(inserted.getBatchType()).isEqualTo(Constants.BATCH_TYPE_RAW);
        assertThat(inserted.getProductionDate()).isNull();
    }

    /**
     * 幂等分支守卫: 批号已存在 (历史行, 日期已有值) 时再走成品入库审核,
     * 既不能再 insert, 也不能 updateById 把既有追溯日期改掉。
     */
    @Test
    void existingBatchKeepsItsHistoricalProductionDate() {
        LocalDate historical = LocalDate.of(2026, 3, 9);
        MesBatch exist = new MesBatch();
        exist.setId(9001L);
        exist.setBatchNo("FG-2603-001");
        exist.setBatchType(Constants.BATCH_TYPE_FG);
        exist.setProductionDate(historical);
        when(batchMapper.selectOne(any())).thenReturn(exist);

        service.registerFgOnFinish("FG-2603-001", 10L, "SC2603001",
                "XS2611001", 7L, null, new BigDecimal("100"));

        assertThat(exist.getProductionDate()).isEqualTo(historical);
        verify(batchMapper, never()).insert(any(MesBatch.class));
        verify(batchMapper, never()).updateById(any(MesBatch.class));
    }

    /**
     * 守门用例: 没走过成品入库审核的 FG 批 (存量直接出库 / 外购成品), 第一次现身就是销售出库
     * 那一刻 —— 这条一旦变红 (production_date 不再为 null), 就说明出库日正在被写成生产日期,
     * 和给 RAW 写当天是同一个性质的造假。批次照样要登记 (流向不能丢), 但日期必须留空,
     * 由入库审核侧 (registerFgOnFinish/registerSemiOnFinish) 显式设置。
     */
    @Test
    void batchNeverRegisteredAtFinishedInStaysWithoutProductionDateWhenFirstSeenAtDelivery() {
        // selectOne 默认返回 null ⇒ 库里没有这个批号, upsertBatch 走 insert 分支
        service.registerFgOutToCustomer("FG-OUT-ONLY", 1L, BigDecimal.TEN, null, "SD20261105001");

        MesBatch inserted = captureInsertedBatch();
        // 批次登记了 (流向没丢), 但日期不能被兜成出库当天
        assertThat(inserted.getBatchType()).isEqualTo(Constants.BATCH_TYPE_FG);
        assertThat(inserted.getProductionDate()).isNull();
    }
}
