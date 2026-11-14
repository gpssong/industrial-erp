package com.industrial.erp.modules.qms.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.qms.entity.MesQualityInspection;
import com.industrial.erp.modules.qms.entity.QmsIqcAttachment;
import com.industrial.erp.modules.qms.mapper.MesQualityInspectionMapper;
import com.industrial.erp.modules.qms.mapper.QmsIqcAttachmentMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v1.1.185 — App 删照片必须真删 (原来只做前端 splice, 服务端 qms_iqc_attachment 行留着,
 * 重新进页面 bootstrap 又从 attachments 读回来 → 删了又出现)。
 *
 * <p>删除语义沿用 sql/117 的触发器: 软删 (deleted=1), 已审核 (CHECKED) 的检验单附件拒删。
 */
class QmsIqcAttachmentServiceTest {

    private final QmsIqcAttachmentMapper attachments = mock(QmsIqcAttachmentMapper.class);
    private final MesQualityInspectionMapper inspections = mock(MesQualityInspectionMapper.class);
    private final QmsIqcAttachmentService service = new QmsIqcAttachmentService(attachments, inspections);

    private QmsIqcAttachment stored;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "qms-attach-test"),
                QmsIqcAttachment.class);
        stored = new QmsIqcAttachment();
        stored.setId(5L);
        stored.setIqcId(1L);
        stored.setAttachType("LABEL_PHOTO");
        stored.setFileUrl("/upload/202611/14/a.jpg");
        when(attachments.selectById(5L)).thenReturn(stored);
    }

    @Test
    void deletesAttachmentWhenInspectionNotApproved() {
        when(inspections.selectById(1L)).thenReturn(inspection("DRAFT"));

        service.delete(5L);

        verify(attachments).deleteById(5L);
    }

    @Test
    void rejectsDeleteWhenInspectionApproved() {
        when(inspections.selectById(1L)).thenReturn(inspection("CHECKED"));

        assertThatThrownBy(() -> service.delete(5L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已审核");

        verify(attachments, never()).deleteById(5L);
    }

    @Test
    void rejectsDeleteWhenAttachmentMissing() {
        when(attachments.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.delete(99L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("附件不存在");

        verify(attachments, never()).deleteById(99L);
    }

    private MesQualityInspection inspection(String status) {
        MesQualityInspection i = new MesQualityInspection();
        i.setId(1L);
        i.setStatus(status);
        return i;
    }
}
