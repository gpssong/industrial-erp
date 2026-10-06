package com.industrial.erp.modules.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.workflow.entity.WfSafetyDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface WfSafetyDetailMapper extends BaseMapper<WfSafetyDetail> {
    List<WfSafetyDetail> selectByRecordId(@Param("recordId") Long recordId);
}
