package com.industrial.erp.modules.purchase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.purchase.entity.PurReceiptDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Mapper
public interface PurReceiptDetailMapper extends BaseMapper<PurReceiptDetail> {
    List<PurReceiptDetail> selectByReceiptId(@Param("receiptId") Long receiptId);

    /** 查询指定供应商+商品的最后一次入库单价 */
    BigDecimal selectLastPriceBySupplierAndProduct(@Param("supplierId") Long supplierId, @Param("productId") Long productId);

    /**
     * 查询指定供应商最近 50 条采购入库明细, 含冗余主表 billNo/billDate.
     * 用于采购入库新增弹窗底部 "该供应商历史采购" 列表, 单击行复制到明细.
     * v1.1.7+ 新增.
     */
    List<Map<String, Object>> selectSupplierHistoryProducts(@Param("supplierId") Long supplierId);
}
