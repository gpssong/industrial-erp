package com.industrial.erp.modules.purchase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.purchase.entity.PurReceiptDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface PurReceiptDetailMapper extends BaseMapper<PurReceiptDetail> {
    List<PurReceiptDetail> selectByReceiptId(@Param("receiptId") Long receiptId);

    /** 查询指定供应商+商品的最后一次入库单价 */
    BigDecimal selectLastPriceBySupplierAndProduct(@Param("supplierId") Long supplierId, @Param("productId") Long productId);
}
