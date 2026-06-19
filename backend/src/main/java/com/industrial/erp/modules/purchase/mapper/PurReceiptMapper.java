package com.industrial.erp.modules.purchase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.purchase.entity.PurReceipt;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PurReceiptMapper extends BaseMapper<PurReceipt> {

    @Select("SELECT r.*, " +
            "(SELECT GROUP_CONCAT(p.product_name ORDER BY dtl.line_no SEPARATOR ', ') " +
            " FROM pur_receipt_detail dtl LEFT JOIN base_product p ON p.id = dtl.product_id " +
            " WHERE dtl.receipt_id = r.id LIMIT 1) AS firstProductName " +
            " FROM pur_receipt r ${ew.customSqlSegment}")
    com.baomidou.mybatisplus.core.metadata.IPage<PurReceipt> selectPageWithProduct(
            com.baomidou.mybatisplus.core.metadata.IPage<PurReceipt> page,
            @Param("ew") com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<PurReceipt> ew);

    @Select("SELECT * FROM pur_receipt WHERE id = #{id}")
    PurReceipt selectByIdRaw(@Param("id") Long id);

    @Select("SELECT * FROM pur_receipt_detail WHERE receipt_id = #{receiptId} ORDER BY line_no")
    List<PurReceipt> selectDetailsByReceiptId(@Param("receiptId") Long receiptId);
}
