package com.industrial.erp.modules.finance.service;

import com.industrial.erp.common.Constants;
import com.industrial.erp.modules.finance.entity.FinArap;
import com.industrial.erp.modules.finance.mapper.FinArapMapper;
import com.industrial.erp.modules.purchase.entity.PurReceipt;
import com.industrial.erp.modules.purchase.entity.PurReceiptDetail;
import com.industrial.erp.modules.sales.entity.SalDelivery;
import com.industrial.erp.modules.sales.entity.SalDeliveryDetail;
import org.springframework.stereotype.Service;

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

    /** 核销: 收/付款单 -> 应收/应付 */
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
