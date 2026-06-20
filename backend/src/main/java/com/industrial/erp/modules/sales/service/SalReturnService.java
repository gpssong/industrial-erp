package com.industrial.erp.modules.sales.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.common.Constants;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseCustomer;
import com.industrial.erp.modules.base.entity.BaseWarehouse;
import com.industrial.erp.modules.base.mapper.BaseCustomerMapper;
import com.industrial.erp.modules.base.mapper.BaseWarehouseMapper;
import com.industrial.erp.modules.finance.service.FinArapService;
import com.industrial.erp.modules.inventory.service.StockService;
import com.industrial.erp.modules.sales.entity.SalReturn;
import com.industrial.erp.modules.sales.entity.SalReturnDetail;
import com.industrial.erp.modules.sales.mapper.SalReturnDetailMapper;
import com.industrial.erp.modules.sales.mapper.SalReturnMapper;
import com.industrial.erp.security.PermissionService;
import com.industrial.erp.utils.BillNoGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * 销售退货服务
 * 业务: 新建退货单 -> 审核 -> 库存入库 (SAL_RETURN) -> 冲减 AR 应收
 */
@Service
public class SalReturnService {

    private static final Logger log = LoggerFactory.getLogger(SalReturnService.class);

    private final SalReturnMapper returnMapper;
    private final SalReturnDetailMapper returnDetailMapper;
    private final BaseCustomerMapper customerMapper;
    private final BaseWarehouseMapper warehouseMapper;
    private final BillNoGenerator billNoGenerator;
    private final StockService stockService;
    private final FinArapService arapService;
    private final PermissionService permService;

    public SalReturnService(SalReturnMapper returnMapper, SalReturnDetailMapper returnDetailMapper,
                            BaseCustomerMapper customerMapper, BaseWarehouseMapper warehouseMapper,
                            BillNoGenerator billNoGenerator, StockService stockService,
                            FinArapService arapService, PermissionService permService) {
        this.returnMapper = returnMapper;
        this.returnDetailMapper = returnDetailMapper;
        this.customerMapper = customerMapper;
        this.warehouseMapper = warehouseMapper;
        this.billNoGenerator = billNoGenerator;
        this.stockService = stockService;
        this.arapService = arapService;
        this.permService = permService;
    }

    public IPage<SalReturn> page(Integer pageNum, Integer pageSize, String billNo, Long customerId, String billStatus) {
        permService.requirePerm("sales:return:list");
        Page<SalReturn> p = new Page<>(pageNum, pageSize);
        QueryWrapper<SalReturn> w = new QueryWrapper<>();
        if (StrUtil.isNotBlank(billNo)) w.like("bill_no", billNo);
        if (customerId != null) w.eq("customer_id", customerId);
        if (StrUtil.isNotBlank(billStatus)) w.eq("bill_status", billStatus);
        w.orderByDesc("id");
        return returnMapper.selectPageWithProduct(p, w);
    }

    public SalReturn detail(Long id) {
        SalReturn r = returnMapper.selectById(id);
        if (r != null) r.setDetails(returnDetailMapper.selectByReturnId(id));
        return r;
    }

    @Transactional(rollbackFor = Exception.class)
    public void add(SalReturn ret) {
        permService.requirePerm("sales:return:add");
        if (ret.getBillDate() == null) ret.setBillDate(LocalDate.now());
        if (StrUtil.isBlank(ret.getBillNo())) {
            ret.setBillNo(billNoGenerator.generate(Constants.BILL_SRT));
        }
        if (StrUtil.isBlank(ret.getBillStatus())) ret.setBillStatus(Constants.STATUS_DRAFT);

        BaseCustomer c = customerMapper.selectById(ret.getCustomerId());
        if (c == null) throw BizException.of("客户不存在");
        ret.setCustomerName(c.getCustomerName());
        BaseWarehouse w = warehouseMapper.selectById(ret.getWarehouseId());
        if (w == null) throw BizException.of("仓库不存在");

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal taxAmount = BigDecimal.ZERO;
        BigDecimal totalAmountTax = BigDecimal.ZERO;
        int line = 0;
        for (SalReturnDetail d : ret.getDetails()) {
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
        ret.setTotalQty(totalQty);
        ret.setTotalAmount(totalAmount);
        ret.setTaxAmount(taxAmount);
        ret.setTotalAmountTax(totalAmountTax);

        returnMapper.insert(ret);
        for (SalReturnDetail d : ret.getDetails()) {
            d.setId(null);
            d.setReturnId(ret.getId());
            returnDetailMapper.insert(d);
        }
    }

    /**
     * 审核退货单 -> 库存入库 (SAL_RETURN ledger) + 冲减应收 (反向 AR)
     */
    @Transactional(rollbackFor = Exception.class)
    public void check(Long id) {
        permService.requirePerm("sales:return:check");
        SalReturn r = returnMapper.selectById(id);
        if (r == null) throw BizException.of("退货单不存在");
        if (!Constants.STATUS_DRAFT.equals(r.getBillStatus())) {
            throw BizException.of("只有草稿状态可审核");
        }
        List<SalReturnDetail> details = returnDetailMapper.selectByReturnId(id);
        BaseWarehouse wh = warehouseMapper.selectById(r.getWarehouseId());

        for (SalReturnDetail d : details) {
            // 库存入库 (销售退货 = 商品回到仓库)
            stockService.inStock(
                    Constants.LEDGER_SAL_RETURN, r.getId(), r.getBillNo(), d.getId(),
                    r.getWarehouseId(), wh.getWarehouseName(), null, null,
                    d.getProductId(), d.getUnitId(), d.getUnitName(), d.getBatchNo(),
                    d.getQty(), d.getPrice(), r.getBillNo(),
                    null, r.getCustomerId(), "销售退货 " + r.getBillNo()
            );
        }

        SalReturn upd = new SalReturn();
        upd.setId(id);
        upd.setBillStatus(Constants.STATUS_CHECKED);
        returnMapper.updateById(upd);

        // 写入反向 AR (冲减应收)
        arapService.reverseArForReturn(r);

        log.info("销售退货审核: billNo={}, amount={}", r.getBillNo(), r.getTotalAmountTax());
    }
}
