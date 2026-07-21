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
import com.industrial.erp.modules.finance.service.FinArapService;
import com.industrial.erp.modules.inventory.service.StockService;
import com.industrial.erp.modules.sales.entity.SalDelivery;
import com.industrial.erp.modules.sales.entity.SalDeliveryDetail;
import com.industrial.erp.modules.sales.mapper.SalDeliveryDetailMapper;
import com.industrial.erp.modules.sales.mapper.SalDeliveryMapper;
import com.industrial.erp.modules.sales.mapper.SalOrderDetailMapper;
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
import java.util.List;

/**
 * 销售出库服务
 * 业务: 新建出库单 -> 校验信用 + 价格 -> 审核 -> 库存出库(锁) -> 应收台账 -> 计算毛利
 */
@Service
public class SalDeliveryService {

    public SalDeliveryService(SalDeliveryMapper deliveryMapper, SalDeliveryDetailMapper detailMapper, BaseCustomerMapper customerMapper, BaseWarehouseMapper warehouseMapper, BillNoGenerator billNoGenerator, StockService stockService, FinArapService arapService, PermissionService permService, SalOrderDetailMapper orderDetailMapper, OperLogPublisher operLogPublisher, BaseProductMapper productMapper) {
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
    }

    private static final Logger log = LoggerFactory.getLogger(SalDeliveryService.class);

    private final SalDeliveryMapper deliveryMapper;
    private final SalDeliveryDetailMapper detailMapper;
    private final BaseCustomerMapper customerMapper;
    private final BaseWarehouseMapper warehouseMapper;
    private final BaseProductMapper productMapper;
    private final SalOrderDetailMapper orderDetailMapper;
    private final BillNoGenerator billNoGenerator;
    private final StockService stockService;
    private final FinArapService arapService;
    private final PermissionService permService;
    private final OperLogPublisher operLogPublisher;

    public IPage<SalDelivery> page(Integer pageNum, Integer pageSize, String billNo, Long customerId, String billStatus, String productName) {
        permService.requirePerm("sales:delivery:list");
        Page<SalDelivery> p = new Page<>(pageNum, pageSize);
        QueryWrapper<SalDelivery> w = new QueryWrapper<>();
        // 自定义 SQL 绕过 @TableLogic 自动过滤, 必须手动加 deleted=0
        w.eq("d.deleted", 0);
        if (StrUtil.isNotBlank(billNo)) w.like("bill_no", billNo);
        if (customerId != null) w.eq("customer_id", customerId);
        if (StrUtil.isNotBlank(billStatus)) w.eq("bill_status", billStatus);
        // 按商品名称查询: 命中任一明细行即返回, 用 EXISTS 子查询避免 GROUP BY 性能问题
        // 注: 主表别名是 d (SalDeliveryMapper), 故明细表必须用不同别名
        if (StrUtil.isNotBlank(productName)) {
            w.and(wq -> wq.apply("EXISTS (SELECT 1 FROM sal_delivery_detail dt LEFT JOIN base_product p ON p.id = dt.product_id " +
                    "WHERE dt.delivery_id = d.id AND p.product_name LIKE CONCAT('%', {0}, '%'))", productName));
        }
        w.orderByDesc("id");
        return deliveryMapper.selectPageWithProduct(p, w);
    }

    public SalDelivery detail(Long id) {
        SalDelivery d = deliveryMapper.selectById(id);
        if (d != null) d.setDetails(detailMapper.selectByDeliveryId(id));
        // 注入商品色号 (打印模板用, 从 base_product.colorNo 取)
        if (d != null && d.getDetails() != null) {
            for (SalDeliveryDetail det : d.getDetails()) {
                if (det.getProductId() != null) {
                    BaseProduct p = productMapper.selectById(det.getProductId());
                    if (p != null) det.setPColorNo(p.getColorNo());
                }
            }
        }
        return d;
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

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal taxAmount = BigDecimal.ZERO;
        BigDecimal totalAmountTax = BigDecimal.ZERO;
        int line = 0;
        for (SalDeliveryDetail d : delivery.getDetails()) {
            d.setLineNo(++line);
            if (d.getTaxRate() == null) d.setTaxRate(c.getTaxRate() == null ? new BigDecimal("13.00") : c.getTaxRate());
            d.setAmount(d.getPrice().multiply(d.getQty()).setScale(4, RoundingMode.HALF_UP));
            d.setTaxAmount(d.getAmount().multiply(d.getTaxRate()).divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP));
            d.setAmountTax(d.getAmount().add(d.getTaxAmount()));
            totalQty = totalQty.add(d.getQty());
            totalAmount = totalAmount.add(d.getAmount());
            taxAmount = taxAmount.add(d.getTaxAmount());
            totalAmountTax = totalAmountTax.add(d.getAmountTax());
        }
        // 抹零
        BigDecimal tail = delivery.getTailAmount() == null ? BigDecimal.ZERO : delivery.getTailAmount();
        // 整单折扣
        BigDecimal discount = delivery.getDiscountAmount() == null ? BigDecimal.ZERO : delivery.getDiscountAmount();
        totalAmount = totalAmount.subtract(discount).subtract(tail);
        totalAmountTax = totalAmountTax.subtract(discount).subtract(tail);

        delivery.setTotalQty(totalQty);
        delivery.setTotalAmount(totalAmount);
        delivery.setTaxAmount(taxAmount);
        delivery.setTotalAmountTax(totalAmountTax);
        delivery.setReceivedAmount(BigDecimal.ZERO);
        delivery.setCostAmount(BigDecimal.ZERO);
        delivery.setProfitAmount(BigDecimal.ZERO);

        deliveryMapper.insert(delivery);
        for (SalDeliveryDetail d : delivery.getDetails()) {
            d.setId(null);
            d.setDeliveryId(delivery.getId());
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
        BigDecimal taxAmount = BigDecimal.ZERO;
        BigDecimal totalAmountTax = BigDecimal.ZERO;
        int line = 0;
        for (SalDeliveryDetail d : delivery.getDetails()) {
            d.setLineNo(++line);
            if (d.getTaxRate() == null) d.setTaxRate(c.getTaxRate() == null ? new BigDecimal("13.00") : c.getTaxRate());
            d.setAmount(d.getPrice().multiply(d.getQty()).setScale(4, RoundingMode.HALF_UP));
            d.setTaxAmount(d.getAmount().multiply(d.getTaxRate()).divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP));
            d.setAmountTax(d.getAmount().add(d.getTaxAmount()));
            totalQty = totalQty.add(d.getQty());
            totalAmount = totalAmount.add(d.getAmount());
            taxAmount = taxAmount.add(d.getTaxAmount());
            totalAmountTax = totalAmountTax.add(d.getAmountTax());
        }
        // 抹零/折扣
        BigDecimal tail = delivery.getTailAmount() == null ? BigDecimal.ZERO : delivery.getTailAmount();
        BigDecimal discount = delivery.getDiscountAmount() == null ? BigDecimal.ZERO : delivery.getDiscountAmount();
        totalAmount = totalAmount.subtract(discount).subtract(tail);
        totalAmountTax = totalAmountTax.subtract(discount).subtract(tail);

        delivery.setTotalQty(totalQty);
        delivery.setTotalAmount(totalAmount);
        delivery.setTaxAmount(taxAmount);
        delivery.setTotalAmountTax(totalAmountTax);
        // 主表不可变字段保持原值
        delivery.setBillNo(origin.getBillNo());
        delivery.setBillDate(origin.getBillDate());
        delivery.setBillStatus(origin.getBillStatus());
        delivery.setBillType(origin.getBillType());
        delivery.setReceivedAmount(origin.getReceivedAmount());

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
    public void check(Long id) {
        permService.requirePerm("sales:delivery:check");
        SalDelivery d = deliveryMapper.selectById(id);
        if (d == null) throw BizException.of("出库单不存在");
        if (!Constants.STATUS_DRAFT.equals(d.getBillStatus())) {
            throw BizException.of("只有草稿状态可审核");
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

        // 2. 库存出库 (每行, 严格防止负库存)
        List<SalDeliveryDetail> details = detailMapper.selectByDeliveryId(id);
        BaseWarehouse wh = warehouseMapper.selectById(d.getWarehouseId());
        BigDecimal totalCost = BigDecimal.ZERO;
        for (SalDeliveryDetail det : details) {
            BigDecimal outCost = stockService.outStock(
                    Constants.LEDGER_SAL_DELIVERY, d.getId(), d.getBillNo(), det.getId(),
                    d.getWarehouseId(), wh.getWarehouseName(), det.getLocationId(), det.getLocationName(),
                    det.getProductId(), det.getUnitId(), det.getUnitName(), det.getBatchNo(),
                    det.getQty(), det.getPrice(), d.getBillNo(),
                    null, d.getCustomerId(), "销售出库 " + d.getBillNo()
            );
            det.setCostPrice(outCost.divide(det.getQty(), 4, RoundingMode.HALF_UP));
            det.setCostAmount(outCost);
            detailMapper.updateById(det);
            totalCost = totalCost.add(outCost);
        }

        // 3. 更新主表 (成本/毛利)
        BigDecimal profit = d.getTotalAmount().subtract(totalCost);
        SalDelivery upd = new SalDelivery();
        upd.setId(id);
        upd.setBillStatus(Constants.STATUS_CHECKED);
        upd.setCostAmount(totalCost);
        upd.setProfitAmount(profit);
        deliveryMapper.updateById(upd);

        // 4. 应收台账
        arapService.createArForSales(d, details);

        log.info("销售出库审核: billNo={}, amount={}, cost={}, profit={}",
                d.getBillNo(), d.getTotalAmount(), totalCost, profit);
    }
}
