package com.industrial.erp.modules.inventory.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import cn.hutool.core.util.StrUtil;
import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.inventory.entity.InvLedger;
import com.industrial.erp.modules.inventory.entity.InvStock;
import com.industrial.erp.modules.inventory.mapper.InvLedgerMapper;
import com.industrial.erp.modules.inventory.mapper.InvLedgerQueryMapper;
import com.industrial.erp.modules.inventory.mapper.InvStockMapper;
import com.industrial.erp.security.PermissionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "库存查询/台账/预警")
@RestController
@RequestMapping("/inventory")
public class InvStockController {

    private final InvStockMapper stockMapper;
    private final InvLedgerMapper ledgerMapper;
    private final InvLedgerQueryMapper ledgerQueryMapper;
    private final PermissionService permService;

    public InvStockController(InvStockMapper stockMapper, InvLedgerMapper ledgerMapper, InvLedgerQueryMapper ledgerQueryMapper, PermissionService permService) {
        this.stockMapper = stockMapper;
        this.ledgerMapper = ledgerMapper;
        this.ledgerQueryMapper = ledgerQueryMapper;
        this.permService = permService;
    }

    @GetMapping("/stock/page")
    public R<PageResult<InvStock>> stockPage(@RequestParam(defaultValue = "1") Integer pageNum,
                                             @RequestParam(defaultValue = "20") Integer pageSize,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) Long warehouseId) {
        permService.requirePerm("inventory:stock:list");
        Page<InvStock> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<InvStock> w = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(keyword)) {
            w.and(q -> q.like(InvStock::getProductCode, keyword)
                    .or().like(InvStock::getProductName, keyword)
                    .or().like(InvStock::getSpec, keyword)
                    .or().like(InvStock::getBatchNo, keyword));
        }
        if (warehouseId != null) w.eq(InvStock::getWarehouseId, warehouseId);
        w.gt(InvStock::getQty, java.math.BigDecimal.ZERO);
        w.orderByDesc(InvStock::getId);
        return R.ok(PageResult.of(stockMapper.selectPage(p, w)));
    }

    @GetMapping("/ledger/page")
    public R<PageResult<Map<String, Object>>> ledgerPage(@RequestParam(defaultValue = "1") Integer pageNum,
                                                        @RequestParam(defaultValue = "20") Integer pageSize,
                                                        @RequestParam(required = false) String billType,
                                                        @RequestParam(required = false) String billNo,
                                                        @RequestParam(required = false) String productName,
                                                        @RequestParam(required = false) Long productId,
                                                        @RequestParam(required = false) String startDate,
                                                        @RequestParam(required = false) String endDate) {
        permService.requirePerm("inventory:ledger:list");
        Page<Map<String, Object>> p = new Page<>(pageNum, pageSize);
        QueryWrapper<InvLedger> w = new QueryWrapper<>();
        if (StrUtil.isNotBlank(billType)) w.eq("bill_type", billType);
        if (StrUtil.isNotBlank(billNo)) w.like("bill_no", billNo);
        if (StrUtil.isNotBlank(productName)) w.like("product_name", productName);
        if (productId != null) w.eq("product_id", productId);
        if (StrUtil.isNotBlank(startDate)) w.ge("biz_date", startDate);
        if (StrUtil.isNotBlank(endDate)) w.le("biz_date", endDate);
        w.eq("deleted", 0);
        w.orderByDesc("id");
        Page<Map<String, Object>> result = ledgerMapper.selectMapsPage(p, w);
        return R.ok(PageResult.of(result));
    }

    @GetMapping("/warning/list")
    public R<List<Map<String, Object>>> warnings() {
        permService.requirePerm("inventory:warning:list");
        return R.ok(ledgerQueryMapper.selectStockAll());
    }
}