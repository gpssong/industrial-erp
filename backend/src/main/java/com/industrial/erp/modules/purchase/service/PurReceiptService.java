package com.industrial.erp.modules.purchase.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.common.Constants;
import com.industrial.erp.common.R;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseProduct;
import com.industrial.erp.modules.base.entity.BaseSupplier;
import com.industrial.erp.modules.base.entity.BaseWarehouse;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.industrial.erp.modules.base.mapper.BaseSupplierMapper;
import com.industrial.erp.modules.base.mapper.BaseWarehouseMapper;
import com.industrial.erp.modules.base.service.ProductAttrInjector;
import com.industrial.erp.modules.finance.service.FinArapService;
import com.industrial.erp.modules.inventory.service.StockService;
import com.industrial.erp.modules.purchase.entity.PurOrder;
import com.industrial.erp.modules.purchase.entity.PurOrderDetail;
import com.industrial.erp.modules.purchase.entity.PurReceipt;
import com.industrial.erp.modules.purchase.entity.PurReceiptDetail;
import com.industrial.erp.modules.purchase.mapper.PurOrderDetailMapper;
import com.industrial.erp.modules.purchase.mapper.PurOrderMapper;
import com.industrial.erp.modules.purchase.mapper.PurReceiptDetailMapper;
import com.industrial.erp.modules.purchase.mapper.PurReceiptMapper;
import com.industrial.erp.modules.qms.service.QmsInspectionService;
import com.industrial.erp.modules.system.annotation.OperLog;
import com.industrial.erp.modules.system.aspect.OperLogPublisher;
import com.industrial.erp.utils.BillNoGenerator;
import com.industrial.erp.security.PermissionService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 采购入库服务
 * 业务: 生成入库单 -> 写明细 -> 调用 StockService 库存入库 -> 生成应付台账
 */
@Service
public class PurReceiptService {

    public PurReceiptService(PurReceiptMapper receiptMapper, PurReceiptDetailMapper receiptDetailMapper, PurOrderMapper orderMapper, PurOrderDetailMapper orderDetailMapper, BaseSupplierMapper supplierMapper, BaseWarehouseMapper warehouseMapper, BillNoGenerator billNoGenerator, StockService stockService, FinArapService arapService, PermissionService permService, OperLogPublisher operLogPublisher, BaseProductMapper productMapper, QmsInspectionService qmsInspectionService, com.industrial.erp.modules.purchase.service.PurInboundPlanService inboundPlanService) {
        this.receiptMapper = receiptMapper;
        this.receiptDetailMapper = receiptDetailMapper;
        this.orderMapper = orderMapper;
        this.orderDetailMapper = orderDetailMapper;
        this.supplierMapper = supplierMapper;
        this.warehouseMapper = warehouseMapper;
        this.billNoGenerator = billNoGenerator;
        this.stockService = stockService;
        this.arapService = arapService;
        this.permService = permService;
        this.operLogPublisher = operLogPublisher;
        this.productMapper = productMapper;
        this.qmsInspectionService = qmsInspectionService;
        this.inboundPlanService = inboundPlanService;
    }

    private final PurReceiptMapper receiptMapper;
    private final PurReceiptDetailMapper receiptDetailMapper;
    private final PurOrderMapper orderMapper;
    private final PurOrderDetailMapper orderDetailMapper;
    private final BaseSupplierMapper supplierMapper;
    private final BaseWarehouseMapper warehouseMapper;
    private final BaseProductMapper productMapper;
    private final BillNoGenerator billNoGenerator;
    private final StockService stockService;
    private final FinArapService arapService;
    private final PermissionService permService;
    private final OperLogPublisher operLogPublisher;
    /** v1.1.90+: QMS 自动触发器 (IQC 草稿在 check() 末 try/catch 调用, 不阻塞主审核) */
    private final QmsInspectionService qmsInspectionService;
    /** v1.1.101 XK — 采购计划 service (createFromIqc 走 plan → 收 plan 上下文) */
    private final com.industrial.erp.modules.purchase.service.PurInboundPlanService inboundPlanService;

    public IPage<PurReceipt> page(Integer pageNum, Integer pageSize, String billNo, Long supplierId, String billStatus, String productName) {
        // v1.1.130: XK 单据打印页要读一张已审核入库单, report:print 独立后该入口也要能读
        permService.requireAnyPerm("purchase:receipt:list", "report:print");
        Page<PurReceipt> p = new Page<>(pageNum, pageSize);
        // productName EXISTS 子查询已下沉到 mapper XML, 避免 QueryWrapper.apply() 字符串拼接反模式 (P1-3)
        IPage<PurReceipt> result = receiptMapper.selectPageWithProduct(p, billNo, supplierId, billStatus, productName);
        // v1.1.15+: MP IPage 不会自动映射 @TableField(exist=false) 字段, 需 Service 层批量注入 warehouseName
        List<PurReceipt> records = result.getRecords();
        if (records != null && !records.isEmpty()) {
            List<Long> whIds = records.stream()
                    .map(PurReceipt::getWarehouseId)
                    .filter(id -> id != null)
                    .distinct()
                    .collect(java.util.stream.Collectors.toList());
            if (!whIds.isEmpty()) {
                Map<Long, BaseWarehouse> whMap = warehouseMapper.selectBatchIds(whIds)
                        .stream().collect(java.util.stream.Collectors.toMap(BaseWarehouse::getId, w -> w));
                records.forEach(r -> {
                    if (r.getWarehouseId() != null) {
                        BaseWarehouse wh = whMap.get(r.getWarehouseId());
                        if (wh != null) r.setWarehouseName(wh.getWarehouseName());
                    }
                });
            }
        }
        return result;
    }

    public PurReceipt detail(Long id) {
        PurReceipt r = receiptMapper.selectById(id);
        if (r != null) r.setDetails(receiptDetailMapper.selectByReceiptId(id));
        // v1.1.15+: 注入仓库名 (detail 场景单次查询, 无需批量)
        if (r != null && r.getWarehouseId() != null) {
            BaseWarehouse wh = warehouseMapper.selectById(r.getWarehouseId());
            if (wh != null) r.setWarehouseName(wh.getWarehouseName());
        }
        // 批量注入商品色号 + 型号 (避免 N+1)
        // v1.1.12+: 同时注入 model (型号) — 打印模板"型号"列需要此字段
        if (r != null && r.getDetails() != null) {
            ProductAttrInjector.injectColorNo(productMapper, r.getDetails(),
                    row -> ((PurReceiptDetail) row).getProductId(),
                    (row, v) -> ((PurReceiptDetail) row).setPColorNo(v));
            ProductAttrInjector.inject(productMapper, r.getDetails(),
                    row -> ((PurReceiptDetail) row).getProductId(),
                    (row, v) -> ((PurReceiptDetail) row).setPModel(v),
                    p -> p.getModel());
        }
        return r;
    }

    /** 查询指定供应商+商品的上次订单单价 */
    public BigDecimal getLastPrice(Long supplierId, Long productId) {
        BigDecimal price = orderDetailMapper.selectLastPriceBySupplierAndProduct(supplierId, productId);
        return price != null ? price : BigDecimal.ZERO;
    }

    /**
     * 查询该供应商最近 50 条历史采购入库明细 (按 bill_date DESC).
     * 用于采购入库新增弹窗底部"该供应商历史采购产品"列表. v1.1.7+ 新增.
     */
    public java.util.List<java.util.Map<String, Object>> getSupplierHistoryProducts(Long supplierId) {
        if (supplierId == null) return java.util.Collections.emptyList();
        return receiptDetailMapper.selectSupplierHistoryProducts(supplierId);
    }

    @Transactional(rollbackFor = Exception.class)
    @OperLog(module="采购入库", businessType="ADD", saveParam=true)
    public void add(PurReceipt receipt) {
        permService.requirePerm("purchase:receipt:add");
        if (receipt.getBillDate() == null) receipt.setBillDate(LocalDate.now());
        if (StrUtil.isBlank(receipt.getBillNo())) {
            receipt.setBillNo(billNoGenerator.generate(Constants.BILL_RKP));
        }
        if (StrUtil.isBlank(receipt.getBillStatus())) receipt.setBillStatus(Constants.STATUS_DRAFT);
        if (StrUtil.isBlank(receipt.getBillType())) receipt.setBillType("NORMAL");
        // 校验供应商
        BaseSupplier s = supplierMapper.selectById(receipt.getSupplierId());
        if (s == null) throw BizException.of("供应商不存在");
        // v1.1.122 XK 门禁按行豁免: 食品级采购 (含食品级行) 才走合格名录硬门禁; 全非食品采购跳过
        boolean anyFood = hasFoodGradeLine(receipt.getDetails());
        if (s.getStatus() != null && s.getStatus() != 1) {
            if (anyFood) {
                throw BizException.of("XK 合规: 该采购含食品级原料, 供应商 " + s.getSupplierName() + " 当前为「暂停供货」, 不能创建采购入库单; 请先在合格供应商名录中恢复供货");
            }
            log.info("采购建单 XK 豁免: 全非食品采购, 跳过供应商合格名录门禁 supplier={}", s.getSupplierName());
        }
        receipt.setSupplierName(s.getSupplierName());
        // 校验仓库
        BaseWarehouse w = warehouseMapper.selectById(receipt.getWarehouseId());
        if (w == null) throw BizException.of("仓库不存在");
        // 汇总
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        // v1.1.19+: tax-inclusive price, taxAmount=0, totalAmountTax=totalAmount=开单金额
        BigDecimal totalAmountTax = BigDecimal.ZERO;
        int line = 0;
        for (PurReceiptDetail d : receipt.getDetails()) {
            d.setLineNo(++line);
            if (d.getTaxRate() == null) d.setTaxRate(s.getTaxRate() == null ? new BigDecimal("13.00") : s.getTaxRate());
            d.setAmount(d.getPrice().multiply(d.getQty()).setScale(4, RoundingMode.HALF_UP));
            d.setTaxAmount(BigDecimal.ZERO);
            d.setAmountTax(d.getAmount());
            totalQty = totalQty.add(d.getQty());
            totalAmount = totalAmount.add(d.getAmount());
            totalAmountTax = totalAmountTax.add(d.getAmount());
        }
        receipt.setTotalQty(totalQty);
        receipt.setTotalAmount(totalAmount);
        receipt.setTaxAmount(BigDecimal.ZERO);
        receipt.setTotalAmountTax(totalAmountTax);
        receipt.setPaidAmount(BigDecimal.ZERO);

        receiptMapper.insert(receipt);
        for (PurReceiptDetail d : receipt.getDetails()) {
            d.setId(null);
            d.setReceiptId(receipt.getId());
            receiptDetailMapper.insert(d);
        }
    }

    /**
     * 修改入库单 (仅 DRAFT 状态可改)
     */
    @Transactional(rollbackFor = Exception.class)
    @OperLog(module="采购入库", businessType="EDIT", saveParam=true)
    public void update(PurReceipt receipt) {
        permService.requirePerm("purchase:receipt:edit");
        PurReceipt origin = receiptMapper.selectById(receipt.getId());
        if (origin == null) throw BizException.of("入库单不存在");
        if (!Constants.STATUS_DRAFT.equals(origin.getBillStatus())) {
            throw BizException.of("只有草稿状态可修改");
        }
        // 校验供应商
        BaseSupplier s = supplierMapper.selectById(receipt.getSupplierId());
        if (s == null) throw BizException.of("供应商不存在");
        // v1.1.122 XK 门禁按行豁免: 食品级采购 (含食品级行) 才走合格名录硬门禁; 全非食品采购跳过
        boolean anyFood = hasFoodGradeLine(receipt.getDetails());
        if (s.getStatus() != null && s.getStatus() != 1) {
            if (anyFood) {
                throw BizException.of("XK 合规: 该采购含食品级原料, 供应商 " + s.getSupplierName() + " 当前为「暂停供货」, 不能修改采购入库单");
            }
            log.info("采购改单 XK 豁免: 全非食品采购, 跳过供应商合格名录门禁 supplier={}", s.getSupplierName());
        }
        receipt.setSupplierName(s.getSupplierName());
        // 校验仓库
        BaseWarehouse w = warehouseMapper.selectById(receipt.getWarehouseId());
        if (w == null) throw BizException.of("仓库不存在");

        // 重算汇总
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        // v1.1.19+: tax-inclusive price, taxAmount=0, totalAmountTax=totalAmount=开单金额
        BigDecimal totalAmountTax = BigDecimal.ZERO;
        int line = 0;
        for (PurReceiptDetail d : receipt.getDetails()) {
            d.setLineNo(++line);
            if (d.getTaxRate() == null) d.setTaxRate(s.getTaxRate() == null ? new BigDecimal("13.00") : s.getTaxRate());
            d.setAmount(d.getPrice().multiply(d.getQty()).setScale(4, RoundingMode.HALF_UP));
            d.setTaxAmount(BigDecimal.ZERO);
            d.setAmountTax(d.getAmount());
            totalQty = totalQty.add(d.getQty());
            totalAmount = totalAmount.add(d.getAmount());
            totalAmountTax = totalAmountTax.add(d.getAmount());
        }
        receipt.setTotalQty(totalQty);
        receipt.setTotalAmount(totalAmount);
        receipt.setTaxAmount(BigDecimal.ZERO);
        receipt.setTotalAmountTax(totalAmountTax);
        // 主表ID固定, 防止前端误传 bill_no/bill_date 覆盖
        receipt.setBillNo(origin.getBillNo());
        receipt.setBillDate(origin.getBillDate());
        receipt.setBillStatus(origin.getBillStatus());
        receipt.setBillType(origin.getBillType());

        receiptMapper.updateById(receipt);
        // 删旧明细, 重新插入
        receiptDetailMapper.delete(new LambdaQueryWrapper<PurReceiptDetail>().eq(PurReceiptDetail::getReceiptId, receipt.getId()));
        for (PurReceiptDetail d : receipt.getDetails()) {
            d.setId(null);
            d.setReceiptId(receipt.getId());
            receiptDetailMapper.insert(d);
        }
    }

    /**
     * 删除入库单 (仅 DRAFT 状态可删)
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        permService.requirePerm("purchase:receipt:delete");
        PurReceipt r = receiptMapper.selectById(id);
        if (r == null) throw BizException.of("入库单不存在");
        if (!Constants.STATUS_DRAFT.equals(r.getBillStatus())) {
            throw BizException.of("只有草稿状态可删除");
        }
        // 取明细快照
        List<PurReceiptDetail> details = receiptDetailMapper.selectByReceiptId(id);
        // 软删除明细
        if (details != null && !details.isEmpty()) {
            receiptDetailMapper.update(null, new LambdaUpdateWrapper<PurReceiptDetail>()
                    .eq(PurReceiptDetail::getReceiptId, id).set(PurReceiptDetail::getDeleted, 1));
        }
        // 软删除主表
        receiptMapper.update(null, new LambdaUpdateWrapper<PurReceipt>()
                .eq(PurReceipt::getId, id).set(PurReceipt::getDeleted, 1));
        // 写操作日志
        operLogPublisher.publishDeleteSnapshot("采购入库", String.valueOf(id), r, details);
    }

    /**
     * 审核入库单 -> 触发库存入库 + 应付台账
     */
    @Transactional(rollbackFor = Exception.class)
    @OperLog(module="采购入库", businessType="CHECK", saveParam=true)
    public void check(Long id) {
        permService.requirePerm("purchase:receipt:check");
        PurReceipt r = receiptMapper.selectById(id);
        if (r == null) throw BizException.of("入库单不存在");
        if (!Constants.STATUS_DRAFT.equals(r.getBillStatus())) {
            throw BizException.of("只有草稿状态可审核");
        }
        // v1.1.122 XK 门禁按行豁免: 逐明细行判 is_food_grade, 含任一行食品级才走 XK IQC 三步门禁;
        // 全非食品采购 (成品/包装/非食品接触件) 跳过 IQC 门禁直接入库. 供应商门禁在 add/update 已按同口径分流.
        List<PurReceiptDetail> details = receiptDetailMapper.selectByReceiptId(id);
        boolean anyFood = hasFoodGradeLine(details);
        // v1.1.95 XK 三步门禁: 食品接触塑料 XK 合规 — 检验合格才入库 (先检验后入库).
        // 须存在该入库单关联的 IQC 检验单且 result=PASS(或 PASS_DISPOSITION, status=CHECKED);
        // 未检验 / 不合格 / 未审核的 IQC 一律拒绝入库. 仅对含食品级行的单生效.
        if (anyFood) {
            if (!qmsInspectionService.hasPassedIqc(id)) {
                com.industrial.erp.modules.qms.entity.MesQualityInspection iqc = qmsInspectionService.findIqcByReceiptId(id);
                if (iqc == null) {
                    throw BizException.of("XK 合规: 该单含食品级原料, 未关联 IQC 来料检验, 不能入库; 请先完成「到货与原料检验」并判定合格 (PASS)");
                }
                throw BizException.of("XK 合规: 该单含食品级原料, 关联 IQC 检验单 " + iqc.getBillNo() + " 判定为 " + iqc.getResult()
                        + (Constants.RESULT_PASS.equals(iqc.getResult()) && !Constants.STATUS_CHECKED.equals(iqc.getStatus()) ? " (尚未审核放行)" : "")
                        + ", 不合格/未放行不能入库; 请拒收或放行后重试");
            }
            // 冗余快照: 把关联 IQC 的判定结果/报告号/抽样数/检验人 写回入库单 (供 XK 前端展示 + 审计留痕)
            qmsInspectionService.snapshotXkGate(r);
        } else {
            log.info("采购入库 XK 豁免: billNo={} 全部行非食品级, 跳过 IQC 门禁直接入库", r.getBillNo());
        }

        BaseWarehouse wh = warehouseMapper.selectById(r.getWarehouseId());

        for (PurReceiptDetail d : details) {
            // 触发库存入库 (移动加权平均成本)
            stockService.inStock(
                    Constants.LEDGER_PUR_RECEIPT, r.getId(), r.getBillNo(), d.getId(),
                    r.getWarehouseId(), wh.getWarehouseName(), d.getLocationId(), d.getLocationName(),
                    d.getProductId(), d.getUnitId(), d.getUnitName(), d.getBatchNo(),
                    d.getQty(), d.getPrice(), r.getBillNo(),
                    r.getSupplierId(), null, "采购入库 " + r.getBillNo()
            );
            // 累计订单已入库数
            if (d.getOrderDetailId() != null) {
                PurOrderDetail od = orderDetailMapper.selectById(d.getOrderDetailId());
                if (od != null) {
                    od.setInQty((od.getInQty() == null ? BigDecimal.ZERO : od.getInQty()).add(d.getQty()));
                    orderDetailMapper.updateById(od);
                }
            }
        }

        // 更新状态
        PurReceipt upd = new PurReceipt();
        upd.setId(id);
        upd.setBillStatus(Constants.STATUS_CHECKED);
        // v1.1.95 XK: 落库冗余快照 (检验报告号/判定结果/抽样数/检验人)
        upd.setInspectionReportNo(r.getInspectionReportNo());
        upd.setInspectionResult(r.getInspectionResult());
        upd.setSampleQty(r.getSampleQty());
        upd.setInspectorName(r.getInspectorName());
        receiptMapper.updateById(upd);

        // 写入应付台账
        arapService.createApForPurchase(r, details);

        // v1.1.95: XK 三步流程 — 检验已在入库前完成 (上面门禁校验过合格 IQC), 不再审核后自动建草稿.
        // 兜底: 仅当「含食品级行的单」完全没有关联 IQC 时 (老数据/异常), 自动补一张 DRAFT IQC 供后续检验; 不阻塞入库.
        // v1.1.122: 全非食品采购不建 IQC (无意义, 本就豁免检验).
        try {
            if (anyFood && qmsInspectionService.findIqcByReceiptId(id) == null) {
                qmsInspectionService.createOnReceipt(r);
            }
        } catch (Exception ex) {
            log.warn("[PurReceipt.check] QMS IQC auto-create failed (non-blocking): {}", ex.getMessage());
        }

        log.info("采购入库审核: billNo={}, amount={}", r.getBillNo(), r.getTotalAmountTax());
    }

    /**
     * 反审核入库单 (v1.1.11+): status-only (CHECKED→DRAFT).
     * 注意: 库存账已实增 + 应付台账已生成, 反审核不会自动回退; 需走红冲单或人工修正
     */
    /** v1.1.18+: 反审核 (CHECKED→DRAFT).
     *  同步回退: 库存出库 (采购入库是入库, 反审核要出库冲掉) + 删除正 AP. */
    @Transactional(rollbackFor = Exception.class)
    @OperLog(module="采购入库", businessType="UNCHECK", saveParam=true)
    public void uncheck(Long id) {
        permService.requirePerm("purchase:receipt:uncheck");
        PurReceipt r = receiptMapper.selectById(id);
        if (r == null) throw BizException.of("入库单不存在");
        if (!Constants.STATUS_CHECKED.equals(r.getBillStatus())) {
            throw BizException.of("只有已审核状态可反审核");
        }

        List<PurReceiptDetail> details = receiptDetailMapper.selectByReceiptId(id);
        BaseWarehouse wh = r.getWarehouseId() == null ? null : warehouseMapper.selectById(r.getWarehouseId());
        for (PurReceiptDetail d : details) {
            stockService.outStock(
                    Constants.LEDGER_PUR_RECEIPT, r.getId(), r.getBillNo(), d.getId(),
                    r.getWarehouseId(), wh == null ? null : wh.getWarehouseName(),
                    d.getLocationId(), d.getLocationName(),
                    d.getProductId(), d.getUnitId(), d.getUnitName(), d.getBatchNo(),
                    d.getQty(), null, r.getBillNo(),
                    r.getSupplierId(), null, "反审核采购入库 " + r.getBillNo()
            );
        }

        arapService.requireCancelableAndDelete(Constants.LEDGER_PUR_RECEIPT, r.getId());

        PurReceipt upd = new PurReceipt();
        upd.setId(id);
        upd.setBillStatus(Constants.STATUS_DRAFT);
        receiptMapper.updateById(upd);
    }

    // ==================================================================
    // v1.1.101 XK — 从 IQC 检验单自动建 DRAFT 采购入库单 (App 端 "生成报告并自动入库" 入口)
    // ==================================================================

    /**
     * v1.1.101 XK — App 端 pages/scan/in "生成报告并自动入库" 调本方法.
     *
     * <p>从已审核 PASS 的 IQC + 关联采购计划自动建 DRAFT 采购入库单. 门禁:
     * <ol>
     *   <li>{@code qmsInspectionService.hasPassedIqcForPlan(planId, iqcId)} 必须 true</li>
     *   <li>供应商必须 status=1 (沿用 add() 的合格名录门禁)</li>
     *   <li>1 行明细 (一期简化): productId/qty/spec/batchNo/warehouseId 从 plan + iqc 冗余快照</li>
     *   <li>价格默认 0 (财务在 PC 端补录), amount=0</li>
     *   <li>XK 6 列快照: inspectionReportNo/inspectionResult/sampleQty/inspectorName 从 IQC 写入</li>
     *   <li>更新 plan 状态 → INBOUNDED</li>
     * </ol>
     *
     * @return 新建入库单 id
     */
    @Transactional(rollbackFor = Exception.class)
    @OperLog(module="采购入库", businessType="ADD", saveParam=true)
    public Long createFromIqc(Long planId, Long iqcId) {
        permService.requirePerm("purchase:receipt:add");
        // 1. 硬门禁: IQC 已审核 PASS
        if (!qmsInspectionService.hasPassedIqcForPlan(planId, iqcId)) {
            throw BizException.of("IQC 检验未合格或未审核, 不能入库");
        }
        com.industrial.erp.modules.qms.entity.MesQualityInspection iqc =
                qmsInspectionService.findIqcByPlanId(planId);
        if (iqc == null || !Constants.INSPECTION_IQC.equals(iqc.getInspectionType())) {
            throw BizException.of("关联的检验单不是 IQC 类型");
        }
        // 2. 计划
        com.industrial.erp.modules.purchase.entity.PurInboundPlan plan =
                inboundPlanService.findById(planId);
        if (plan == null) throw BizException.of("采购计划不存在");

        // 3. 供应商合格名录
        BaseSupplier s = supplierMapper.selectById(plan.getSupplierId());
        if (s == null) throw BizException.of("供应商不存在");
        if (s.getStatus() != null && s.getStatus() != 1) {
            throw BizException.of("XK: 供应商 " + s.getSupplierName() + " 当前暂停供货, 不能入库");
        }
        // 4. 仓库
        if (plan.getWarehouseId() == null) throw BizException.of("采购计划未指定仓库, 请先在 PC 端补录");
        BaseWarehouse w = warehouseMapper.selectById(plan.getWarehouseId());
        if (w == null) throw BizException.of("仓库不存在");

        // 5. 构造 head
        PurReceipt receipt = new PurReceipt();
        receipt.setBillNo(billNoGenerator.generate(Constants.BILL_RKP));
        receipt.setBillDate(LocalDate.now());
        receipt.setBillStatus(Constants.STATUS_DRAFT);
        receipt.setBillType("NORMAL");
        receipt.setSupplierId(plan.getSupplierId());
        receipt.setSupplierName(s.getSupplierName());
        receipt.setWarehouseId(plan.getWarehouseId());
        receipt.setWarehouseName(w.getWarehouseName());
        receipt.setRemark("v1.1.101 XK 自动入库, 来源采购计划 " + plan.getPlanNo());

        // 6. 1 行明细 (一期简化)
        PurReceiptDetail d = new PurReceiptDetail();
        d.setLineNo(1);
        d.setProductId(plan.getProductId());
        d.setProductCode(plan.getProductCode());
        d.setProductName(plan.getProductName());
        d.setSpec(plan.getSpec());
        d.setUnitId(plan.getUnitId());
        d.setUnitName(plan.getUnitName());
        d.setQty(plan.getPlanQty() == null ? BigDecimal.ZERO : plan.getPlanQty());
        d.setPrice(BigDecimal.ZERO);  // 一期: 价格为 0, 财务在 PC 端补录
        d.setTaxRate(s.getTaxRate() == null ? new BigDecimal("13.00") : s.getTaxRate());
        d.setAmount(BigDecimal.ZERO);
        d.setTaxAmount(BigDecimal.ZERO);
        d.setAmountTax(BigDecimal.ZERO);
        d.setBatchNo(iqc.getBatchNo());
        receipt.setDetails(java.util.Collections.singletonList(d));

        // 7. 汇总
        receipt.setTotalQty(d.getQty());
        receipt.setTotalAmount(BigDecimal.ZERO);
        receipt.setTaxAmount(BigDecimal.ZERO);
        receipt.setTotalAmountTax(BigDecimal.ZERO);
        receipt.setPaidAmount(BigDecimal.ZERO);

        // 8. XK 冗余快照 (前端列表展示 + 审计追溯)
        receipt.setInspectionReportNo(iqc.getBillNo());
        receipt.setInspectionResult(Constants.RESULT_PASS);
        receipt.setSampleQty(iqc.getSampleQty());
        receipt.setInspectorName(iqc.getInspectorName());

        // 9. 落库
        receiptMapper.insert(receipt);
        d.setId(null);
        d.setReceiptId(receipt.getId());
        receiptDetailMapper.insert(d);

        // 10. 更新 plan 状态 INBOUNDED
        inboundPlanService.updateStatus(planId, "INBOUNDED");
        log.info("[PurReceipt] createFromIqc: planId={} iqcId={} → receiptId={} billNo={}",
                planId, iqcId, receipt.getId(), receipt.getBillNo());
        return receipt.getId();
    }

    /**
     * v1.1.101 订单为源 — 从采购订单 + 已合格 IQC 自动建 DRAFT 采购入库单 (完全对齐 PC).
     * 与 createFromIqc 平行: 源上下文从 PurInboundPlan 换为 PurOrder, 明细按订单全部行展开.
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createFromOrder(Long orderId, Long iqcId) {
        permService.requirePerm("purchase:receipt:add");
        // 1. 硬门禁: IQC 已审核 PASS (订单版)
        if (!qmsInspectionService.hasPassedIqcForOrder(orderId, iqcId)) {
            throw BizException.of("IQC 检验未合格或未审核, 不能入库");
        }
        // 2. 订单
        com.industrial.erp.modules.purchase.entity.PurOrder order = orderMapper.selectById(orderId);
        if (order == null) throw BizException.of("采购订单不存在");
        // 3. 供应商合格名录
        BaseSupplier s = supplierMapper.selectById(order.getSupplierId());
        if (s == null) throw BizException.of("供应商不存在");
        if (s.getStatus() != null && s.getStatus() != 1) {
            throw BizException.of("XK: 供应商 " + s.getSupplierName() + " 当前暂停供货, 不能入库");
        }
        // 4. 仓库
        if (order.getWarehouseId() == null) throw BizException.of("采购订单未指定仓库, 请先在 PC 端补录");
        BaseWarehouse w = warehouseMapper.selectById(order.getWarehouseId());
        if (w == null) throw BizException.of("仓库不存在");

        // 5. 构造 head
        PurReceipt receipt = new PurReceipt();
        receipt.setBillNo(billNoGenerator.generate(Constants.BILL_RKP));
        receipt.setBillDate(LocalDate.now());
        receipt.setBillStatus(Constants.STATUS_DRAFT);
        receipt.setBillType("NORMAL");
        receipt.setSupplierId(order.getSupplierId());
        receipt.setSupplierName(s.getSupplierName());
        receipt.setWarehouseId(order.getWarehouseId());
        receipt.setWarehouseName(w.getWarehouseName());
        receipt.setOrderId(orderId);
        receipt.setRemark("v1.1.101 XK 自动入库, 来源采购订单 " + order.getBillNo());

        // 6. 明细: 按订单全部行展开 (一期价格为 0, 财务 PC 补录; 数量 = 订单行 qty - 已入)
        java.util.List<com.industrial.erp.modules.purchase.entity.PurOrderDetail> odtls =
                orderDetailMapper.selectByOrderId(orderId);
        if (odtls == null || odtls.isEmpty()) throw BizException.of("采购订单无明细行, 不能入库");
        java.util.List<PurReceiptDetail> dtls = new java.util.ArrayList<>();
        java.math.BigDecimal totalQty = java.math.BigDecimal.ZERO;
        int line = 1;
        for (com.industrial.erp.modules.purchase.entity.PurOrderDetail od : odtls) {
            PurReceiptDetail d = new PurReceiptDetail();
            d.setLineNo(line++);
            d.setProductId(od.getProductId());
            d.setProductCode(od.getProductCode());
            d.setProductName(od.getProductName());
            d.setSpec(od.getSpec());
            d.setUnitId(od.getUnitId());
            d.setUnitName(od.getUnitName());
            java.math.BigDecimal remain = od.getQty() == null ? java.math.BigDecimal.ZERO
                    : od.getQty().subtract(od.getInQty() == null ? java.math.BigDecimal.ZERO : od.getInQty());
            if (remain.compareTo(java.math.BigDecimal.ZERO) < 0) remain = java.math.BigDecimal.ZERO;
            d.setQty(remain);
            d.setPrice(java.math.BigDecimal.ZERO);  // 一期价格为 0
            d.setTaxRate(s.getTaxRate() == null ? new java.math.BigDecimal("13.00") : s.getTaxRate());
            d.setAmount(java.math.BigDecimal.ZERO);
            d.setTaxAmount(java.math.BigDecimal.ZERO);
            d.setAmountTax(java.math.BigDecimal.ZERO);
            d.setBatchNo(null);
            dtls.add(d);
            totalQty = totalQty.add(remain);
        }
        receipt.setDetails(dtls);

        // 7. 汇总
        receipt.setTotalQty(totalQty);
        receipt.setTotalAmount(java.math.BigDecimal.ZERO);
        receipt.setTaxAmount(java.math.BigDecimal.ZERO);
        receipt.setTotalAmountTax(java.math.BigDecimal.ZERO);
        receipt.setPaidAmount(java.math.BigDecimal.ZERO);

        // 8. XK 冗余快照
        com.industrial.erp.modules.qms.entity.MesQualityInspection iqc =
                qmsInspectionService.findIqcByOrderId(orderId);
        if (iqc != null) {
            receipt.setInspectionReportNo(iqc.getBillNo());
            receipt.setInspectionResult(Constants.RESULT_PASS);
            receipt.setSampleQty(iqc.getSampleQty());
            receipt.setInspectorName(iqc.getInspectorName());
        }

        // 9. 落库
        receiptMapper.insert(receipt);
        for (PurReceiptDetail d : dtls) {
            d.setId(null);
            d.setReceiptId(receipt.getId());
            receiptDetailMapper.insert(d);
        }
        log.info("[PurReceipt] createFromOrder: orderId={} iqcId={} → receiptId={} billNo={} lines={}",
                orderId, iqcId, receipt.getId(), receipt.getBillNo(), dtls.size());
        return receipt.getId();
    }

    /**
     * v1.1.122: 该明细行商品是否食品级 (base_product.is_food_grade=1).
     * 复用 QmsInspectionService.isFoodGradeProduct (与生产侧 v1.1.102 豁免口径一致, 零新发明).
     */
    private boolean isFoodGradeLine(PurReceiptDetail d) {
        return d != null && d.getProductId() != null
                && qmsInspectionService.isFoodGradeProduct(d.getProductId());
    }

    /**
     * v1.1.122: 该入库单是否含任一行食品级. 驱动 XK 门禁分流:
     * 含食品级行 → 走 XK IQC 三步门禁 + 供应商合格名录硬门禁; 全非食品 → 整单豁免.
     * 供应商是单头字段 (无法按行拆), 故判定落在「该单是否含食品级行」单头级.
     */
    private boolean hasFoodGradeLine(java.util.List<PurReceiptDetail> details) {
        if (details == null) return false;
        for (PurReceiptDetail d : details) {
            if (isFoodGradeLine(d)) return true;
        }
        return false;
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PurReceiptService.class);
}
