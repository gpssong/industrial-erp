package com.industrial.erp.modules.sales.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.sales.entity.SalOrderDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SalOrderDetailMapper extends BaseMapper<SalOrderDetail> {
    List<SalOrderDetail> selectByOrderId(@Param("orderId") Long orderId);
}
