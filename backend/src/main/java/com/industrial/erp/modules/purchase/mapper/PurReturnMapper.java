package com.industrial.erp.modules.purchase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.industrial.erp.modules.purchase.entity.PurReturn;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PurReturnMapper extends BaseMapper<PurReturn> {

    @Select("SELECT r.*, " +
            "(SELECT GROUP_CONCAT(p.product_name ORDER BY dtl.line_no SEPARATOR ', ') " +
            " FROM pur_return_detail dtl LEFT JOIN base_product p ON p.id = dtl.product_id " +
            " WHERE dtl.return_id = r.id LIMIT 1) AS firstProductName, " +
            "w.warehouse_name AS warehouseName " +
            "FROM pur_return r " +
            "LEFT JOIN base_warehouse w ON w.id = r.warehouse_id AND w.deleted = 0 " +
            "${ew.customSqlSegment}")
    IPage<PurReturn> selectPageWithProduct(IPage<PurReturn> page, @Param("ew") com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<PurReturn> ew);
}
