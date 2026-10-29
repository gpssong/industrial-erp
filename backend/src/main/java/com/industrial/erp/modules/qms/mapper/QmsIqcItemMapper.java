package com.industrial.erp.modules.qms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.qms.entity.QmsIqcItem;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * v1.1.101 XK — IQC 检验项 mapper.
 */
@Mapper
public interface QmsIqcItemMapper extends BaseMapper<QmsIqcItem> {

    @Select("SELECT * FROM qms_iqc_item WHERE iqc_id = #{iqcId} AND deleted = 0 ORDER BY line_no ASC")
    List<QmsIqcItem> selectByIqcId(@Param("iqcId") Long iqcId);

    @Select("SELECT COUNT(*) FROM qms_iqc_item WHERE iqc_id = #{iqcId} AND deleted = 0 AND pass_flag = 0")
    int countFailByIqcId(@Param("iqcId") Long iqcId);

    @Delete("DELETE FROM qms_iqc_item WHERE iqc_id = #{iqcId} AND deleted = 0")
    int deleteByIqcId(@Param("iqcId") Long iqcId);
}
