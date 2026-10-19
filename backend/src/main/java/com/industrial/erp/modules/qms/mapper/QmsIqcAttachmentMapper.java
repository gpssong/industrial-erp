package com.industrial.erp.modules.qms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.qms.entity.QmsIqcAttachment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * v1.1.101 XK — IQC 附件/照片 mapper.
 */
@Mapper
public interface QmsIqcAttachmentMapper extends BaseMapper<QmsIqcAttachment> {

    @Select("SELECT * FROM qms_iqc_attachment WHERE iqc_id = #{iqcId} AND deleted = 0 ORDER BY attach_type, id")
    List<QmsIqcAttachment> selectByIqcId(@Param("iqcId") Long iqcId);

    @Select("SELECT * FROM qms_iqc_attachment WHERE iqc_id = #{iqcId} AND attach_type = #{attachType} AND deleted = 0 ORDER BY id")
    List<QmsIqcAttachment> selectByIqcIdAndType(@Param("iqcId") Long iqcId,
                                                @Param("attachType") String attachType);

    @Select("SELECT * FROM qms_iqc_attachment WHERE upload_id = #{uploadId} AND deleted = 0 LIMIT 1")
    QmsIqcAttachment findByUploadId(@Param("uploadId") Long uploadId);
}
