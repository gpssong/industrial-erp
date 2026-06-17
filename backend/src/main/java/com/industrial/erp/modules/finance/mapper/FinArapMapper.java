package com.industrial.erp.modules.finance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.finance.entity.FinArap;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;

@Mapper
public interface FinArapMapper extends BaseMapper<FinArap> {
    int updatePaidAmount(@Param("id") Long id, @Param("amount") BigDecimal amount);
}
