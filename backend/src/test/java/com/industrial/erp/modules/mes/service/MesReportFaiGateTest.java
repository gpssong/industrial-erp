package com.industrial.erp.modules.mes.service;

import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.mes.entity.PrdProcess;
import com.industrial.erp.modules.mes.mapper.*;
import com.industrial.erp.modules.production.mapper.PrdOrderMapper;
import com.industrial.erp.modules.system.mapper.SysOperLogMapper;
import com.industrial.erp.modules.pdm.mapper.MesProcessParamMapper;
import com.industrial.erp.modules.qms.service.QmsInspectionService;
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
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MesReportFaiGateTest {
    @Mock PrdProcessMapper procMapper;
    @Mock MesProcessDefMapper defMapper;
    @Mock MesBatchMapper batchMapper;
    @Mock MesBatchLinkMapper linkMapper;
    @Mock PrdOrderMapper orderMapper;
    @Mock SysOperLogMapper operLogMapper;
    @Mock MesProcessParamMapper paramMapper;
    @Mock QmsInspectionService qmsInspectionService;
    @InjectMocks MesReportService service;
    PrdProcess process;

    @BeforeEach
    void setUp() {
        SecurityContext.setCurrentUserIdForTest(1L);
        process = new PrdProcess();
        process.setId(20L);
        process.setPrdOrderId(10L);
        process.setProcessName("制袋");
        process.setStatus(0);
        process.setPlanQty(BigDecimal.TEN);
        when(procMapper.selectById(20L)).thenReturn(process);
    }

    @AfterEach
    void cleanUp() { SecurityContext.clearForTest(); }

    @Test
    void rejectsBagProductionWithoutItsOwnPassedFai() {
        when(qmsInspectionService.isBagProcess(process)).thenReturn(true);
        assertThatThrownBy(() -> service.reportWork(10L, 20L, BigDecimal.ONE, BigDecimal.ZERO, "tester"))
                .isInstanceOf(BizException.class).hasMessageContaining("制袋工序首检尚未合格");
        verify(procMapper, never()).updateById(any(PrdProcess.class));
        verifyNoInteractions(orderMapper);
    }

    @Test
    void rejectsDefectOnlyBagProductionWithoutFai() {
        when(qmsInspectionService.isBagProcess(process)).thenReturn(true);
        assertThatThrownBy(() -> service.reportWork(10L, 20L, BigDecimal.ZERO, BigDecimal.ONE, "tester"))
                .isInstanceOf(BizException.class).hasMessageContaining("首检");
        verify(procMapper, never()).updateById(any(PrdProcess.class));
    }

    @Test
    void allowsBagProductionAfterSameProcessFaiPassed() {
        when(qmsInspectionService.isBagProcess(process)).thenReturn(true);
        when(qmsInspectionService.hasPassedFaiForProcess(20L)).thenReturn(true);
        when(procMapper.selectList(any())).thenReturn(List.of(process));
        service.reportWork(10L, 20L, BigDecimal.ONE, BigDecimal.ZERO, "tester");
        ArgumentCaptor<PrdProcess> captor = ArgumentCaptor.forClass(PrdProcess.class);
        verify(procMapper).updateById(captor.capture());
        assertThat(captor.getValue().getGoodQty()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(captor.getValue().getStatus()).isEqualTo(1);
    }

    @Test
    void doesNotRequireBagFaiForBlowFilmProcess() {
        process.setProcessName("吹膜");
        when(procMapper.selectList(any())).thenReturn(List.of(process));
        service.reportWork(10L, 20L, BigDecimal.ONE, BigDecimal.ZERO, "tester");
        verify(qmsInspectionService, never()).hasPassedFaiForProcess(any());
        verify(procMapper).updateById(any(PrdProcess.class));
    }

    @Test
    void rejectsProcessFromAnotherOrderBeforeInspectionCheck() {
        assertThatThrownBy(() -> service.reportWork(11L, 20L, BigDecimal.ONE, BigDecimal.ZERO, "tester"))
                .isInstanceOf(BizException.class).hasMessageContaining("不属于该生产单");
        verifyNoInteractions(qmsInspectionService);
        verify(procMapper, never()).updateById(any(PrdProcess.class));
    }
}
