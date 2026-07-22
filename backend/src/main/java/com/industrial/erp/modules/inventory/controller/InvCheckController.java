package com.industrial.erp.modules.inventory.controller;

import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.inventory.dto.AppCheckSubmitDTO;
import com.industrial.erp.modules.inventory.entity.InvCheck;
import com.industrial.erp.modules.inventory.service.InvCheckService;
import com.industrial.erp.modules.inventory.vo.AppCheckSubmitVO;
import com.industrial.erp.modules.inventory.vo.WarehouseStockSnapshotVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "库存盘点")
@RestController
@RequestMapping("/inventory/check")
public class InvCheckController {

    public InvCheckController(InvCheckService service) {
        this.service = service;
    }
    private final InvCheckService service;

    @Operation(summary = "分页查询盘点单")
    @GetMapping("/page")
    public R<PageResult<InvCheck>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                        @RequestParam(defaultValue = "20") Integer pageSize,
                                        @RequestParam(required = false) String billNo,
                                        @RequestParam(required = false) String billStatus,
                                        @RequestParam(required = false) Long warehouseId) {
        return R.ok(PageResult.of(service.page(pageNum, pageSize, billNo, billStatus, warehouseId)));
    }

    @Operation(summary = "盘点单详情")
    @GetMapping("/{id}")
    public R<InvCheck> detail(@PathVariable Long id) { return R.ok(service.detail(id)); }

    @Operation(summary = "新增盘点单 (PC 端)")
    @PostMapping
    public R<Void> add(@RequestBody InvCheck c) { service.add(c); return R.ok(); }

    @Operation(summary = "审核盘点单 (生成盈亏单)")
    @PostMapping("/{id}/check")
    public R<Void> check(@PathVariable Long id) { service.check(id); return R.ok(); }

    @Operation(summary = "删除盘点单")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) { service.delete(id); return R.ok(); }

    // ============ v1.0.8+ App 外勤盘点对接 ============

    @Operation(summary = "App 提交外勤盘点 (生成 DRAFT 盘点单)")
    @PostMapping("/submit-from-app")
    public R<AppCheckSubmitVO> submitFromApp(@Valid @RequestBody AppCheckSubmitDTO dto) {
        return R.ok(service.submitFromApp(dto));
    }

    @Operation(summary = "列出仓库商品账面快照 (App 预加载)")
    @GetMapping("/stock-snapshot/{warehouseId}")
    public R<List<WarehouseStockSnapshotVO>> stockSnapshot(@PathVariable Long warehouseId) {
        return R.ok(service.listStockSnapshot(warehouseId));
    }
}
