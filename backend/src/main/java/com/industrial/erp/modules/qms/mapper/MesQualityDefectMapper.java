package com.industrial.erp.modules.qms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.qms.entity.MesQualityDefect;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MesQualityDefectMapper extends BaseMapper<MesQualityDefect> {
    /** 查某检验单全部缺陷行 (按 lineNo 升序). XML: backend/src/main/resources/mapper/qms/MesQualityDefectMapper.xml */
    List<MesQualityDefect> selectByInspectionId(@Param("inspectionId") Long inspectionId);

    /** 软删某检验单下全部缺陷行 (deleted=1). 由 QmsInspectionService.delete 调. */
    void deleteByInspectionId(@Param("inspectionId") Long inspectionId);
}
