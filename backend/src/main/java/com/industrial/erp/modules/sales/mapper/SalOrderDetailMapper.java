package com.industrial.erp.modules.sales.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.sales.entity.SalOrderDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Mapper
public interface SalOrderDetailMapper extends BaseMapper<SalOrderDetail> {
    List<SalOrderDetail> selectByOrderId(@Param("orderId") Long orderId);

    /** 查询指定客户+商品的最后一次订单单价 */
    BigDecimal selectLastPriceByCustomerAndProduct(@Param("customerId") Long customerId, @Param("productId") Long productId);

    /** v1.1.41: 累计该订单明细行已审核出库单的 qty 总和 (用于回写 out_qty) */
    @Select("SELECT COALESCE(SUM(d.qty), 0) FROM sal_delivery_detail d " +
            "JOIN sal_delivery s ON s.id = d.delivery_id " +
            "WHERE d.order_detail_id = #{orderDetailId} AND s.bill_status = 'CHECKED' AND d.deleted = 0 AND s.deleted = 0")
    BigDecimal selectShippedQtyByOrderDetailId(@Param("orderDetailId") Long orderDetailId);

    /** v1.1.41+hotfix: 批量按订单汇总已审核出库数量 (用于销售订单列表"已发/未发"列)
     *  实时 SUM, 不依赖 out_qty 回写列; SQL 在 SalOrderDetailMapper.xml 里 (foreach 必须在 XML) */
    List<Map<String, Object>> selectShippedQtyRawGroupByOrderId(@Param("orderIds") List<Long> orderIds);

    /** 包装方法: 把 List<Map> 转成 Map<Long, BigDecimal> */
    default Map<Long, BigDecimal> selectShippedQtyGroupByOrderId(List<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) return java.util.Collections.emptyMap();
        List<Map<String, Object>> raw = selectShippedQtyRawGroupByOrderId(orderIds);
        Map<Long, BigDecimal> result = new java.util.HashMap<>();
        for (Map<String, Object> row : raw) {
            Object oidObj = row.get("order_id");
            Object qtyObj = row.get("shipped_qty");
            if (oidObj == null) continue;
            Long oid = (oidObj instanceof Number) ? ((Number) oidObj).longValue() : Long.parseLong(oidObj.toString());
            BigDecimal qty = (qtyObj instanceof BigDecimal) ? (BigDecimal) qtyObj : new BigDecimal(qtyObj == null ? "0" : qtyObj.toString());
            result.put(oid, qty);
        }
        return result;
    }
}
