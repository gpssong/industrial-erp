package com.industrial.erp.modules.production.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.production.entity.PrdBomDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PrdBomDetailMapper extends BaseMapper<PrdBomDetail> {
    List<PrdBomDetail> selectByBomId(@Param("bomId") Long bomId);
}
