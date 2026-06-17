package com.industrial.erp.modules.finance.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import cn.hutool.core.util.StrUtil;
import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.finance.entity.FinArap;
import com.industrial.erp.modules.finance.entity.FinCashFlow;
import com.industrial.erp.modules.finance.mapper.FinArapMapper;
import com.industrial.erp.modules.finance.mapper.FinCashFlowMapper;
import com.industrial.erp.modules.finance.service.FinArapService;
import com.industrial.erp.security.PermissionService;
import com.industrial.erp.utils.BillNoGenerator;
import com.industrial.erp.common.Constants;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Tag(name = "财务应收应付")
@RestController
@RequestMapping("/finance/arap")
public class FinArapController {

    public FinArapController(FinArapMapper arapMapper, FinCashFlowMapper cashFlowMapper, FinArapService arapService, BillNoGenerator billNoGenerator, PermissionService permService) {
        this.arapMapper = arapMapper;
        this.cashFlowMapper = cashFlowMapper;
        this.arapService = arapService;
        this.billNoGenerator = billNoGenerator;
        this.permService = permService;
    }

    private final FinArapMapper arapMapper;
    private final FinCashFlowMapper cashFlowMapper;
    private final FinArapService arapService;
    private final BillNoGenerator billNoGenerator;
    private final PermissionService permService;

    @GetMapping("/page")
    public R<PageResult<FinArap>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                       @RequestParam(defaultValue = "20") Integer pageSize,
                                       @RequestParam(required = false) String billType,
                                       @RequestParam(required = false) String billStatus,
                                       @RequestParam(required = false) String keyword) {
        permService.requirePerm("finance:arap:list");
        Page<FinArap> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<FinArap> w = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(billType)) w.eq(FinArap::getBillType, billType);
        if (StrUtil.isNotBlank(billStatus)) w.eq(FinArap::getBillStatus, billStatus);
        if (StrUtil.isNotBlank(keyword)) {
            w.and(q -> q.like(FinArap::getCustomerName, keyword).or().like(FinArap::getSupplierName, keyword).or().like(FinArap::getSourceBillNo, keyword));
        }
        w.orderByDesc(FinArap::getId);
        return R.ok(PageResult.of(arapMapper.selectPage(p, w)));
    }

    /**
     * 收款 / 付款
     */
    @PostMapping("/cash")
    public R<Void> cash(@RequestBody FinCashFlow flow) {
        if ("RECEIPT".equals(flow.getBillType())) permService.requirePerm("finance:receipt:add");
        else permService.requirePerm("finance:payment:add");
        if (flow.getBillDate() == null) flow.setBillDate(LocalDate.now());
        if (StrUtil.isBlank(flow.getBillNo())) {
            flow.setBillNo(billNoGenerator.generate("RECEIPT".equals(flow.getBillType()) ? Constants.BILL_SK : Constants.BILL_FK));
        }
        if (StrUtil.isBlank(flow.getBillStatus())) flow.setBillStatus(Constants.STATUS_CHECKED);
        cashFlowMapper.insert(flow);
        // 核销
        if (flow.getSourceBillId() != null) {
            arapService.writeoff(flow.getSourceBillId(), flow.getAmount());
        }
        return R.ok();
    }
}
