package com.industrial.erp.modules.base.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.base.entity.BaseCustomer;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;

@Mapper
public interface BaseCustomerMapper extends BaseMapper<BaseCustomer> {
    int incrCreditUsed(@Param("id") Long id, @Param("delta") BigDecimal delta);
}
