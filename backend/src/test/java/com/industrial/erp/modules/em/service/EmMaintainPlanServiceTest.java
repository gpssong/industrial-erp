package com.industrial.erp.modules.em.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.em.entity.BaseEquipment;
import com.industrial.erp.modules.em.entity.EmMaintainPlan;
import com.industrial.erp.modules.em.mapper.BaseEquipmentMapper;
import com.industrial.erp.modules.em.mapper.EmMaintainPlanMapper;
import com.industrial.erp.security.PermissionService;
import com.industrial.erp.utils.BillNoGenerator;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmMaintainPlanServiceTest {

    private final EmMaintainPlanMapper mapper = mock(EmMaintainPlanMapper.class);
    private final BaseEquipmentMapper equipmentMapper = mock(BaseEquipmentMapper.class);
    private final BillNoGenerator billNoGenerator = mock(BillNoGenerator.class);
    private final PermissionService permService = mock(PermissionService.class);
    private final EmMaintainPlanService service =
            new EmMaintainPlanService(mapper, equipmentMapper, billNoGenerator, permService);

    private BaseEquipment equipment;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "em-plan-test"), EmMaintainPlan.class);
        equipment = new BaseEquipment();
        equipment.setId(5L);
        equipment.setEquipmentCode("EQ-005");
        equipment.setEquipmentName("吹膜机 3 号");
        when(equipmentMapper.selectById(5L)).thenReturn(equipment);
        when(billNoGenerator.generate("MP")).thenReturn("MP202611120001");
    }

    private EmMaintainPlan plan() {
        EmMaintainPlan p = new EmMaintainPlan();
        p.setEquipmentId(5L);
        p.setMaintContent("更换螺杆密封圈");
        p.setCycleDays(30);
        return p;
    }

    @Test
    void createRejectsMissingRequiredFields() {
        assertThatThrownBy(() -> service.save(new EmMaintainPlan()))
                .isInstanceOf(BizException.class).hasMessageContaining("请选择设备");

        EmMaintainPlan noContent = plan();
        noContent.setMaintContent("  ");
        assertThatThrownBy(() -> service.save(noContent))
                .isInstanceOf(BizException.class).hasMessageContaining("维保内容必填");

        EmMaintainPlan noCycle = plan();
        noCycle.setCycleDays(0);
        assertThatThrownBy(() -> service.save(noCycle))
                .isInstanceOf(BizException.class).hasMessageContaining("周期天数");

        verify(mapper, never()).insert(any(EmMaintainPlan.class));
    }

    @Test
    void createRejectsUnknownEquipment() {
        when(equipmentMapper.selectById(5L)).thenReturn(null);
        assertThatThrownBy(() -> service.save(plan()))
                .isInstanceOf(BizException.class).hasMessageContaining("设备不存在");
        verify(mapper, never()).insert(any(EmMaintainPlan.class));
    }

    @Test
    void createBackfillsEquipmentAndDefaults() {
        EmMaintainPlan p = plan();
        service.save(p);

        assertThat(p.getEquipmentCode()).isEqualTo("EQ-005");
        assertThat(p.getEquipmentName()).isEqualTo("吹膜机 3 号");
        assertThat(p.getPlanNo()).isEqualTo("MP202611120001");
        assertThat(p.getStatus()).isEqualTo("ACTIVE");
        verify(mapper).insert(p);
    }

    @Test
    void createKeepsCallerSuppliedPlanNoAndStatus() {
        EmMaintainPlan p = plan();
        p.setPlanNo("MP-CUSTOM-1");
        p.setStatus("DRAFT");
        service.save(p);

        assertThat(p.getPlanNo()).isEqualTo("MP-CUSTOM-1");
        assertThat(p.getStatus()).isEqualTo("DRAFT");
        verify(billNoGenerator, never()).generate(anyString());
    }

    @Test
    void updateLocksIdentityFieldsToStoredPlan() {
        EmMaintainPlan origin = new EmMaintainPlan();
        origin.setId(9L);
        origin.setPlanNo("MP202601010001");
        origin.setEquipmentId(5L);
        origin.setEquipmentCode("EQ-005");
        origin.setEquipmentName("吹膜机 3 号");
        when(mapper.selectById(9L)).thenReturn(origin);

        EmMaintainPlan edit = new EmMaintainPlan();
        edit.setId(9L);
        edit.setPlanNo("HACKED");
        edit.setEquipmentId(999L);
        edit.setEquipmentCode("OTHER");
        edit.setMaintContent("调整周期");
        edit.setCycleDays(60);
        service.save(edit);

        assertThat(edit.getPlanNo()).isEqualTo("MP202601010001");
        assertThat(edit.getEquipmentId()).isEqualTo(5L);
        assertThat(edit.getEquipmentCode()).isEqualTo("EQ-005");
        assertThat(edit.getEquipmentName()).isEqualTo("吹膜机 3 号");
        verify(mapper).updateById(edit);
        verify(mapper, never()).insert(any(EmMaintainPlan.class));
    }

    @Test
    void updateRejectsMissingPlan() {
        when(mapper.selectById(9L)).thenReturn(null);
        EmMaintainPlan edit = new EmMaintainPlan();
        edit.setId(9L);
        assertThatThrownBy(() -> service.save(edit))
                .isInstanceOf(BizException.class).hasMessageContaining("计划不存在");
    }

    @Test
    void deleteRejectsMissingPlan() {
        when(mapper.selectById(1L)).thenReturn(null);
        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(BizException.class).hasMessageContaining("计划不存在");
        verify(mapper, never()).deleteById(anyLong());
    }

    @Test
    void deleteRejectsActivePlan() {
        EmMaintainPlan active = new EmMaintainPlan();
        active.setId(2L);
        active.setStatus("ACTIVE");
        when(mapper.selectById(2L)).thenReturn(active);

        assertThatThrownBy(() -> service.delete(2L))
                .isInstanceOf(BizException.class).hasMessageContaining("不可删除");
        verify(mapper, never()).deleteById(anyLong());
    }

    @Test
    void deleteRemovesDeactivatedPlan() {
        EmMaintainPlan origin = new EmMaintainPlan();
        origin.setId(3L);
        origin.setPlanNo("MP202611120001");
        origin.setStatus("DISABLED");
        when(mapper.selectById(3L)).thenReturn(origin);

        service.delete(3L);

        verify(mapper).deleteById(3L);
    }
}
