package com.industrial.erp.modules.sales.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.common.Constants;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseCustomer;
import com.industrial.erp.modules.base.entity.BaseProduct;
import com.industrial.erp.modules.base.entity.BaseProductUnit;
import com.industrial.erp.modules.base.entity.BaseWarehouse;
import com.industrial.erp.modules.base.mapper.BaseCustomerMapper;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.industrial.erp.modules.base.mapper.BaseProductUnitMapper;
import com.industrial.erp.modules.base.mapper.BaseWarehouseMapper;
import com.industrial.erp.modules.sales.entity.SalOrder;
import com.industrial.erp.modules.sales.entity.SalOrderDetail;
import com.industrial.erp.modules.sales.mapper.SalOrderDetailMapper;
import com.industrial.erp.modules.sales.mapper.SalOrderMapper;
import com.industrial.erp.modules.sales.mapper.SalDeliveryDetailMapper;
import com.industrial.erp.modules.system.annotation.OperLog;
import com.industrial.erp.modules.system.aspect.OperLogPublisher;
import com.industrial.erp.utils.BillNoGenerator;
import com.industrial.erp.security.PermissionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class SalOrderService {

    public SalOrderService(SalOrderMapper orderMapper, SalOrderDetailMapper detailMapper, BaseCustomerMapper customerMapper, BillNoGenerator billNoGenerator, PermissionService permService, SalDeliveryDetailMapper deliveryDetailMapper, OperLogPublisher operLogPublisher, BaseProductMapper productMapper, BaseProductUnitMapper unitMapper, BaseWarehouseMapper warehouseMapper) {
        this.orderMapper = orderMapper;
        this.detailMapper = detailMapper;
        this.customerMapper = customerMapper;
        this.billNoGenerator = billNoGenerator;
        this.permService = permService;
        this.deliveryDetailMapper = deliveryDetailMapper;
        this.operLogPublisher = operLogPublisher;
        this.productMapper = productMapper;
        this.unitMapper = unitMapper;
        this.warehouseMapper = warehouseMapper;
    }

    private final SalOrderMapper orderMapper;
    private final SalOrderDetailMapper detailMapper;
    private final BaseCustomerMapper customerMapper;
    private final SalDeliveryDetailMapper deliveryDetailMapper;
    private final BillNoGenerator billNoGenerator;
    private final PermissionService permService;
    private final OperLogPublisher operLogPublisher;
    private final BaseProductMapper productMapper;
    private final BaseProductUnitMapper unitMapper;
    private final BaseWarehouseMapper warehouseMapper;

    public IPage<SalOrder> page(Integer pageNum, Integer pageSize, String billNo, Long customerId, String billStatus) {
        permService.requirePerm("sales:order:list");
        Page<SalOrder> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<SalOrder> w = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(billNo)) w.like(SalOrder::getBillNo, billNo);
        if (customerId != null) w.eq(SalOrder::getCustomerId, customerId);
        if (StrUtil.isNotBlank(billStatus)) w.eq(SalOrder::getBillStatus, billStatus);
        w.orderByDesc(SalOrder::getId);
        IPage<SalOrder> result = orderMapper.selectPage(p, w);
        // v1.1.41: 批量注入已发货数量 (实时 SUM 出库单明细, 不依赖 out_qty 回写列)
        // 历史原因: v1.1.41 之前审核的出库单 out_qty 列未被回写, 列表显示 0; 现改为实时计算
        if (result.getRecords() != null && !result.getRecords().isEmpty()) {
            List<Long> orderIds = result.getRecords().stream().map(SalOrder::getId).collect(Collectors.toList());
            Map<Long, BigDecimal> shippedMap = detailMapper.selectShippedQtyGroupByOrderId(orderIds);
            result.getRecords().forEach(o -> o.setShippedQty(shippedMap.getOrDefault(o.getId(), BigDecimal.ZERO)));
        }
        return result;
    }

    public SalOrder detail(Long id) {
        SalOrder o = orderMapper.selectById(id);
        if (o != null) o.setDetails(detailMapper.selectByOrderId(id));
        // v1.1.38: 注入仓库名称 (对齐销售出库单)
        if (o != null && o.getWarehouseId() != null) {
            BaseWarehouse wh = warehouseMapper.selectById(o.getWarehouseId());
            if (wh != null) o.setWarehouseName(wh.getWarehouseName());
        }
        // v1.1.38: 注入商品 spec + colorNo + model (打印模板字段补齐)
        // - spec: 从 base_product.spec 注入, 前端录入时未自动填充该字段
        // - colorNo / model: 原有逻辑, 保持
        if (o != null && o.getDetails() != null && !o.getDetails().isEmpty()) {
            com.industrial.erp.modules.base.service.ProductAttrInjector.inject(productMapper, o.getDetails(),
                    r -> ((SalOrderDetail) r).getProductId(),
                    (r, v) -> ((SalOrderDetail) r).setSpec(v),
                    p -> p.getSpec());
            com.industrial.erp.modules.base.service.ProductAttrInjector.inject(productMapper, o.getDetails(),
                    r -> ((SalOrderDetail) r).getProductId(),
                    (r, v) -> ((SalOrderDetail) r).setPModel(v),
                    p -> p.getModel());
            com.industrial.erp.modules.base.service.ProductAttrInjector.injectColorNo(productMapper, o.getDetails(),
                    r -> ((SalOrderDetail) r).getProductId(),
                    (r, v) -> ((SalOrderDetail) r).setPColorNo(v));
            // v1.1.38: 注入 unitName (打印模板"单位"列) — 从 base_product_unit 取第一个有效单位
            // 注意: 历史数据 unit_id 多为 NULL (base_product_unit.unit_name 也有乱码 ?), 这里直接取 unit_name 字符串
            for (SalOrderDetail d : o.getDetails()) {
                if (d.getUnitName() == null) {
                    BaseProductUnit pu = unitMapper.selectMainUnit(d.getProductId());
                    if (pu != null && StrUtil.isNotBlank(pu.getUnitName())) {
                        d.setUnitName(pu.getUnitName());
                    }
                }
            }
        }
        // v1.1.38: 注入客户采购订单号 (poNo 是头字段, 明细行重复以便打印模板显示)
        if (o != null && o.getDetails() != null && o.getPoNo() != null) {
            for (SalOrderDetail d : o.getDetails()) {
                d.setPoNo(o.getPoNo());
            }
        }
        // v1.1.38+: 交货方式枚举值 → 中文 label (浏览器打印前端读 deliveryMethodLabel)
        if (o != null && o.getDeliveryMethod() != null) {
            o.setDeliveryMethodLabel(mapDeliveryMethod(o.getDeliveryMethod()));
        }
        // v1.1.38+: 付款方式枚举值 → 中文 label
        if (o != null && o.getPayType() != null) {
            o.setPayTypeLabel(mapPayType(o.getPayType()));
        }
        return o;
    }

    private static String mapDeliveryMethod(String code) {
        if (code == null) return "";
        switch (code) {
            case "DELIVERY": return "送货";
            case "PICKUP":   return "自提";
            case "DIRECT":   return "专车直送";
            default:         return code;
        }
    }

    private static String mapPayType(String code) {
        if (code == null) return "";
        switch (code) {
            case "PREPAY":  return "款到发货";
            case "MONTHLY": return "月结";
            case "ARRIVAL": return "货到付款";
            default:        return code;
        }
    }

    public BigDecimal getLastPrice(Long customerId, Long productId) {
        BigDecimal price = deliveryDetailMapper.selectLastPriceByCustomerAndProduct(customerId, productId);
        return price != null ? price : BigDecimal.ZERO;
    }

    /** v1.1.41: 查询订单的已发货明细汇总 (用于订单列表"发货详情"弹窗) */
    public java.util.List<java.util.Map<String, Object>> getDeliverySummary(Long orderId) {
        if (orderId == null) return java.util.Collections.emptyList();
        List<SalOrderDetail> details = detailMapper.selectByOrderId(orderId);
        if (details == null || details.isEmpty()) return java.util.Collections.emptyList();
        java.util.List<java.util.Map<String, Object>> summary = new java.util.ArrayList<>();
        for (SalOrderDetail d : details) {
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("lineNo", d.getLineNo());
            row.put("productCode", d.getProductCode());
            row.put("productName", d.getProductName());
            row.put("spec", d.getSpec());
            row.put("unitName", d.getUnitName());
            row.put("qty", d.getQty());
            // 累计已审核出库数量
            BigDecimal shipped = detailMapper.selectShippedQtyByOrderDetailId(d.getId());
            row.put("shippedQty", shipped != null ? shipped : BigDecimal.ZERO);
            row.put("unshippedQty", (d.getQty() != null ? d.getQty() : BigDecimal.ZERO)
                    .subtract(shipped != null ? shipped : BigDecimal.ZERO));
            summary.add(row);
        }
        return summary;
    }

    @Transactional(rollbackFor = Exception.class)
    @OperLog(module="销售订单", businessType="ADD", saveParam=true)
    public void add(SalOrder order) {
        permService.requirePerm("sales:order:add");
        if (order.getBillDate() == null) order.setBillDate(LocalDate.now());
        if (StrUtil.isBlank(order.getBillNo())) order.setBillNo(billNoGenerator.generate(Constants.BILL_SO));
        if (StrUtil.isBlank(order.getBillStatus())) order.setBillStatus(Constants.STATUS_DRAFT);

        BaseCustomer c = customerMapper.selectById(order.getCustomerId());
        if (c == null) throw BizException.of("客户不存在");
        order.setCustomerName(c.getCustomerName());

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        // v1.1.19+: tax-inclusive price, taxAmount=0, totalAmountTax=totalAmount=开单金额
        BigDecimal totalAmountTax = BigDecimal.ZERO;
        int line = 0;
        for (SalOrderDetail d : order.getDetails()) {
            d.setLineNo(++line);
            if (d.getTaxRate() == null) d.setTaxRate(c.getTaxRate() == null ? new BigDecimal("13.00") : c.getTaxRate());
            d.setAmount(d.getPrice().multiply(d.getQty()).setScale(4, RoundingMode.HALF_UP));
            d.setTaxAmount(BigDecimal.ZERO);
            d.setAmountTax(d.getAmount());
            // v1.1.38: 自动从商品填充 spec (如果前端没传)
            if (d.getProductId() != null && StrUtil.isBlank(d.getSpec())) {
                BaseProduct p = productMapper.selectById(d.getProductId());
                if (p != null) d.setSpec(p.getSpec());
            }
            totalQty = totalQty.add(d.getQty());
            totalAmount = totalAmount.add(d.getAmount());
            totalAmountTax = totalAmountTax.add(d.getAmount());
        }
        order.setTotalQty(totalQty);
        order.setTotalAmount(totalAmount);
        order.setTaxAmount(BigDecimal.ZERO);
        order.setTotalAmountTax(totalAmountTax);
        order.setReceivedAmount(BigDecimal.ZERO);
        orderMapper.insert(order);
        for (SalOrderDetail d : order.getDetails()) {
            d.setId(null);
            d.setOrderId(order.getId());
            detailMapper.insert(d);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        permService.requirePerm("sales:order:delete");
        SalOrder order = orderMapper.selectById(id);
        if (order == null) throw new BizException("订单不存在或已删除");
        // 取快照 (主+子)
        List<SalOrderDetail> details = detailMapper.selectByOrderId(id);
        // 软删除主
        orderMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SalOrder>()
                .eq(SalOrder::getId, id).set(SalOrder::getDeleted, 1));
        // 软删除子
        if (details != null && !details.isEmpty()) {
            detailMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SalOrderDetail>()
                    .eq(SalOrderDetail::getOrderId, id).set(SalOrderDetail::getDeleted, 1));
        }
        // 写操作日志
        operLogPublisher.publishDeleteSnapshot("销售订单", String.valueOf(id), order, details);
    }

    @Transactional(rollbackFor = Exception.class)
    @OperLog(module="销售订单", businessType="EDIT", saveParam=true)
    public void update(SalOrder order) {
        permService.requirePerm("sales:order:edit");
        BaseCustomer c = customerMapper.selectById(order.getCustomerId());
        if (c == null) throw BizException.of("客户不存在");
        order.setCustomerName(c.getCustomerName());

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        // v1.1.19+: tax-inclusive price, taxAmount=0, totalAmountTax=totalAmount=开单金额
        BigDecimal totalAmountTax = BigDecimal.ZERO;
        int line = 0;
        for (SalOrderDetail d : order.getDetails()) {
            d.setLineNo(++line);
            if (d.getTaxRate() == null) d.setTaxRate(new BigDecimal("13.00"));
            d.setAmount(d.getPrice().multiply(d.getQty()).setScale(4, RoundingMode.HALF_UP));
            d.setTaxAmount(BigDecimal.ZERO);
            d.setAmountTax(d.getAmount());
            totalQty = totalQty.add(d.getQty());
            totalAmount = totalAmount.add(d.getAmount());
            totalAmountTax = totalAmountTax.add(d.getAmount());
        }
        order.setTotalQty(totalQty);
        order.setTotalAmount(totalAmount);
        order.setTaxAmount(BigDecimal.ZERO);
        order.setTotalAmountTax(totalAmountTax);
        orderMapper.updateById(order);
        // 删除原明细，重新插入
        detailMapper.delete(new LambdaQueryWrapper<SalOrderDetail>().eq(SalOrderDetail::getOrderId, order.getId()));
        for (SalOrderDetail d : order.getDetails()) {
            d.setId(null);
            d.setOrderId(order.getId());
            detailMapper.insert(d);
        }
    }

    /** v1.1.11+ 审核销售订单 (status-only, 下游出库单触发库存账)
     *  v1.1.35-1: 改用 LambdaUpdateWrapper 只 SET billStatus,
     *  避免 SalOrderMapper.xml 自定义 updateById 全字段 SET 把 bill_no 等列覆盖成 NULL */
    @Transactional(rollbackFor = Exception.class)
    public void check(Long id) {
        permService.requirePerm("sales:order:check");
        SalOrder order = orderMapper.selectById(id);
        if (order == null) throw BizException.of("订单不存在");
        if (!Constants.STATUS_DRAFT.equals(order.getBillStatus())) {
            throw BizException.of("只有草稿状态可审核");
        }
        orderMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SalOrder>()
                .eq(SalOrder::getId, id)
                .set(SalOrder::getBillStatus, Constants.STATUS_CHECKED));
    }

    /** v1.1.11+ 反审核销售订单 (CHECKED→DRAFT)
     *  v1.1.35-1: 同 check(), 改用 LambdaUpdateWrapper 避免全字段覆盖 */
    @Transactional(rollbackFor = Exception.class)
    public void uncheck(Long id) {
        permService.requirePerm("sales:order:uncheck");
        SalOrder order = orderMapper.selectById(id);
        if (order == null) throw BizException.of("订单不存在");
        if (!Constants.STATUS_CHECKED.equals(order.getBillStatus())) {
            throw BizException.of("只有已审核状态可反审核");
        }
        orderMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SalOrder>()
                .eq(SalOrder::getId, id)
                .set(SalOrder::getBillStatus, Constants.STATUS_DRAFT));
    }
}
