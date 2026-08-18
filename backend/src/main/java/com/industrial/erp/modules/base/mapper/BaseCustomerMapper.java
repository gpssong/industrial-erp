package com.industrial.erp.modules.base.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.base.entity.BaseCustomer;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;

@Mapper
public interface BaseCustomerMapper extends BaseMapper<BaseCustomer> {
    int incrCreditUsed(@Param("id") Long id, @Param("delta") BigDecimal delta);
    /** v1.1.18+: 反审核销售出库时回退信用占用. GREATEST 防止负数. */
    int decrCreditUsed(@Param("id") Long id, @Param("delta") BigDecimal delta);
}
