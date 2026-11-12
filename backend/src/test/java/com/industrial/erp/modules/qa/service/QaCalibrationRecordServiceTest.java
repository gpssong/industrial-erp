package com.industrial.erp.modules.qa.service;

import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.qa.entity.QaCalibrationEquipment;
import com.industrial.erp.modules.qa.entity.QaCalibrationRecord;
import com.industrial.erp.modules.qa.mapper.QaCalibrationEquipmentMapper;
import com.industrial.erp.modules.qa.mapper.QaCalibrationRecordMapper;
import com.industrial.erp.modules.xk.service.XkAuditService;
import com.industrial.erp.security.PermissionService;
import com.industrial.erp.utils.BillNoGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校准记录状态机: PENDING 新建 -> 审核 PASSED/FAILED 联动设备;
 * 已审核不可重复审核 / 不可删除 (XK 合规历史).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("QaCalibrationRecordService 校准状态机")
class QaCalibrationRecordServiceTest {

    @Mock private QaCalibrationRecordMapper recordMapper;
    @Mock private QaCalibrationEquipmentMapper equipmentMapper;
    @Mock private BillNoGenerator billNoGenerator;
    @Mock private PermissionService permService;
    @Mock private XkAuditService xkAuditService;

    @InjectMocks private QaCalibrationRecordService service;

    private QaCalibrationEquipment equipment;

    @BeforeEach
    void setUp() {
        equipment = new QaCalibrationEquipment();
        equipment.setId(5L);
        equipment.setCode("CE202611010001");
        equipment.setName("数显测厚仪");
        equipment.setIntvMonths(12);
        when(equipmentMapper.selectById(5L)).thenReturn(equipment);
        when(billNoGenerator.generate("CR")).thenReturn("CR202611120001");
    }

    private QaCalibrationRecord newRecord(LocalDate calDate) {
        QaCalibrationRecord rec = new QaCalibrationRecord();
        rec.setEquipmentId(5L);
        rec.setCalDate(calDate);
        rec.setCalOrg("市计量所");
        rec.setCertNo("CERT-001");
        return rec;
    }

    private QaCalibrationRecord stored(String result) {
        QaCalibrationRecord rec = newRecord(LocalDate.of(2026, 11, 1));
        rec.setId(9L);
        rec.setRecordNo("CR202611010001");
        rec.setEquipmentCode("CE202611010001");
        rec.setEquipmentName("数显测厚仪");
        rec.setResult(result);
        return rec;
    }

    @Test
    @DisplayName("新建: 回填设备冗余字段 + 生成单号 + 默认 PENDING + 按周期推有效期")
    void save_derivesValidUntilFromCycle() {
        QaCalibrationRecord rec = newRecord(LocalDate.of(2026, 11, 1));

        service.save(rec);

        assertThat(rec.getRecordNo()).isEqualTo("CR202611120001");
        assertThat(rec.getEquipmentCode()).isEqualTo("CE202611010001");
        assertThat(rec.getEquipmentName()).isEqualTo("数显测厚仪");
        assertThat(rec.getResult()).isEqualTo("PENDING");
        assertThat(rec.getValidUntil()).isEqualTo(LocalDate.of(2027, 11, 1));
        verify(recordMapper).insert(rec);
        verify(xkAuditService).writeAudit(eq("QA_CAL_REC"), eq("CR202611120001"), eq("CREATE"),
                anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("新建: 前端已填有效期时不覆盖")
    void save_keepsExplicitValidUntil() {
        QaCalibrationRecord rec = newRecord(LocalDate.of(2026, 11, 1));
        rec.setValidUntil(LocalDate.of(2027, 6, 30));

        service.save(rec);

        assertThat(rec.getValidUntil()).isEqualTo(LocalDate.of(2027, 6, 30));
    }

    @Test
    @DisplayName("新建: 设备周期为空则不推有效期")
    void save_noCycleLeavesValidUntilNull() {
        equipment.setIntvMonths(null);
        QaCalibrationRecord rec = newRecord(LocalDate.of(2026, 11, 1));

        service.save(rec);

        assertThat(rec.getValidUntil()).isNull();
    }

    @Test
    @DisplayName("新建: 缺设备/缺日期/设备不存在都拒绝")
    void save_rejectsIncompleteInput() {
        QaCalibrationRecord noEq = newRecord(LocalDate.of(2026, 11, 1));
        noEq.setEquipmentId(null);
        assertThatThrownBy(() -> service.save(noEq)).isInstanceOf(BizException.class)
                .hasMessageContaining("请选择校准设备");

        QaCalibrationRecord noDate = newRecord(null);
        assertThatThrownBy(() -> service.save(noDate)).isInstanceOf(BizException.class)
                .hasMessageContaining("校准日期必填");

        when(equipmentMapper.selectById(5L)).thenReturn(null);
        assertThatThrownBy(() -> service.save(newRecord(LocalDate.of(2026, 11, 1))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("校准设备不存在");
        verify(recordMapper, never()).insert(any(QaCalibrationRecord.class));
    }

    @Test
    @DisplayName("更新: 单号/设备不许被前端改掉")
    void save_updateKeepsIdentityFields() {
        when(recordMapper.selectById(9L)).thenReturn(stored("PENDING"));
        QaCalibrationRecord input = newRecord(LocalDate.of(2026, 11, 2));
        input.setId(9L);
        input.setRecordNo("HACKED");
        input.setEquipmentId(77L);

        service.save(input);

        assertThat(input.getRecordNo()).isEqualTo("CR202611010001");
        assertThat(input.getEquipmentId()).isEqualTo(5L);
        assertThat(input.getEquipmentCode()).isEqualTo("CE202611010001");
        verify(recordMapper).updateById(input);
    }

    @Test
    @DisplayName("审核通过: 推设备的最近/下次校准日期与机构证书")
    void check_passedPushesEquipmentDates() {
        QaCalibrationRecord rec = stored("PENDING");
        when(recordMapper.selectById(9L)).thenReturn(rec);

        service.check(9L, "PASSED", null, "qa01");

        assertThat(rec.getResult()).isEqualTo("PASSED");
        assertThat(rec.getReviewer()).isEqualTo("qa01");
        assertThat(rec.getReviewTime()).isNotNull();
        assertThat(equipment.getStatus()).isEqualTo("VALID");
        assertThat(equipment.getLastCalDate()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(equipment.getNextCalDate()).isEqualTo(LocalDate.of(2027, 11, 1));
        assertThat(equipment.getLastOrg()).isEqualTo("市计量所");
        assertThat(equipment.getLastCertNo()).isEqualTo("CERT-001");
        verify(equipmentMapper).updateById(equipment);
        verify(xkAuditService).writeAudit(eq("QA_CAL_REC"), eq("CR202611010001"), eq("CHECK_PASSED"),
                anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("审核不通过: 设备立刻置 EXPIRED, 不推日期")
    void check_failedExpiresEquipment() {
        QaCalibrationRecord rec = stored("PENDING");
        when(recordMapper.selectById(9L)).thenReturn(rec);

        service.check(9L, "FAILED", "示值超差", "qa01");

        assertThat(equipment.getStatus()).isEqualTo("EXPIRED");
        assertThat(equipment.getLastCalDate()).isNull();
        assertThat(equipment.getNextCalDate()).isNull();
        assertThat(rec.getDeviation()).isEqualTo("示值超差");
    }

    @Test
    @DisplayName("审核: 重复审核与非法结果都拒绝")
    void check_rejectsRepeatAndIllegalResult() {
        when(recordMapper.selectById(9L)).thenReturn(stored("PASSED"));
        assertThatThrownBy(() -> service.check(9L, "PASSED", null, "qa01"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已审核");

        when(recordMapper.selectById(9L)).thenReturn(stored("PENDING"));
        assertThatThrownBy(() -> service.check(9L, "OK", null, "qa01"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("PASSED/FAILED");
        verify(recordMapper, never()).updateById(any(QaCalibrationRecord.class));
    }

    @Test
    @DisplayName("删除: 已审核记录保留合规历史, 拒绝删除")
    void delete_rejectsCheckedRecord() {
        when(recordMapper.selectById(9L)).thenReturn(stored("FAILED"));

        assertThatThrownBy(() -> service.delete(9L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不可删除");
        verify(recordMapper, never()).deleteById(any(Long.class));
    }

    @Test
    @DisplayName("删除: PENDING 记录可删并留痕")
    void delete_pendingRecord() {
        QaCalibrationRecord rec = stored("PENDING");
        when(recordMapper.selectById(9L)).thenReturn(rec);

        service.delete(9L);

        verify(recordMapper).deleteById(9L);
        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        verify(xkAuditService).writeAudit(eq("QA_CAL_REC"), eq("CR202611010001"), action.capture(),
                anyString(), any(), any(), any());
        assertThat(action.getValue()).isEqualTo("DELETE");
    }
}
