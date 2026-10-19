package com.industrial.erp.modules.pdm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.pdm.entity.MesProcessParam;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MesProcessParamMapper extends BaseMapper<MesProcessParam> {

    /**
     * 某工序的工艺参数列表 (按 sort_no 升序).
     */
    List<MesProcessParam> selectByProcessDefId(@Param("processDefId") Long processDefId);
}
