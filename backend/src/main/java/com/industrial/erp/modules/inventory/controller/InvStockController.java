package com.industrial.erp.modules.inventory.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import cn.hutool.core.util.StrUtil;
import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.inventory.entity.InvLedger;
import com.industrial.erp.modules.inventory.entity.InvStock;
import com.industrial.erp.modules.inventory.mapper.InvLedgerMapper;
import com.industrial.erp.modules.inventory.mapper.InvLedgerQueryMapper;
import com.industrial.erp.modules.inventory.mapper.InvStockMapper;
import com.industrial.erp.modules.inventory.mapper.InvStockPageQueryMapper;
import com.industrial.erp.modules.system.entity.SysUser;
import com.industrial.erp.modules.system.mapper.SysUserMapper;
import com.industrial.erp.security.PermissionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Tag(name = "库存查询/台账/预警")
@RestController
@RequestMapping("/inventory")
public class InvStockController {

    private final InvStockMapper stockMapper;
    private final InvLedgerMapper ledgerMapper;
    private final InvLedgerQueryMapper ledgerQueryMapper;
    private final InvStockPageQueryMapper stockPageQueryMapper;
    private final SysUserMapper userMapper;
    private final PermissionService permService;

    public InvStockController(InvStockMapper stockMapper, InvLedgerMapper ledgerMapper, InvLedgerQueryMapper ledgerQueryMapper, InvStockPageQueryMapper stockPageQueryMapper, SysUserMapper userMapper, PermissionService permService) {
        this.stockMapper = stockMapper;
        this.ledgerMapper = ledgerMapper;
        this.ledgerQueryMapper = ledgerQueryMapper;
        this.stockPageQueryMapper = stockPageQueryMapper;
        this.userMapper = userMapper;
        this.permService = permService;
    }

    @SaCheckPermission(value = {"inventory:stock:list"}, orRole = "admin")
    @GetMapping("/stock/page")
    public R<PageResult<Map<String, Object>>> stockPage(@RequestParam(defaultValue = "1") Integer pageNum,
                                                        @RequestParam(defaultValue = "20") Integer pageSize,
                                                        @RequestParam(required = false) String keyword,
                                                        @RequestParam(required = false) Long warehouseId) {
        permService.requirePerm("inventory:stock:list");
        // v1.1.62: 原实现用 LambdaQueryWrapper<InvStock> 查 inv_stock 表 + qty > 0 严格过滤,
        //   库存为 0 且无 inv_stock 行的产品查不到 (用户截图: "塑料袋30*38*0.16" Total 0 案例).
        //   改为 InvStockPageQueryMapper — 以 base_product 为驱动 LEFT JOIN inv_stock 聚合,
        //   库存为 0 时产品仍命中, qty/availableQty/lockQty/avgCost/totalCost 列全 0/空.
        //   返回 Map<String,Object> 以兼容 PC Stock.vue 表格 + App inventory/query.vue 卡片.
        Page<Map<String, Object>> p = new Page<>(pageNum, pageSize);
        String kw = StrUtil.isNotBlank(keyword) ? keyword.trim() : null;
        return R.ok(PageResult.of(stockPageQueryMapper.selectStockPage(p, kw, warehouseId)));
    }

    @SaCheckPermission(value = {"inventory:ledger:list"}, orRole = "admin")
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
        // v1.1.20+: 输入长度校验, 防恶意构造大 LIKE 串拖慢查询
        if (billNo != null && billNo.length() > 32) {
            throw BizException.of("单号查询条件最长 32 字符");
        }
        if (productName != null && productName.length() > 64) {
            throw BizException.of("商品名称查询条件最长 64 字符");
        }
        Page<Map<String, Object>> p = new Page<>(pageNum, pageSize);
        QueryWrapper<InvLedger> w = new QueryWrapper<>();
        if (StrUtil.isNotBlank(billType)) w.eq("bill_type", billType);
        if (StrUtil.isNotBlank(billNo)) w.like("bill_no", billNo);
        if (StrUtil.isNotBlank(productName)) w.like("product_name", productName);
        if (productId != null) w.eq("product_id", productId);
        if (StrUtil.isNotBlank(startDate)) w.ge("biz_date", startDate);
        if (StrUtil.isNotBlank(endDate)) w.le("biz_date", endDate);
        w.eq("deleted", 0);
        // v1.1.20+: 多租户过滤 (P0-4). inv_ledger 表已预留 tenant_id 字段, 当前所有租户均为 1.
        w.eq("tenant_id", com.industrial.erp.security.SecurityContext.getTenantId());
        w.orderByDesc("id");
        Page<Map<String, Object>> result = ledgerMapper.selectMapsPage(p, w);
        // 注入操作员姓名 (前端表格新增「操作员」列)。selectMapsPage 返回的 Map 字段是 create_by (snake_case).
        List<Map<String, Object>> records = result.getRecords();
        if (records != null && !records.isEmpty()) {
            Set<Long> userIds = records.stream()
                    .map(m -> m.get("create_by"))
                    .filter(o -> o instanceof Number)
                    .map(o -> ((Number) o).longValue())
                    .collect(Collectors.toSet());
            if (!userIds.isEmpty()) {
                Map<Long, String> nameMap = new HashMap<>();
                for (SysUser u : userMapper.selectBatchIds(userIds)) {
                    if (u != null && u.getId() != null) {
                        nameMap.put(u.getId(), u.getRealName());
                    }
                }
                for (Map<String, Object> row : records) {
                    Object cb = row.get("create_by");
                    if (cb instanceof Number) {
                        row.put("create_by_name", nameMap.get(((Number) cb).longValue()));
                    }
                }
            }
        }
        return R.ok(PageResult.of(result));
    }

    @SaCheckPermission(value = {"inventory:warning:list"}, orRole = "admin")
    @GetMapping("/warning/list")
    public R<List<Map<String, Object>>> warnings() {
        permService.requirePerm("inventory:warning:list");
        return R.ok(ledgerQueryMapper.selectStockAll());
    }

    /**
     * 列某仓库+某商品下所有可用 (qty>0) 的库存批次 (供 销售/委外开单 时选 batchNo).
     * <p>v1.1.7+ 新增; 不走 stock/page (需 inventory:stock:list 权限), 让业务开单页能直接 dropdown.
     */
    @SaCheckPermission(value = {"inventory:stock:list"}, orRole = "admin")
    @GetMapping("/stock/batches")
    public R<List<InvStock>> listBatches(@RequestParam Long warehouseId, @RequestParam Long productId) {
        return R.ok(stockMapper.listByWarehouseAndProduct(warehouseId, productId));
    }
}