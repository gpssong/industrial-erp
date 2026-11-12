package com.industrial.erp.modules.workflow.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.industrial.erp.modules.workflow.entity.WfEmergencyPlan;
import com.industrial.erp.modules.workflow.entity.WfMaintainRecord;
import com.industrial.erp.modules.workflow.entity.WfSafetyRecord;
import com.industrial.erp.modules.workflow.mapper.WfEmergencyPlanMapper;
import com.industrial.erp.modules.workflow.mapper.WfMaintainRecordMapper;
import com.industrial.erp.modules.workflow.mapper.WfSafetyRecordMapper;
import com.industrial.erp.security.PermissionService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 工作流看板聚合: 三类到期记录合并成同一份卡片数据 (type/dueDate/daysLeft).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WfDashboardService 到期看板聚合")
class WfDashboardServiceTest {

    @Mock private WfMaintainRecordMapper maintainMapper;
    @Mock private WfSafetyRecordMapper safetyMapper;
    @Mock private WfEmergencyPlanMapper emergencyMapper;
    @Mock private PermissionService permService;

    @InjectMocks private WfDashboardService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "wf-maintain-test"), WfMaintainRecord.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "wf-safety-test"), WfSafetyRecord.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "wf-emergency-test"), WfEmergencyPlan.class);
    }

    @Test
    @DisplayName("三类记录合并, daysLeft 按到期日算")
    void upcoming_mergesThreeSources() {
        LocalDate today = LocalDate.now();
        WfMaintainRecord m = new WfMaintainRecord();
        m.setId(1L);
        m.setRecordNo("WB001");
        m.setDeviceName("吹膜机A");
        m.setNextDueDate(today.plusDays(3));
        m.setBillStatus("CHECKED");
        when(maintainMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(m));

        WfSafetyRecord s = new WfSafetyRecord();
        s.setId(2L);
        s.setRecordNo("AQ001");
        s.setSite(null);
        s.setNextDueDate(today.plusDays(7));
        when(safetyMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(s));

        WfEmergencyPlan e = new WfEmergencyPlan();
        e.setId(3L);
        e.setPlanNo("YJ001");
        e.setScenario("火灾");
        e.setNextDrillDate(today);
        when(emergencyMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(e));

        List<Map<String, Object>> out = service.upcoming(30);

        assertThat(out).hasSize(3);
        assertThat(out).extracting(r -> r.get("type")).containsExactly("MAINTAIN", "SAFETY", "EMERGENCY");

        Map<String, Object> maintain = out.get(0);
        assertThat(maintain.get("no")).isEqualTo("WB001");
        assertThat(maintain.get("label")).isEqualTo("吹膜机A");
        assertThat(maintain.get("dueDate")).isEqualTo(today.plusDays(3));
        assertThat(maintain.get("daysLeft")).isEqualTo(3L);
        assertThat(maintain.get("status")).isEqualTo("CHECKED");

        // 检查记录没有站点时占位空串, 不返回 null (前端直接拼接)
        assertThat(out.get(1).get("label")).isEqualTo("");
        assertThat(out.get(1).get("daysLeft")).isEqualTo(7L);

        // 应急预案标签 = 预案号 · 场景, 到期日取演练日期
        assertThat(out.get(2).get("label")).isEqualTo("YJ001 · 火灾");
        assertThat(out.get(2).get("no")).isEqualTo("YJ001");
        assertThat(out.get(2).get("daysLeft")).isEqualTo(0L);
    }

    @Test
    @DisplayName("预案号与场景都为空: 标签为空串而不是 null 拼接")
    void upcoming_emergencyBlankLabel() {
        LocalDate today = LocalDate.now();
        WfEmergencyPlan e = new WfEmergencyPlan();
        e.setId(3L);
        e.setNextDrillDate(today.plusDays(1));
        when(maintainMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(safetyMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(emergencyMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(e));

        List<Map<String, Object>> out = service.upcoming(30);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).get("label")).isEqualTo("");
        assertThat(out.get(0).get("no")).isNull();
    }

    @Test
    @DisplayName("三类都没有到期记录: 返回空列表")
    void upcoming_emptyWhenNothingDue() {
        when(maintainMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(safetyMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(emergencyMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        assertThat(service.upcoming(30)).isEmpty();
    }
}
