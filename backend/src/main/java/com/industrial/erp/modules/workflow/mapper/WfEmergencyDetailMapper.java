package com.industrial.erp.modules.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.workflow.entity.WfEmergencyDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface WfEmergencyDetailMapper extends BaseMapper<WfEmergencyDetail> {
    List<WfEmergencyDetail> selectByPlanId(@Param("planId") Long planId);
}
