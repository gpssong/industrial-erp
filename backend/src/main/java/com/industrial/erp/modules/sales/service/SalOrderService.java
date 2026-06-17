package com.industrial.erp.modules.sales.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.common.Constants;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseCustomer;
import com.industrial.erp.modules.base.mapper.BaseCustomerMapper;
import com.industrial.erp.modules.sales.entity.SalOrder;
import com.industrial.erp.modules.sales.entity.SalOrderDetail;
import com.industrial.erp.modules.sales.mapper.SalOrderDetailMapper;
import com.industrial.erp.modules.sales.mapper.SalOrderMapper;
import com.industrial.erp.utils.BillNoGenerator;
import com.industrial.erp.security.PermissionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

@Service
public class SalOrderService {

    public SalOrderService(SalOrderMapper orderMapper, SalOrderDetailMapper detailMapper, BaseCustomerMapper customerMapper, BillNoGenerator billNoGenerator, PermissionService permService) {
        this.orderMapper = orderMapper;
        this.detailMapper = detailMapper;
        this.customerMapper = customerMapper;
        this.billNoGenerator = billNoGenerator;
        this.permService = permService;
    }

    private final SalOrderMapper orderMapper;
    private final SalOrderDetailMapper detailMapper;
    private final BaseCustomerMapper customerMapper;
    private final BillNoGenerator billNoGenerator;
    private final PermissionService permService;

    public IPage<SalOrder> page(Integer pageNum, Integer pageSize, String billNo, Long customerId, String billStatus) {
        permService.requirePerm("sales:order:list");
        Page<SalOrder> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<SalOrder> w = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(billNo)) w.like(SalOrder::getBillNo, billNo);
        if (customerId != null) w.eq(SalOrder::getCustomerId, customerId);
        if (StrUtil.isNotBlank(billStatus)) w.eq(SalOrder::getBillStatus, billStatus);
        w.orderByDesc(SalOrder::getId);
        return orderMapper.selectPage(p, w);
    }

    public SalOrder detail(Long id) {
        SalOrder o = orderMapper.selectById(id);
        if (o != null) o.setDetails(detailMapper.selectByOrderId(id));
        return o;
    }

    @Transactional(rollbackFor = Exception.class)
    public void add(SalOrder order) {
        permService.requirePerm("sales:order:add");
        if (order.getBillDate() == null) order.setBillDate(LocalDate.now());
        if (StrUtil.isBlank(order.getBillNo())) order.setBillNo(billNoGenerator.generate(Constants.BILL_SO));
        if (StrUtil.isBlank(order.getBillStatus())) order.setBillStatus(Constants.STATUS_DRAFT);

        BaseCustomer c = customerMapper.selectById(order.getCustomerId());
        if (c == null) throw BizException.of("客户不存在");
        order.setCustomerName(c.getCustomerName());

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal taxAmount = BigDecimal.ZERO;
        BigDecimal totalAmountTax = BigDecimal.ZERO;
        int line = 0;
        for (SalOrderDetail d : order.getDetails()) {
            d.setLineNo(++line);
            if (d.getTaxRate() == null) d.setTaxRate(c.getTaxRate() == null ? new BigDecimal("13.00") : c.getTaxRate());
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
        order.setReceivedAmount(BigDecimal.ZERO);
        orderMapper.insert(order);
        for (SalOrderDetail d : order.getDetails()) {
            d.setId(null);
            d.setOrderId(order.getId());
            detailMapper.insert(d);
        }
    }

    public void delete(Long id) {
        permService.requirePerm("sales:order:delete");
        orderMapper.deleteById(id);
    }
}
