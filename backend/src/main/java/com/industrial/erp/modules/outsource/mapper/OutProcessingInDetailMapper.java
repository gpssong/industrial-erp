package com.industrial.erp.modules.outsource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.outsource.entity.OutProcessingInDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface OutProcessingInDetailMapper extends BaseMapper<OutProcessingInDetail> {
    List<OutProcessingInDetail> selectByPiId(@Param("id") Long id);
}
