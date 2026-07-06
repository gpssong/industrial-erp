package com.industrial.erp.modules.purchase.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.common.Constants;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseSupplier;
import com.industrial.erp.modules.base.entity.BaseWarehouse;
import com.industrial.erp.modules.base.mapper.BaseSupplierMapper;
import com.industrial.erp.modules.base.mapper.BaseWarehouseMapper;
import com.industrial.erp.modules.finance.service.FinArapService;
import com.industrial.erp.modules.inventory.service.StockService;
import com.industrial.erp.modules.purchase.entity.PurReturn;
import com.industrial.erp.modules.purchase.entity.PurReturnDetail;
import com.industrial.erp.modules.purchase.mapper.PurReturnDetailMapper;
import com.industrial.erp.modules.purchase.mapper.PurReturnMapper;
import com.industrial.erp.modules.system.annotation.OperLog;
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
 * 采购退货服务
 * 业务: 新建退货单 -> 审核 -> 库存出库 (PUR_RETURN) -> 冲减 AP 应付
 */
@Service
public class PurReturnService {

    private static final Logger log = LoggerFactory.getLogger(PurReturnService.class);

    private final PurReturnMapper returnMapper;
    private final PurReturnDetailMapper returnDetailMapper;
    private final BaseSupplierMapper supplierMapper;
    private final BaseWarehouseMapper warehouseMapper;
    private final BillNoGenerator billNoGenerator;
    private final StockService stockService;
    private final FinArapService arapService;
    private final PermissionService permService;

    public PurReturnService(PurReturnMapper returnMapper, PurReturnDetailMapper returnDetailMapper,
                            BaseSupplierMapper supplierMapper, BaseWarehouseMapper warehouseMapper,
                            BillNoGenerator billNoGenerator, StockService stockService,
                            FinArapService arapService, PermissionService permService) {
        this.returnMapper = returnMapper;
        this.returnDetailMapper = returnDetailMapper;
        this.supplierMapper = supplierMapper;
        this.warehouseMapper = warehouseMapper;
        this.billNoGenerator = billNoGenerator;
        this.stockService = stockService;
        this.arapService = arapService;
        this.permService = permService;
    }

    public IPage<PurReturn> page(Integer pageNum, Integer pageSize, String billNo, Long supplierId, String billStatus) {
        permService.requirePerm("purchase:return:list");
        Page<PurReturn> p = new Page<>(pageNum, pageSize);
        QueryWrapper<PurReturn> w = new QueryWrapper<>();
        if (StrUtil.isNotBlank(billNo)) w.like("bill_no", billNo);
        if (supplierId != null) w.eq("supplier_id", supplierId);
        if (StrUtil.isNotBlank(billStatus)) w.eq("bill_status", billStatus);
        w.orderByDesc("id");
        return returnMapper.selectPageWithProduct(p, w);
    }

    public PurReturn detail(Long id) {
        PurReturn r = returnMapper.selectById(id);
        if (r != null) r.setDetails(returnDetailMapper.selectByReturnId(id));
        return r;
    }

    @Transactional(rollbackFor = Exception.class)
    @OperLog(module="采购退货", businessType="ADD", saveParam=true)
    public void add(PurReturn ret) {
        permService.requirePerm("purchase:return:add");
        if (ret.getBillDate() == null) ret.setBillDate(LocalDate.now());
        if (StrUtil.isBlank(ret.getBillNo())) {
            ret.setBillNo(billNoGenerator.generate(Constants.BILL_RT));
        }
        if (StrUtil.isBlank(ret.getBillStatus())) ret.setBillStatus(Constants.STATUS_DRAFT);

        // 校验供应商 + 仓库
        BaseSupplier s = supplierMapper.selectById(ret.getSupplierId());
        if (s == null) throw BizException.of("供应商不存在");
        ret.setSupplierName(s.getSupplierName());
        BaseWarehouse w = warehouseMapper.selectById(ret.getWarehouseId());
        if (w == null) throw BizException.of("仓库不存在");

        // 汇总金额
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal taxAmount = BigDecimal.ZERO;
        BigDecimal totalAmountTax = BigDecimal.ZERO;
        int line = 0;
        for (PurReturnDetail d : ret.getDetails()) {
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
        ret.setTotalQty(totalQty);
        ret.setTotalAmount(totalAmount);
        ret.setTaxAmount(taxAmount);
        ret.setTotalAmountTax(totalAmountTax);

        returnMapper.insert(ret);
        for (PurReturnDetail d : ret.getDetails()) {
            d.setId(null);
            d.setReturnId(ret.getId());
            returnDetailMapper.insert(d);
        }
    }

    /**
     * 审核退货单 -> 库存出库 (PUR_RETURN ledger) + 冲减应付 (反向 AP)
     */
    @Transactional(rollbackFor = Exception.class)
    public void check(Long id) {
        permService.requirePerm("purchase:return:check");
        PurReturn r = returnMapper.selectById(id);
        if (r == null) throw BizException.of("退货单不存在");
        if (!Constants.STATUS_DRAFT.equals(r.getBillStatus())) {
            throw BizException.of("只有草稿状态可审核");
        }
        List<PurReturnDetail> details = returnDetailMapper.selectByReturnId(id);
        BaseWarehouse wh = warehouseMapper.selectById(r.getWarehouseId());

        for (PurReturnDetail d : details) {
            // 库存出库 (退货 = 商品离开仓库)
            stockService.outStock(
                    Constants.LEDGER_PUR_RETURN, r.getId(), r.getBillNo(), d.getId(),
                    r.getWarehouseId(), wh.getWarehouseName(), null, null,
                    d.getProductId(), d.getUnitId(), d.getUnitName(), d.getBatchNo(),
                    d.getQty(), d.getPrice(), r.getBillNo(),
                    r.getSupplierId(), null, "采购退货 " + r.getBillNo()
            );
        }

        // 更新状态
        PurReturn upd = new PurReturn();
        upd.setId(id);
        upd.setBillStatus(Constants.STATUS_CHECKED);
        returnMapper.updateById(upd);

        // 写入反向 AP (冲减应付)
        arapService.reverseApForReturn(r);

        log.info("采购退货审核: billNo={}, amount={}", r.getBillNo(), r.getTotalAmountTax());
    }
}
