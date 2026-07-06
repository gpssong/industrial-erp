package com.industrial.erp.modules.outsource.service;

import cn.hutool.core.util.StrUtil;
import com.industrial.erp.common.Constants;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseSupplier;
import com.industrial.erp.modules.base.entity.BaseWarehouse;
import com.industrial.erp.modules.base.mapper.BaseSupplierMapper;
import com.industrial.erp.modules.base.mapper.BaseWarehouseMapper;
import com.industrial.erp.modules.inventory.service.StockService;
import com.industrial.erp.modules.outsource.entity.OutIssue;
import com.industrial.erp.modules.outsource.entity.OutIssueDetail;
import com.industrial.erp.modules.outsource.entity.OutProcessingIn;
import com.industrial.erp.modules.outsource.entity.OutProcessingInDetail;
import com.industrial.erp.modules.outsource.mapper.OutIssueDetailMapper;
import com.industrial.erp.modules.outsource.mapper.OutIssueMapper;
import com.industrial.erp.modules.outsource.mapper.OutProcessingInDetailMapper;
import com.industrial.erp.modules.outsource.mapper.OutProcessingInMapper;
import com.industrial.erp.utils.BillNoGenerator;
import com.industrial.erp.modules.system.annotation.OperLog;
import com.industrial.erp.security.PermissionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

@Service
public class OutsourceService {

    public OutsourceService(OutIssueMapper issueMapper, OutIssueDetailMapper issueDetailMapper, OutProcessingInMapper piMapper, OutProcessingInDetailMapper piDetailMapper, BaseSupplierMapper supplierMapper, BaseWarehouseMapper warehouseMapper, BillNoGenerator billNoGenerator, StockService stockService, PermissionService permService) {
        this.issueMapper = issueMapper;
        this.issueDetailMapper = issueDetailMapper;
        this.piMapper = piMapper;
        this.piDetailMapper = piDetailMapper;
        this.supplierMapper = supplierMapper;
        this.warehouseMapper = warehouseMapper;
        this.billNoGenerator = billNoGenerator;
        this.stockService = stockService;
        this.permService = permService;
    }

    private final OutIssueMapper issueMapper;
    private final OutIssueDetailMapper issueDetailMapper;
    private final OutProcessingInMapper piMapper;
    private final OutProcessingInDetailMapper piDetailMapper;
    private final BaseSupplierMapper supplierMapper;
    private final BaseWarehouseMapper warehouseMapper;
    private final BillNoGenerator billNoGenerator;
    private final StockService stockService;
    private final PermissionService permService;

    // 委外发料
    @Transactional(rollbackFor = Exception.class)
    @OperLog(module="外协加工", businessType="ADD", saveParam=true)
    public void addIssue(OutIssue issue) {
        permService.requirePerm("outsource:issue:add");
        if (issue.getBillDate() == null) issue.setBillDate(LocalDate.now());
        if (StrUtil.isBlank(issue.getBillNo())) issue.setBillNo(billNoGenerator.generate(Constants.BILL_OI));
        if (StrUtil.isBlank(issue.getBillStatus())) issue.setBillStatus(Constants.STATUS_DRAFT);

        BaseSupplier s = supplierMapper.selectById(issue.getSupplierId());
        if (s == null) throw BizException.of("外协厂不存在");
        issue.setSupplierName(s.getSupplierName());

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        int line = 0;
        for (OutIssueDetail d : issue.getDetails()) {
            d.setLineNo(++line);
            d.setAmount(d.getPrice().multiply(d.getQty()).setScale(4, RoundingMode.HALF_UP));
            totalQty = totalQty.add(d.getQty());
            totalCost = totalCost.add(d.getAmount());
        }
        issue.setTotalQty(totalQty);
        issue.setTotalCost(totalCost);
        issueMapper.insert(issue);
        for (OutIssueDetail d : issue.getDetails()) {
            d.setId(null);
            d.setIssueId(issue.getId());
            issueDetailMapper.insert(d);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void checkIssue(Long id) {
        permService.requirePerm("outsource:issue:check");
        OutIssue o = issueMapper.selectById(id);
        if (o == null) throw BizException.of("发料单不存在");
        if (!Constants.STATUS_DRAFT.equals(o.getBillStatus())) throw BizException.of("状态不正确");
        List<OutIssueDetail> details = issueDetailMapper.selectByIssueId(id);
        BaseWarehouse wh = warehouseMapper.selectById(o.getWarehouseId());
        String whName = wh != null ? wh.getWarehouseName() : "";
        for (OutIssueDetail d : details) {
            stockService.outStock(Constants.LEDGER_PROD_OUT, o.getId(), o.getBillNo(), d.getId(),
                    o.getWarehouseId(), whName, null, null,
                    d.getProductId(), d.getUnitId(), d.getUnitName(), d.getBatchNo(),
                    d.getQty(), d.getPrice(), o.getBillNo(),
                    o.getSupplierId(), null, "委外发料 " + o.getBillNo());
        }
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<OutIssue> w = new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<>();
        w.eq(OutIssue::getId, id).set(OutIssue::getBillStatus, Constants.STATUS_CHECKED);
        issueMapper.update(null, w);
    }

    // 委外入库
    @Transactional(rollbackFor = Exception.class)
    @OperLog(module="外协加工", businessType="ADD", saveParam=true)
    public void addProcessingIn(OutProcessingIn in) {
        permService.requirePerm("outsource:pi:add");
        if (in.getBillDate() == null) in.setBillDate(LocalDate.now());
        if (StrUtil.isBlank(in.getBillNo())) in.setBillNo(billNoGenerator.generate(Constants.BILL_OPI));
        if (StrUtil.isBlank(in.getBillStatus())) in.setBillStatus(Constants.STATUS_DRAFT);

        BaseSupplier s = supplierMapper.selectById(in.getSupplierId());
        if (s == null) throw BizException.of("外协厂不存在");
        in.setSupplierName(s.getSupplierName());

        BigDecimal mat = BigDecimal.ZERO;
        BigDecimal proc = BigDecimal.ZERO;
        for (OutProcessingInDetail d : in.getDetails()) {
            d.setAmount(d.getPrice().multiply(d.getQty()).setScale(4, RoundingMode.HALF_UP));
            d.setProcessAmount(d.getProcessPrice().multiply(d.getQty()).setScale(4, RoundingMode.HALF_UP));
            mat = mat.add(d.getAmount());
            proc = proc.add(d.getProcessAmount());
        }
        in.setMaterialFee(mat);
        in.setProcessFee(proc);
        in.setTotalAmount(mat.add(proc));
        piMapper.insert(in);
        for (OutProcessingInDetail d : in.getDetails()) {
            d.setId(null);
            d.setPiId(in.getId());
            piDetailMapper.insert(d);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void checkProcessingIn(Long id) {
        permService.requirePerm("outsource:pi:check");
        OutProcessingIn in = piMapper.selectById(id);
        if (in == null) throw BizException.of("入库单不存在");
        if (!Constants.STATUS_DRAFT.equals(in.getBillStatus())) throw BizException.of("状态不正确");
        List<OutProcessingInDetail> details = piDetailMapper.selectByPiId(id);
        BaseWarehouse wh = warehouseMapper.selectById(in.getWarehouseId());
        String whName = wh != null ? wh.getWarehouseName() : "";
        for (OutProcessingInDetail d : details) {
            // 入库 (按材料价入库, 加工费单独结算)
            stockService.inStock(Constants.LEDGER_PROD_IN, in.getId(), in.getBillNo(), d.getId(),
                    in.getWarehouseId(), whName, null, null,
                    d.getProductId(), d.getUnitId(), d.getUnitName(), d.getBatchNo(),
                    d.getQty(), d.getPrice(), in.getBillNo(),
                    in.getSupplierId(), null, "委外入库 " + in.getBillNo());
        }
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<OutProcessingIn> w = new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<>();
        w.eq(OutProcessingIn::getId, id).set(OutProcessingIn::getBillStatus, Constants.STATUS_CHECKED);
        piMapper.update(null, w);
    }
}
