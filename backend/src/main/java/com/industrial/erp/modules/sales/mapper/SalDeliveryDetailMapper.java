package com.industrial.erp.modules.sales.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.sales.entity.SalDeliveryDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Mapper
public interface SalDeliveryDetailMapper extends BaseMapper<SalDeliveryDetail> {
    List<SalDeliveryDetail> selectByDeliveryId(@Param("deliveryId") Long deliveryId);

    /** 查询指定客户+商品的最后一次出库单价 */
    BigDecimal selectLastPriceByCustomerAndProduct(@Param("customerId") Long customerId, @Param("productId") Long productId);

    /**
     * 查询指定客户最近 50 条销售出库明细, 含冗余主表 billNo/billDate
     * 用于新增销售出库弹窗底部"该客户历史销售"列表, 单击行复制到明细.
     * v1.1.7+ 新增.
     */
    List<Map<String, Object>> selectCustomerHistoryProducts(@Param("customerId") Long customerId);
}
