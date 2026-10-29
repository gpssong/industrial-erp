package com.industrial.erp.modules.qms.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.industrial.erp.modules.qms.dto.ProcessInspectionEnsureDTO;
import com.industrial.erp.modules.qms.dto.ProcessInspectionRecordDTO;
import com.industrial.erp.modules.qms.entity.MesQualityInspection;
import com.industrial.erp.modules.qms.entity.QmsIqcItem;
import com.industrial.erp.modules.qms.mapper.MesQualityInspectionMapper;
import com.industrial.erp.modules.qms.service.QmsInspectionService;
import com.industrial.erp.modules.qms.service.QmsIqcItemService;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class QmsProcessInspectionContractTest {
    @Test
    void processListAllowsQualityReadOrCheckWithoutProductionReportPermission() throws Exception {
        Method method = QmsInspectionController.class.getMethod("processes", Long.class);
        SaCheckPermission auth = method.getAnnotation(SaCheckPermission.class);
        assertThat(auth.value()).containsExactlyInAnyOrder("quality:inspection:list", "quality:inspection:check");
        assertThat(auth.mode()).isEqualTo(SaMode.OR);
        assertThat(method.getAnnotation(GetMapping.class).value()).containsExactly("/processes/{orderId}");
    }

    @Test
    void recordAndEnsureRequireCheckOrAdminAndItemWritesNeverAllowListOnly() throws Exception {
        Method record = QmsInspectionController.class.getMethod("processRecord", Long.class, ProcessInspectionRecordDTO.class);
        Method ensure = QmsInspectionController.class.getMethod("ensureForProcess", ProcessInspectionEnsureDTO.class);
        for (Method method : List.of(record, ensure)) {
            SaCheckPermission auth = method.getAnnotation(SaCheckPermission.class);
            assertThat(auth.value()).containsExactly("quality:inspection:check");
            assertThat(auth.orRole()).containsExactly("admin");
        }
        assertThat(record.getAnnotation(PutMapping.class).value()).containsExactly("/{id}/process-record");
        assertThat(ensure.getAnnotation(PostMapping.class).value()).containsExactly("/ensure-for-process");
        SaCheckPermission itemAuth = QmsIqcItemController.class.getMethod("batchSave", Long.class, List.class)
                .getAnnotation(SaCheckPermission.class);
        assertThat(itemAuth.value()).containsExactlyInAnyOrder("quality:inspection:check", "qms:iqc:detail");
        assertThat(itemAuth.mode()).isEqualTo(SaMode.OR);
    }

    @Test
    void recordJsonDeserializesItemsToEntitiesRatherThanMaps() throws Exception {
        ProcessInspectionRecordDTO dto = new ObjectMapper().readValue("""
                {"sampleQty":5,"qualifiedQty":4,"unqualifiedQty":1,"batchNo":"B10",
                 "photoUrls":"[]","result":"FAIL",
                 "items":[{"itemCode":"BAG_MATERIAL","resultValue":"批次不符","passFlag":0,"judgmentBasis":"工艺卡"}]}
                """, ProcessInspectionRecordDTO.class);
        assertThat(dto.getItems().get(0)).isInstanceOf(QmsIqcItem.class);
        assertThat(dto.getItems().get(0).getResultValue()).isEqualTo("批次不符");
        assertThat(dto.getResult()).isEqualTo("FAIL");
        assertThat(dto.getSampleQty()).isEqualByComparingTo("5");
    }

    @Test
    void writesUseTransactionsAndHeadLockIsAnActualForUpdateQuery() throws Exception {
        Method[] methods = {
                QmsInspectionService.class.getMethod("ensureForProcess", Long.class, Long.class, String.class),
                QmsInspectionService.class.getMethod("saveProcessRecord", Long.class, ProcessInspectionRecordDTO.class),
                QmsInspectionService.class.getMethod("check", Long.class, String.class, String.class, String.class),
                QmsIqcItemService.class.getMethod("batchSave", Long.class, List.class)
        };
        for (Method method : methods) {
            Transactional transaction = method.getAnnotation(Transactional.class);
            assertThat(transaction).isNotNull();
            assertThat(transaction.rollbackFor()).contains(Exception.class);
        }
        Method lock = MesQualityInspectionMapper.class.getMethod("selectByIdForUpdate", Long.class);
        assertThat(lock.getReturnType()).isEqualTo(MesQualityInspection.class);
        assertThat(lock.getAnnotation(Select.class).value()).singleElement()
                .asString().contains("FOR UPDATE", "deleted = 0", "#{id}");
    }
}
