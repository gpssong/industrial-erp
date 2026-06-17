package com.industrial.erp.modules.sales.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.sales.entity.SalDeliveryDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SalDeliveryDetailMapper extends BaseMapper<SalDeliveryDetail> {
    List<SalDeliveryDetail> selectByDeliveryId(@Param("deliveryId") Long deliveryId);
}
