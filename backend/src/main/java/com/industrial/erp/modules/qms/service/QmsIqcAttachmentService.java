package com.industrial.erp.modules.qms.service;

import cn.hutool.core.util.StrUtil;
import com.industrial.erp.common.Constants;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.qms.entity.MesQualityInspection;
import com.industrial.erp.modules.qms.entity.QmsIqcAttachment;
import com.industrial.erp.modules.qms.mapper.MesQualityInspectionMapper;
import com.industrial.erp.modules.qms.mapper.QmsIqcAttachmentMapper;
import com.industrial.erp.security.SecurityContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * v1.1.101 XK — IQC 附件/照片 service.
 *
 * <p>App 端调 "上传 → bind" 两步: ① uni.uploadFile 拿 url → ② bind 把 url 登记到 qms_iqc_attachment.
 * 已审核 (CHECKED) 的 IQC 附件不可单独删除 (sql/117 trg_qms_iqc_attachment_protect 触发器保护).
 *
 * <p>注意: 本项目上传接口 {@code SysUploadController} 不落库 sys_upload, 直接写文件 + 返回 url.
 * 所以本 service 也不依赖 sys_upload, 直接接 url/fileName/fileSize 即可.
 */
@Service
public class QmsIqcAttachmentService {

    private final QmsIqcAttachmentMapper attachmentMapper;
    private final MesQualityInspectionMapper inspectionMapper;

    public QmsIqcAttachmentService(QmsIqcAttachmentMapper attachmentMapper,
                                   MesQualityInspectionMapper inspectionMapper) {
        this.attachmentMapper = attachmentMapper;
        this.inspectionMapper = inspectionMapper;
    }

    @Transactional(readOnly = true)
    public List<QmsIqcAttachment> listByIqcId(Long iqcId) {
        if (iqcId == null) return java.util.Collections.emptyList();
        return attachmentMapper.selectByIqcId(iqcId);
    }

    @Transactional(readOnly = true)
    public List<QmsIqcAttachment> listByIqcIdAndType(Long iqcId, String attachType) {
        if (iqcId == null || StrUtil.isBlank(attachType)) return java.util.Collections.emptyList();
        return attachmentMapper.selectByIqcIdAndType(iqcId, attachType);
    }

    /**
     * App 端两步走第二步: 上传完拿到 url 后, 调此方法把上传文件关联到 IQC.
     *
     * @param iqcId       IQC 检验单 id
     * @param fileUrl     上传接口返回的 url (如 /upload/202610/18/xxx.png)
     * @param fileName    原始文件名
     * @param fileSizeKb  文件大小 KB
     * @param attachType  REPORT/CONTRACT/INVOICE/LABEL_PHOTO/PACK_PHOTO/SAMPLE_PHOTO/OTHER
     * @return 关联后的 attachment id
     */
    @Transactional(rollbackFor = Exception.class)
    public Long bind(Long iqcId, String fileUrl, String fileName, Integer fileSizeKb, String attachType) {
        if (iqcId == null) throw BizException.of("iqcId 必填");
        if (StrUtil.isBlank(fileUrl)) throw BizException.of("fileUrl 必填 (先调上传接口拿 url)");
        if (StrUtil.isBlank(attachType)) attachType = "OTHER";

        QmsIqcAttachment a = new QmsIqcAttachment();
        a.setIqcId(iqcId);
        a.setUploadId(null);  // 本项目上传接口不落 sys_upload; sql/144 起该列可空 (原 NOT NULL 无默认值 → 绑定照片一直 500)
        a.setAttachType(attachType);
        a.setAttachName(StrUtil.isNotBlank(fileName) ? fileName : "unnamed");
        a.setFileUrl(fileUrl);
        a.setFileSizeKb(fileSizeKb);
        a.setVerified(0);
        attachmentMapper.insert(a);
        return a.getId();
    }

    /** 标记已核验 (XK 留痕, App 端 UI 触发). */
    @Transactional(rollbackFor = Exception.class)
    public void markVerified(Long id) {
        if (id == null) return;
        QmsIqcAttachment a = attachmentMapper.selectById(id);
        if (a == null) return;
        a.setVerified(1);
        a.setVerifiedBy(SecurityContext.getUserId());
        a.setVerifiedTime(LocalDateTime.now());
        attachmentMapper.updateById(a);
    }

    /**
     * v1.1.185 — 删一张现场照片/附件 (App 三个检验页共用).
     *
     * <p>软删 (deleted=1), 与 sql/117 的 trg_qms_iqc_attachment_protect 对齐:
     * 所属检验单已审核 (CHECKED) 的附件拒删 —— XK 审计链不允许事后抽掉凭证照片。
     * 物理文件保留在上传目录 (标签照片需归档 ≥2 年), 这里只摘掉单据上的挂接关系。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        if (id == null) throw BizException.of("附件 id 必填");
        QmsIqcAttachment a = attachmentMapper.selectById(id);
        if (a == null) throw BizException.of("附件不存在或已删除");
        MesQualityInspection insp = inspectionMapper.selectById(a.getIqcId());
        if (insp != null && Constants.STATUS_CHECKED.equals(insp.getStatus())) {
            throw BizException.of("XK 合规: 已审核的检验单附件不可删除, 请先反审核该检验单");
        }
        attachmentMapper.deleteById(id);
    }
}
