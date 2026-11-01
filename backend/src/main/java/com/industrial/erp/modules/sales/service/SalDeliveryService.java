package com.industrial.erp.modules.sales.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.common.Constants;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseCustomer;
import com.industrial.erp.modules.base.entity.BaseProduct;
import com.industrial.erp.modules.base.entity.BaseWarehouse;
import com.industrial.erp.modules.base.mapper.BaseCustomerMapper;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.industrial.erp.modules.base.mapper.BaseWarehouseMapper;
import com.industrial.erp.modules.base.service.ProductAttrInjector;
import com.industrial.erp.modules.finance.service.FinArapService;
import com.industrial.erp.modules.inventory.mapper.InvStockMapper;
import com.industrial.erp.modules.inventory.mapper.InvLedgerMapper;
import com.industrial.erp.modules.inventory.service.StockService;
import com.industrial.erp.modules.sales.entity.SalDelivery;
import com.industrial.erp.modules.sales.entity.SalDeliveryDetail;
import com.industrial.erp.modules.sales.entity.SalOrder;
import com.industrial.erp.modules.sales.entity.SalOrderDetail;
import com.industrial.erp.modules.sales.mapper.SalDeliveryDetailMapper;
import com.industrial.erp.modules.sales.mapper.SalDeliveryMapper;
import com.industrial.erp.modules.sales.mapper.SalOrderDetailMapper;
import com.industrial.erp.modules.sales.mapper.SalOrderMapper;
import com.industrial.erp.modules.system.annotation.OperLog;
import com.industrial.erp.modules.system.aspect.OperLogPublisher;
import com.industrial.erp.utils.BillNoGenerator;
import com.industrial.erp.security.PermissionService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 销售出库服务
 * 业务: 新建出库单 -> 校验信用 + 价格 -> 审核 -> 库存出库(锁) -> 应收台账 -> 计算毛利
 */
@Service
public class SalDeliveryService {

    public SalDeliveryService(SalDeliveryMapper deliveryMapper, SalDeliveryDetailMapper detailMapper, BaseCustomerMapper customerMapper, BaseWarehouseMapper warehouseMapper, BillNoGenerator billNoGenerator, StockService stockService, FinArapService arapService, PermissionService permService, SalOrderDetailMapper orderDetailMapper, OperLogPublisher operLogPublisher, BaseProductMapper productMapper, SalOrderMapper orderMapper, com.industrial.erp.modules.mes.service.MesReportService mesReportService, InvStockMapper inventoryStockMapper, InvLedgerMapper ledgerMapper) {
        this.deliveryMapper = deliveryMapper;
        this.detailMapper = detailMapper;
        this.customerMapper = customerMapper;
        this.warehouseMapper = warehouseMapper;
        this.billNoGenerator = billNoGenerator;
        this.stockService = stockService;
        this.arapService = arapService;
        this.permService = permService;
        this.orderDetailMapper = orderDetailMapper;
        this.operLogPublisher = operLogPublisher;
        this.productMapper = productMapper;
        this.orderMapper = orderMapper;
        this.mesReportService = mesReportService;
        this.inventoryStockMapper = inventoryStockMapper;
        this.ledgerMapper = ledgerMapper;
    }

    private static final Logger log = LoggerFactory.getLogger(SalDeliveryService.class);

    private final SalDeliveryMapper deliveryMapper;
    private final SalDeliveryDetailMapper detailMapper;
    private final BaseCustomerMapper customerMapper;
    private final BaseWarehouseMapper warehouseMapper;
    private final BaseProductMapper productMapper;
    private final SalOrderMapper orderMapper;
    private final SalOrderDetailMapper orderDetailMapper;
    private final BillNoGenerator billNoGenerator;
    private final StockService stockService;
    private final FinArapService arapService;
    private final PermissionService permService;
    private final OperLogPublisher operLogPublisher;
    /** v1.1.117: 多批次复选拆分时读各批号当前库存. */
    private final InvStockMapper inventoryStockMapper;
    /** v1.1.117: 反审核按台账实际出库分布逐批回补. */
    private final InvLedgerMapper ledgerMapper;
    /** v1.1.99 C2: 销售出库登记 FG 批次→客户 流向 (打通追溯出口). */
    private final com.industrial.erp.modules.mes.service.MesReportService mesReportService;

    public IPage<SalDelivery> page(Integer pageNum, Integer pageSize, String billNo, Long customerId, String customerName, String billStatus, String productName) {
        permService.requirePerm("sales:delivery:list");
        Page<SalDelivery> p = new Page<>(pageNum, pageSize);
        // productName/customerName EXISTS 子查询已下沉到 mapper XML, 避免 QueryWrapper.apply() 字符串拼接反模式 (P1-3)
        IPage<SalDelivery> result = deliveryMapper.selectPageWithProduct(p, billNo, customerId, customerName, billStatus, productName);
        // v1.1.42: 批量注入交货方式中文标签 (列表页展示用, 与 detail() 保持一致)
        if (result.getRecords() != null) {
            for (SalDelivery d : result.getRecords()) {
                if (d.getDeliveryMethod() != null) d.setDeliveryMethodLabel(mapDeliveryMethod(d.getDeliveryMethod()));
            }
        }
        return result;
    }

    public SalDelivery detail(Long id) {
        SalDelivery d = deliveryMapper.selectById(id);
        if (d != null) d.setDetails(detailMapper.selectByDeliveryId(id));
        // 批量注入商品色号 + 型号 (避免 N+1 查询: 之前每行 selectById, 现在 selectBatchIds)
        // v1.1.12+: 同时注入 model (型号) — 打印模板"型号"列需要此字段, 否则渲染为空
        if (d != null && d.getDetails() != null) {
            ProductAttrInjector.injectColorNo(productMapper, d.getDetails(),
                    r -> ((SalDeliveryDetail) r).getProductId(),
                    (r, v) -> ((SalDeliveryDetail) r).setPColorNo(v));
            ProductAttrInjector.inject(productMapper, d.getDetails(),
                    r -> ((SalDeliveryDetail) r).getProductId(),
                    (r, v) -> ((SalDeliveryDetail) r).setPModel(v),
                    p -> p.getModel());
            // v1.1.47: 注入采购订单号 (poNo) — 从源订单自动带入, 供打印模板显示
            for (SalDeliveryDetail dDetail : d.getDetails()) {
                if (dDetail.getOrderDetailId() != null
                        && StrUtil.isBlank(dDetail.getPoNo())) {
                    SalOrderDetail od = orderDetailMapper.selectById(dDetail.getOrderDetailId());
                    if (od != null && od.getOrderId() != null) {
                        SalOrder order = orderMapper.selectById(od.getOrderId());
                        if (order != null && order.getPoNo() != null) {
                            dDetail.setPoNo(order.getPoNo());
                        }
                    }
                }
            }
        }
        // v1.1.40: 交货方式枚举值 → 中文 label (与 SalOrderService 保持一致)
        if (d != null && d.getDeliveryMethod() != null) {
            d.setDeliveryMethodLabel(mapDeliveryMethod(d.getDeliveryMethod()));
        }
        return d;
    }

    /** v1.1.38: 按源订单 ID 分页查询关联出库单 (追溯入口) */
    public IPage<SalDelivery> pageByOrderId(Integer pageNum, Integer pageSize, Long orderId) {
        permService.requirePerm("sales:delivery:list");
        Page<SalDelivery> p = new Page<>(pageNum, pageSize);
        return deliveryMapper.selectPageByOrderId(p, orderId);
    }

    /** 查询指定客户+商品的上次订单单价 */
    public BigDecimal getLastPrice(Long customerId, Long productId) {
        BigDecimal price = orderDetailMapper.selectLastPriceByCustomerAndProduct(customerId, productId);
        return price != null ? price : BigDecimal.ZERO;
    }

    /**
     * 查询该客户最近 50 条历史销售出库明细 (按 bill_date DESC).
     * 用于销售出库新增弹窗底部"该客户历史销售产品"列表. v1.1.7+ 新增.
     */
    public java.util.List<java.util.Map<String, Object>> getCustomerHistoryProducts(Long customerId) {
        if (customerId == null) return java.util.Collections.emptyList();
        return detailMapper.selectCustomerHistoryProducts(customerId);
    }

    /**
     * v1.1.67: 从商品主数据 (base_product) 批量补全出库单明细行的 productCode/productName/spec/unitId/unitName.
     * <p>背景: App 端「扫码出库」提交时只带 productId (连 spec/unit 都常缺), 若后端原样 insert,
     * 这些真实 DB 列落库为 NULL, PC 端打印送货单时"商品编码/商品名称"栏就空白.
     * 与 {@link ProductAttrInjector} 同款 selectBatchIds 批量模式, 一次查全, 无 N+1.
     * <p>策略: **仅在明细行对应字段为空 (blank) 时才回填**, 不覆盖 PC 端已显式录入的值,
     * 保证 PC 手工开单 / 订单生成 (已带全字段) 行为不变, 只对 App 等"瘦"客户端兜底.
     */
    private void enrichDetailsFromProduct(List<SalDeliveryDetail> details) {
        if (details == null || details.isEmpty()) return;
        Set<Long> pids = new HashSet<>();
        for (SalDeliveryDetail d : details) {
            if (d.getProductId() != null) pids.add(d.getProductId());
        }
        if (pids.isEmpty()) return;
        List<BaseProduct> prods = productMapper.selectBatchIds(pids);
        if (prods == null || prods.isEmpty()) return;
        Map<Long, BaseProduct> byId = prods.stream()
                .collect(Collectors.toMap(BaseProduct::getId, Function.identity(), (a, b) -> a));
        for (SalDeliveryDetail d : details) {
            BaseProduct p = d.getProductId() == null ? null : byId.get(d.getProductId());
            if (p == null) continue;
            if (StrUtil.isBlank(d.getProductCode())) d.setProductCode(p.getProductCode());
            if (StrUtil.isBlank(d.getProductName())) d.setProductName(p.getProductName());
            if (StrUtil.isBlank(d.getSpec())) d.setSpec(p.getSpec());
            if (d.getUnitId() == null) d.setUnitId(p.getMainUnitId());
            // unitName 不在 base_product 上 (需查单位表), 由 App payload 携带 (v1.1.67 App 端已补全),
            // 此处不再额外引入 BaseUnitMapper 依赖. 仍缺失时打印单位列允许为空.
        }
    }

    @Transactional(rollbackFor = Exception.class)
    @OperLog(module="销售出库", businessType="ADD", saveParam=true)
    public void add(SalDelivery delivery) {
        permService.requirePerm("sales:delivery:add");
        if (delivery.getBillDate() == null) delivery.setBillDate(LocalDate.now());
        if (StrUtil.isBlank(delivery.getBillNo())) {
            delivery.setBillNo(billNoGenerator.generate(Constants.BILL_CKP));
        }
        if (StrUtil.isBlank(delivery.getBillStatus())) delivery.setBillStatus(Constants.STATUS_DRAFT);
        if (StrUtil.isBlank(delivery.getBillType())) delivery.setBillType("NORMAL");

        BaseCustomer c = customerMapper.selectById(delivery.getCustomerId());
        if (c == null) throw BizException.of("客户不存在");
        delivery.setCustomerName(c.getCustomerName());
        // 校验仓库
        BaseWarehouse w = warehouseMapper.selectById(delivery.getWarehouseId());
        if (w == null) throw BizException.of("仓库不存在");

        // v1.1.41: 从源订单自动带入交货方式 (出库单主表无 poNo 列, poNo 写入明细行)
        if (delivery.getOrderId() != null) {
            SalOrder order = orderMapper.selectById(delivery.getOrderId());
            if (order != null && StrUtil.isBlank(delivery.getDeliveryMethod())) {
                delivery.setDeliveryMethod(order.getDeliveryMethod());
            }
        }

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        // v1.1.19+: price = 含税单价. taxAmount 字段保留但不再计算 (留作将来报税报表),
        // totalAmountTax = totalAmount = 开单金额 = 应收金额. AR 直接拿 totalAmountTax.
        BigDecimal totalAmountTax = BigDecimal.ZERO;
        int line = 0;
        for (SalDeliveryDetail d : delivery.getDetails()) {
            // S2 安全红线: 数量/单价基本守卫 — 防止 0 或负数行算出 0/负 AR 金额入库.
            if (d.getQty() == null || d.getQty().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BizException("第 " + (line + 1) + " 行数量必须大于 0, 当前=" + d.getQty());
            }
            if (d.getPrice() == null || d.getPrice().compareTo(BigDecimal.ZERO) < 0) {
                throw new BizException("第 " + (line + 1) + " 行单价不能为负数, 当前=" + d.getPrice());
            }
            d.setLineNo(++line);
            if (d.getTaxRate() == null) d.setTaxRate(c.getTaxRate() == null ? new BigDecimal("13.00") : c.getTaxRate());
            d.setAmount(d.getPrice().multiply(d.getQty()).setScale(4, RoundingMode.HALF_UP));
            d.setTaxAmount(BigDecimal.ZERO);
            d.setAmountTax(d.getAmount());
            totalQty = totalQty.add(d.getQty());
            totalAmount = totalAmount.add(d.getAmount());
            totalAmountTax = totalAmountTax.add(d.getAmount());
        }
        // 抹零
        BigDecimal tail = delivery.getTailAmount() == null ? BigDecimal.ZERO : delivery.getTailAmount();
        // 整单折扣
        BigDecimal discount = delivery.getDiscountAmount() == null ? BigDecimal.ZERO : delivery.getDiscountAmount();
        totalAmount = totalAmount.subtract(discount).subtract(tail);
        totalAmountTax = totalAmountTax.subtract(discount).subtract(tail);

        delivery.setTotalQty(totalQty);
        delivery.setTotalAmount(totalAmount);
        delivery.setTaxAmount(BigDecimal.ZERO);
        delivery.setTotalAmountTax(totalAmountTax);
        delivery.setReceivedAmount(BigDecimal.ZERO);
        delivery.setCostAmount(BigDecimal.ZERO);
        delivery.setProfitAmount(BigDecimal.ZERO);

        // v1.1.67: 从商品主数据补全明细行 productCode/productName/spec/unitId/unitName.
        // App 扫码出库等客户端只传 productId (可能连 spec/unit 都没带), 若后端原样 insert,
        // 这些列落库为 NULL, 后续 PC 端打印送货单时"商品编码/商品名称"栏空白.
        // 型号(model) 是 transient 在 detail() 里从 base_product 实时注入, 所以打印能显示;
        // 而编码/名称是真实 DB 列, 不在商品主数据回填就会永久缺失.
        enrichDetailsFromProduct(delivery.getDetails());

        deliveryMapper.insert(delivery);
        for (SalDeliveryDetail d : delivery.getDetails()) {
            d.setId(null);
            d.setDeliveryId(delivery.getId());
            // v1.1.41: 从源订单自动带入采购订单号到明细行
            if (d.getOrderDetailId() != null && StrUtil.isBlank(d.getPoNo())) {
                SalOrderDetail od = orderDetailMapper.selectById(d.getOrderDetailId());
                if (od != null && od.getOrderId() != null) {
                    SalOrder order = orderMapper.selectById(od.getOrderId());
                    if (order != null && order.getPoNo() != null) {
                        d.setPoNo(order.getPoNo());
                    }
                }
            }
            detailMapper.insert(d);
        }
    }

    /**
     * 修改销售出库单 (仅 DRAFT 状态可改)
     */
    @Transactional(rollbackFor = Exception.class)
    @OperLog(module="销售出库", businessType="EDIT", saveParam=true)
    public void update(SalDelivery delivery) {
        permService.requirePerm("sales:delivery:edit");
        SalDelivery origin = deliveryMapper.selectById(delivery.getId());
        if (origin == null) throw BizException.of("出库单不存在");
        if (!Constants.STATUS_DRAFT.equals(origin.getBillStatus())) {
            throw BizException.of("只有草稿状态可修改");
        }
        BaseCustomer c = customerMapper.selectById(delivery.getCustomerId());
        if (c == null) throw BizException.of("客户不存在");
        delivery.setCustomerName(c.getCustomerName());
        BaseWarehouse w = warehouseMapper.selectById(delivery.getWarehouseId());
        if (w == null) throw BizException.of("仓库不存在");

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        // v1.1.19+: tax-inclusive price, taxAmount=0, totalAmountTax=totalAmount=开单金额
        BigDecimal totalAmountTax = BigDecimal.ZERO;
        int line = 0;
        for (SalDeliveryDetail d : delivery.getDetails()) {
            // S2 安全红线: 数量/单价基本守卫 — 防止 0 或负数行算出 0/负 AR 金额入库.
            if (d.getQty() == null || d.getQty().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BizException("第 " + (line + 1) + " 行数量必须大于 0, 当前=" + d.getQty());
            }
            if (d.getPrice() == null || d.getPrice().compareTo(BigDecimal.ZERO) < 0) {
                throw new BizException("第 " + (line + 1) + " 行单价不能为负数, 当前=" + d.getPrice());
            }
            d.setLineNo(++line);
            if (d.getTaxRate() == null) d.setTaxRate(c.getTaxRate() == null ? new BigDecimal("13.00") : c.getTaxRate());
            d.setAmount(d.getPrice().multiply(d.getQty()).setScale(4, RoundingMode.HALF_UP));
            d.setTaxAmount(BigDecimal.ZERO);
            d.setAmountTax(d.getAmount());
            totalQty = totalQty.add(d.getQty());
            totalAmount = totalAmount.add(d.getAmount());
            totalAmountTax = totalAmountTax.add(d.getAmount());
        }
        // 抹零/折扣
        BigDecimal tail = delivery.getTailAmount() == null ? BigDecimal.ZERO : delivery.getTailAmount();
        BigDecimal discount = delivery.getDiscountAmount() == null ? BigDecimal.ZERO : delivery.getDiscountAmount();
        totalAmount = totalAmount.subtract(discount).subtract(tail);
        totalAmountTax = totalAmountTax.subtract(discount).subtract(tail);

        delivery.setTotalQty(totalQty);
        delivery.setTotalAmount(totalAmount);
        delivery.setTaxAmount(BigDecimal.ZERO);
        delivery.setTotalAmountTax(totalAmountTax);
        // 主表不可变字段保持原值
        delivery.setBillNo(origin.getBillNo());
        delivery.setBillDate(origin.getBillDate());
        delivery.setBillStatus(origin.getBillStatus());
        delivery.setBillType(origin.getBillType());
        delivery.setReceivedAmount(origin.getReceivedAmount());

        // v1.1.67: 与 add() 一致, 从商品主数据补全明细行 (App 扫码出库客户端可能没带这些字段)
        enrichDetailsFromProduct(delivery.getDetails());

        deliveryMapper.updateById(delivery);
        detailMapper.delete(new LambdaQueryWrapper<SalDeliveryDetail>().eq(SalDeliveryDetail::getDeliveryId, delivery.getId()));
        for (SalDeliveryDetail d : delivery.getDetails()) {
            d.setId(null);
            d.setDeliveryId(delivery.getId());
            detailMapper.insert(d);
        }
    }

    /**
     * 删除销售出库单 (仅 DRAFT 状态可删)
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        permService.requirePerm("sales:delivery:delete");
        SalDelivery d = deliveryMapper.selectById(id);
        if (d == null) throw BizException.of("出库单不存在");
        if (!Constants.STATUS_DRAFT.equals(d.getBillStatus())) {
            throw BizException.of("只有草稿状态可删除");
        }
        // 取明细快照
        List<SalDeliveryDetail> details = detailMapper.selectByDeliveryId(id);
        // 软删除明细
        if (details != null && !details.isEmpty()) {
            detailMapper.update(null, new LambdaUpdateWrapper<SalDeliveryDetail>()
                    .eq(SalDeliveryDetail::getDeliveryId, id).set(SalDeliveryDetail::getDeleted, 1));
        }
        // 软删除主表
        deliveryMapper.update(null, new LambdaUpdateWrapper<SalDelivery>()
                .eq(SalDelivery::getId, id).set(SalDelivery::getDeleted, 1));
        // 写操作日志
        operLogPublisher.publishDeleteSnapshot("销售出库", String.valueOf(id), d, details);
    }

    /**
     * 审核销售出库单
     * 流程: 校验信用 -> 库存出库(锁) -> 应收台账 -> 计算毛利
     */
    @Transactional(rollbackFor = Exception.class)
    @OperLog(module="销售出库", businessType="CHECK", saveParam=true)
    public void check(Long id) {
        doCheck(id, null);
    }

    /**
     * v1.1.152: 审核时可指定每行明细的出库批次与数量 (PC 审核弹窗).
     * <p>不传分配 (App / 旧客户端) 时沿用旧逻辑: 按明细 batch_no 勾选顺序、依各批当前库存自动拆分整行数量.
     */
    @Transactional(rollbackFor = Exception.class)
    @OperLog(module="销售出库", businessType="CHECK", saveParam=true)
    public void check(Long id, com.industrial.erp.modules.sales.dto.SalDeliveryCheckDTO req) {
        doCheck(id, req);
    }

    private void doCheck(Long id, com.industrial.erp.modules.sales.dto.SalDeliveryCheckDTO req) {
        permService.requirePerm("sales:delivery:check");
        SalDelivery d = deliveryMapper.selectById(id);
        if (d == null) throw BizException.of("出库单不存在");
        if (!Constants.STATUS_DRAFT.equals(d.getBillStatus())) {
            throw BizException.of("只有草稿状态可审核");
        }
        // 并发防护: 原子抢占 DRAFT -> CHECKED。两个请求同时审核时只有一个能更新成功,
        // 另一个会阻塞到前者提交后返回 0, 从而避免重复扣库存/重复生成应收。
        int claimed = deliveryMapper.update(null, new LambdaUpdateWrapper<SalDelivery>()
                .eq(SalDelivery::getId, id)
                .eq(SalDelivery::getBillStatus, Constants.STATUS_DRAFT)
                .eq(SalDelivery::getDeleted, 0)
                .set(SalDelivery::getBillStatus, Constants.STATUS_CHECKED));
        if (claimed == 0) {
            throw BizException.of("单据已被审核或状态已变更，请刷新后重试");
        }
        // 1. 信用校验
        BaseCustomer c = customerMapper.selectById(d.getCustomerId());
        if (c != null && c.getCreditLimit() != null && c.getCreditLimit().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal will = (c.getCreditUsed() == null ? BigDecimal.ZERO : c.getCreditUsed()).add(d.getTotalAmountTax());
            if (will.compareTo(c.getCreditLimit()) > 0) {
                throw BizException.of("客户信用额度不足, 信用额度=" + c.getCreditLimit()
                        + ", 已用=" + c.getCreditUsed() + ", 需新增=" + d.getTotalAmountTax());
            }
            // 预占
            customerMapper.incrCreditUsed(d.getCustomerId(), d.getTotalAmountTax());
        }

        // 2. 库存出库 (每行, 严格防止负库存).
        // v1.1.117: batchNo 支持 "|" 分隔的多批次复选 — 按勾选顺序把整行 qty 自动按库存
        // 拆分到各批号 (先扣满第一个, 不够再落到下一个), 逐批 outStock 累加成本.
        // 单批次/空批次走原逻辑 (det.getBatchNo() 原样), 存量单零回归.
        List<SalDeliveryDetail> details = detailMapper.selectByDeliveryId(id);
        BaseWarehouse wh = warehouseMapper.selectById(d.getWarehouseId());
        // v1.1.152: 前端显式指定了批次分配的行, 严格按分配出库; 其余行沿用自动拆分.
        // 分配在扣库前一次性校验完 (数量合计/批次存在/逐批可用), 校验不过不产生任何扣减.
        Map<Long, List<com.industrial.erp.modules.sales.dto.SalDeliveryCheckDTO.BatchQty>> explicit =
                resolveExplicitAllocations(d, details, req);
        BigDecimal totalCost = BigDecimal.ZERO;
        Map<Long, Map<String, BigDecimal>> outByBatchByDetail = new java.util.LinkedHashMap<>();
        for (int i = 0; i < details.size(); i++) {
            SalDeliveryDetail det = details.get(i);
            List<com.industrial.erp.modules.sales.dto.SalDeliveryCheckDTO.BatchQty> allocs = explicit.get(det.getId());
            LineOut out = (allocs == null || allocs.isEmpty())
                    ? outStockSplitBatch(d, wh, det,
                            Constants.LEDGER_SAL_DELIVERY, d.getBillNo(), d.getCustomerId(), i + 1)
                    : outStockByAllocation(d, wh, det,
                            Constants.LEDGER_SAL_DELIVERY, d.getBillNo(), d.getCustomerId(), allocs, i + 1);
            det.setCostPrice(out.cost.divide(det.getQty(), 4, RoundingMode.HALF_UP));
            det.setCostAmount(out.cost);
            // 显式分配: 把实际出库批次回写到明细 (打印/追溯/反审核按此展示)
            // 空串段表示"无批次", 按既有习惯放在末尾 (A|B|), 避免出现 "|A" 这种空段在前的写法
            if (allocs != null && !allocs.isEmpty()) {
                det.setBatchNo(joinBatchNoForDisplay(out.byBatch));
            }
            detailMapper.updateById(det);
            outByBatchByDetail.put(det.getId(), out.byBatch);
            totalCost = totalCost.add(out.cost);
        }

        // 3. 更新主表 (成本/毛利)
        BigDecimal profit = d.getTotalAmount().subtract(totalCost);
        SalDelivery upd = new SalDelivery();
        upd.setId(id);
        upd.setCostAmount(totalCost);
        upd.setProfitAmount(profit);
        deliveryMapper.updateById(upd);

        // 4. 应收台账
        arapService.createArForSales(d, details);

        // 5. v1.1.41: 按 order_detail_id 累计已审核出库数量回写到 sal_order_detail.out_qty
        for (SalDeliveryDetail det : details) {
            if (det.getOrderDetailId() == null) continue;
            BigDecimal shipped = orderDetailMapper.selectShippedQtyByOrderDetailId(det.getOrderDetailId());
            if (shipped != null && shipped.compareTo(BigDecimal.ZERO) > 0) {
                SalOrderDetail od = orderDetailMapper.selectById(det.getOrderDetailId());
                if (od != null) {
                    od.setOutQty(shipped);
                    orderDetailMapper.updateById(od);
                }
            }
        }

        // 6. v1.1.99 C2: 销售出库 = FG 成品批次流向客户的追溯出口时机.
        // 每个有 batchNo 的明细行登记 FG 批次 + mes_batch_link(FG_OUT, customer_id).
        // MES 是副作用登记, 失败只记日志不阻塞出库主流程 (库存/成本/AR 已落库).
        // v1.1.117: 多批次复选 (batchNo 含 "|") 逐批登记, 单批原样.
        // v1.1.152: 以 outStock 实际出库的批次数量登记 (旧版多批次时整行 qty 只记到首个批次)
        for (Map<String, BigDecimal> byBatch : outByBatchByDetail.values()) {
            for (Map.Entry<String, BigDecimal> b : byBatch.entrySet()) {
                String bn = b.getKey();
                // 空串 = 无批次, mes_batch 无此主数据, 不登记流向 (与存量单 batch_no 空一致)
                if (bn == null || bn.isEmpty()) continue;
                BigDecimal q = b.getValue();
                if (q == null || q.compareTo(BigDecimal.ZERO) <= 0) continue;
                try {
                    mesReportService.registerFgOutToCustomer(bn, d.getCustomerId(), q, null, d.getBillNo());
                } catch (Exception e) {
                    log.warn("[MES] 销售出库登记 FG 批次→客户失败 (不阻塞出库) billNo={} batchNo={} err={}",
                            d.getBillNo(), bn, e.getMessage());
                }
            }
        }

        log.info("销售出库审核: billNo={}, amount={}, cost={}, profit={}",
                d.getBillNo(), d.getTotalAmount(), totalCost, profit);
    }

    /**
     * 一行明细的出库结果: 累计成本 (供毛利) + 各批次实际出库量 (供 MES 流向登记).
     */
    private static final class LineOut {
        private final BigDecimal cost;
        private final Map<String, BigDecimal> byBatch = new java.util.LinkedHashMap<>();

        private LineOut(BigDecimal cost) { this.cost = cost; }

        private LineOut(BigDecimal cost, Map<String, BigDecimal> byBatch) {
            this.cost = cost;
            this.byBatch.putAll(byBatch);
        }
    }

    /**
     * v1.1.117: 按 "|" 分隔的多批次, 把整行 qty 自动按库存拆分到各批号后逐批出库.
     * <p>分配规则 (按勾选顺序): 第 i 个批号先扣到它自身库存上限, 剩余落到下一个批号;
     * 所有批号都扣不完才报错. 单批次/空批次 走原有整行单次 outStock (零回归).
     */
    private LineOut outStockSplitBatch(SalDelivery d, BaseWarehouse wh, SalDeliveryDetail det,
                                       String billType, String billNo, Long customerId, int lineNo) {
        List<String> batches = splitBatchNo(det.getBatchNo());
        // 单批次 / 无批次: 原逻辑整行一次出库 (行为完全不变)
        if (batches.size() <= 1) {
            String bn = batches.isEmpty() ? null : batches.get(0);
            BigDecimal c = stockService.outStock(
                    billType, d.getId(), billNo, det.getId(),
                    d.getWarehouseId(), wh == null ? null : wh.getWarehouseName(), det.getLocationId(), det.getLocationName(),
                    det.getProductId(), det.getUnitId(), det.getUnitName(), bn,
                    det.getQty(), det.getPrice(), billNo,
                    null, customerId, "销售出库 " + billNo
            );
            LineOut out = new LineOut(c);
            out.byBatch.put(bn == null ? "" : bn, det.getQty());
            return out;
        }

        // 多批次: 依次取各批号当前库存 (库存行), 按勾选顺序分配 qty.
        Map<String, BigDecimal> availByBatch = loadBatchStockMap(d.getWarehouseId(), det.getProductId());
        BigDecimal remaining = det.getQty();
        BigDecimal totalCost = BigDecimal.ZERO;
        List<String> missing = new java.util.ArrayList<>();
        LineOut out = new LineOut(totalCost);
        for (int i = 0; i < batches.size() && remaining.compareTo(BigDecimal.ZERO) > 0; i++) {
            String bn = batches.get(i);
            BigDecimal avail = availByBatch.getOrDefault(bn, BigDecimal.ZERO);
            BigDecimal take = remaining.min(avail);
            if (take.compareTo(BigDecimal.ZERO) > 0) {
                // outStock 内部按 unitId 折算到主单位再扣, 这里 qty 用行原始单位, 与单批路径一致
                BigDecimal c = stockService.outStock(
                        billType, d.getId(), billNo, det.getId(),
                        d.getWarehouseId(), wh == null ? null : wh.getWarehouseName(), det.getLocationId(), det.getLocationName(),
                        det.getProductId(), det.getUnitId(), det.getUnitName(), bn,
                        take, det.getPrice(), billNo,
                        null, customerId, "销售出库 " + billNo + " (批次" + bn + ")"
                );
                out.byBatch.merge(bn, take, BigDecimal::add);
                totalCost = totalCost.add(c);
                // 同一行内同一批次被重复勾选时, 第二次起可用量已被本次占用, 需从上一次剩余里扣
                availByBatch.put(bn, avail.subtract(take));
                remaining = remaining.subtract(take);
            } else {
                missing.add(bn.isEmpty() ? "<无批次>" : bn);
            }
        }
        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            // 库存够但分布到多批后仍扣不完 — 提示哪些批号库存不足
            throw BizException.of("第 " + lineNo + " 行批次库存不足, 商品=" + productLabel(det)
                    + ", 未扣完数量=" + remaining + " (批次 " + String.join(",", missing) + " 库存不足)");
        }
        return new LineOut(totalCost, out.byBatch);
    }

    /**
     * v1.1.152: 按前端指定的「批次 → 数量」逐批出库. 校验已在 {@link #resolveExplicitAllocations} 完成.
     */
    private LineOut outStockByAllocation(SalDelivery d, BaseWarehouse wh, SalDeliveryDetail det,
                                         String billType, String billNo, Long customerId,
                                         List<com.industrial.erp.modules.sales.dto.SalDeliveryCheckDTO.BatchQty> allocs,
                                         int lineNo) {
        BigDecimal totalCost = BigDecimal.ZERO;
        LineOut out = new LineOut(totalCost);
        for (com.industrial.erp.modules.sales.dto.SalDeliveryCheckDTO.BatchQty a : allocs) {
            String bn = a.getBatchNo() == null ? "" : a.getBatchNo();
            BigDecimal c = stockService.outStock(
                    billType, d.getId(), billNo, det.getId(),
                    d.getWarehouseId(), wh == null ? null : wh.getWarehouseName(), det.getLocationId(), det.getLocationName(),
                    det.getProductId(), det.getUnitId(), det.getUnitName(), bn,
                    a.getQty(), det.getPrice(), billNo,
                    null, customerId, "销售出库 " + billNo + " (批次" + (bn.isEmpty() ? "无" : bn) + ")"
            );
            out.byBatch.merge(bn, a.getQty(), BigDecimal::add);
            totalCost = totalCost.add(c);
        }
        return new LineOut(totalCost, out.byBatch);
    }

    /**
     * v1.1.152: 校验并规整前端提交的批次分配 (在扣库前一次性做完).
     * <p>校验: 明细行属于本单 / 每批数量 > 0 / 分配合计 = 行出库数量 (0.0001 浮点尾差并入最后一笔) /
     * 批次在该仓库有库存且本次分配不超过可用量 (同商品多行共用可用量).
     * 任一不过直接抛错, 事务未做任何库存扣减.
     */
    private Map<Long, List<com.industrial.erp.modules.sales.dto.SalDeliveryCheckDTO.BatchQty>> resolveExplicitAllocations(
            SalDelivery d, List<SalDeliveryDetail> details,
            com.industrial.erp.modules.sales.dto.SalDeliveryCheckDTO req) {
        Map<Long, List<com.industrial.erp.modules.sales.dto.SalDeliveryCheckDTO.BatchQty>> out =
                new java.util.LinkedHashMap<>();
        if (req == null || req.getDetails() == null || req.getDetails().isEmpty()) return out;

        Map<Long, SalDeliveryDetail> byId = new java.util.HashMap<>();
        for (SalDeliveryDetail det : details) byId.put(det.getId(), det);
        Map<Long, List<com.industrial.erp.modules.sales.dto.SalDeliveryCheckDTO.BatchQty>> reqByDetail =
                new java.util.HashMap<>();
        for (com.industrial.erp.modules.sales.dto.SalDeliveryCheckDTO.Line line : req.getDetails()) {
            if (line == null || line.getDetailId() == null) continue;
            if (line.getAllocations() == null || line.getAllocations().isEmpty()) continue;
            if (!byId.containsKey(line.getDetailId())) {
                throw BizException.of("批次分配包含不属于本单的明细行 (ID=" + line.getDetailId() + "), 请刷新后重试");
            }
            reqByDetail.put(line.getDetailId(), line.getAllocations());
        }
        if (reqByDetail.isEmpty()) return out;

        // 可用量按 仓库+商品 缓存, 同商品多行跨行累计扣减 (key=batchNo, 空串=无批次)
        Map<String, Map<String, BigDecimal>> availByProduct = new java.util.HashMap<>();
        for (int i = 0; i < details.size(); i++) {
            SalDeliveryDetail det = details.get(i);
            List<com.industrial.erp.modules.sales.dto.SalDeliveryCheckDTO.BatchQty> allocs = reqByDetail.get(det.getId());
            if (allocs == null) continue;
            int lineNo = i + 1;

            BigDecimal sum = BigDecimal.ZERO;
            for (com.industrial.erp.modules.sales.dto.SalDeliveryCheckDTO.BatchQty a : allocs) {
                if (a == null || a.getQty() == null || a.getQty().compareTo(BigDecimal.ZERO) <= 0) {
                    throw BizException.of("第 " + lineNo + " 行批次 " + batchLabel(a == null ? null : a.getBatchNo())
                            + " 出库数量必须大于 0");
                }
                sum = sum.add(a.getQty());
            }
            BigDecimal diff = det.getQty().subtract(sum);
            if (diff.abs().compareTo(new BigDecimal("0.0001")) > 0) {
                throw BizException.of("第 " + lineNo + " 行批次分配合计=" + sum.stripTrailingZeros().toPlainString()
                        + ", 与出库数量=" + det.getQty().stripTrailingZeros().toPlainString() + " 不一致, 请重新分配");
            }
            if (diff.compareTo(BigDecimal.ZERO) != 0) {
                // 浮点尾差 (前端 0.1+0.2) 并入最后一笔, 保证合计与行数量严格相等
                com.industrial.erp.modules.sales.dto.SalDeliveryCheckDTO.BatchQty last = allocs.get(allocs.size() - 1);
                last.setQty(last.getQty().add(diff));
            }

            Map<String, BigDecimal> avail = availByProduct.computeIfAbsent(
                    det.getProductId() + "|" + d.getWarehouseId(), k -> loadBatchStockMap(d.getWarehouseId(), det.getProductId()));
            for (com.industrial.erp.modules.sales.dto.SalDeliveryCheckDTO.BatchQty a : allocs) {
                String key = a.getBatchNo() == null ? "" : a.getBatchNo().trim();
                a.setBatchNo(key);
                BigDecimal left = avail.get(key);
                if (left == null) {
                    throw BizException.of("第 " + lineNo + " 行商品=" + productLabel(det) + " 批次 " + batchLabel(key)
                            + " 在该仓库没有库存记录, 请重新选择批次");
                }
                if (a.getQty().compareTo(left) > 0) {
                    throw BizException.of("第 " + lineNo + " 行商品=" + productLabel(det) + " 批次 " + batchLabel(key)
                            + " 库存不足, 可用=" + left.stripTrailingZeros().toPlainString()
                            + ", 本次分配=" + a.getQty().stripTrailingZeros().toPlainString());
                }
                avail.put(key, left.subtract(a.getQty()));
            }
            out.put(det.getId(), allocs);
        }
        return out;
    }

    /** 仓库+商品下各批次可用量 (key=batchNo, 空串=无批次). */
    private Map<String, BigDecimal> loadBatchStockMap(Long warehouseId, Long productId) {
        Map<String, BigDecimal> map = new java.util.LinkedHashMap<>();
        List<com.industrial.erp.modules.inventory.entity.InvStock> stocks =
                inventoryStockMapper.listByWarehouseAndProduct(warehouseId, productId);
        if (stocks == null) return map;
        for (com.industrial.erp.modules.inventory.entity.InvStock s : stocks) {
            String k = s.getBatchNo() == null ? "" : s.getBatchNo();
            BigDecimal q = s.getQty() == null ? BigDecimal.ZERO : s.getQty();
            map.merge(k, q, BigDecimal::add);
        }
        return map;
    }

    private static String productLabel(SalDeliveryDetail det) {
        return det.getProductName() == null ? String.valueOf(det.getProductId()) : det.getProductName();
    }

    private static String batchLabel(String batchNo) {
        return batchNo == null || batchNo.isEmpty() ? "<无批次>" : batchNo;
    }

    /**
     * v1.1.117: 读该出库单明细在台账里<b>每个批号还净出库多少</b> (出库 − 反审核回补).
     * <p>check() 多批次复选时按库存顺序把 qty 分摊到各批次, uncheck 必须按<b>同一分布</b>回补,
     * 否则库存会凭空多出一行幽灵批次. 返回 key=batchNo (空串=无批次), value=该批净出库量.
     * <p>v1.1.152: 必须冲销已回补过的部分 — 同一张单「审核→反审核→再审核→再反审核」时,
     * 台账里同一明细会累计多条 OUT; 若只按 OUT 求和, 第二次反审核会把上一轮已回补的量
     * 再入一次库 (实测多出整行数量). 因此按批次求净额, 只有 &gt;0 的批次才回补.
     * 查不到 (历史单台账被清) 返回空 Map, 由调用方退回旧的整行回补路径.
     */
    private Map<String, BigDecimal> ledgerNetOutQtyByBatch(String billNo, SalDeliveryDetail det) {
        Map<String, BigDecimal> out = new java.util.LinkedHashMap<>();
        List<com.industrial.erp.modules.inventory.entity.InvLedger> rows = ledgerMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.industrial.erp.modules.inventory.entity.InvLedger>()
                        .eq(com.industrial.erp.modules.inventory.entity.InvLedger::getBillNo, billNo)
                        .eq(com.industrial.erp.modules.inventory.entity.InvLedger::getBillDetailId, det.getId())
        );
        if (rows == null || rows.isEmpty()) return out;
        for (com.industrial.erp.modules.inventory.entity.InvLedger l : rows) {
            int dir = l.getBizDirection() == null ? 0 : l.getBizDirection();
            if (dir != Constants.DIRECTION_OUT && dir != Constants.DIRECTION_IN) continue;
            String k = l.getBatchNo() == null ? "" : l.getBatchNo();
            BigDecimal q = l.getQty() == null ? BigDecimal.ZERO : l.getQty();
            out.merge(k, dir == Constants.DIRECTION_OUT ? q : q.negate(), BigDecimal::add);
        }
        return out;
    }

    /**
     * v1.1.152: 把各批次实际出库量合成明细 batch_no 展示串.
     * <p>无批次 (空串 key) 按既有习惯放在末尾, 例如 "PD001|" ; 只有无批次时存空串.
     */
    private static String joinBatchNoForDisplay(Map<String, BigDecimal> byBatch) {
        if (byBatch == null || byBatch.isEmpty()) return "";
        boolean hasNoBatch = false;
        java.util.List<String> names = new java.util.ArrayList<>();
        for (String k : byBatch.keySet()) {
            if (k == null || k.isEmpty()) hasNoBatch = true;
            else names.add(k);
        }
        if (names.isEmpty()) return "";
        String joined = String.join("|", names);
        return hasNoBatch ? joined + "|" : joined;
    }

    /**
     * v1.1.117: 按 "|" 拆 batchNo, 保留勾选顺序. 空串 = 合法批号「无批次」.
     * <p>⚠️ 空串是<b>合法</b>批号 — 它代表「无批次」(inv_stock.batch_no IS NULL 那行).
     * 批次下拉里 "无批次 (库存N)" 选中后 value 就是 "". 所以这里<b>不能</b>过滤空串,
     * 否则「批号A + 无批次」复选会被退化成只剩批号A, 整行数量全扣到批号A → 报库存不足.
     * 用 split("\\|", -1) 保留尾部空元素; 整串为空时返回单元素 {@code [""]},
     * 与存量单「batch_no 为空 = 走无批次」行为一致.
     */
    private static List<String> splitBatchNo(String batchNo) {
        if (batchNo == null || batchNo.trim().isEmpty()) return java.util.Collections.singletonList("");
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String p : batchNo.split("\\|", -1)) {
            out.add(p.trim());
        }
        if (out.isEmpty()) out.add("");
        return out;
    }

    /** v1.1.18+: 反审核销售出库 (CHECKED→DRAFT).
     *  同步回退: 库存入库 + 客户信用额度 + 删除原应收 AR.
     *  - 库存入库: 每行按 conversion_rate 折算到主单位 (v1.1.17+ 语义)
     *  - 信用回退: GREATEST(0, used - amount), 防御性防负
     *  - AR 删除: 校验 paidAmount/invoicedAmount 为 0, 否则抛错让用户先平账 */
    @Transactional(rollbackFor = Exception.class)
    @OperLog(module="销售出库", businessType="UNCHECK", saveParam=true)
    public void uncheck(Long id) {
        permService.requirePerm("sales:delivery:uncheck");
        SalDelivery d = deliveryMapper.selectById(id);
        if (d == null) throw BizException.of("出库单不存在");
        if (!Constants.STATUS_CHECKED.equals(d.getBillStatus())) {
            throw BizException.of("只有已审核状态可反审核");
        }

        // 1. 库存入库 (每行调 stockService.inStock, qty 自动折算到主单位)
        // v1.1.117: 多批次复选 (batchNo 含 "|") 必须按 check() 的拆分<b>镜像</b>回补:
        // check() 是按库存顺序把 qty 分摊到各批次的, uncheck 若把整串当单批入库,
        // 会凭空造出一行 batch_no='A|B' 的幽灵库存 (实测踩过), 账面数量对不上.
        // 正确做法: 读台账里该出库单每个批号的<b>实际出库量</b>, 逐批等量回补.
        List<SalDeliveryDetail> details = detailMapper.selectByDeliveryId(id);
        BaseWarehouse wh = d.getWarehouseId() == null ? null : warehouseMapper.selectById(d.getWarehouseId());
        for (SalDeliveryDetail det : details) {
            Map<String, BigDecimal> outPerBatch = ledgerNetOutQtyByBatch(d.getBillNo(), det);
            if (outPerBatch == null || outPerBatch.isEmpty()) {
                // 台账查不到 (历史单/台账被清) → 退回旧行为: 整行按单批回补
                stockService.inStock(
                        Constants.LEDGER_SAL_DELIVERY, d.getId(), d.getBillNo(), det.getId(),
                        d.getWarehouseId(), wh == null ? null : wh.getWarehouseName(),
                        det.getLocationId(), det.getLocationName(),
                        det.getProductId(), det.getUnitId(), det.getUnitName(), det.getBatchNo(),
                        det.getQty(), det.getCostPrice(), d.getBillNo(),
                        null, d.getCustomerId(), "反审核销售出库 " + d.getBillNo()
                );
                continue;
            }
            for (Map.Entry<String, BigDecimal> e : outPerBatch.entrySet()) {
                BigDecimal q = e.getValue();
                if (q == null || q.compareTo(BigDecimal.ZERO) <= 0) continue;
                stockService.inStock(
                        Constants.LEDGER_SAL_DELIVERY, d.getId(), d.getBillNo(), det.getId(),
                        d.getWarehouseId(), wh == null ? null : wh.getWarehouseName(),
                        det.getLocationId(), det.getLocationName(),
                        det.getProductId(), det.getUnitId(), det.getUnitName(), e.getKey(),
                        q, det.getCostPrice(), d.getBillNo(),
                        null, d.getCustomerId(), "反审核销售出库 " + d.getBillNo()
                );
            }
        }

        // 2. 客户信用额度回退
        if (d.getCustomerId() != null && d.getTotalAmountTax() != null
                && d.getTotalAmountTax().compareTo(BigDecimal.ZERO) > 0) {
            customerMapper.decrCreditUsed(d.getCustomerId(), d.getTotalAmountTax());
        }

        // 3. 删除原应收 AR (已核销/已开票则抛错)
        arapService.requireCancelableAndDelete(Constants.LEDGER_SAL_DELIVERY, d.getId());

        // 4. 主表状态 + 清零成本/毛利
        SalDelivery upd = new SalDelivery();
        upd.setId(id);
        upd.setBillStatus(Constants.STATUS_DRAFT);
        upd.setCostAmount(BigDecimal.ZERO);
        upd.setProfitAmount(BigDecimal.ZERO);
        deliveryMapper.updateById(upd);

        // 5. v1.1.41: 反审核后重置关联订单明细的已发货数量
        for (SalDeliveryDetail det : details) {
            if (det.getOrderDetailId() == null) continue;
            SalOrderDetail od = orderDetailMapper.selectById(det.getOrderDetailId());
            if (od != null) {
                od.setOutQty(BigDecimal.ZERO);
                orderDetailMapper.updateById(od);
            }
        }
    }

    /** v1.1.40: 交货方式枚举值 → 中文标签 (与 SalOrderService 保持一致) */
    private static String mapDeliveryMethod(String code) {
        if (code == null) return "";
        switch (code) {
            case "DELIVERY": return "送货";
            case "PICKUP":   return "自提";
            case "DIRECT":   return "专车直送";
            default:         return code;
        }
    }
}
