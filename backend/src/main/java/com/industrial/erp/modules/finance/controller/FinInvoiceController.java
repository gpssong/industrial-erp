package com.industrial.erp.modules.finance.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.finance.dto.FinInvoiceIssueDTO;
import com.industrial.erp.modules.finance.entity.FinArap;
import com.industrial.erp.modules.finance.entity.FinInvoice;
import com.industrial.erp.modules.finance.service.FinInvoiceService;
import com.industrial.erp.modules.finance.vo.FinInvoiceIssuedVO;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 发票管理 (v1.1.10+)
 */
@Tag(name = "发票管理")
@RestController
@RequestMapping("/finance/invoice")
public class FinInvoiceController {

    private final FinInvoiceService invoiceService;

    public FinInvoiceController(FinInvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    /**
     * 开票 (创建发票 + 关联 AR/AP 单)
     */
    @PostMapping
    public R<Long> issue(@RequestBody FinInvoiceIssueDTO dto) {
        return R.ok(invoiceService.issue(dto));
    }

    /**
     * 发票分页
     */
    @GetMapping("/page")
    public R<PageResult<FinInvoice>> page(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) String invoiceType,
            @RequestParam(required = false) String invoiceStatus,
            @RequestParam(required = false) String keyword) {
        IPage<FinInvoice> p = invoiceService.page(pageNum, pageSize, invoiceType, invoiceStatus, keyword);
        return R.ok(PageResult.of(p));
    }

    /**
     * 已开发票列表 (v1.1.19+)
     *
     * <p>⚠️ 必须在 /{id} 之前声明, 否则 Spring MVC 会把 "issued" 当 id 匹配, 导致 NumberFormatException
     */
    @GetMapping("/issued")
    public R<List<FinInvoiceIssuedVO>> listIssued(
            @RequestParam(required = false) String invoiceType,
            @RequestParam(required = false) String keyword) {
        return R.ok(invoiceService.listIssued(invoiceType, keyword));
    }

    /**
     * 发票详情 (含关联 AR/AP 单)
     */
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.ok(invoiceService.getDetail(id));
    }

    /**
     * 作废发票
     */
    @PutMapping("/{id}/void")
    public R<Void> voidInvoice(@PathVariable Long id) {
        invoiceService.voidInvoice(id);
        return R.ok();
    }

    /**
     * 按 AR/AP 单查关联的所有发票
     */
    @GetMapping("/by-arap/{arapId}")
    public R<List<FinInvoice>> getByArap(@PathVariable Long arapId) {
        return R.ok(invoiceService.getByArapId(arapId));
    }

    /**
     * 查客户/供应商未开票的 AR/AP 单 (开票选单)
     */
    @GetMapping("/uninvoiced")
    public R<List<FinArap>> uninvoiced(
            @RequestParam(required = false) String partnerType,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Long supplierId) {
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