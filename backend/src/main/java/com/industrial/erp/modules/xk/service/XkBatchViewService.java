package com.industrial.erp.modules.xk.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.industrial.erp.modules.base.entity.BaseProduct;
import com.industrial.erp.modules.base.entity.BaseSupplier;
import com.industrial.erp.modules.base.entity.BaseUnit;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.industrial.erp.modules.base.mapper.BaseSupplierMapper;
import com.industrial.erp.modules.base.mapper.BaseUnitMapper;
import com.industrial.erp.modules.mes.entity.MesBatch;
import com.industrial.erp.modules.mes.mapper.MesBatchMapper;
import com.industrial.erp.modules.production.entity.PrdOrder;
import com.industrial.erp.modules.production.mapper.PrdOrderMapper;
import com.industrial.erp.modules.xk.dto.XkBatchView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * v1.1.189 — 批次台账视图服务: 把 mes_batch 的 id 补成现场看得懂的名称.
 *
 * <p>品名口径:
 * <ul>
 *   <li>RAW 原料批 → base_product.product_name (mes_batch.product_id)</li>
 *   <li>FG 成品批 → mes_batch.product_id 为空 ⇒ 按来源工单 prd_order.bill_no 回查 product_name</li>
 * </ul>
 * 名称一律批量查 (selectBatchIds / in), 不用逐行查库。
 */
@Service
public class XkBatchViewService {

    private final MesBatchMapper batchMapper;
    private final BaseProductMapper productMapper;
    private final BaseSupplierMapper supplierMapper;
    private final BaseUnitMapper unitMapper;
    private final PrdOrderMapper orderMapper;

    public XkBatchViewService(MesBatchMapper batchMapper,
                              BaseProductMapper productMapper,
                              BaseSupplierMapper supplierMapper,
                              BaseUnitMapper unitMapper,
                              PrdOrderMapper orderMapper) {
        this.batchMapper = batchMapper;
        this.productMapper = productMapper;
        this.supplierMapper = supplierMapper;
        this.unitMapper = unitMapper;
        this.orderMapper = orderMapper;
    }

    /** 批次台账 (按类型/产品筛, 最近 200 条) + 名称补全. */
    @Transactional(readOnly = true)
    public List<XkBatchView> listViews(String batchType, Long productId) {
        List<MesBatch> batches = batchMapper.selectList(new LambdaQueryWrapper<MesBatch>()
                .eq(StrUtil.isNotBlank(batchType), MesBatch::getBatchType, batchType)
                .eq(productId != null, MesBatch::getProductId, productId)
                .orderByDesc(MesBatch::getCreateTime)
                .last("LIMIT 200"));
        if (batches.isEmpty()) return Collections.emptyList();
        Names names = loadNames(batches);
        List<XkBatchView> out = new ArrayList<>(batches.size());
        for (MesBatch b : batches) out.add(toView(b, names));
        return out;
    }

    /** 单个批次的品名 (详情页用; 查不到返回空串, 不报错). */
    @Transactional(readOnly = true)
    public String productNameOf(MesBatch batch) {
        if (batch == null) return "";
        Names names = loadNames(Collections.singletonList(batch));
        return names.productName(batch);
    }

    /** 单个批次的供应商名 (详情页用). */
    @Transactional(readOnly = true)
    public String supplierNameOf(MesBatch batch) {
        if (batch == null) return "";
        Names names = loadNames(Collections.singletonList(batch));
        return names.supplierName(batch);
    }

    private XkBatchView toView(MesBatch b, Names names) {
        XkBatchView v = new XkBatchView();
        v.setBatchNo(b.getBatchNo());
        v.setBatchType(b.getBatchType());
        v.setProductId(b.getProductId());
        v.setProductName(names.productName(b));
        v.setSupplierId(b.getSupplierId());
        v.setSupplierName(names.supplierName(b));
        v.setReceiptBillNo(b.getReceiptBillNo());
        v.setSourcePrdOrderNo(b.getSourcePrdOrderNo());
        v.setProcessName(b.getProcessName());
        v.setRollNo(b.getRollNo());
        v.setStatus(b.getStatus());
        v.setHoldReason(b.getHoldReason());
        v.setQty(b.getQty());
        v.setUnitId(b.getUnitId());
        v.setUnitName(names.unitName(b));
        v.setProductionDate(b.getProductionDate());
        v.setCreateTime(b.getCreateTime());
        v.setRemark(b.getRemark());
        return v;
    }

    /** 批量把 id 换成名称 (一次查一批, 不做 N+1). */
    private Names loadNames(List<MesBatch> batches) {
        Set<Long> productIds = batches.stream().map(MesBatch::getProductId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> supplierIds = batches.stream().map(MesBatch::getSupplierId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> unitIds = batches.stream().map(MesBatch::getUnitId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> orderNos = batches.stream().map(MesBatch::getSourcePrdOrderNo)
                .filter(StrUtil::isNotBlank).collect(Collectors.toCollection(LinkedHashSet::new));

        Map<Long, String> products = new LinkedHashMap<>();
        if (!productIds.isEmpty()) {
            for (BaseProduct p : productMapper.selectBatchIds(productIds)) {
                products.put(p.getId(), p.getProductName());
            }
        }
        Map<Long, String> suppliers = new LinkedHashMap<>();
        if (!supplierIds.isEmpty()) {
            for (BaseSupplier s : supplierMapper.selectBatchIds(supplierIds)) {
                suppliers.put(s.getId(), s.getSupplierName());
            }
        }
        Map<Long, String> units = new LinkedHashMap<>();
        if (!unitIds.isEmpty()) {
            for (BaseUnit u : unitMapper.selectBatchIds(unitIds)) {
                units.put(u.getId(), u.getUnitName());
            }
        }
        Map<String, PrdOrder> orders = new LinkedHashMap<>();
        if (!orderNos.isEmpty()) {
            for (PrdOrder o : orderMapper.selectList(new LambdaQueryWrapper<PrdOrder>()
                    .in(PrdOrder::getBillNo, orderNos))) {
                orders.put(o.getBillNo(), o);
            }
        }
        return new Names(products, suppliers, units, orders);
    }

    /** 名称索引: 批次 → 名称. */
    private static class Names {
        private final Map<Long, String> products;
        private final Map<Long, String> suppliers;
        private final Map<Long, String> units;
        private final Map<String, PrdOrder> orders;

        Names(Map<Long, String> products, Map<Long, String> suppliers,
              Map<Long, String> units, Map<String, PrdOrder> orders) {
            this.products = products;
            this.suppliers = suppliers;
            this.units = units;
            this.orders = orders;
        }

        String productName(MesBatch b) {
            if (b.getProductId() != null) {
                String n = products.get(b.getProductId());
                if (StrUtil.isNotBlank(n)) return n;
            }
            // FG 成品批没有 product_id ⇒ 按来源工单回查 (prd_order.product_name)
            PrdOrder o = b.getSourcePrdOrderNo() == null ? null : orders.get(b.getSourcePrdOrderNo());
            return o != null ? StrUtil.nullToEmpty(o.getProductName()) : "";
        }

        String supplierName(MesBatch b) {
            if (b.getSupplierId() == null) return "";
            String n = suppliers.get(b.getSupplierId());
            return n == null ? "" : n;
        }

        String unitName(MesBatch b) {
            if (b.getUnitId() != null) {
                String n = units.get(b.getUnitId());
                if (StrUtil.isNotBlank(n)) return n;
            }
            PrdOrder o = b.getSourcePrdOrderNo() == null ? null : orders.get(b.getSourcePrdOrderNo());
            return o != null ? StrUtil.nullToEmpty(o.getUnitName()) : "";
        }
    }
}
