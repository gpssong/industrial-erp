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
import com.industrial.erp.modules.inventory.entity.InvCheck;
import com.industrial.erp.modules.inventory.entity.InvCheckDetail;
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
import java.util.List;

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

        // 自动计算差异
        BigDecimal totalDiffQty = BigDecimal.ZERO;
        BigDecimal totalDiffAmount = BigDecimal.ZERO;
        for (InvCheckDetail d : check.getDetails()) {
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

        for (InvCheckDetail d : check.getDetails()) {
            d.setId(null);
            d.setCheckId(check.getId());
            checkDetailMapper.insert(d);
        }
    }

    /**
     * 审核: 根据差异自动调整库存
     * 盘盈 (diff > 0) -> inStock
     * 盘亏 (diff < 0) -> outStock
     */
    @Transactional(rollbackFor = Exception.class)
    public void check(Long id) {
        permService.requirePerm("inventory:check:check");
        InvCheck c = checkMapper.selectById(id);
        if (c == null) throw BizException.of("盘点单不存在");
        if (!Constants.STATUS_DRAFT.equals(c.getBillStatus())) throw BizException.of("状态不正确");
        List<InvCheckDetail> details = checkDetailMapper.selectByCheckId(id);
        BaseWarehouse wh = warehouseMapper.selectById(c.getWarehouseId());
        String whName = wh == null ? "" : wh.getWarehouseName();

        for (InvCheckDetail d : details) {
            if (d.getDiffQty() == null || d.getDiffQty().compareTo(BigDecimal.ZERO) == 0) continue;
            if (d.getDiffQty().compareTo(BigDecimal.ZERO) > 0) {
                // 盘盈 -> 入库
                stockService.inStock(Constants.LEDGER_CHECK, c.getId(), c.getBillNo(), d.getId(),
                        c.getWarehouseId(), whName, d.getLocationId(), null,
                        d.getProductId(), d.getUnitId(), d.getUnitName(), d.getBatchNo(),
                        d.getDiffQty(), d.getPrice(), c.getBillNo(),
                        null, null, "盘盈 " + c.getBillNo());
            } else {
                // 盘亏 -> 出库
                BigDecimal outQty = d.getDiffQty().abs();
                stockService.outStock(Constants.LEDGER_CHECK, c.getId(), c.getBillNo(), d.getId(),
                        c.getWarehouseId(), whName, d.getLocationId(), null,
                        d.getProductId(), d.getUnitId(), d.getUnitName(), d.getBatchNo(),
                        outQty, d.getPrice(), c.getBillNo(),
                        null, null, "盘亏 " + c.getBillNo());
            }
        }
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<InvCheck> w =
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<>();
        w.eq(InvCheck::getId, id).set(InvCheck::getBillStatus, Constants.STATUS_ADJUSTED);
        checkMapper.update(null, w);
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
}
