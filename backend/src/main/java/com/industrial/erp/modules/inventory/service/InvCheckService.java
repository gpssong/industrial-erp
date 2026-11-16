package com.industrial.erp.modules.inventory.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.common.Constants;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseProduct;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.industrial.erp.modules.base.entity.BaseWarehouse;
import com.industrial.erp.modules.base.mapper.BaseWarehouseMapper;
import com.industrial.erp.modules.inventory.dto.AppCheckSubmitDTO;
import com.industrial.erp.modules.inventory.dto.InvCheckCheckDTO;
import com.industrial.erp.modules.inventory.entity.InvCheck;
import com.industrial.erp.modules.inventory.entity.InvCheckDetail;
import com.industrial.erp.modules.inventory.entity.InvStock;
import com.industrial.erp.modules.inventory.mapper.InvCheckDetailMapper;
import com.industrial.erp.modules.inventory.mapper.InvCheckMapper;
import com.industrial.erp.modules.inventory.mapper.InvStockMapper;
import com.industrial.erp.modules.inventory.vo.AppCheckSubmitVO;
import com.industrial.erp.utils.BillNoGenerator;
import com.industrial.erp.modules.system.annotation.OperLog;
import com.industrial.erp.security.PermissionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 库存盘点
 * 业务: 生成盘点单 -> 录入实盘 -> 审核时根据差异自动生成盈亏单(库存调整)
 */
@Service
public class InvCheckService {

    public InvCheckService(InvCheckMapper checkMapper, InvCheckDetailMapper checkDetailMapper, BaseWarehouseMapper warehouseMapper, BaseProductMapper productMapper, InvStockMapper stockMapper, BillNoGenerator billNoGenerator, StockService stockService, PermissionService permService) {
        this.checkMapper = checkMapper;
        this.checkDetailMapper = checkDetailMapper;
        this.warehouseMapper = warehouseMapper;
        this.productMapper = productMapper;
        this.stockMapper = stockMapper;
        this.billNoGenerator = billNoGenerator;
        this.stockService = stockService;
        this.permService = permService;
    }

    private final InvCheckMapper checkMapper;
    private final InvCheckDetailMapper checkDetailMapper;
    private final BaseWarehouseMapper warehouseMapper;
    private final BaseProductMapper productMapper;
    private final InvStockMapper stockMapper;
    private final BillNoGenerator billNoGenerator;
    private final StockService stockService;
    private final PermissionService permService;

    public IPage<InvCheck> page(Integer pageNum, Integer pageSize, String billNo, String billStatus, Long warehouseId) {
        permService.requirePerm("inventory:check:list");
        Page<InvCheck> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<InvCheck> w = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(billNo)) w.like(InvCheck::getBillNo, billNo);
        if (StrUtil.isNotBlank(billStatus)) w.eq(InvCheck::getBillStatus, billStatus);
        if (warehouseId != null) w.eq(InvCheck::getWarehouseId, warehouseId);
        w.orderByDesc(InvCheck::getId);
        return checkMapper.selectPage(p, w);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        permService.requirePerm("inventory:check:delete");
        InvCheck c = checkMapper.selectById(id);
        if (c == null) throw BizException.of("盘点单不存在");
        if (!Constants.STATUS_DRAFT.equals(c.getBillStatus())) {
            throw BizException.of("仅 DRAFT 状态可删除, 已审核的盘点单请通过反审核流程");
        }
        // 软删主表
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<InvCheck> w =
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<>();
        w.eq(InvCheck::getId, id).set(InvCheck::getDeleted, 1);
        checkMapper.update(null, w);
        // 软删明细
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<InvCheckDetail> dw =
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<>();
        dw.eq(InvCheckDetail::getCheckId, id).set(InvCheckDetail::getDeleted, 1);
        checkDetailMapper.update(null, dw);
    }

    public InvCheck detail(Long id) {
        InvCheck c = checkMapper.selectById(id);
        if (c != null) c.setDetails(checkDetailMapper.selectByCheckId(id));
        // v1.1.12+: 注入 model 字段 (打印模板"型号"列)
        if (c != null && c.getDetails() != null) {
            com.industrial.erp.modules.base.service.ProductAttrInjector.inject(productMapper, c.getDetails(),
                    r -> ((InvCheckDetail) r).getProductId(),
                    (r, v) -> ((InvCheckDetail) r).setPModel(v),
                    p -> p.getModel());
        }
        return c;
    }

    @Transactional(rollbackFor = Exception.class)
    @OperLog(module="库存盘点", businessType="ADD", saveParam=true)
    public void add(InvCheck check) {
        permService.requirePerm("inventory:check:add");
        if (check.getBillDate() == null) check.setBillDate(LocalDate.now());
        if (StrUtil.isBlank(check.getBillNo())) check.setBillNo(billNoGenerator.generate(Constants.BILL_CK));
        if (StrUtil.isBlank(check.getBillStatus())) check.setBillStatus(Constants.STATUS_DRAFT);
        BaseWarehouse wh = warehouseMapper.selectById(check.getWarehouseId());
        if (wh != null) check.setWarehouseName(wh.getWarehouseName());

        // P0 fix: BaseMapper.insert 会扫描 transient details 字段, 报 "Type handler was null" 错
        // 保存前先清空 details (明细走独立 insert), 这里只插入主表
        List<InvCheckDetail> details = check.getDetails();
        check.setDetails(null);

        // 自动计算差异
        BigDecimal totalDiffQty = BigDecimal.ZERO;
        BigDecimal totalDiffAmount = BigDecimal.ZERO;
        for (InvCheckDetail d : details) {
            BigDecimal book = d.getBookQty() == null ? BigDecimal.ZERO : d.getBookQty();
            BigDecimal actual = d.getActualQty() == null ? BigDecimal.ZERO : d.getActualQty();
            d.setDiffQty(actual.subtract(book));
            if (d.getPrice() != null) {
                d.setDiffAmount(d.getDiffQty().multiply(d.getPrice()).setScale(4, RoundingMode.HALF_UP));
            }
            if (d.getDiffQty().compareTo(BigDecimal.ZERO) > 0) d.setDiffType("PROFIT");
            else if (d.getDiffQty().compareTo(BigDecimal.ZERO) < 0) d.setDiffType("LOSS");
            else d.setDiffType("NORMAL");
            totalDiffQty = totalDiffQty.add(d.getDiffQty());
            if (d.getDiffAmount() != null) totalDiffAmount = totalDiffAmount.add(d.getDiffAmount());
        }
        check.setTotalDiffQty(totalDiffQty);
        check.setTotalDiffAmount(totalDiffAmount);
        checkMapper.insert(check);

        for (InvCheckDetail d : details) {
            d.setId(null);
            d.setCheckId(check.getId());
            checkDetailMapper.insert(d);
        }
    }

    /**
     * 审核: 根据差异自动调整库存
     * 盘盈 (diff > 0) -> inStock (按明细自身批次)
     * 盘亏 (diff < 0) -> outStock (按批次分摊: 显式指定优先, 否则按实际批次库存自动拆分)
     *
     * <p>必须先按批次规划并校验, 再动库存: App 提交的明细 batch_no 为空且账面数是跨批次合计,
     * 直接按空批次出库会落到 batch_no IS NULL 的 0 库存行, 误报「库存不足」。
     */
    @Transactional(rollbackFor = Exception.class)
    @OperLog(module="库存盘点", businessType="CHECK", saveParam=true)
    public void check(Long id, InvCheckCheckDTO req) {
        permService.requirePerm("inventory:check:check");
        InvCheck c = checkMapper.selectById(id);
        if (c == null) throw BizException.of("盘点单不存在");
        if (!Constants.STATUS_DRAFT.equals(c.getBillStatus())) throw BizException.of("状态不正确");
        List<InvCheckDetail> details = checkDetailMapper.selectByCheckId(id);
        BaseWarehouse wh = warehouseMapper.selectById(c.getWarehouseId());
        String whName = wh == null ? "" : wh.getWarehouseName();

        Map<Long, List<InvCheckCheckDTO.BatchQty>> explicit = indexAllocations(req);
        Map<Long, Map<String, BigDecimal>> availability = new HashMap<>();
        Map<Long, List<InvCheckCheckDTO.BatchQty>> lossPlans = new LinkedHashMap<>();

        for (InvCheckDetail d : details) {
            if (d.getDiffQty() == null || d.getDiffQty().compareTo(BigDecimal.ZERO) >= 0) continue;
            BigDecimal outQty = d.getDiffQty().abs();
            List<InvCheckCheckDTO.BatchQty> given = explicit.get(d.getId());
            List<InvCheckCheckDTO.BatchQty> plan = given == null || given.isEmpty()
                    ? planAutoByBatch(c.getWarehouseId(), d, outQty, availability)
                    : planExplicitBatches(c.getWarehouseId(), d, outQty, given, availability);
            lossPlans.put(d.getId(), plan);
        }

        for (InvCheckDetail d : details) {
            if (d.getDiffQty() == null || d.getDiffQty().compareTo(BigDecimal.ZERO) == 0) continue;
            if (d.getDiffQty().compareTo(BigDecimal.ZERO) > 0) {
                // 盘盈 -> 入库
                stockService.inStock(Constants.LEDGER_CHECK, c.getId(), c.getBillNo(), d.getId(),
                        c.getWarehouseId(), whName, d.getLocationId(), null,
                        d.getProductId(), d.getUnitId(), d.getUnitName(), d.getBatchNo(),
                        d.getDiffQty(), d.getPrice(), c.getBillNo(),
                        null, null, "盘盈 " + c.getBillNo());
                continue;
            }
            List<InvCheckCheckDTO.BatchQty> plan = lossPlans.get(d.getId());
            for (InvCheckCheckDTO.BatchQty a : plan) {
                stockService.outStock(Constants.LEDGER_CHECK, c.getId(), c.getBillNo(), d.getId(),
                        c.getWarehouseId(), whName, d.getLocationId(), null,
                        d.getProductId(), d.getUnitId(), d.getUnitName(), a.getBatchNo(),
                        a.getQty(), d.getPrice(), c.getBillNo(),
                        null, null, "盘亏 " + c.getBillNo());
            }
            String joined = joinBatchNo(plan);
            if (!java.util.Objects.equals(joined, d.getBatchNo())) {
                d.setBatchNo(joined);
                checkDetailMapper.updateById(d);
            }
        }
        c.setBillStatus(Constants.STATUS_ADJUSTED);
        checkMapper.updateById(c);
    }

    private Map<Long, List<InvCheckCheckDTO.BatchQty>> indexAllocations(InvCheckCheckDTO req) {
        Map<Long, List<InvCheckCheckDTO.BatchQty>> m = new HashMap<>();
        if (req == null || req.getDetails() == null) return m;
        for (InvCheckCheckDTO.Line line : req.getDetails()) {
            if (line == null || line.getDetailId() == null) continue;
            if (line.getAllocations() == null || line.getAllocations().isEmpty()) continue;
            m.put(line.getDetailId(), line.getAllocations());
        }
        return m;
    }

    /** 显式分配: 合计必须等于盘亏数量, 每个批次扣完后不能超过该批次可用库存. */
    private List<InvCheckCheckDTO.BatchQty> planExplicitBatches(Long warehouseId, InvCheckDetail d, BigDecimal outQty,
                                                               List<InvCheckCheckDTO.BatchQty> given,
                                                               Map<Long, Map<String, BigDecimal>> availability) {
        BigDecimal sum = BigDecimal.ZERO;
        for (InvCheckCheckDTO.BatchQty a : given) {
            if (a == null || a.getQty() == null) throw BizException.of("批次分配数量不能为空, 商品=" + d.getProductName());
            if (a.getQty().compareTo(BigDecimal.ZERO) <= 0) throw BizException.of("批次分配数量必须大于 0, 商品=" + d.getProductName());
            sum = sum.add(a.getQty());
        }
        if (sum.subtract(outQty).abs().compareTo(new BigDecimal("0.0001")) > 0) {
            throw BizException.of(String.format("批次分配合计 %s 与盘亏数量 %s 不一致, 商品=%s",
                    sum.stripTrailingZeros().toPlainString(), outQty.stripTrailingZeros().toPlainString(), d.getProductName()));
        }
        Map<String, BigDecimal> avail = availOf(warehouseId, d.getProductId(), availability);
        List<InvCheckCheckDTO.BatchQty> plan = new ArrayList<>();
        for (InvCheckCheckDTO.BatchQty a : given) {
            String bn = normalizeBatch(a.getBatchNo());
            BigDecimal left = avail.getOrDefault(bn, BigDecimal.ZERO);
            if (left.compareTo(a.getQty()) < 0) {
                throw BizException.of(String.format("批次库存不足, 商品=%s, 批次=%s, 可用=%s, 需要=%s",
                        d.getProductName(), bn == null ? "<无批次>" : bn, left.toPlainString(), a.getQty().toPlainString()));
            }
            avail.put(bn, left.subtract(a.getQty()));
            plan.add(a);
        }
        return plan;
    }

    /** 未指定批次: 按该商品在当前仓库的实际批次库存顺序拆分, 合计不足则整单不执行. */
    private List<InvCheckCheckDTO.BatchQty> planAutoByBatch(Long warehouseId, InvCheckDetail d, BigDecimal outQty,
                                                            Map<Long, Map<String, BigDecimal>> availability) {
        Map<String, BigDecimal> avail = availOf(warehouseId, d.getProductId(), availability);
        List<InvCheckCheckDTO.BatchQty> plan = new ArrayList<>();
        BigDecimal remaining = outQty;
        for (Map.Entry<String, BigDecimal> e : avail.entrySet()) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
            BigDecimal take = e.getValue().min(remaining);
            if (take.compareTo(BigDecimal.ZERO) <= 0) continue;
            InvCheckCheckDTO.BatchQty a = new InvCheckCheckDTO.BatchQty();
            a.setBatchNo(e.getKey());
            a.setQty(take);
            plan.add(a);
            avail.put(e.getKey(), e.getValue().subtract(take));
            remaining = remaining.subtract(take);
        }
        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            StringBuilder detail = new StringBuilder();
            for (Map.Entry<String, BigDecimal> e : avail.entrySet()) {
                if (detail.length() > 0) detail.append(", ");
                detail.append("[批次=").append(e.getKey() == null ? "<无批次>" : e.getKey())
                        .append(", 库存=").append(e.getValue().toPlainString()).append("]");
            }
            throw BizException.of(String.format("批次可用库存不足, 商品=%s, 需要=%s, 尚缺=%s. %s",
                    d.getProductName(), outQty.stripTrailingZeros().toPlainString(),
                    remaining.stripTrailingZeros().toPlainString(),
                    detail.length() == 0 ? "(该仓库无该商品库存)" : detail.toString()));
        }
        return plan;
    }

    /** key = batch_no 归一化后的值 (null 代表无批次行), value = 剩余可用量. 同单多明细共用一份可用量. */
    private Map<String, BigDecimal> availOf(Long warehouseId, Long productId, Map<Long, Map<String, BigDecimal>> cache) {
        Map<String, BigDecimal> avail = cache.get(productId);
        if (avail != null) return avail;
        avail = new LinkedHashMap<>();
        for (InvStock s : stockMapper.listByWarehouseAndProduct(warehouseId, productId)) {
            String bn = normalizeBatch(s.getBatchNo());
            BigDecimal q = s.getQty() == null ? BigDecimal.ZERO : s.getQty();
            avail.merge(bn, q, BigDecimal::add);
        }
        cache.put(productId, avail);
        return avail;
    }

    private static String normalizeBatch(String batchNo) {
        return batchNo == null || batchNo.isEmpty() ? null : batchNo;
    }

    private static String joinBatchNo(List<InvCheckCheckDTO.BatchQty> allocations) {
        StringBuilder sb = new StringBuilder();
        for (InvCheckCheckDTO.BatchQty a : allocations) {
            if (sb.length() > 0) sb.append('|');
            if (a.getBatchNo() != null) sb.append(a.getBatchNo());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    // ============ v1.0.8+ App 外勤盘点对接 ============

    /**
     * v1.0.8+ App 提交盘点入口.
     * <p>流程:
     * <ol>
     *   <li>校验仓库存在</li>
     *   <li>校验商品 ID 全部有效</li>
     *   <li>事务内拉每个商品的当前账面数 (inv_stock 聚合)</li>
     *   <li>计算 diff_qty / diff_amount / diff_type</li>
     *   <li>生成 DRAFT 状态盘点单, 单号格式 CK-APP-yyyyMMdd-XXXXX</li>
     * </ol>
     *
     * <p>为什么不在 App 端算 book_qty: 避免 App 缓存 + 网络延迟导致账面数与服务器不一致;
     * 后端事务内取数, 保证审核前所有明细的 book_qty 与提交时刻 inv_stock 一致.
     */
    @Transactional(rollbackFor = Exception.class)
    @OperLog(module="库存盘点(App)", businessType="ADD", saveParam=true)
    public AppCheckSubmitVO submitFromApp(AppCheckSubmitDTO dto) {
        permService.requirePerm("inventory:check:list");

        if (dto.getWarehouseId() == null) throw BizException.of("仓库不能为空");
        BaseWarehouse wh = warehouseMapper.selectById(dto.getWarehouseId());
        if (wh == null) throw BizException.of("仓库不存在");

        // 1. 校验商品 (批量 selectByIds)
        List<Long> productIds = dto.getItems().stream().map(AppCheckSubmitDTO.Item::getProductId).toList();
        List<BaseProduct> products = productMapper.selectBatchIds(productIds);
        if (products.size() != new java.util.HashSet<>(productIds).size()) {
            throw BizException.of("部分商品不存在或已删除, 请刷新盘点列表");
        }

        // 2. 构造主表
        InvCheck check = new InvCheck();
        check.setBillDate(dto.getBillDate() == null ? LocalDate.now() : dto.getBillDate());
        // App 来源单号: CK-APP-yyyyMMdd-5位随机 (避免与 PC 自动生成冲突)
        check.setBillNo("CK-APP-" + check.getBillDate().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "-" + IdUtil.fastSimpleUUID().substring(0, 5).toUpperCase());
        check.setWarehouseId(wh.getId());
        check.setWarehouseName(wh.getWarehouseName());
        check.setCheckType("PARTIAL");
        check.setBillStatus(Constants.STATUS_DRAFT);
        check.setRemark("[App 提交] " + (StrUtil.blankToDefault(dto.getRemark(), "")));

        // 3. 构造明细 + 拉账面数
        List<InvCheckDetail> details = new ArrayList<>(dto.getItems().size());
        BigDecimal totalDiffQty = BigDecimal.ZERO;
        BigDecimal totalDiffAmount = BigDecimal.ZERO;

        for (AppCheckSubmitDTO.Item item : dto.getItems()) {
            BaseProduct p = products.stream().filter(x -> x.getId().equals(item.getProductId())).findFirst().orElse(null);
            if (p == null) continue;

            // 账面数: inv_stock 该商品在该仓库的 qty 聚合
            BigDecimal bookQty = queryBookQty(wh.getId(), p.getId());

            InvCheckDetail d = new InvCheckDetail();
            d.setProductId(p.getId());
            d.setProductCode(p.getProductCode());
            d.setProductName(p.getProductName());
            d.setUnitId(p.getMainUnitId());
            // BaseProduct 没有 unitName 字段, 留空 — 前端展示时按 unitId 查单位表
            d.setUnitName(null);
            d.setBatchNo(null);  // App 不录入批次, 留 null
            d.setBookQty(bookQty);
            d.setActualQty(item.getActualQty());
            d.setDiffQty(item.getActualQty().subtract(bookQty));
            d.setPrice(p.getCostPrice() == null ? BigDecimal.ZERO : p.getCostPrice());
            d.setDiffAmount(d.getDiffQty().multiply(d.getPrice()).setScale(4, RoundingMode.HALF_UP));
            d.setRemark(item.getRemark());
            if (d.getDiffQty().compareTo(BigDecimal.ZERO) > 0) d.setDiffType("PROFIT");
            else if (d.getDiffQty().compareTo(BigDecimal.ZERO) < 0) d.setDiffType("LOSS");
            else d.setDiffType("NORMAL");

            totalDiffQty = totalDiffQty.add(d.getDiffQty());
            totalDiffAmount = totalDiffAmount.add(d.getDiffAmount());
            details.add(d);
        }

        check.setTotalDiffQty(totalDiffQty);
        check.setTotalDiffAmount(totalDiffAmount);
        checkMapper.insert(check);

        // 4. 批量插入明细
        for (InvCheckDetail d : details) {
            d.setId(null);
            d.setCheckId(check.getId());
            checkDetailMapper.insert(d);
        }

        AppCheckSubmitVO vo = new AppCheckSubmitVO();
        vo.setId(check.getId());
        vo.setBillNo(check.getBillNo());
        vo.setBillStatus(check.getBillStatus());
        vo.setTotalDiffQty(totalDiffQty);
        vo.setTotalDiffAmount(totalDiffAmount);
        vo.setItemCount(details.size());
        return vo;
    }

    /**
     * 查某仓库某商品的账面数 (聚合 inv_stock.qty).
     * 不区分批次 — App 端无批次信息, 后续审核时按库存现状调整 (FIFO).
     */
    private BigDecimal queryBookQty(Long warehouseId, Long productId) {
        // 直接用 stockMapper 自定义的 SUM 查询, 避免 ServiceImpl 依赖
        BigDecimal sum = stockMapper.sumQtyByWarehouseAndProduct(warehouseId, productId);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /**
     * v1.0.8+ App 盘点前预加载仓库账面快照.
     */
    public List<com.industrial.erp.modules.inventory.vo.WarehouseStockSnapshotVO> listStockSnapshot(Long warehouseId) {
        permService.requirePerm("inventory:check:list");
        return checkMapper.selectStockSnapshotByWarehouse(warehouseId);
    }

    /** v1.1.11+ 反审核盘点单 (CHECKED→DRAFT, status-only).
     *  库存账已写入实际库存调整 (盘盈盘亏), 反审核不会自动回退; 需走新盘点单修复 */
    @Transactional(rollbackFor = Exception.class)
    @OperLog(module="库存盘点", businessType="UNCHECK", saveParam=true)
    public void uncheck(Long id) {
        permService.requirePerm("inventory:check:uncheck");
        InvCheck c = checkMapper.selectById(id);
        if (c == null) throw BizException.of("盘点单不存在");
        if (!Constants.STATUS_CHECKED.equals(c.getBillStatus())) {
            throw BizException.of("只有已审核状态可反审核");
        }
        InvCheck upd = new InvCheck();
        upd.setId(id);
        upd.setBillStatus(Constants.STATUS_DRAFT);
        checkMapper.updateById(upd);
    }
}
