package com.industrial.erp.modules.pdm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.pdm.entity.MesProcessSop;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MesProcessSopMapper extends BaseMapper<MesProcessSop> {

    /**
     * 某工序的 SOP 列表 (按 effective_date 倒序, 最新的在前).
     */
    List<MesProcessSop> selectByProcessDefId(@Param("processDefId") Long processDefId);

    /**
     * 某工序当前生效的 SOP (status=ACTIVE, 唯一).
     */
    MesProcessSop selectActiveByProcessDefId(@Param("processDefId") Long processDefId);
}
