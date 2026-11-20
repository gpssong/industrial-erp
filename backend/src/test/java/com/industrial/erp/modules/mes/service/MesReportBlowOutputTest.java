package com.industrial.erp.modules.mes.service;

import com.industrial.erp.common.Constants;
import com.industrial.erp.modules.mes.entity.PrdProcess;
import com.industrial.erp.modules.mes.mapper.*;
import com.industrial.erp.modules.production.entity.PrdOrder;
import com.industrial.erp.modules.production.mapper.PrdOrderMapper;
import com.industrial.erp.modules.pdm.mapper.MesProcessParamMapper;
import com.industrial.erp.modules.qms.service.QmsInspectionService;
import com.industrial.erp.modules.system.mapper.SysOperLogMapper;
import com.industrial.erp.security.SecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * v1.1.204 — 半成品入库审核量 = 吹膜工序的产出 (口径由用户拍板「半成品入库=吹膜的报工」).
 *
 * <p>事故: 工单 PD202611030002 现场「所有工序都做完了」, App 点「审核入库」仍被
 * 「生产工单存在未完工工序, 不能成品入库: 吹膜」拦下。根因不是数据缺失, 是流程没有写入口:
 * 工序 status=2 唯一写方是报工 (reportWork), 而 App 的吹膜链路 (开工 → 首检 → 半成品入库)
 * 压根没有报工这一步 (报工只在制袋), 所以食品级单的吹膜行永远停在 status=1,
 * v1.1.99 C1 的成品入库工序门禁对「有吹膜工序的食品级单」从来没可能被满足 (库内 13 行吹膜工序 0/1, 无一 status=2)。
 *
 * <p>口径: 半成品入库审核通过时, 按该单<b>已 CHECKED 的半成品入库总量重算</b>吹膜工序合格量
 * (不是增量, 所以 审核→反审核→再审核 不会重复累计); 只抬高不压低 (手工报工过的量不被抹掉);
 * 已完工的行不回退 (与半成品反审核「MES/QMS 不回退」同口径)。制袋仍走报工, 本方法不碰。
 */
@ExtendWith(MockitoExtension.class)
class MesReportBlowOutputTest {
    @Mock PrdProcessMapper procMapper;
    @Mock MesProcessDefMapper defMapper;
    @Mock MesBatchMapper batchMapper;
    @Mock MesBatchLinkMapper linkMapper;
    @Mock PrdOrderMapper orderMapper;
    @Mock SysOperLogMapper operLogMapper;
    @Mock MesProcessParamMapper paramMapper;
    @Mock QmsInspectionService qmsInspectionService;
    @InjectMocks MesReportService service;

    PrdProcess blow;
    PrdProcess bag;

    @BeforeEach
    void setUp() {
        SecurityContext.setCurrentUserIdForTest(1L);
        bag = process(20L, "制袋", 1, BigDecimal.ZERO);
        blow = process(21L, "吹膜", 1, BigDecimal.ZERO);
    }

    @AfterEach
    void cleanUp() { SecurityContext.clearForTest(); }

    private static PrdProcess process(Long id, String name, int status, BigDecimal good) {
        PrdProcess p = new PrdProcess();
        p.setId(id);
        p.setPrdOrderId(10L);
        p.setProcessName(name);
        p.setStatus(status);
        p.setPlanQty(new BigDecimal("2000"));
        p.setGoodQty(good);
        return p;
    }

    /** 该单已审核的半成品入库总量达计划量 → 吹膜工序记满量并完工. */
    @Test
    void marksBlowProcessFinishedWhenSemiInOutputReachesPlan() {
        when(qmsInspectionService.listInspectionProcesses(10L)).thenReturn(List.of(bag, blow));
        when(qmsInspectionService.isBagProcess(bag)).thenReturn(true);
        when(qmsInspectionService.isBagProcess(blow)).thenReturn(false);
        when(procMapper.selectList(any())).thenReturn(List.of(bag, blow));

        service.recordBlowOutput(10L, new BigDecimal("2000"), "PSI202611170002");

        ArgumentCaptor<PrdProcess> captor = ArgumentCaptor.forClass(PrdProcess.class);
        verify(procMapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(21L);
        assertThat(captor.getValue().getGoodQty()).isEqualByComparingTo("2000");
        assertThat(captor.getValue().getStatus()).isEqualTo(Constants.PROC_STATUS_FINISHED);
        assertThat(captor.getValue().getEndTime()).isNotNull();
    }

    /** 半成品入库量未达计划量 → 吹膜工序只记进度, 不算完工. */
    @Test
    void keepsBlowProcessInProgressBelowPlan() {
        when(qmsInspectionService.listInspectionProcesses(10L)).thenReturn(List.of(bag, blow));
        when(qmsInspectionService.isBagProcess(bag)).thenReturn(true);
        when(qmsInspectionService.isBagProcess(blow)).thenReturn(false);
        when(procMapper.selectList(any())).thenReturn(List.of(bag, blow));

        service.recordBlowOutput(10L, new BigDecimal("500"), "PSI202611170002");

        ArgumentCaptor<PrdProcess> captor = ArgumentCaptor.forClass(PrdProcess.class);
        verify(procMapper).updateById(captor.capture());
        assertThat(captor.getValue().getGoodQty()).isEqualByComparingTo("500");
        assertThat(captor.getValue().getStatus()).isEqualTo(Constants.PROC_STATUS_IN_PROGRESS);
        assertThat(captor.getValue().getEndTime()).isNull();
    }

    /** 已完工的吹膜行不回退: 半成品反审核/重审不能把 status=2 改回去. */
    @Test
    void doesNotTouchFinishedBlowProcess() {
        blow.setStatus(Constants.PROC_STATUS_FINISHED);
        when(qmsInspectionService.listInspectionProcesses(10L)).thenReturn(List.of(bag, blow));
        when(qmsInspectionService.isBagProcess(bag)).thenReturn(true);
        when(qmsInspectionService.isBagProcess(blow)).thenReturn(false);

        service.recordBlowOutput(10L, new BigDecimal("2000"), "PSI202611170002");

        verify(procMapper, never()).updateById(any(PrdProcess.class));
        verify(orderMapper, never()).updateById(any(PrdOrder.class));
    }

    /** 重算不是覆盖: 现场已手工报工 2000, 半成品只入到 1800, 不能被抹低. */
    @Test
    void doesNotLowerGoodQtyAlreadyReported() {
        blow.setGoodQty(new BigDecimal("2000"));
        when(qmsInspectionService.listInspectionProcesses(10L)).thenReturn(List.of(bag, blow));
        when(qmsInspectionService.isBagProcess(bag)).thenReturn(true);
        when(qmsInspectionService.isBagProcess(blow)).thenReturn(false);
        when(procMapper.selectList(any())).thenReturn(List.of(bag, blow));

        service.recordBlowOutput(10L, new BigDecimal("1800"), "PSI202611170002");

        ArgumentCaptor<PrdProcess> captor = ArgumentCaptor.forClass(PrdProcess.class);
        verify(procMapper).updateById(captor.capture());
        assertThat(captor.getValue().getGoodQty()).isEqualByComparingTo("2000");
        assertThat(captor.getValue().getStatus()).isEqualTo(Constants.PROC_STATUS_FINISHED);
    }

    /** 没有吹膜工序的单 (只有制袋/无工序链) 不写任何东西. */
    @Test
    void writesNothingWhenOrderHasNoBlowProcess() {
        when(qmsInspectionService.listInspectionProcesses(10L)).thenReturn(List.of(bag));
        when(qmsInspectionService.isBagProcess(bag)).thenReturn(true);

        service.recordBlowOutput(10L, new BigDecimal("2000"), "PSI202611170002");

        verify(procMapper, never()).updateById(any(PrdProcess.class));
    }

    /** 该单还没有任何已审核半成品入库 (量 0) → 不碰工序, 也不重算工单头. */
    @Test
    void writesNothingWithoutCheckedOutput() {
        service.recordBlowOutput(10L, BigDecimal.ZERO, "PSI202611170002");

        verifyNoInteractions(qmsInspectionService);
        verify(procMapper, never()).updateById(any(PrdProcess.class));
        verify(orderMapper, never()).updateById(any(PrdOrder.class));
    }

    /** 无工单的手工半成品入库单 (orderId 为空) 不能凭空找工序. */
    @Test
    void writesNothingWithoutOrderId() {
        service.recordBlowOutput(null, new BigDecimal("2000"), "PSI202611170002");

        verifyNoInteractions(qmsInspectionService);
        verify(procMapper, never()).updateById(any(PrdProcess.class));
    }
}
