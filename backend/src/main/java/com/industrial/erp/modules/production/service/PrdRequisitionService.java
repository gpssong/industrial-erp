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
import com.industrial.erp.modules.production.entity.PrdRequisition;
import com.industrial.erp.modules.production.entity.PrdRequisitionDetail;
import com.industrial.erp.modules.production.mapper.PrdRequisitionDetailMapper;
import com.industrial.erp.modules.production.mapper.PrdRequisitionMapper;
import com.industrial.erp.modules.system.annotation.OperLog;
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

/**
 * 领料/补料/退料
 * 业务: 领料 -> 库存出库 (出库到车间仓)
 *      退料 -> 库存入库 (退回到原仓库)
 */
@Service
public class PrdRequisitionService {

    public PrdRequisitionService(PrdRequisitionMapper reqMapper, PrdRequisitionDetailMapper detailMapper, BaseWarehouseMapper warehouseMapper, BillNoGenerator billNoGenerator, StockService stockService, PermissionService permService, OperLogPublisher operLogPublisher) {
        this.reqMapper = reqMapper;
        this.detailMapper = detailMapper;
        this.warehouseMapper = warehouseMapper;
        this.billNoGenerator = billNoGenerator;
        this.stockService = stockService;
        this.permService = permService;
        this.operLogPublisher = operLogPublisher;
    }

    private final PrdRequisitionMapper reqMapper;
    private final PrdRequisitionDetailMapper detailMapper;
    private final BaseWarehouseMapper warehouseMapper;
    private final BillNoGenerator billNoGenerator;
    private final StockService stockService;
    private final PermissionService permService;
    private final OperLogPublisher operLogPublisher;

    public IPage<PrdRequisition> page(Integer pageNum, Integer pageSize, String billNo, String billType) {
        permService.requirePerm("production:requisition:list");
        Page<PrdRequisition> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<PrdRequisition> w = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(billNo)) w.like(PrdRequisition::getBillNo, billNo);
        if (StrUtil.isNotBlank(billType)) w.eq(PrdRequisition::getBillType, billType);
        w.orderByDesc(PrdRequisition::getId);
        return reqMapper.selectPage(p, w);
    }

    public PrdRequisition detail(Long id) {
        PrdRequisition r = reqMapper.selectById(id);
        if (r != null) r.setDetails(detailMapper.selectByRequisitionId(id));
        return r;
    }

    @Transactional(rollbackFor = Exception.class)
    @OperLog(module="生产领料", businessType="ADD", saveParam=true)
    public void add(PrdRequisition req) {
        permService.requirePerm("production:requisition:add");
        if (req.getBillDate() == null) req.setBillDate(LocalDate.now());
        if (StrUtil.isBlank(req.getBillNo())) req.setBillNo(billNoGenerator.generate(Constants.BILL_RQ));
        if (StrUtil.isBlank(req.getBillType())) req.setBillType("ISSUE");
        if (StrUtil.isBlank(req.getBillStatus())) req.setBillStatus(Constants.STATUS_DRAFT);

        reqMapper.insert(req);
        int line = 0;
        for (PrdRequisitionDetail d : req.getDetails()) {
            d.setLineNo(++line);
            d.setId(null);
            d.setRequisitionId(req.getId());
            if (d.getPrice() != null) {
                d.setAmount(d.getPrice().multiply(d.getQty()).setScale(4, RoundingMode.HALF_UP));
            }
            detailMapper.insert(d);
        }
    }

    /**
     * 审核: 触发库存
     * ISSUE -> 库存出库到车间
     * RETURN -> 库存入库回到仓库
     * REPLENISH -> 库存出库 (与ISSUE类似)
     */
    @Transactional(rollbackFor = Exception.class)
    public void check(Long id) {
        permService.requirePerm("production:requisition:check");
        PrdRequisition req = reqMapper.selectById(id);
        if (req == null) throw BizException.of("单据不存在");
        if (!Constants.STATUS_DRAFT.equals(req.getBillStatus())) throw BizException.of("状态不正确");
        List<PrdRequisitionDetail> details = detailMapper.selectByRequisitionId(id);
        BaseWarehouse wh = warehouseMapper.selectById(req.getWarehouseId());
        String whName = wh == null ? "" : wh.getWarehouseName();

        for (PrdRequisitionDetail d : details) {
            if ("ISSUE".equals(req.getBillType()) || "REPLENISH".equals(req.getBillType())) {
                stockService.outStock(Constants.LEDGER_PROD_OUT, req.getId(), req.getBillNo(), d.getId(),
                        req.getWarehouseId(), whName, d.getLocationId(), null,
                        d.getProductId(), d.getUnitId(), d.getUnitName(), d.getBatchNo(),
                        d.getQty(), d.getPrice(), req.getBillNo(),
                        null, null, "领料 " + req.getBillNo());
            } else if ("RETURN".equals(req.getBillType())) {
                stockService.inStock(Constants.LEDGER_PROD_OUT, req.getId(), req.getBillNo(), d.getId(),
                        req.getWarehouseId(), whName, d.getLocationId(), null,
                        d.getProductId(), d.getUnitId(), d.getUnitName(), d.getBatchNo(),
                        d.getQty(), d.getPrice(), req.getBillNo(),
                        null, null, "退料 " + req.getBillNo());
            }
        }
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<PrdRequisition> w =
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<>();
        w.eq(PrdRequisition::getId, id).set(PrdRequisition::getBillStatus, Constants.STATUS_CHECKED);
        reqMapper.update(null, w);
    }

    public void delete(Long id) {
        permService.requirePerm("production:requisition:delete");
        PrdRequisition req = reqMapper.selectById(id);
        if (req == null) throw BizException.of("单据不存在或已删除");
        List<PrdRequisitionDetail> details = detailMapper.selectByRequisitionId(id);
        // 软删除主
        reqMapper.update(null, new LambdaUpdateWrapper<PrdRequisition>()
                .eq(PrdRequisition::getId, id).set(PrdRequisition::getDeleted, 1));
        // 软删除子
        if (details != null && !details.isEmpty()) {
            detailMapper.update(null, new LambdaUpdateWrapper<PrdRequisitionDetail>()
                    .eq(PrdRequisitionDetail::getRequisitionId, id).set(PrdRequisitionDetail::getDeleted, 1));
        }
        operLogPublisher.publishDeleteSnapshot("生产领料单", String.valueOf(id), req, details);
    }
}
