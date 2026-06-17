package com.industrial.erp.modules.inventory.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.common.Constants;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseWarehouse;
import com.industrial.erp.modules.base.mapper.BaseWarehouseMapper;
import com.industrial.erp.modules.inventory.entity.InvCheck;
import com.industrial.erp.modules.inventory.entity.InvCheckDetail;
import com.industrial.erp.modules.inventory.mapper.InvCheckDetailMapper;
import com.industrial.erp.modules.inventory.mapper.InvCheckMapper;
import com.industrial.erp.utils.BillNoGenerator;
import com.industrial.erp.security.PermissionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * 库存盘点
 * 业务: 生成盘点单 -> 录入实盘 -> 审核时根据差异自动生成盈亏单(库存调整)
 */
@Service
public class InvCheckService {

    public InvCheckService(InvCheckMapper checkMapper, InvCheckDetailMapper checkDetailMapper, BaseWarehouseMapper warehouseMapper, BillNoGenerator billNoGenerator, StockService stockService, PermissionService permService) {
        this.checkMapper = checkMapper;
        this.checkDetailMapper = checkDetailMapper;
        this.warehouseMapper = warehouseMapper;
        this.billNoGenerator = billNoGenerator;
        this.stockService = stockService;
        this.permService = permService;
    }

    private final InvCheckMapper checkMapper;
    private final InvCheckDetailMapper checkDetailMapper;
    private final BaseWarehouseMapper warehouseMapper;
    private final BillNoGenerator billNoGenerator;
    private final StockService stockService;
    private final PermissionService permService;

    public IPage<InvCheck> page(Integer pageNum, Integer pageSize, String billNo) {
        permService.requirePerm("inventory:check:list");
        Page<InvCheck> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<InvCheck> w = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(billNo)) w.like(InvCheck::getBillNo, billNo);
        w.orderByDesc(InvCheck::getId);
        return checkMapper.selectPage(p, w);
    }

    public InvCheck detail(Long id) {
        InvCheck c = checkMapper.selectById(id);
        if (c != null) c.setDetails(checkDetailMapper.selectByCheckId(id));
        return c;
    }

    @Transactional(rollbackFor = Exception.class)
    public void add(InvCheck check) {
        permService.requirePerm("inventory:check:add");
        if (check.getBillDate() == null) check.setBillDate(LocalDate.now());
        if (StrUtil.isBlank(check.getBillNo())) check.setBillNo(billNoGenerator.generate(Constants.BILL_CK));
        if (StrUtil.isBlank(check.getBillStatus())) check.setBillStatus(Constants.STATUS_DRAFT);
        BaseWarehouse wh = warehouseMapper.selectById(check.getWarehouseId());
        if (wh != null) check.setWarehouseName(wh.getWarehouseName());

        // 自动计算差异
        BigDecimal totalDiffQty = BigDecimal.ZERO;
        BigDecimal totalDiffAmount = BigDecimal.ZERO;
        for (InvCheckDetail d : check.getDetails()) {
            BigDecimal book = d.getBookQty() == null ? BigDecimal.ZERO : d.getBookQty();
            BigDecimal actual = d.getActualQty() == null ? BigDecimal.ZERO : d.getActualQty();
            d.setDiffQty(actual.subtract(book));
            if (d.getPrice() != null) {
                d.setDiffAmount(d.getDiffQty().multiply(d.getPrice()).setScale(4, RoundingMode.HALF_UP));
            }
            if (d.getDiffQty().compareTo(BigDecimal.ZERO) > 0) d.setDiffType("PROFIT");
            else if (d.getDiffQty().compareTo(BigDecimal.ZERO) < 0) d.setDiffType("LOSS");
            else d.setDiffType("NORMAL");
            totalDiffQty = totalDiffQty.add(d.getDiffQty());
            if (d.getDiffAmount() != null) totalDiffAmount = totalDiffAmount.add(d.getDiffAmount());
        }
        check.setTotalDiffQty(totalDiffQty);
        check.setTotalDiffAmount(totalDiffAmount);
        checkMapper.insert(check);

        for (InvCheckDetail d : check.getDetails()) {
            d.setId(null);
            d.setCheckId(check.getId());
            checkDetailMapper.insert(d);
        }
    }

    /**
     * 审核: 根据差异自动调整库存
     * 盘盈 (diff > 0) -> inStock
     * 盘亏 (diff < 0) -> outStock
     */
    @Transactional(rollbackFor = Exception.class)
    public void check(Long id) {
        permService.requirePerm("inventory:check:check");
        InvCheck c = checkMapper.selectById(id);
        if (c == null) throw BizException.of("盘点单不存在");
        if (!Constants.STATUS_DRAFT.equals(c.getBillStatus())) throw BizException.of("状态不正确");
        List<InvCheckDetail> details = checkDetailMapper.selectByCheckId(id);
        BaseWarehouse wh = warehouseMapper.selectById(c.getWarehouseId());
        String whName = wh == null ? "" : wh.getWarehouseName();

        for (InvCheckDetail d : details) {
            if (d.getDiffQty() == null || d.getDiffQty().compareTo(BigDecimal.ZERO) == 0) continue;
            if (d.getDiffQty().compareTo(BigDecimal.ZERO) > 0) {
                // 盘盈 -> 入库
                stockService.inStock(Constants.LEDGER_CHECK, c.getId(), c.getBillNo(), d.getId(),
                        c.getWarehouseId(), whName, d.getLocationId(), null,
                        d.getProductId(), d.getUnitId(), d.getUnitName(), d.getBatchNo(),
                        d.getDiffQty(), d.getPrice(), c.getBillNo(),
                        null, null, "盘盈 " + c.getBillNo());
            } else {
                // 盘亏 -> 出库
                BigDecimal outQty = d.getDiffQty().abs();
                stockService.outStock(Constants.LEDGER_CHECK, c.getId(), c.getBillNo(), d.getId(),
                        c.getWarehouseId(), whName, d.getLocationId(), null,
                        d.getProductId(), d.getUnitId(), d.getUnitName(), d.getBatchNo(),
                        outQty, d.getPrice(), c.getBillNo(),
                        null, null, "盘亏 " + c.getBillNo());
            }
        }
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<InvCheck> w =
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<>();
        w.eq(InvCheck::getId, id).set(InvCheck::getBillStatus, Constants.STATUS_ADJUSTED);
        checkMapper.update(null, w);
    }
}
