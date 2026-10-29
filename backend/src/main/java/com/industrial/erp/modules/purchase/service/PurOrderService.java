package com.industrial.erp.modules.purchase.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.common.Constants;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseProduct;
import com.industrial.erp.modules.base.entity.BaseSupplier;
import com.industrial.erp.modules.base.mapper.BaseSupplierMapper;
import com.industrial.erp.modules.purchase.entity.PurOrder;
import com.industrial.erp.modules.purchase.entity.PurOrderDetail;
import com.industrial.erp.modules.purchase.mapper.PurOrderDetailMapper;
import com.industrial.erp.modules.purchase.mapper.PurOrderMapper;
import com.industrial.erp.modules.purchase.mapper.PurReceiptDetailMapper;
import com.industrial.erp.modules.system.annotation.OperLog;
import com.industrial.erp.modules.system.aspect.OperLogPublisher;
import com.industrial.erp.modules.xk.entity.XkSupplierReview;
import com.industrial.erp.modules.xk.mapper.XkSupplierReviewMapper;
import com.industrial.erp.utils.BillNoGenerator;
import com.industrial.erp.security.PermissionService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

@Service
public class PurOrderService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PurOrderService.class);

    public PurOrderService(PurOrderMapper orderMapper, PurOrderDetailMapper detailMapper, BaseSupplierMapper supplierMapper, BillNoGenerator billNoGenerator, PermissionService permService, PurReceiptDetailMapper receiptDetailMapper, OperLogPublisher operLogPublisher, com.industrial.erp.modules.base.mapper.BaseProductMapper productMapper, XkSupplierReviewMapper reviewMapper) {
        this.orderMapper = orderMapper;
        this.detailMapper = detailMapper;
        this.supplierMapper = supplierMapper;
        this.billNoGenerator = billNoGenerator;
        this.permService = permService;
        this.receiptDetailMapper = receiptDetailMapper;
        this.operLogPublisher = operLogPublisher;
        this.productMapper = productMapper;
        this.reviewMapper = reviewMapper;
    }
    private final PurOrderMapper orderMapper;
    private final PurOrderDetailMapper detailMapper;
    private final BaseSupplierMapper supplierMapper;
    private final PurReceiptDetailMapper receiptDetailMapper;
    private final BillNoGenerator billNoGenerator;
    private final PermissionService permService;
    private final OperLogPublisher operLogPublisher;
    private final com.industrial.erp.modules.base.mapper.BaseProductMapper productMapper;
    /** v1.1.130 P1 XK — 采购订单门禁读最近一次准入评审结论 */
    private final XkSupplierReviewMapper reviewMapper;

    public IPage<PurOrder> page(Integer pageNum, Integer pageSize, String billNo, Long supplierId, String billStatus) {
        permService.requirePerm("purchase:order:list");
        Page<PurOrder> p = new Page<>(pageNum, pageSize);
        // v1.1.131: 用 QueryWrapper (非 Lambda) 配合 @Select 的 ${ew.customSqlSegment},
        // 以便在 SQL 里带上 firstProductName 子查询 (App 选单器显示「单号 · 品名」)
        QueryWrapper<PurOrder> w = new QueryWrapper<>();
        if (StrUtil.isNotBlank(billNo)) w.like("bill_no", billNo);
        if (supplierId != null) w.eq("supplier_id", supplierId);
        if (StrUtil.isNotBlank(billStatus)) w.eq("bill_status", billStatus);
        w.orderByDesc("id");
        return orderMapper.selectPageWithProduct(p, w);
    }

    public PurOrder detail(Long id) {
        PurOrder o = orderMapper.selectById(id);
        if (o != null) o.setDetails(detailMapper.selectByOrderId(id));
        // v1.1.12+: 注入 model 字段 (打印模板"型号"列)
        if (o != null && o.getDetails() != null) {
            com.industrial.erp.modules.base.service.ProductAttrInjector.inject(productMapper, o.getDetails(),
                    r -> ((PurOrderDetail) r).getProductId(),
                    (r, v) -> ((PurOrderDetail) r).setPModel(v),
                    p -> p.getModel());
        }
        return o;
    }

    public BigDecimal getLastPrice(Long supplierId, Long productId) {
        BigDecimal price = receiptDetailMapper.selectLastPriceBySupplierAndProduct(supplierId, productId);
        return price != null ? price : BigDecimal.ZERO;
    }

    @Transactional(rollbackFor = Exception.class)
    @OperLog(module="采购订单", businessType="ADD", saveParam=true)
    public void add(PurOrder order) {
        permService.requirePerm("purchase:order:add");
        if (order.getBillDate() == null) order.setBillDate(LocalDate.now());
        if (StrUtil.isBlank(order.getBillNo())) order.setBillNo(billNoGenerator.generate(Constants.BILL_PO));
        if (StrUtil.isBlank(order.getBillStatus())) order.setBillStatus(Constants.STATUS_DRAFT);
        BaseSupplier s = supplierMapper.selectById(order.getSupplierId());
        if (s == null) throw BizException.of("供应商不存在");
        assertXkSupplierGate(s, order.getDetails(), "创建");
        order.setSupplierName(s.getSupplierName());

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        // v1.1.19+: tax-inclusive price, taxAmount=0, totalAmountTax=totalAmount=开单金额
        BigDecimal totalAmountTax = BigDecimal.ZERO;
        int line = 0;
        for (PurOrderDetail d : order.getDetails()) {
            d.setLineNo(++line);
            if (d.getTaxRate() == null) d.setTaxRate(s.getTaxRate() == null ? new BigDecimal("13.00") : s.getTaxRate());
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
        order.setPaidAmount(BigDecimal.ZERO);
        orderMapper.insert(order);
        for (PurOrderDetail d : order.getDetails()) {
            d.setId(null);
            d.setOrderId(order.getId());
            detailMapper.insert(d);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        permService.requirePerm("purchase:order:delete");
        PurOrder order = orderMapper.selectById(id);
        if (order == null) throw BizException.of("订单不存在或已删除");
        List<PurOrderDetail> details = detailMapper.selectByOrderId(id);
        // 软删除主
        orderMapper.update(null, new LambdaUpdateWrapper<PurOrder>()
                .eq(PurOrder::getId, id).set(PurOrder::getDeleted, 1));
        // 软删除子
        if (details != null && !details.isEmpty()) {
            detailMapper.update(null, new LambdaUpdateWrapper<PurOrderDetail>()
                    .eq(PurOrderDetail::getOrderId, id).set(PurOrderDetail::getDeleted, 1));
        }
        // 写操作日志
        operLogPublisher.publishDeleteSnapshot("采购订单", String.valueOf(id), order, details);
    }

    @Transactional(rollbackFor = Exception.class)
    @OperLog(module="采购订单", businessType="EDIT", saveParam=true)
    public void update(PurOrder order) {
        permService.requirePerm("purchase:order:edit");
        PurOrder origin = orderMapper.selectById(order.getId());
        if (origin == null) throw BizException.of("订单不存在");
        BaseSupplier s = supplierMapper.selectById(order.getSupplierId());
        if (s == null) throw BizException.of("供应商不存在");
        assertXkSupplierGate(s, order.getDetails(), "修改");
        order.setSupplierName(s.getSupplierName());

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        // v1.1.19+: tax-inclusive price, taxAmount=0, totalAmountTax=totalAmount=开单金额
        BigDecimal totalAmountTax = BigDecimal.ZERO;
        int line = 0;
        for (PurOrderDetail d : order.getDetails()) {
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
        detailMapper.delete(new LambdaQueryWrapper<PurOrderDetail>().eq(PurOrderDetail::getOrderId, order.getId()));
        for (PurOrderDetail d : order.getDetails()) {
            d.setId(null);
            d.setOrderId(order.getId());
            detailMapper.insert(d);
        }
    }

    /**
     * 审核采购订单 (v1.1.11+): 仅状态机 DRAFT→CHECKED; 不动库存 (库存事务在下游入库单)
     */
    @Transactional(rollbackFor = Exception.class)
    @OperLog(module="采购订单", businessType="CHECK", saveParam=true)
    public void check(Long id) {
        permService.requirePerm("purchase:order:check");
        PurOrder order = orderMapper.selectById(id);
        if (order == null) throw BizException.of("订单不存在");
        if (!Constants.STATUS_DRAFT.equals(order.getBillStatus())) {
            throw BizException.of("只有草稿状态可审核");
        }
        // v1.1.130 P1 XK 门禁复核: 草稿期间供应商可能被暂停供货 / 资质过期 / 评审不通过
        BaseSupplier s = supplierMapper.selectById(order.getSupplierId());
        if (s == null) throw BizException.of("供应商不存在");
        assertXkSupplierGate(s, detailMapper.selectByOrderId(id), "审核");
        PurOrder upd = new PurOrder();
        upd.setId(id);
        upd.setBillStatus(Constants.STATUS_CHECKED);
        orderMapper.updateById(upd);
    }

    /**
     * 反审核采购订单 (v1.1.11+): CHECKED→DRAFT (status-only);
     * 注意: 若下游已生成入库单, 需先删入库单再反审核, 否则会导致库存账错位
     */
    @Transactional(rollbackFor = Exception.class)
    @OperLog(module="采购订单", businessType="UNCHECK", saveParam=true)
    public void uncheck(Long id) {
        permService.requirePerm("purchase:order:uncheck");
        PurOrder order = orderMapper.selectById(id);
        if (order == null) throw BizException.of("订单不存在");
        if (!Constants.STATUS_CHECKED.equals(order.getBillStatus())) {
            throw BizException.of("只有已审核状态可反审核");
        }
        PurOrder upd = new PurOrder();
        upd.setId(id);
        upd.setBillStatus(Constants.STATUS_DRAFT);
        orderMapper.updateById(upd);
    }

    // ==================================================================
    // v1.1.130 P1 XK — 采购订单侧合格供方名录门禁
    // ==================================================================

    /**
     * 含食品级原料行 (base_product.is_food_grade=1) 的采购订单必须满足:
     * <ol>
     *   <li>供应商准入状态为「合格」(status=1) — 暂停供货不得下单</li>
     *   <li>食品级资质已登记且在有效期内 (base_supplier.food_cert_exp, 由证照档案回写)</li>
     *   <li>最近一次准入评审结论不为 FAIL</li>
     * </ol>
     * 全部非食品级行 → 与采购入库同口径豁免 (仅记日志)。
     */
    private void assertXkSupplierGate(BaseSupplier s, List<PurOrderDetail> details, String action) {
        if (!hasFoodGradeLine(details)) {
            log.info("采购订单 XK 豁免: 全部行非食品级, 跳过合格供方名录门禁 supplier={} action={}",
                    s.getSupplierName(), action);
            return;
        }
        if (s.getStatus() == null || s.getStatus() != 1) {
            throw BizException.of("XK 合规: 该订单含食品级原料, 供应商 " + s.getSupplierName()
                    + " 当前为「暂停供货」, 不能" + action + "采购订单; 请先在合格供应商名录中恢复供货");
        }
        if (s.getFoodCertExp() == null) {
            throw BizException.of("XK 合规: 供应商 " + s.getSupplierName()
                    + " 未登记食品级资质有效期, 不能" + action + "含食品级原料的采购订单; 请在「证照与检测报告」补录生产许可证 / GB 4806 检测报告");
        }
        if (s.getFoodCertExp().isBefore(LocalDate.now())) {
            throw BizException.of("XK 合规: 供应商 " + s.getSupplierName() + " 食品级资质已于 "
                    + s.getFoodCertExp() + " 到期, 不能" + action + "含食品级原料的采购订单; 请更新证照并完成复评");
        }
        XkSupplierReview latest = reviewMapper.selectOne(new LambdaQueryWrapper<XkSupplierReview>()
                .eq(XkSupplierReview::getSupplierId, s.getId())
                .orderByDesc(XkSupplierReview::getReviewDate)
                .orderByDesc(XkSupplierReview::getId)
                .last("LIMIT 1"));
        if (latest != null && "FAIL".equals(latest.getResult())) {
            throw BizException.of("XK 合规: 供应商 " + s.getSupplierName() + " 最近一次准入评审 ("
                    + latest.getReviewDate() + ") 结论为「不通过」, 不能" + action
                    + "含食品级原料的采购订单; 请先完成整改并重新评审");
        }
    }

    private boolean hasFoodGradeLine(List<PurOrderDetail> details) {
        if (details == null) return false;
        for (PurOrderDetail d : details) {
            if (d == null || d.getProductId() == null) continue;
            BaseProduct p = productMapper.selectById(d.getProductId());
            if (p != null && p.getIsFoodGrade() != null && p.getIsFoodGrade() == 1) return true;
        }
        return false;
    }
}
