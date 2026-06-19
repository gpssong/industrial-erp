package com.industrial.erp.modules.purchase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.purchase.entity.PurOrderDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface PurOrderDetailMapper extends BaseMapper<PurOrderDetail> {
    List<PurOrderDetail> selectByOrderId(@Param("orderId") Long orderId);

    /** 查询指定供应商+商品的最后一次订单单价 */
    BigDecimal selectLastPriceBySupplierAndProduct(@Param("supplierId") Long supplierId, @Param("productId") Long productId);
}
