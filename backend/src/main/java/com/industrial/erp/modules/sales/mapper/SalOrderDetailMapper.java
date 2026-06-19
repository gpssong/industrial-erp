package com.industrial.erp.modules.sales.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.sales.entity.SalOrderDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface SalOrderDetailMapper extends BaseMapper<SalOrderDetail> {
    List<SalOrderDetail> selectByOrderId(@Param("orderId") Long orderId);

    /** 查询指定客户+商品的最后一次订单单价 */
    BigDecimal selectLastPriceByCustomerAndProduct(@Param("customerId") Long customerId, @Param("productId") Long productId);
}
