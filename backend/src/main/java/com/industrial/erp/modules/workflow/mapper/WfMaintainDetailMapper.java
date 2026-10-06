package com.industrial.erp.modules.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.workflow.entity.WfMaintainDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface WfMaintainDetailMapper extends BaseMapper<WfMaintainDetail> {
    List<WfMaintainDetail> selectByRecordId(@Param("recordId") Long recordId);
}
