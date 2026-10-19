package com.industrial.erp.modules.qms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.qms.entity.MesQualityInspection;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MesQualityInspectionMapper extends BaseMapper<MesQualityInspection> {
    /** 自定义方法放这里, 比如 selectWithDefects(id) 查详情. 详情走 service 拼装 (head + defectMapper.selectByInspectionId). */
}
