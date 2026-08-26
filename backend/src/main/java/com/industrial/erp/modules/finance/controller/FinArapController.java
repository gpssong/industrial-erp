package com.industrial.erp.modules.finance.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
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
import com.industrial.erp.modules.finance.service.FinInvoiceService;
import com.industrial.erp.security.PermissionService;
import com.industrial.erp.utils.BillNoGenerator;
import com.industrial.erp.common.Constants;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Tag(name = "财务应收应付")
@RestController
@RequestMapping("/finance/arap")
public class FinArapController {

    public FinArapController(FinArapMapper arapMapper, FinCashFlowMapper cashFlowMapper,
                             FinArapService arapService, FinInvoiceService invoiceService,
                             BillNoGenerator billNoGenerator, PermissionService permService) {
        this.arapMapper = arapMapper;
        this.cashFlowMapper = cashFlowMapper;
        this.arapService = arapService;
        this.invoiceService = invoiceService;
        this.billNoGenerator = billNoGenerator;
        this.permService = permService;
    }

    private final FinArapMapper arapMapper;
    private final FinCashFlowMapper cashFlowMapper;
    private final FinArapService arapService;
    private final FinInvoiceService invoiceService;
    private final BillNoGenerator billNoGenerator;
    private final PermissionService permService;

    @SaCheckPermission(value = {"finance:arap:list"}, orRole = "admin")
    @GetMapping("/page")
    public R<PageResult<FinArap>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                       @RequestParam(defaultValue = "20") Integer pageSize,
                                       @RequestParam(required = false) String billType,
                                       @RequestParam(required = false) String billStatus,
                                       @RequestParam(required = false) String invoiceStatus,
                                       @RequestParam(required = false) String invoiceStatuses,
                                       @RequestParam(required = false) String keyword) {
        permService.requirePerm("finance:arap:list");
        Page<FinArap> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<FinArap> w = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(billType)) w.eq(FinArap::getBillType, billType);
        if (StrUtil.isNotBlank(billStatus)) w.eq(FinArap::getBillStatus, billStatus);
        if (StrUtil.isNotBlank(invoiceStatuses)) {
            // 多状态查询 (逗号分隔): "FULL_INVOICED,PARTIAL_INVOICED"
            String[] statuses = invoiceStatuses.split(",");
            w.in(FinArap::getInvoiceStatus, (Object[]) statuses);
        } else if (StrUtil.isNotBlank(invoiceStatus)) {
            w.eq(FinArap::getInvoiceStatus, invoiceStatus);
        }
        if (StrUtil.isNotBlank(keyword)) {
            w.and(q -> q.like(FinArap::getCustomerName, keyword).or().like(FinArap::getSupplierName, keyword).or().like(FinArap::getSourceBillNo, keyword));
        }
        w.orderByDesc(FinArap::getId);
        return R.ok(PageResult.of(arapMapper.selectPage(p, w)));
    }

    /**
     * v1.1.20+ P1-1: 事务已下沉到 FinArapService (Controller 层不加 @Transactional)
     * 收款 / 付款
     *
     * <p>v1.1.10+ 支持按发票核销: 当 request.invoiceId 不为空时,
     * 调用 FinInvoiceService.writeoffByInvoice, 联动更新 AR/AP 单.
     *
     * <p>v1.1.24+ 权限注解: 收款 finance:receipt:add, 付款 finance:payment:add,
     * 两者有任一即可 (Sa-Token 数组默认 OR 关系).
     */
    @SaCheckPermission(value = {"finance:receipt:add", "finance:payment:add"}, orRole = "admin")
    @PostMapping("/cash")
    public R<Object> cash(@RequestBody FinCashFlow flow) {
        if ("RECEIPT".equals(flow.getBillType())) permService.requirePerm("finance:receipt:add");
        else permService.requirePerm("finance:payment:add");
        if (flow.getBillDate() == null) flow.setBillDate(LocalDate.now());
        if (StrUtil.isBlank(flow.getBillNo())) {
            flow.setBillNo(billNoGenerator.generate("RECEIPT".equals(flow.getBillType()) ? Constants.BILL_SK : Constants.BILL_FK));
        }
        if (StrUtil.isBlank(flow.getBillStatus())) flow.setBillStatus(Constants.STATUS_CHECKED);

        if (flow.getInvoiceId() != null) {
            // v1.1.10+: 按发票核销
            Map<String, Object> result = invoiceService.writeoffByInvoice(flow.getInvoiceId(), flow.getAmount());
            // 现金流水也写一份 (记录), invoice_id 已设
            cashFlowMapper.insert(flow);
            return R.ok(result);
        } else {
            // v1.1.20+ P1-1: 事务在 Service 层
            arapService.cash(flow.getSourceBillId(), flow.getAmount());
            cashFlowMapper.insert(flow);
            return R.ok();
        }
    }

    /**
     * v1.1.10+: 列出客户/供应商"未开票"的 AR/AP 单 (开票选单界面)
     */
    @SaCheckPermission(value = {"finance:invoice:uninvoiced"}, orRole = "admin")
    @GetMapping("/uninvoiced")
    public R<List<FinArap>> listUninvoiced(
            @RequestParam(required = false) String partnerType,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Long supplierId) {
        permService.requirePerm("finance:invoice:uninvoiced");
        // 兼容 partnerType/customerId/supplierId
        String pt = partnerType;
        Long pid;
        if ("CUSTOMER".equals(pt) || customerId != null) {
            pt = "CUSTOMER";
            pid = customerId;
        } else if ("SUPPLIER".equals(pt) || supplierId != null) {
            pt = "SUPPLIER";
            pid = supplierId;
        } else {
            throw new com.industrial.erp.exception.BizException("需指定 partnerType + customerId 或 supplierId");
        }
        return R.ok(invoiceService.listUninvoiced(pt, pid));
    }
}