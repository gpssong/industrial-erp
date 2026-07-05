package com.industrial.erp.modules.sales.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.sales.entity.SalDelivery;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SalDeliveryMapper extends BaseMapper<SalDelivery> {
    @Select("SELECT d.*, " +
            "(SELECT GROUP_CONCAT(p.product_name ORDER BY dtl.line_no SEPARATOR ', ') " +
            " FROM sal_delivery_detail dtl LEFT JOIN base_product p ON p.id = dtl.product_id " +
            " WHERE dtl.delivery_id = d.id LIMIT 1) AS firstProductName, " +
            "w.warehouse_name AS warehouseName " +
            "FROM sal_delivery d " +
            "LEFT JOIN base_warehouse w ON w.id = d.warehouse_id AND w.deleted = 0 " +
            "${ew.customSqlSegment}")
    com.baomidou.mybatisplus.core.metadata.IPage<SalDelivery> selectPageWithProduct(
            com.baomidou.mybatisplus.core.metadata.IPage<SalDelivery> page,
            @Param("ew") com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SalDelivery> ew);

    @Select("SELECT * FROM sal_delivery WHERE id = #{id}")
    SalDelivery selectByIdRaw(@Param("id") Long id);

    @Select("SELECT * FROM sal_delivery_detail WHERE delivery_id = #{deliveryId} ORDER BY line_no")
    List<SalDelivery> selectDetailsByDeliveryId(@Param("deliveryId") Long deliveryId);
}
