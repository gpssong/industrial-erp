package com.industrial.erp.modules.purchase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.purchase.entity.PurReceipt;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PurReceiptMapper extends BaseMapper<PurReceipt> {

    /**
     * 分页查询 (带 productName EXISTS 子查询 + firstProductName 聚合).
     * <p>productName 已下沉到 SQL, 避免 QueryWrapper.apply() 字符串拼接反模式 (P1-3).
     */
    @Select("<script>" +
            "SELECT r.*, " +
            // v1.1.15+: 仿 SalDeliveryMapper.java:23, 注入仓库名供 App 列表展示
            "w.warehouse_name AS warehouseName, " +
            "(SELECT GROUP_CONCAT(p.product_name ORDER BY dtl.line_no SEPARATOR ', ') " +
            " FROM pur_receipt_detail dtl LEFT JOIN base_product p ON p.id = dtl.product_id " +
            " WHERE dtl.receipt_id = r.id LIMIT 1) AS firstProductName, " +
            // v1.1.21+: 注入首行商品的规格和型号
            "(SELECT p.spec FROM pur_receipt_detail dtl LEFT JOIN base_product p ON p.id = dtl.product_id " +
            " WHERE dtl.receipt_id = r.id ORDER BY dtl.line_no LIMIT 1) AS firstProductSpec, " +
            "(SELECT p.model FROM pur_receipt_detail dtl LEFT JOIN base_product p ON p.id = dtl.product_id " +
            " WHERE dtl.receipt_id = r.id ORDER BY dtl.line_no LIMIT 1) AS firstProductModel " +
            " FROM pur_receipt r " +
            // v1.1.15+: LEFT JOIN 仓库表注入 warehouseName
            "LEFT JOIN base_warehouse w ON w.id = r.warehouse_id AND w.deleted = 0 " +
            "<where>" +
            "  r.deleted = 0 " +
            "  <if test=\"billNo != null and billNo != ''\">AND r.bill_no LIKE CONCAT('%', #{billNo}, '%')</if>" +
            "  <if test=\"supplierId != null\">AND r.supplier_id = #{supplierId}</if>" +
            "  <if test=\"billStatus != null and billStatus != ''\">AND r.bill_status = #{billStatus}</if>" +
            "  <if test=\"productName != null and productName != ''\">" +
            "    AND EXISTS (SELECT 1 FROM pur_receipt_detail d " +
            "                LEFT JOIN base_product p ON p.id = d.product_id " +
            "                WHERE d.receipt_id = r.id AND p.product_name LIKE CONCAT('%', #{productName}, '%'))" +
            "  </if>" +
            "</where>" +
            "ORDER BY r.id DESC" +
            "</script>")
    com.baomidou.mybatisplus.core.metadata.IPage<PurReceipt> selectPageWithProduct(
            com.baomidou.mybatisplus.core.metadata.IPage<PurReceipt> page,
            @Param("billNo") String billNo,
            @Param("supplierId") Long supplierId,
            @Param("billStatus") String billStatus,
            @Param("productName") String productName);

    @Select("SELECT * FROM pur_receipt WHERE id = #{id}")
    PurReceipt selectByIdRaw(@Param("id") Long id);

    @Select("SELECT * FROM pur_receipt_detail WHERE receipt_id = #{receiptId} ORDER BY line_no")
    List<PurReceipt> selectDetailsByReceiptId(@Param("receiptId") Long receiptId);
}
