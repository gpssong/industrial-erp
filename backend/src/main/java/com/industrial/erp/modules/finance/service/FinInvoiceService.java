package com.industrial.erp.modules.finance.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.industrial.erp.common.Constants;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseCustomer;
import com.industrial.erp.modules.base.entity.BaseSupplier;
import com.industrial.erp.modules.base.mapper.BaseCustomerMapper;
import com.industrial.erp.modules.base.mapper.BaseSupplierMapper;
import com.industrial.erp.modules.finance.dto.FinInvoiceIssueDTO;
import com.industrial.erp.modules.finance.entity.FinArap;
import com.industrial.erp.modules.finance.entity.FinInvoice;
import com.industrial.erp.modules.finance.entity.FinInvoiceApply;
import com.industrial.erp.modules.finance.mapper.FinArapMapper;
import com.industrial.erp.modules.finance.mapper.FinInvoiceApplyMapper;
import com.industrial.erp.modules.finance.mapper.FinInvoiceMapper;
import com.industrial.erp.modules.system.annotation.OperLog;
import com.industrial.erp.security.PermissionService;
import com.industrial.erp.utils.BillNoGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 发票服务 (v1.1.10+)
 *
 * <p>核心业务:
 * <ul>
 *   <li>开票: 创建 fin_invoice + 关联 fin_invoice_apply, 联动更新 AR/AP 单的开票状态</li>
 *   <li>按发票核销: 收款时按 invoice_id 累加 collected_amount, 按比例分摊到 AR/AP 单</li>
 *   <li>作废发票: 反向回滚 AR/AP 单的开票状态</li>
 * </ul>
 */
@Service
public class FinInvoiceService {

    private static final String INVOICE_TYPE_AR_SALE = "AR_SALE";
    private static final String INVOICE_TYPE_AP_PURCHASE = "AP_PURCHASE";
    private static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_ISSUED = "ISSUED";
    private static final String STATUS_PARTIAL = "PARTIAL";
    private static final String STATUS_PAID = "PAID";
    private static final String STATUS_VOID = "VOID";
    private static final String INVOICE_STATUS_FULL = "FULL_INVOICED";
    private static final String INVOICE_STATUS_PARTIAL = "PARTIAL_INVOICED";
    private static final String INVOICE_STATUS_NONE = "UNINVOICED";

    private final FinInvoiceMapper invoiceMapper;
    private final FinInvoiceApplyMapper applyMapper;
    private final FinArapMapper arapMapper;
    private final BaseCustomerMapper customerMapper;
    private final BaseSupplierMapper supplierMapper;
    private final BillNoGenerator billNoGenerator;
    private final PermissionService permService;

    public FinInvoiceService(FinInvoiceMapper invoiceMapper,
                             FinInvoiceApplyMapper applyMapper,
                             FinArapMapper arapMapper,
                             BaseCustomerMapper customerMapper,
                             BaseSupplierMapper supplierMapper,
                             BillNoGenerator billNoGenerator,
                             PermissionService permService) {
        this.invoiceMapper = invoiceMapper;
        this.applyMapper = applyMapper;
        this.arapMapper = arapMapper;
        this.customerMapper = customerMapper;
        this.supplierMapper = supplierMapper;
        this.billNoGenerator = billNoGenerator;
        this.permService = permService;
    }

    /**
     * 开票: 创建发票 + 关联 AR/AP 单
     *
     * <p>校验规则:
     * <ol>
     *   <li>items 不能为空</li>
     *   <li>每个 arap_id 必须存在, deleted=0, invoice_status != 'VOID'</li>
     *   <li>apply_amount 必须 ≤ AR/AP 单的 uninvoiced_amount</li>
     *   <li>所有 arap 的 customer/supplier 必须 == dto.partnerId</li>
     * </ol>
     */
    @OperLog(module="发票管理", businessType="ADD", saveParam=true)
    @Transactional(rollbackFor = Exception.class)
    public Long issue(FinInvoiceIssueDTO dto) {
        permService.requirePerm("finance:invoice:add");
        if (dto.getItems() == null || dto.getItems().isEmpty()) {
            throw BizException.of("开票明细不能为空");
        }

        // 1. 校验 invoice_type
        if (!INVOICE_TYPE_AR_SALE.equals(dto.getInvoiceType())
                && !INVOICE_TYPE_AP_PURCHASE.equals(dto.getInvoiceType())) {
            throw BizException.of("invoice_type 错误: " + dto.getInvoiceType());
        }

        // 2. 校验 partner
        String partnerName;
        String partnerTaxNo;
        if ("CUSTOMER".equals(dto.getPartnerType())) {
            BaseCustomer c = customerMapper.selectById(dto.getPartnerId());
            if (c == null) throw BizException.of("客户不存在: id=" + dto.getPartnerId());
            partnerName = c.getCustomerName();
            partnerTaxNo = c.getTaxNo();
        } else if ("SUPPLIER".equals(dto.getPartnerType())) {
            BaseSupplier s = supplierMapper.selectById(dto.getPartnerId());
            if (s == null) throw BizException.of("供应商不存在: id=" + dto.getPartnerId());
            partnerName = s.getSupplierName();
            partnerTaxNo = s.getTaxNo();
        } else {
            throw BizException.of("partner_type 错误: " + dto.getPartnerType());
        }

        // 3. 校验 AR/AP 单 + 累加 total_amount
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (FinInvoiceIssueDTO.InvoiceApplyItem item : dto.getItems()) {
            FinArap arap = arapMapper.selectById(item.getArapId());
            if (arap == null) throw BizException.of("AR/AP 单不存在: id=" + item.getArapId());
            if (arap.getDeleted() != null && arap.getDeleted() != 0) {
                throw BizException.of("AR/AP 单已删除: id=" + item.getArapId());
            }
            // 校验 partner 一致
            if ("CUSTOMER".equals(dto.getPartnerType())
                    && (arap.getCustomerId() == null || !arap.getCustomerId().equals(dto.getPartnerId()))) {
                throw BizException.of("AR/AP 单 " + arap.getSourceBillNo() + " 的客户与发票客户不一致");
            }
            if ("SUPPLIER".equals(dto.getPartnerType())
                    && (arap.getSupplierId() == null || !arap.getSupplierId().equals(dto.getPartnerId()))) {
                throw BizException.of("AR/AP 单 " + arap.getSourceBillNo() + " 的供应商与发票供应商不一致");
            }
            // 校验金额
            BigDecimal uninvoiced = arap.getUninvoicedAmount() == null ? BigDecimal.ZERO : arap.getUninvoicedAmount();
            if (item.getApplyAmount() == null || item.getApplyAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw BizException.of("开票金额必须 > 0 (AR/AP " + arap.getSourceBillNo() + ")");
            }
            if (item.getApplyAmount().compareTo(uninvoiced) > 0) {
                throw BizException.of("开票金额超未开票余额: " + arap.getSourceBillNo()
                        + ", 未开票=" + uninvoiced + ", 本次开票=" + item.getApplyAmount());
            }
            totalAmount = totalAmount.add(item.getApplyAmount());
        }

        // 4. 创建发票主表
        FinInvoice inv = new FinInvoice();
        inv.setBillNo(billNoGenerator.generate(Constants.BILL_INV));
        inv.setExternalNo(dto.getExternalNo());
        inv.setInvoiceType(dto.getInvoiceType());
        inv.setPartnerType(dto.getPartnerType());
        inv.setPartnerId(dto.getPartnerId());
        inv.setPartnerName(partnerName);
        inv.setPartnerTaxNo(partnerTaxNo);
        inv.setBillDate(dto.getBillDate());
        inv.setTotalAmount(totalAmount);
        inv.setTaxAmount(dto.getTaxAmount() == null ? BigDecimal.ZERO : dto.getTaxAmount());
        inv.setCollectedAmount(BigDecimal.ZERO);
        inv.setBalance(totalAmount);
        inv.setInvoiceStatus(STATUS_ISSUED);
        inv.setDueDate(dto.getDueDate());
        inv.setTitle(dto.getTitle());
        inv.setRemark(dto.getRemark());
        invoiceMapper.insert(inv);

        // 5. 写关联明细 + 更新 AR/AP 单
        for (FinInvoiceIssueDTO.InvoiceApplyItem item : dto.getItems()) {
            FinArap arap = arapMapper.selectById(item.getArapId());

            // 5.1 写关联
            FinInvoiceApply apply = new FinInvoiceApply();
            apply.setInvoiceId(inv.getId());
            apply.setArapId(arap.getId());
            apply.setSourceBillType(arap.getSourceBillType());
            apply.setSourceBillId(arap.getSourceBillId());
            apply.setSourceBillNo(arap.getSourceBillNo());
            apply.setApplyAmount(item.getApplyAmount());
            apply.setRemark(item.getApplyAmount().toString());
            applyMapper.insert(apply);

            // 5.2 累加 AR/AP 单的已开票金额
            BigDecimal oldInvoiced = arap.getInvoicedAmount() == null ? BigDecimal.ZERO : arap.getInvoicedAmount();
            BigDecimal newInvoiced = oldInvoiced.add(item.getApplyAmount());
            BigDecimal newUninvoiced = arap.getAmount().subtract(newInvoiced);

            String newStatus;
            if (newUninvoiced.compareTo(BigDecimal.ZERO) <= 0) {
                newStatus = INVOICE_STATUS_FULL;
                newUninvoiced = BigDecimal.ZERO;
            } else if (newInvoiced.compareTo(BigDecimal.ZERO) > 0) {
                newStatus = INVOICE_STATUS_PARTIAL;
            } else {
                newStatus = INVOICE_STATUS_NONE;
            }

            FinArap upd = new FinArap();
            upd.setId(arap.getId());
            upd.setInvoicedAmount(newInvoiced);
            upd.setUninvoicedAmount(newUninvoiced);
            upd.setInvoiceStatus(newStatus);
            upd.setLastInvoiceDate(dto.getBillDate());
            arapMapper.updateById(upd);
        }

        return inv.getId();
    }

    /**
     * 按发票核销: 收款时调用
     *
     * <p>逻辑:
     * <ol>
     *   <li>fin_invoice.collected_amount += amount, balance -= amount</li>
     *   <li>更新 invoice_status: balance<=0 → PAID; else PARTIAL</li>
     *   <li>按 apply 明细按比例分摊 amount 到各 AR/AP 单:
     *       每个 AR/AP 单的 paid_amount += 分摊金额; balance = amount - paid_amount;
     *       bill_status: balance<=0 → PAID; else PARTIAL (UNPAID 保留)</li>
     * </ol>
     *
     * @return 分摊明细 (用于前端展示 / 调试)
     */
    @OperLog(module="发票管理", businessType="EDIT", saveParam=true)
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> writeoffByInvoice(Long invoiceId, BigDecimal amount) {
        permService.requirePerm("finance:writeoff:by-invoice");
        FinInvoice inv = invoiceMapper.selectById(invoiceId);
        if (inv == null) throw BizException.of("发票不存在: id=" + invoiceId);
        if (STATUS_VOID.equals(inv.getInvoiceStatus())) throw BizException.of("发票已作废, 不能核销");

        BigDecimal newCollected = (inv.getCollectedAmount() == null ? BigDecimal.ZERO : inv.getCollectedAmount()).add(amount);
        BigDecimal newBalance = inv.getTotalAmount().subtract(newCollected);

        String newInvStatus;
        if (newBalance.compareTo(BigDecimal.ZERO) <= 0) {
            newBalance = BigDecimal.ZERO;
            newInvStatus = STATUS_PAID;
        } else {
            newInvStatus = STATUS_PARTIAL;
        }

        FinInvoice upd = new FinInvoice();
        upd.setId(invoiceId);
        upd.setCollectedAmount(newCollected);
        upd.setBalance(newBalance);
        upd.setInvoiceStatus(newInvStatus);
        invoiceMapper.updateById(upd);

        // 按比例分摊到各 AR/AP 单
        List<FinInvoiceApply> applies = applyMapper.selectByInvoiceId(invoiceId);
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> details = new ArrayList<>();

        if (!applies.isEmpty()) {
            BigDecimal allocatedTotal = BigDecimal.ZERO;
            for (int i = 0; i < applies.size(); i++) {
                FinInvoiceApply apply = applies.get(i);
                FinArap arap = arapMapper.selectById(apply.getArapId());
                if (arap == null) continue;

                BigDecimal portion;
                if (i == applies.size() - 1) {
                    // 最后一单: 拿剩余, 避免舍入误差
                    portion = amount.subtract(allocatedTotal);
                } else {
                    // 按 apply_amount 比例分摊: portion = amount * (apply_amount / total_invoice)
                    portion = amount.multiply(apply.getApplyAmount())
                            .divide(inv.getTotalAmount(), 4, RoundingMode.HALF_UP);
                }
                allocatedTotal = allocatedTotal.add(portion);

                BigDecimal oldPaid = arap.getPaidAmount() == null ? BigDecimal.ZERO : arap.getPaidAmount();
                BigDecimal newPaid = oldPaid.add(portion);
                BigDecimal newArapBalance = arap.getAmount().subtract(newPaid);
                String arapStatus;
                if (newArapBalance.compareTo(BigDecimal.ZERO) <= 0) {
                    newArapBalance = BigDecimal.ZERO;
                    arapStatus = Constants.STATUS_PAID;
                } else {
                    arapStatus = Constants.STATUS_PARTIAL;
                }

                FinArap arapUpd = new FinArap();
                arapUpd.setId(arap.getId());
                arapUpd.setPaidAmount(newPaid);
                arapUpd.setBalance(newArapBalance);
                arapUpd.setBillStatus(arapStatus);
                arapMapper.updateById(arapUpd);

                Map<String, Object> d = new HashMap<>();
                d.put("arapId", arap.getId());
                d.put("sourceBillNo", arap.getSourceBillNo());
                d.put("allocated", portion);
                details.add(d);
            }
        }

        result.put("invoiceId", invoiceId);
        result.put("newCollected", newCollected);
        result.put("newBalance", newBalance);
        result.put("invoiceStatus", newInvStatus);
        result.put("details", details);
        return result;
    }

    /**
     * 作废发票: 反向回滚 AR/AP 单
     *
     * <p>规则:
     * <ul>
     *   <li>invoice.collected_amount 必须 = 0 (没有回款) 才能作废; 否则拒绝</li>
     *   <li>软删 fin_invoice_apply 关联</li>
     *   <li>AR/AP 单: invoiced_amount -= apply_amount; uninvoiced_amount = amount - invoiced_amount;
     *       invoice_status 重算; 清 last_invoice_date (取最近一次未作废的发票日期, 简化: 设为今天, 待后续查询时回填)</li>
     *   <li>发票 invoice_status = 'VOID'</li>
     * </ul>
     */
    @OperLog(module="发票管理", businessType="VOID", saveParam=true)
    @Transactional(rollbackFor = Exception.class)
    public void voidInvoice(Long invoiceId) {
        permService.requirePerm("finance:invoice:void");
        FinInvoice inv = invoiceMapper.selectById(invoiceId);
        if (inv == null) throw BizException.of("发票不存在: id=" + invoiceId);
        if (STATUS_VOID.equals(inv.getInvoiceStatus())) throw BizException.of("发票已作废");
        if (inv.getCollectedAmount() != null && inv.getCollectedAmount().compareTo(BigDecimal.ZERO) > 0) {
            throw BizException.of("发票已有回款 ¥" + inv.getCollectedAmount() + ", 不能作废");
        }

        List<FinInvoiceApply> applies = applyMapper.selectByInvoiceId(invoiceId);
        for (FinInvoiceApply apply : applies) {
            FinArap arap = arapMapper.selectById(apply.getArapId());
            if (arap == null) continue;
            BigDecimal oldInvoiced = arap.getInvoicedAmount() == null ? BigDecimal.ZERO : arap.getInvoicedAmount();
            BigDecimal newInvoiced = oldInvoiced.subtract(apply.getApplyAmount());
            if (newInvoiced.compareTo(BigDecimal.ZERO) < 0) newInvoiced = BigDecimal.ZERO;
            BigDecimal newUninvoiced = arap.getAmount().subtract(newInvoiced);

            String newStatus;
            if (newInvoiced.compareTo(BigDecimal.ZERO) <= 0) {
                newStatus = INVOICE_STATUS_NONE;
                newInvoiced = BigDecimal.ZERO;
                newUninvoiced = arap.getAmount();
            } else {
                newStatus = INVOICE_STATUS_PARTIAL;
            }

            FinArap upd = new FinArap();
            upd.setId(arap.getId());
            upd.setInvoicedAmount(newInvoiced);
            upd.setUninvoicedAmount(newUninvoiced);
            upd.setInvoiceStatus(newStatus);
            // last_invoice_date 不重算 (避免误覆盖其他发票的最近开票日), 保留
            arapMapper.updateById(upd);
        }

        // 软删关联明细
        applyMapper.softDeleteByInvoiceId(invoiceId);

        // 更新发票状态
        FinInvoice upd = new FinInvoice();
        upd.setId(invoiceId);
        upd.setInvoiceStatus(STATUS_VOID);
        invoiceMapper.updateById(upd);
    }

    /**
     * 查发票详情 (含关联 AR/AP 单)
     */
    public Map<String, Object> getDetail(Long id) {
        FinInvoice inv = invoiceMapper.selectById(id);
        if (inv == null) throw BizException.of("发票不存在: id=" + id);
        List<FinInvoiceApply> applies = applyMapper.selectByInvoiceId(id);

        List<Map<String, Object>> araps = new ArrayList<>();
        for (FinInvoiceApply apply : applies) {
            FinArap arap = arapMapper.selectById(apply.getArapId());
            Map<String, Object> m = new HashMap<>();
            m.put("applyAmount", apply.getApplyAmount());
            m.put("arap", arap);
            araps.add(m);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("invoice", inv);
        result.put("applies", araps);
        return result;
    }

    /**
     * 按 AR/AP 单 ID 查关联的所有发票
     */
    public List<FinInvoice> getByArapId(Long arapId) {
        List<FinInvoiceApply> applies = applyMapper.selectByArapId(arapId);
        List<FinInvoice> result = new ArrayList<>();
        for (FinInvoiceApply a : applies) {
            FinInvoice inv = invoiceMapper.selectById(a.getInvoiceId());
            if (inv != null && !STATUS_VOID.equals(inv.getInvoiceStatus())) {
                result.add(inv);
            }
        }
        return result;
    }

    /**
     * 查客户/供应商的"未开票"AR/AP 单 (开票选单界面用)
     *
     * @param partnerType CUSTOMER / SUPPLIER
     * @param partnerId   客户/供应商 ID
     */
    public List<FinArap> listUninvoiced(String partnerType, Long partnerId) {
        LambdaQueryWrapper<FinArap> w = new LambdaQueryWrapper<>();
        w.eq(FinArap::getDeleted, 0);
        // AR + AP 决定 bill_type
        if ("CUSTOMER".equals(partnerType)) {
            w.eq(FinArap::getBillType, "AR");
            w.eq(FinArap::getCustomerId, partnerId);
        } else if ("SUPPLIER".equals(partnerType)) {
            w.eq(FinArap::getBillType, "AP");
            w.eq(FinArap::getSupplierId, partnerId);
        } else {
            throw BizException.of("partner_type 错误");
        }
        // 未开票或部分开票
        w.and(q -> q.eq(FinArap::getInvoiceStatus, INVOICE_STATUS_NONE)
                .or().eq(FinArap::getInvoiceStatus, INVOICE_STATUS_PARTIAL));
        w.gt(FinArap::getUninvoicedAmount, BigDecimal.ZERO);
        w.orderByAsc(FinArap::getBizDate);
        return arapMapper.selectList(w);
    }

    /**
     * 发票分页查询
     */
    public com.baomidou.mybatisplus.core.metadata.IPage<FinInvoice> page(
            Integer pageNum, Integer pageSize,
            String invoiceType, String invoiceStatus,
            String keyword) {
        permService.requirePerm("finance:invoice:list");
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<FinInvoice> p =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(pageNum, pageSize);
        LambdaQueryWrapper<FinInvoice> w = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(invoiceType)) w.eq(FinInvoice::getInvoiceType, invoiceType);
        if (StrUtil.isNotBlank(invoiceStatus)) w.eq(FinInvoice::getInvoiceStatus, invoiceStatus);
        if (StrUtil.isNotBlank(keyword)) {
            w.and(q -> q.like(FinInvoice::getExternalNo, keyword)
                    .or().like(FinInvoice::getBillNo, keyword)
                    .or().like(FinInvoice::getPartnerName, keyword));
        }
        w.orderByDesc(FinInvoice::getId);
        return invoiceMapper.selectPage(p, w);
    }
}