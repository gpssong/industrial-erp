package com.industrial.erp.modules.qms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.qms.entity.MesQualityInspection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MesQualityInspectionMapper extends BaseMapper<MesQualityInspection> {
    @Select("SELECT * FROM mes_quality_inspection WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    MesQualityInspection selectByIdForUpdate(@Param("id") Long id);
}
