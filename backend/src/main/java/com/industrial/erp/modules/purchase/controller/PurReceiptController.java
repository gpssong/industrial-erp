package com.industrial.erp.modules.purchase.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.industrial.erp.common.CreateByNameInjector;
import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.purchase.entity.PurReceipt;
import com.industrial.erp.modules.purchase.service.PurReceiptService;
import com.industrial.erp.modules.system.mapper.SysUserMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@Tag(name = "采购入库")
@RestController
@RequestMapping("/purchase/receipt")
public class PurReceiptController {

    private final PurReceiptService service;
    private final SysUserMapper userMapper;

    public PurReceiptController(PurReceiptService service, SysUserMapper userMapper) {
        this.service = service;
        this.userMapper = userMapper;
    }

    @SaCheckPermission(value = {"purchase:receipt:list"}, orRole = "admin")
    @GetMapping("/page")
    public R<PageResult<PurReceipt>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                         @RequestParam(defaultValue = "20") Integer pageSize,
                                         @RequestParam(required = false) String billNo,
                                         @RequestParam(required = false) Long supplierId,
                                         @RequestParam(required = false) String billStatus,
                                         @RequestParam(required = false) String productName) {
        PageResult<PurReceipt> pr = PageResult.of(service.page(pageNum, pageSize, billNo, supplierId, billStatus, productName));
        CreateByNameInjector.inject(userMapper, pr.getRecords(), PurReceipt::getCreateBy, PurReceipt::setCreateByName);
        return R.ok(pr);
    }

    @SaCheckPermission(value = {"purchase:receipt:list"}, orRole = "admin")
    @GetMapping("/{id}")
    public R<PurReceipt> detail(@PathVariable Long id) { return R.ok(service.detail(id)); }

    @SaCheckPermission(value = {"purchase:receipt:add"}, orRole = "admin")
    @PostMapping
    public R<Long> add(@RequestBody PurReceipt receipt) {
        service.add(receipt);
        // v1.1.95 XK: 返回新建入库单 id, 供三步流程「签署检验 → 自动入库」链接后续步骤
        return R.ok(receipt.getId());
    }

    @SaCheckPermission(value = {"purchase:receipt:edit"}, orRole = "admin")
    @PutMapping
    public R<Long> update(@RequestBody PurReceipt receipt) {
        service.update(receipt);
        return R.ok(receipt.getId());
    }

    @SaCheckPermission(value = {"purchase:receipt:delete"}, orRole = "admin")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.ok();
    }

    @SaCheckPermission(value = {"purchase:receipt:check"}, orRole = "admin")
    @PostMapping("/{id}/check")
    public R<Void> check(@PathVariable Long id) {
        service.check(id);
        return R.ok();
    }

    /** v1.1.55+ 反审核拆分独立 perm (sql/35 seed 新增 purchase:receipt:uncheck) */
    @SaCheckPermission(value = {"purchase:receipt:uncheck"}, orRole = "admin")
    @PostMapping("/{id}/uncheck")
    public R<Void> uncheck(@PathVariable Long id) {
        service.uncheck(id);
        return R.ok();
    }

    /** 查询指定供应商+商品的上次订单单价 */
    @SaCheckPermission(value = {"purchase:receipt:list"}, orRole = "admin")
    @GetMapping("/last-price")
    public R<BigDecimal> lastPrice(@RequestParam Long supplierId, @RequestParam Long productId) {
        return R.ok(service.getLastPrice(supplierId, productId));
    }

    /**
     * 查询指定供应商最近 50 条历史采购入库明细 (按入库日期 DESC).
     * 用于采购入库新增/编辑弹窗底部的"该供应商历史采购产品"下拉/列表.
     * v1.1.7+ 新增.
     */
    @SaCheckPermission(value = {"purchase:receipt:list"}, orRole = "admin")
    @GetMapping("/supplier-history-products")
    public R<java.util.List<java.util.Map<String, Object>>> supplierHistoryProducts(@RequestParam Long supplierId) {
        return R.ok(service.getSupplierHistoryProducts(supplierId));
    }

    /**
     * v1.1.101 XK — App 端 pages/scan/in "生成报告并自动入库" 调本端点.
     * <p>从已审核 PASS 的 IQC + 关联采购计划自动建 DRAFT 采购入库单 (DRAFT, 待 PC 端财务补录价格后审核).</p>
     */
    @Operation(summary = "XK 自动入库 — 从 IQC 检验单建 DRAFT 采购入库单")
    @SaCheckPermission(value = {"purchase:receipt:add"}, orRole = "admin")
    @PostMapping("/create-from-iqc")
    public R<java.util.Map<String, Object>> createFromIqc(@RequestBody java.util.Map<String, Long> body) {
        Long planId = body == null ? null : body.get("planId");
        Long iqcId = body == null ? null : body.get("iqcId");
        Long receiptId = service.createFromIqc(planId, iqcId);
        return R.ok(java.util.Map.of("receiptId", receiptId));
    }

    /**
     * v1.1.101 订单为源 — 从采购订单 + 已合格 IQC 自动建 DRAFT 入库单 (完全对齐 PC).
     */
    @PostMapping("/create-from-order")
    public R<java.util.Map<String, Object>> createFromOrder(@RequestBody java.util.Map<String, Long> body) {
        Long orderId = body == null ? null : body.get("orderId");
        Long iqcId = body == null ? null : body.get("iqcId");
        Long receiptId = service.createFromOrder(orderId, iqcId);
        return R.ok(java.util.Map.of("receiptId", receiptId));
    }
}
