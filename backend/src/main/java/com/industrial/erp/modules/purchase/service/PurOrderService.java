package com.industrial.erp.modules.purchase.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.common.Constants;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseSupplier;
import com.industrial.erp.modules.base.mapper.BaseSupplierMapper;
import com.industrial.erp.modules.purchase.entity.PurOrder;
import com.industrial.erp.modules.purchase.entity.PurOrderDetail;
import com.industrial.erp.modules.purchase.mapper.PurOrderDetailMapper;
import com.industrial.erp.modules.purchase.mapper.PurOrderMapper;
import com.industrial.erp.modules.purchase.mapper.PurReceiptDetailMapper;
import com.industrial.erp.modules.system.aspect.OperLogPublisher;
import com.industrial.erp.utils.BillNoGenerator;
import com.industrial.erp.security.PermissionService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

@Service
public class PurOrderService {

    public PurOrderService(PurOrderMapper orderMapper, PurOrderDetailMapper detailMapper, BaseSupplierMapper supplierMapper, BillNoGenerator billNoGenerator, PermissionService permService, PurReceiptDetailMapper receiptDetailMapper, OperLogPublisher operLogPublisher) {
        this.orderMapper = orderMapper;
        this.detailMapper = detailMapper;
        this.supplierMapper = supplierMapper;
        this.billNoGenerator = billNoGenerator;
        this.permService = permService;
        this.receiptDetailMapper = receiptDetailMapper;
        this.operLogPublisher = operLogPublisher;
    }
    private final PurOrderMapper orderMapper;
    private final PurOrderDetailMapper detailMapper;
    private final BaseSupplierMapper supplierMapper;
    private final PurReceiptDetailMapper receiptDetailMapper;
    private final BillNoGenerator billNoGenerator;
    private final PermissionService permService;
    private final OperLogPublisher operLogPublisher;

    public IPage<PurOrder> page(Integer pageNum, Integer pageSize, String billNo, Long supplierId, String billStatus) {
        permService.requirePerm("purchase:order:list");
        Page<PurOrder> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<PurOrder> w = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(billNo)) w.like(PurOrder::getBillNo, billNo);
        if (supplierId != null) w.eq(PurOrder::getSupplierId, supplierId);
        if (StrUtil.isNotBlank(billStatus)) w.eq(PurOrder::getBillStatus, billStatus);
        w.orderByDesc(PurOrder::getId);
        return orderMapper.selectPage(p, w);
    }

    public PurOrder detail(Long id) {
        PurOrder o = orderMapper.selectById(id);
        if (o != null) o.setDetails(detailMapper.selectByOrderId(id));
        return o;
    }

    public BigDecimal getLastPrice(Long supplierId, Long productId) {
        BigDecimal price = receiptDetailMapper.selectLastPriceBySupplierAndProduct(supplierId, productId);
        return price != null ? price : BigDecimal.ZERO;
    }

    @Transactional(rollbackFor = Exception.class)
    public void add(PurOrder order) {
        permService.requirePerm("purchase:order:add");
        if (order.getBillDate() == null) order.setBillDate(LocalDate.now());
        if (StrUtil.isBlank(order.getBillNo())) order.setBillNo(billNoGenerator.generate(Constants.BILL_PO));
        if (StrUtil.isBlank(order.getBillStatus())) order.setBillStatus(Constants.STATUS_DRAFT);
        BaseSupplier s = supplierMapper.selectById(order.getSupplierId());
        if (s == null) throw BizException.of("供应商不存在");
        order.setSupplierName(s.getSupplierName());

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal taxAmount = BigDecimal.ZERO;
        BigDecimal totalAmountTax = BigDecimal.ZERO;
        int line = 0;
        for (PurOrderDetail d : order.getDetails()) {
            d.setLineNo(++line);
            if (d.getTaxRate() == null) d.setTaxRate(s.getTaxRate() == null ? new BigDecimal("13.00") : s.getTaxRate());
            d.setAmount(d.getPrice().multiply(d.getQty()).setScale(4, RoundingMode.HALF_UP));
            d.setTaxAmount(d.getAmount().multiply(d.getTaxRate()).divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP));
            d.setAmountTax(d.getAmount().add(d.getTaxAmount()));
            totalQty = totalQty.add(d.getQty());
            totalAmount = totalAmount.add(d.getAmount());
            taxAmount = taxAmount.add(d.getTaxAmount());
            totalAmountTax = totalAmountTax.add(d.getAmountTax());
        }
        order.setTotalQty(totalQty);
        order.setTotalAmount(totalAmount);
        order.setTaxAmount(taxAmount);
        order.setTotalAmountTax(totalAmountTax);
        order.setPaidAmount(BigDecimal.ZERO);
        orderMapper.insert(order);
        for (PurOrderDetail d : order.getDetails()) {
            d.setId(null);
            d.setOrderId(order.getId());
            detailMapper.insert(d);
        }
    }

    public void delete(Long id) {
        permService.requirePerm("purchase:order:delete");
        PurOrder order = orderMapper.selectById(id);
        if (order == null) throw BizException.of("订单不存在或已删除");
        List<PurOrderDetail> details = detailMapper.selectByOrderId(id);
        // 软删除主
        orderMapper.update(null, new LambdaUpdateWrapper<PurOrder>()
                .eq(PurOrder::getId, id).set(PurOrder::getDeleted, 1));
        // 软删除子
        if (details != null && !details.isEmpty()) {
            detailMapper.update(null, new LambdaUpdateWrapper<PurOrderDetail>()
                    .eq(PurOrderDetail::getOrderId, id).set(PurOrderDetail::getDeleted, 1));
        }
        // 写操作日志
        operLogPublisher.publishDeleteSnapshot("采购订单", String.valueOf(id), order, details);
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(PurOrder order) {
        permService.requirePerm("purchase:order:edit");
        PurOrder origin = orderMapper.selectById(order.getId());
        if (origin == null) throw BizException.of("订单不存在");
        BaseSupplier s = supplierMapper.selectById(order.getSupplierId());
        if (s == null) throw BizException.of("供应商不存在");
        order.setSupplierName(s.getSupplierName());

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal taxAmount = BigDecimal.ZERO;
        BigDecimal totalAmountTax = BigDecimal.ZERO;
        int line = 0;
        for (PurOrderDetail d : order.getDetails()) {
            d.setLineNo(++line);
            if (d.getTaxRate() == null) d.setTaxRate(new BigDecimal("13.00"));
            d.setAmount(d.getPrice().multiply(d.getQty()).setScale(4, RoundingMode.HALF_UP));
            d.setTaxAmount(d.getAmount().multiply(d.getTaxRate()).divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP));
            d.setAmountTax(d.getAmount().add(d.getTaxAmount()));
            totalQty = totalQty.add(d.getQty());
            totalAmount = totalAmount.add(d.getAmount());
            taxAmount = taxAmount.add(d.getTaxAmount());
            totalAmountTax = totalAmountTax.add(d.getAmountTax());
        }
        order.setTotalQty(totalQty);
        order.setTotalAmount(totalAmount);
        order.setTaxAmount(taxAmount);
        order.setTotalAmountTax(totalAmountTax);
        orderMapper.updateById(order);
        // 删除原明细，重新插入
        detailMapper.delete(new LambdaQueryWrapper<PurOrderDetail>().eq(PurOrderDetail::getOrderId, order.getId()));
        for (PurOrderDetail d : order.getDetails()) {
            d.setId(null);
            d.setOrderId(order.getId());
            detailMapper.insert(d);
        }
    }
}
