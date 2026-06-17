package com.industrial.erp.modules.inventory.service;

import cn.hutool.core.util.StrUtil;
import com.industrial.erp.common.Constants;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseWarehouse;
import com.industrial.erp.modules.base.mapper.BaseWarehouseMapper;
import com.industrial.erp.modules.inventory.entity.InvTransfer;
import com.industrial.erp.modules.inventory.entity.InvTransferDetail;
import com.industrial.erp.modules.inventory.mapper.InvTransferDetailMapper;
import com.industrial.erp.modules.inventory.mapper.InvTransferMapper;
import com.industrial.erp.utils.BillNoGenerator;
import com.industrial.erp.security.PermissionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 调拨: 同一公司, 不同仓库之间转移
 * 业务: outStock(出库) + inStock(入库)
 */
@Service
public class InvTransferService {

    public InvTransferService(InvTransferMapper transferMapper, InvTransferDetailMapper detailMapper, BaseWarehouseMapper warehouseMapper, BillNoGenerator billNoGenerator, StockService stockService, PermissionService permService) {
        this.transferMapper = transferMapper;
        this.detailMapper = detailMapper;
        this.warehouseMapper = warehouseMapper;
        this.billNoGenerator = billNoGenerator;
        this.stockService = stockService;
        this.permService = permService;
    }

    private final InvTransferMapper transferMapper;
    private final InvTransferDetailMapper detailMapper;
    private final BaseWarehouseMapper warehouseMapper;
    private final BillNoGenerator billNoGenerator;
    private final StockService stockService;
    private final PermissionService permService;

    @Transactional(rollbackFor = Exception.class)
    public void add(InvTransfer transfer) {
        permService.requirePerm("inventory:transfer:add");
        if (transfer.getBillDate() == null) transfer.setBillDate(LocalDate.now());
        if (StrUtil.isBlank(transfer.getBillNo())) transfer.setBillNo(billNoGenerator.generate(Constants.BILL_TR));
        if (StrUtil.isBlank(transfer.getBillStatus())) transfer.setBillStatus(Constants.STATUS_DRAFT);
        if (transfer.getOutWarehouseId().equals(transfer.getInWarehouseId())) {
            throw BizException.of("调入和调出仓库不能相同");
        }
        BigDecimal totalQty = BigDecimal.ZERO;
        for (InvTransferDetail d : transfer.getDetails()) {
            totalQty = totalQty.add(d.getQty() == null ? BigDecimal.ZERO : d.getQty());
        }
        transfer.setTotalQty(totalQty);
        transferMapper.insert(transfer);
        for (InvTransferDetail d : transfer.getDetails()) {
            d.setId(null);
            d.setTransferId(transfer.getId());
            detailMapper.insert(d);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void check(Long id) {
        permService.requirePerm("inventory:transfer:check");
        InvTransfer t = transferMapper.selectById(id);
        if (t == null) throw BizException.of("调拨单不存在");
        if (!Constants.STATUS_DRAFT.equals(t.getBillStatus())) throw BizException.of("状态不正确");
        List<InvTransferDetail> details = detailMapper.selectByTransferId(id);
        BaseWarehouse outWh = warehouseMapper.selectById(t.getOutWarehouseId());
        BaseWarehouse inWh = warehouseMapper.selectById(t.getInWarehouseId());
        for (InvTransferDetail d : details) {
            // 1. 出库
            stockService.outStock(Constants.LEDGER_TRANSFER, t.getId(), t.getBillNo(), d.getId(),
                    t.getOutWarehouseId(), outWh == null ? "" : outWh.getWarehouseName(),
                    d.getOutLocationId(), null,
                    d.getProductId(), d.getUnitId(), d.getUnitName(), d.getBatchNo(),
                    d.getQty(), null, t.getBillNo(),
                    null, null, "调拨出 " + t.getBillNo());
            // 2. 入库
            stockService.inStock(Constants.LEDGER_TRANSFER, t.getId(), t.getBillNo(), d.getId(),
                    t.getInWarehouseId(), inWh == null ? "" : inWh.getWarehouseName(),
                    d.getInLocationId(), null,
                    d.getProductId(), d.getUnitId(), d.getUnitName(), d.getBatchNo(),
                    d.getQty(), null, t.getBillNo(),
                    null, null, "调拨入 " + t.getBillNo());
        }
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<InvTransfer> w =
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<>();
        w.eq(InvTransfer::getId, id).set(InvTransfer::getBillStatus, Constants.STATUS_FINISHED);
        transferMapper.update(null, w);
    }
}
