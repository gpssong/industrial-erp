package com.industrial.erp.modules.purchase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.purchase.entity.PurOrderDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PurOrderDetailMapper extends BaseMapper<PurOrderDetail> {
    List<PurOrderDetail> selectByOrderId(@Param("orderId") Long orderId);
}
