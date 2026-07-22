package com.industrial.erp.modules.finance.service;

import com.industrial.erp.common.Constants;
import com.industrial.erp.modules.finance.entity.FinArap;
import com.industrial.erp.modules.finance.mapper.FinArapMapper;
import com.industrial.erp.modules.system.annotation.OperLog;
import com.industrial.erp.modules.purchase.entity.PurReceipt;
import com.industrial.erp.modules.purchase.entity.PurReceiptDetail;
import com.industrial.erp.modules.purchase.entity.PurReturn;
import com.industrial.erp.modules.purchase.entity.PurReturnDetail;
import com.industrial.erp.modules.sales.entity.SalDelivery;
import com.industrial.erp.modules.sales.entity.SalDeliveryDetail;
import com.industrial.erp.modules.sales.entity.SalReturn;
import com.industrial.erp.modules.sales.entity.SalReturnDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 应收应付台账服务
 * 业务: 采购入库 -> AP 应付,  销售出库 -> AR 应收,  收/付款 -> 核销
 */
@Service
public class FinArapService {

    public FinArapService(FinArapMapper arapMapper) {
        this.arapMapper = arapMapper;
    }

    private final FinArapMapper arapMapper;

    /** 采购入库时生成 AP 应付 */
    @Transactional(rollbackFor = Exception.class)
    public void createApForPurchase(PurReceipt receipt, List<PurReceiptDetail> details) {
        FinArap ap = new FinArap();
        ap.setBillType("AP");
        ap.setSourceBillType(Constants.LEDGER_PUR_RECEIPT);
        ap.setSourceBillId(receipt.getId());
        ap.setSourceBillNo(receipt.getBillNo());
        ap.setSupplierId(receipt.getSupplierId());
        ap.setSupplierName(receipt.getSupplierName());
        ap.setBizDate(receipt.getBillDate());
        ap.setAmount(receipt.getTotalAmountTax());
        ap.setPaidAmount(BigDecimal.ZERO);
        ap.setBalance(receipt.getTotalAmountTax());
        ap.setBillStatus(Constants.STATUS_UNPAID);
        ap.setRemark("采购入库自动生成");
        arapMapper.insert(ap);
    }

    /** 销售出库时生成 AR 应收 */
    @Transactional(rollbackFor = Exception.class)
    public void createArForSales(SalDelivery delivery, List<SalDeliveryDetail> details) {
        FinArap ar = new FinArap();
        ar.setBillType("AR");
        ar.setSourceBillType(Constants.LEDGER_SAL_DELIVERY);
        ar.setSourceBillId(delivery.getId());
        ar.setSourceBillNo(delivery.getBillNo());
        ar.setCustomerId(delivery.getCustomerId());
        ar.setCustomerName(delivery.getCustomerName());
        ar.setBizDate(delivery.getBillDate());
        ar.setAmount(delivery.getTotalAmountTax());
        ar.setPaidAmount(BigDecimal.ZERO);
        ar.setBalance(delivery.getTotalAmountTax());
        ar.setBillStatus(Constants.STATUS_UNPAID);
        ar.setRemark("销售出库自动生成");
        arapMapper.insert(ar);
    }

    /** 采购退货 -> 反向 AP (负数, 冲减对供应商的应付) */
    @Transactional(rollbackFor = Exception.class)
    public void reverseApForReturn(PurReturn ret) {
        FinArap ap = new FinArap();
        ap.setBillType("AP");
        ap.setSourceBillType(Constants.LEDGER_PUR_RETURN);
        ap.setSourceBillId(ret.getId());
        ap.setSourceBillNo(ret.getBillNo());
        ap.setSupplierId(ret.getSupplierId());
        ap.setSupplierName(ret.getSupplierName());
        ap.setBizDate(ret.getBillDate());
        // 退货金额取负 (冲减应付)
        ap.setAmount(ret.getTotalAmountTax() == null ? BigDecimal.ZERO : ret.getTotalAmountTax().negate());
        ap.setPaidAmount(BigDecimal.ZERO);
        ap.setBalance(ap.getAmount());
        ap.setBillStatus(ap.getAmount().compareTo(BigDecimal.ZERO) >= 0 ? Constants.STATUS_UNPAID : Constants.STATUS_PAID);
        ap.setRemark("采购退货自动冲账 " + ret.getBillNo());
        arapMapper.insert(ap);
    }

    /** 销售退货 -> 反向 AR (负数, 冲减对客户的应收) */
    @Transactional(rollbackFor = Exception.class)
    public void reverseArForReturn(SalReturn ret) {
        FinArap ar = new FinArap();
        ar.setBillType("AR");
        ar.setSourceBillType(Constants.LEDGER_SAL_RETURN);
        ar.setSourceBillId(ret.getId());
        ar.setSourceBillNo(ret.getBillNo());
        ar.setCustomerId(ret.getCustomerId());
        ar.setCustomerName(ret.getCustomerName());
        ar.setBizDate(ret.getBillDate());
        ar.setAmount(ret.getTotalAmountTax() == null ? BigDecimal.ZERO : ret.getTotalAmountTax().negate());
        ar.setPaidAmount(BigDecimal.ZERO);
        ar.setBalance(ar.getAmount());
        ar.setBillStatus(ar.getAmount().compareTo(BigDecimal.ZERO) >= 0 ? Constants.STATUS_UNPAID : Constants.STATUS_PAID);
        ar.setRemark("销售退货自动冲账 " + ret.getBillNo());
        arapMapper.insert(ar);
    }

    /** 核销: 收/付款单 -> 应收/应付 */
    @OperLog(module="应收应付", businessType="EDIT", saveParam=true)
    @Transactional(rollbackFor = Exception.class)
    public void writeoff(Long arapId, BigDecimal amount) {
        FinArap origin = arapMapper.selectById(arapId);
        if (origin == null) throw new com.industrial.erp.exception.BizException("应收/应付单不存在");
        BigDecimal newPaid = (origin.getPaidAmount() == null ? BigDecimal.ZERO : origin.getPaidAmount()).add(amount);
        BigDecimal newBalance = (origin.getAmount() == null ? BigDecimal.ZERO : origin.getAmount()).subtract(newPaid);
        FinArap upd = new FinArap();
        upd.setId(arapId);
        upd.setPaidAmount(newPaid);
        upd.setBalance(newBalance);
        upd.setBillStatus(newBalance.compareTo(BigDecimal.ZERO) <= 0
                ? Constants.STATUS_PAID
                : Constants.STATUS_PARTIAL);
        arapMapper.updateById(upd);
    }
}
