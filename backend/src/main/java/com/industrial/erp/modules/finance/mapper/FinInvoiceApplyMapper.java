package com.industrial.erp.modules.finance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.finance.entity.FinInvoiceApply;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface FinInvoiceApplyMapper extends BaseMapper<FinInvoiceApply> {
    /** 按 invoice_id 查所有关联明细 */
    List<FinInvoiceApply> selectByInvoiceId(@Param("invoiceId") Long invoiceId);
    /** 按 arap_id 查所有关联发票明细 */
    List<FinInvoiceApply> selectByArapId(@Param("arapId") Long arapId);
    /** 按 invoice_id 软删所有关联明细 (作废发票时) */
    void softDeleteByInvoiceId(@Param("invoiceId") Long invoiceId);
}