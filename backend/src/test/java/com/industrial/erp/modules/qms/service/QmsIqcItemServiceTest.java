package com.industrial.erp.modules.qms.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.qms.entity.MesQualityInspection;
import com.industrial.erp.modules.qms.entity.QmsIqcItem;
import com.industrial.erp.modules.qms.mapper.MesQualityInspectionMapper;
import com.industrial.erp.modules.qms.mapper.QmsIqcItemMapper;
import com.industrial.erp.security.SecurityContext;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.BeanUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QmsIqcItemServiceTest {
    private final QmsIqcItemMapper items = mock(QmsIqcItemMapper.class);
    private final MesQualityInspectionMapper inspections = mock(MesQualityInspectionMapper.class);
    private final QmsIqcItemService service = new QmsIqcItemService(items, inspections);
    private MesQualityInspection head;
    private QmsIqcItem stored;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "qms-item-test"), QmsIqcItem.class);
        SecurityContext.setCurrentUserIdForTest(77L);
        head = new MesQualityInspection();
        head.setId(1L);
        head.setInspectionType("FAI");
        head.setStatus("DRAFT");
        when(inspections.selectByIdForUpdate(1L)).thenReturn(head);
        stored = new QmsIqcItem();
        stored.setId(2L);
        stored.setIqcId(1L);
        stored.setLineNo(1);
        stored.setItemCode("BAG_MATERIAL");
        stored.setItemName("半成品卷批次及放行");
        stored.setRequirement("产品标准与工艺卡要求");
        stored.setMethod("核对放行记录");
        stored.setSource("TEMPLATE");
        stored.setIsFoodGradeItem(0);
        stored.setPassFlag(-1);
        when(items.selectByIqcId(1L)).thenReturn(List.of(stored));
        when(items.update(isNull(), any())).thenReturn(1);
    }

    @AfterEach
    void cleanUp() { SecurityContext.clearForTest(); }

    @ParameterizedTest
    @ValueSource(strings = {"FAI", "PAT"})
    void onlyUpdatesActualValuesWithOriginalIdsAndServerDefinitions(String type) {
        head.setInspectionType(type);
        QmsIqcItem input = input();
        input.setResultValue("卷批 B10 已核验");
        input.setPassFlag(1);
        input.setJudgmentBasis("放行报告 QI10");
        service.batchSave(1L, List.of(input));
        ArgumentCaptor<LambdaUpdateWrapper> update = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(items).update(isNull(), update.capture());
        assertThat(update.getValue().getSqlSegment()).contains("id", "iqc_id");
        assertThat(update.getValue().getSqlSet()).contains("result_value", "pass_flag", "judgment_basis", "update_by", "version = version + 1")
                .doesNotContain("item_name", "requirement", "method", "source", "food", "line_no");
        assertThat(update.getValue().getParamNameValuePairs().values()).contains(2L, 1L, "卷批 B10 已核验", "放行报告 QI10", 77L);
        verify(items, never()).deleteByIqcId(anyLong());
        verify(items, never()).insert(any(QmsIqcItem.class));
        assertThat(stored.getId()).isEqualTo(2L);
        assertThat(stored.getRequirement()).isEqualTo("产品标准与工艺卡要求");
    }

    @Test
    void nullJudgmentMeansUntestedAndClearsOldMeasurement() {
        service.batchSave(1L, List.of(input()));
        ArgumentCaptor<LambdaUpdateWrapper> update = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(items).update(isNull(), update.capture());
        assertThat(update.getValue().getParamNameValuePairs().values()).contains(-1, (Object) null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"IQC", "FAI", "PAT"})
    void signedHeadIsReadOnlyForAllTypes(String type) {
        head.setInspectionType(type);
        head.setStatus("CHECKED");
        assertThatThrownBy(() -> service.batchSave(1L, List.of(input()))).hasMessageContaining("已签核记录只读");
        assertThatThrownBy(() -> service.wipeByIqcId(1L)).hasMessageContaining("已签核记录只读");
        verifyNoInteractions(items);
    }

    @Test
    void missingHeadIsRejectedBeforeItemWrites() {
        assertThatThrownBy(() -> service.batchSave(9L, List.of(input()))).hasMessageContaining("不存在");
        verifyNoInteractions(items);
    }

    @Test
    void cannotRemoveEmptyOrReplaceTemplatesWithManualItem() {
        assertThatThrownBy(() -> service.batchSave(1L, null)).hasMessageContaining("完整检验项");
        assertThatThrownBy(() -> service.batchSave(1L, List.of())).hasMessageContaining("完整检验项");
        QmsIqcItem item = input();
        item.setItemCode("MANUAL_PASS");
        assertThatThrownBy(() -> service.batchSave(1L, List.of(item))).hasMessageContaining("模板检验项不完整");
        when(items.selectByIqcId(1L)).thenReturn(List.of());
        assertThatThrownBy(() -> service.batchSave(1L, List.of(item))).hasMessageContaining("缺少服务器模板");
        assertThatThrownBy(() -> service.wipeByIqcId(1L)).hasMessageContaining("不允许删除");
        verify(items, never()).deleteByIqcId(anyLong());
        verify(items, never()).update(isNull(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "requirement", "method", "source", "food", "id", "owner", "unit", "deleted"})
    void rejectsForgedTemplateDefinitionsAndOwnership(String field) {
        QmsIqcItem input = input();
        switch (field) {
            case "name" -> input.setItemName("假的检验项");
            case "requirement" -> input.setRequirement("无要求");
            case "method" -> input.setMethod("手拉冒充强度");
            case "source" -> input.setSource("MANUAL");
            case "food" -> input.setIsFoodGradeItem(1);
            case "id" -> input.setId(999L);
            case "owner" -> input.setIqcId(99L);
            case "unit" -> input.setUnit("fake");
            case "deleted" -> input.setDeleted(1);
        }
        assertThatThrownBy(() -> service.batchSave(1L, List.of(input))).hasMessageContaining("不允许修改服务器模板");
        verify(items, never()).update(isNull(), any());
    }

    @Test
    void duplicateCodesAndInvalidFlagsAreRejected() {
        QmsIqcItem second = new QmsIqcItem();
        BeanUtils.copyProperties(stored, second);
        second.setId(3L);
        second.setItemCode("BAG_THICKNESS");
        when(items.selectByIqcId(1L)).thenReturn(List.of(stored, second));
        assertThatThrownBy(() -> service.batchSave(1L, List.of(input(), input()))).hasMessageContaining("重复");
        when(items.selectByIqcId(1L)).thenReturn(List.of(stored));
        QmsIqcItem input = input();
        input.setPassFlag(2);
        assertThatThrownBy(() -> service.batchSave(1L, List.of(input))).hasMessageContaining("仅支持");
        input.setPassFlag(1);
        input.setResultValue("x".repeat(129));
        assertThatThrownBy(() -> service.batchSave(1L, List.of(input))).hasMessageContaining("128");
    }

    @Test
    void fullServerRowPayloadRemainsCompatibleAndUpdatesNoDefinitions() {
        QmsIqcItem input = new QmsIqcItem();
        BeanUtils.copyProperties(stored, input);
        input.setResultValue("符合，批号20");
        input.setPassFlag(1);
        service.batchSave(1L, List.of(input));
        verify(items).update(isNull(), any());
        verify(items, never()).deleteByIqcId(anyLong());
    }

    @Test
    void draftIqcCanStillReplaceItemsButNeverDefaultsUntestedToPass() {
        head.setInspectionType("IQC");
        QmsIqcItem input = input();
        input.setItemName("原料核验");
        service.batchSave(1L, List.of(input));
        verify(items).deleteByIqcId(1L);
        verify(items).insert(input);
        assertThat(input.getPassFlag()).isEqualTo(-1);
    }

    @Test
    void allPassedDoesNotTreatEmptyOrUntestedOrBlankActualValueAsPass() {
        assertThat(service.allPassed(1L)).isFalse();
        stored.setPassFlag(1);
        assertThat(service.allPassed(1L)).isFalse();
        stored.setResultValue("核验符合");
        assertThat(service.allPassed(1L)).isTrue();
        when(items.selectByIqcId(1L)).thenReturn(List.of());
        assertThat(service.allPassed(1L)).isFalse();
    }

    @Test
    void failedItemUpdateIsNotReportedAsSuccessfulSave() {
        when(items.update(isNull(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.batchSave(1L, List.of(input()))).isInstanceOf(BizException.class).hasMessageContaining("刷新");
    }

    private QmsIqcItem input() {
        QmsIqcItem input = new QmsIqcItem();
        input.setItemCode(stored.getItemCode());
        return input;
    }
}
