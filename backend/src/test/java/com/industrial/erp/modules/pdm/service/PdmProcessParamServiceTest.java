package com.industrial.erp.modules.pdm.service;

import com.industrial.erp.modules.pdm.entity.MesProcessParam;
import com.industrial.erp.modules.pdm.mapper.MesProcessParamMapper;
import com.industrial.erp.modules.system.entity.SysOperLog;
import com.industrial.erp.modules.system.mapper.SysOperLogMapper;
import com.industrial.erp.security.SecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PdmProcessParamServiceTest {

    private final MesProcessParamMapper paramMapper = mock(MesProcessParamMapper.class);
    private final SysOperLogMapper operLogMapper = mock(SysOperLogMapper.class);
    private final PdmProcessParamService service = new PdmProcessParamService(paramMapper, operLogMapper);

    @BeforeEach
    void setUp() {
        SecurityContext.setCurrentUserIdForTest(42L);
    }

    @AfterEach
    void cleanUp() {
        SecurityContext.clearForTest();
    }

    private MesProcessParam param() {
        MesProcessParam p = new MesProcessParam();
        p.setProcessDefId(9L);
        p.setParamKey("温度");
        p.setParamValue("165");
        p.setUnit("℃");
        return p;
    }

    @Test
    void listReturnsEmptyWhenProcessDefIdMissing() {
        assertThat(service.list(null)).isEmpty();
        verify(paramMapper, never()).selectByProcessDefId(any());
    }

    @Test
    void listDelegatesToMapper() {
        MesProcessParam p = param();
        when(paramMapper.selectByProcessDefId(9L)).thenReturn(List.of(p));
        assertThat(service.list(9L)).containsExactly(p);
    }

    @Test
    void saveRejectsMissingProcessDefId() {
        MesProcessParam p = param();
        p.setProcessDefId(null);
        assertThatThrownBy(() -> service.save(p))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("processDefId");
        verify(paramMapper, never()).insert(any(MesProcessParam.class));
    }

    @Test
    void saveRejectsBlankParamKey() {
        MesProcessParam p = param();
        p.setParamKey(" ");
        assertThatThrownBy(() -> service.save(p))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("paramKey");
        verify(paramMapper, never()).insert(any(MesProcessParam.class));
    }

    @Test
    void saveInsertAppliesDefaultsAndWritesAudit() {
        MesProcessParam p = param();
        service.save(p);

        assertThat(p.getIsCritical()).isZero();
        assertThat(p.getSortNo()).isZero();
        verify(paramMapper).insert(p);
        verify(paramMapper, never()).updateById(any(MesProcessParam.class));

        ArgumentCaptor<SysOperLog> log = ArgumentCaptor.forClass(SysOperLog.class);
        verify(operLogMapper).insert(log.capture());
        assertThat(log.getValue().getModule()).isEqualTo("工艺参数");
        assertThat(log.getValue().getBusinessType()).isEqualTo("PDM");
        assertThat(log.getValue().getMethod()).contains("SAVE_PARAM");
        assertThat(log.getValue().getRequestParam()).contains("key=温度");
        assertThat(log.getValue().getUserId()).isEqualTo(42L);
        assertThat(log.getValue().getStatus()).isZero();
    }

    @Test
    void saveUpdateKeepsExplicitCriticalFlag() {
        MesProcessParam p = param();
        p.setId(11L);
        p.setIsCritical(1);
        p.setSortNo(3);
        service.save(p);

        verify(paramMapper).updateById(p);
        verify(paramMapper, never()).insert(any(MesProcessParam.class));
        assertThat(p.getIsCritical()).isEqualTo(1);
        assertThat(p.getSortNo()).isEqualTo(3);
    }

    @Test
    void saveStillSucceedsWhenAuditWriteFails() {
        when(operLogMapper.insert(any(SysOperLog.class))).thenThrow(new RuntimeException("audit down"));
        MesProcessParam p = param();

        assertThatCode(() -> service.save(p)).doesNotThrowAnyException();

        verify(paramMapper).insert(p);
    }

    @Test
    void deleteMissingParamDoesNothing() {
        when(paramMapper.selectById(7L)).thenReturn(null);
        service.delete(7L);

        verify(paramMapper, never()).deleteById(anyLong());
        verify(operLogMapper, never()).insert(any(SysOperLog.class));
    }

    @Test
    void deleteExistingParamSoftDeletesAndAudits() {
        MesProcessParam exist = param();
        exist.setId(7L);
        when(paramMapper.selectById(7L)).thenReturn(exist);

        service.delete(7L);

        verify(paramMapper).deleteById(7L);
        ArgumentCaptor<SysOperLog> log = ArgumentCaptor.forClass(SysOperLog.class);
        verify(operLogMapper).insert(log.capture());
        assertThat(log.getValue().getMethod()).contains("DEL_PARAM");
        assertThat(log.getValue().getRequestParam()).contains("id=7");
    }
}
