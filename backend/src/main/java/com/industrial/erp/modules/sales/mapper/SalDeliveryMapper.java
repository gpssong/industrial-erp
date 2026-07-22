package com.industrial.erp.modules.sales.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.sales.entity.SalDelivery;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SalDeliveryMapper extends BaseMapper<SalDelivery> {

    /**
     * 分页查询 (带商品名称 + warehouse_name JOIN).
     * <p>productName 通过 EXISTS 子查询命中任一明细行 — 已移到 SQL 内部, 避免 QueryWrapper.apply() 字符串拼接反模式.
     */
    @Select("<script>" +
            "SELECT d.*, " +
            "(SELECT GROUP_CONCAT(p.product_name ORDER BY dtl.line_no SEPARATOR ', ') " +
            " FROM sal_delivery_detail dtl LEFT JOIN base_product p ON p.id = dtl.product_id " +
            " WHERE dtl.delivery_id = d.id LIMIT 1) AS firstProductName, " +
            "w.warehouse_name AS warehouseName " +
            "FROM sal_delivery d " +
            "LEFT JOIN base_warehouse w ON w.id = d.warehouse_id AND w.deleted = 0 " +
            "<where>" +
            "  d.deleted = 0 " +
            "  <if test=\"billNo != null and billNo != ''\">AND d.bill_no LIKE CONCAT('%', #{billNo}, '%')</if>" +
            "  <if test=\"customerId != null\">AND d.customer_id = #{customerId}</if>" +
            "  <if test=\"billStatus != null and billStatus != ''\">AND d.bill_status = #{billStatus}</if>" +
            "  <if test=\"productName != null and productName != ''\">" +
            "    AND EXISTS (SELECT 1 FROM sal_delivery_detail dt " +
            "                LEFT JOIN base_product p ON p.id = dt.product_id " +
            "                WHERE dt.delivery_id = d.id AND p.product_name LIKE CONCAT('%', #{productName}, '%'))" +
            "  </if>" +
            "</where>" +
            "ORDER BY d.id DESC" +
            "</script>")
    com.baomidou.mybatisplus.core.metadata.IPage<SalDelivery> selectPageWithProduct(
            com.baomidou.mybatisplus.core.metadata.IPage<SalDelivery> page,
            @Param("billNo") String billNo,
            @Param("customerId") Long customerId,
            @Param("billStatus") String billStatus,
            @Param("productName") String productName);

    @Select("SELECT * FROM sal_delivery WHERE id = #{id}")
    SalDelivery selectByIdRaw(@Param("id") Long id);

    @Select("SELECT * FROM sal_delivery_detail WHERE delivery_id = #{deliveryId} ORDER BY line_no")
    List<SalDelivery> selectDetailsByDeliveryId(@Param("deliveryId") Long deliveryId);
}
