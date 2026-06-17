package com.industrial.erp.modules.production.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.production.entity.PrdRequisitionDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PrdRequisitionDetailMapper extends BaseMapper<PrdRequisitionDetail> {
    List<PrdRequisitionDetail> selectByRequisitionId(@Param("reqId") Long reqId);
}
