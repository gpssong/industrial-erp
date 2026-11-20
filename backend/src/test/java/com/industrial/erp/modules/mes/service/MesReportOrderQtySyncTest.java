package com.industrial.erp.modules.mes.service;

import com.industrial.erp.modules.mes.entity.PrdProcess;
import com.industrial.erp.modules.mes.mapper.*;
import com.industrial.erp.modules.pdm.mapper.MesProcessParamMapper;
import com.industrial.erp.modules.production.entity.PrdOrder;
import com.industrial.erp.modules.production.mapper.PrdOrderMapper;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * v1.1.204 — 工单头的良品/实际量必须包含「本次」报工, 不能永远滞后一次。
 *
 * <p>取证时发现 PD202611030002 制袋工序 good_qty=2000, 工单头 good_qty 却是 0:
 * reportWork 里 syncOrderQtyFromProcesses(orderId) 排在 procMapper.updateById(upd) <b>之前</b>,
 * 聚合读到的还是更新前的行 ⇒ 每次报工后的工单看板数字都少一次报工的量。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("报工后工单头数量聚合")
class MesReportOrderQtySyncTest {
    @Mock PrdProcessMapper procMapper;
    @Mock MesProcessDefMapper defMapper;
    @Mock MesBatchMapper batchMapper;
    @Mock MesBatchLinkMapper linkMapper;
    @Mock PrdOrderMapper orderMapper;
    @Mock SysOperLogMapper operLogMapper;
    @Mock MesProcessParamMapper paramMapper;
    @Mock QmsInspectionService qmsInspectionService;
    @InjectMocks MesReportService service;

    PrdProcess bag;

    @BeforeEach
    void setUp() {
        SecurityContext.setCurrentUserIdForTest(1L);
        bag = new PrdProcess();
        bag.setId(20L);
        bag.setPrdOrderId(10L);
        bag.setProcessName("制袋");
        bag.setStatus(1);
        bag.setPlanQty(new BigDecimal("2000"));
        bag.setGoodQty(BigDecimal.ZERO);
        when(procMapper.selectById(20L)).thenReturn(bag);
        when(qmsInspectionService.isBagProcess(bag)).thenReturn(true);
        when(qmsInspectionService.isFoodGradeOrder(10L)).thenReturn(false);
    }

    @AfterEach
    void cleanUp() { SecurityContext.clearForTest(); }

    @Test
    @DisplayName("先落工序行, 再聚合工单头 (工单头不能少算本次报工)")
    void aggregatesOrderQtyAfterProcessRowIsWritten() {
        when(procMapper.selectList(any())).thenReturn(List.of(bag));

        service.reportWork(10L, 20L, new BigDecimal("1000"), BigDecimal.ZERO, "tester");

        InOrder order = inOrder(procMapper, orderMapper);
        order.verify(procMapper).updateById(any(PrdProcess.class));
        order.verify(orderMapper).updateById(any(PrdOrder.class));
    }
}
