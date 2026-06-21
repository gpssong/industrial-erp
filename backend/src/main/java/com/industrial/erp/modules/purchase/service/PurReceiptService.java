package com.industrial.erp.modules.purchase.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.common.Constants;
import com.industrial.erp.common.R;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseSupplier;
import com.industrial.erp.modules.base.entity.BaseWarehouse;
import com.industrial.erp.modules.base.mapper.BaseSupplierMapper;
import com.industrial.erp.modules.base.mapper.BaseWarehouseMapper;
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
import com.industrial.erp.utils.BillNoGenerator;
import com.industrial.erp.security.PermissionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * 采购入库服务
 * 业务: 生成入库单 -> 写明细 -> 调用 StockService 库存入库 -> 生成应付台账
 */
@Service
public class PurReceiptService {

    public PurReceiptService(PurReceiptMapper receiptMapper, PurReceiptDetailMapper receiptDetailMapper, PurOrderMapper orderMapper, PurOrderDetailMapper orderDetailMapper, BaseSupplierMapper supplierMapper, BaseWarehouseMapper warehouseMapper, BillNoGenerator billNoGenerator, StockService stockService, FinArapService arapService, PermissionService permService) {
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
    }

    private final PurReceiptMapper receiptMapper;
    private final PurReceiptDetailMapper receiptDetailMapper;
    private final PurOrderMapper orderMapper;
    private final PurOrderDetailMapper orderDetailMapper;
    private final BaseSupplierMapper supplierMapper;
    private final BaseWarehouseMapper warehouseMapper;
    private final BillNoGenerator billNoGenerator;
    private final StockService stockService;
    private final FinArapService arapService;
    private final PermissionService permService;

    public IPage<PurReceipt> page(Integer pageNum, Integer pageSize, String billNo, Long supplierId, String billStatus, String productName) {
        permService.requirePerm("purchase:receipt:list");
        Page<PurReceipt> p = new Page<>(pageNum, pageSize);
        QueryWrapper<PurReceipt> w = new QueryWrapper<>();
        if (StrUtil.isNotBlank(billNo)) w.like("bill_no", billNo);
        if (supplierId != null) w.eq("supplier_id", supplierId);
        if (StrUtil.isNotBlank(billStatus)) w.eq("bill_status", billStatus);
        // 按商品名称查询: 命中任一明细行即返回, 用 EXISTS 子查询避免 GROUP BY 性能问题
        if (StrUtil.isNotBlank(productName)) {
            w.and(wq -> wq.exists("SELECT 1 FROM pur_receipt_detail d LEFT JOIN base_product p ON p.id = d.product_id " +
                    "WHERE d.receipt_id = r.id AND p.product_name LIKE {0}", "%" + productName + "%"));
        }
        w.orderByDesc("id");
        return receiptMapper.selectPageWithProduct(p, w);
    }

    public PurReceipt detail(Long id) {
        PurReceipt r = receiptMapper.selectById(id);
        if (r != null) r.setDetails(receiptDetailMapper.selectByReceiptId(id));
        return r;
    }

    /** 查询指定供应商+商品的上次订单单价 */
    public BigDecimal getLastPrice(Long supplierId, Long productId) {
        BigDecimal price = orderDetailMapper.selectLastPriceBySupplierAndProduct(supplierId, productId);
        return price != null ? price : BigDecimal.ZERO;
    }

    @Transactional(rollbackFor = Exception.class)
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
        receipt.setSupplierName(s.getSupplierName());
        // 校验仓库
        BaseWarehouse w = warehouseMapper.selectById(receipt.getWarehouseId());
        if (w == null) throw BizException.of("仓库不存在");
        // 汇总
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal taxAmount = BigDecimal.ZERO;
        BigDecimal totalAmountTax = BigDecimal.ZERO;
        int line = 0;
        for (PurReceiptDetail d : receipt.getDetails()) {
            d.setLineNo(++line);
            if (d.getTaxRate() == null) d.setTaxRate(s.getTaxRate() == null ? new BigDecimal("13.00") : s.getTaxRate());
            d.setAmount(d.getPrice().multiply(d.getQty()).setScale(4, RoundingMode.HALF_UP));
            d.setTaxAmount(d.getAmount().multiply(d.getTaxRate()).divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP));
            d.setAmountTax(d.getAmount().add(d.getTaxAmount()));
            totalQty = totalQty.add(d.getQty());
            totalAmount = totalAmount.add(d.getAmount());
            taxAmount = taxAmount.add(d.getTaxAmount());
            totalAmountTax = totalAmountTax.add(d.getAmountTax());
        }
        receipt.setTotalQty(totalQty);
        receipt.setTotalAmount(totalAmount);
        receipt.setTaxAmount(taxAmount);
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
     * 审核入库单 -> 触发库存入库 + 应付台账
     */
    @Transactional(rollbackFor = Exception.class)
    public void check(Long id) {
        permService.requirePerm("purchase:receipt:check");
        PurReceipt r = receiptMapper.selectById(id);
        if (r == null) throw BizException.of("入库单不存在");
        if (!Constants.STATUS_DRAFT.equals(r.getBillStatus())) {
            throw BizException.of("只有草稿状态可审核");
        }
        List<PurReceiptDetail> details = receiptDetailMapper.selectByReceiptId(id);
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
        receiptMapper.updateById(upd);

        // 写入应付台账
        arapService.createApForPurchase(r, details);

        log.info("采购入库审核: billNo={}, amount={}", r.getBillNo(), r.getTotalAmountTax());
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PurReceiptService.class);
}
