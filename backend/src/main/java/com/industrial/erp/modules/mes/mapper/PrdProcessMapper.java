package com.industrial.erp.modules.mes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.mes.entity.PrdProcess;
import org.apache.ibatis.annotations.Mapper;

/** 启用 sql/06 prd_process 死表. 报工进度查询 + 开单展开写入. */
@Mapper
public interface PrdProcessMapper extends BaseMapper<PrdProcess> {}
