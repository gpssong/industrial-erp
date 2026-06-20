package com.industrial.erp.modules.sales.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.sales.entity.SalReturnDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SalReturnDetailMapper extends BaseMapper<SalReturnDetail> {
    List<SalReturnDetail> selectByReturnId(@Param("returnId") Long returnId);
}
