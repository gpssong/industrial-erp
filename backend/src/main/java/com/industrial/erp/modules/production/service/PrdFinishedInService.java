package com.industrial.erp.modules.production.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.common.Constants;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseWarehouse;
import com.industrial.erp.modules.base.mapper.BaseWarehouseMapper;
import com.industrial.erp.modules.inventory.service.StockService;
import com.industrial.erp.modules.production.entity.PrdFinishedIn;
import com.industrial.erp.modules.production.mapper.PrdFinishedInMapper;
import com.industrial.erp.utils.BillNoGenerator;
import com.industrial.erp.modules.system.annotation.OperLog;
import com.industrial.erp.security.PermissionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
public class PrdFinishedInService {

    public PrdFinishedInService(PrdFinishedInMapper mapper, BaseWarehouseMapper warehouseMapper, BillNoGenerator billNoGenerator, StockService stockService, PermissionService permService) {
        this.mapper = mapper;
        this.warehouseMapper = warehouseMapper;
        this.billNoGenerator = billNoGenerator;
        this.stockService = stockService;
        this.permService = permService;
    }
    private final PrdFinishedInMapper mapper;
    private final BaseWarehouseMapper warehouseMapper;
    private final BillNoGenerator billNoGenerator;
    private final StockService stockService;
    private final PermissionService permService;

    public IPage<PrdFinishedIn> page(Integer pageNum, Integer pageSize, String billNo) {
        permService.requirePerm("production:finished-in:list");
        Page<PrdFinishedIn> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<PrdFinishedIn> w = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(billNo)) w.like(PrdFinishedIn::getBillNo, billNo);
        w.orderByDesc(PrdFinishedIn::getId);
        return mapper.selectPage(p, w);
    }

    @Transactional(rollbackFor = Exception.class)
    @OperLog(module="成品入库", businessType="ADD", saveParam=true)
    public void add(PrdFinishedIn in) {
        permService.requirePerm("production:finished-in:add");
        if (in.getBillDate() == null) in.setBillDate(LocalDate.now());
        if (StrUtil.isBlank(in.getBillNo())) in.setBillNo(billNoGenerator.generate(Constants.BILL_PFI));
        if (StrUtil.isBlank(in.getBillStatus())) in.setBillStatus(Constants.STATUS_DRAFT);
        if (in.getQty() == null) in.setQty(java.math.BigDecimal.ZERO);
        if (in.getPrice() == null) in.setPrice(java.math.BigDecimal.ZERO);
        in.setAmount(in.getPrice().multiply(in.getQty()));
        mapper.insert(in);
    }

    @Transactional(rollbackFor = Exception.class)
    public void check(Long id) {
        permService.requirePerm("production:finished-in:check");
        PrdFinishedIn in = mapper.selectById(id);
        if (in == null) throw BizException.of("入库单不存在");
        if (!Constants.STATUS_DRAFT.equals(in.getBillStatus())) throw BizException.of("状态不正确");
        BaseWarehouse wh = warehouseMapper.selectById(in.getWarehouseId());
        stockService.inStock(Constants.LEDGER_PROD_IN, in.getId(), in.getBillNo(), in.getId(),
                in.getWarehouseId(), wh == null ? "" : wh.getWarehouseName(), in.getLocationId(), null,
                in.getProductId(), in.getUnitId(), in.getUnitName(), in.getBatchNo(),
                in.getQty(), in.getPrice(), in.getBillNo(),
                null, null, "成品入库 " + in.getBillNo());
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<PrdFinishedIn> w =
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<>();
        w.eq(PrdFinishedIn::getId, id).set(PrdFinishedIn::getBillStatus, Constants.STATUS_CHECKED);
        mapper.update(null, w);
    }
}
