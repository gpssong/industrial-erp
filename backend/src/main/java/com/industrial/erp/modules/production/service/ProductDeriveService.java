package com.industrial.erp.modules.production.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.industrial.erp.modules.base.entity.BaseProduct;
import com.industrial.erp.modules.base.entity.BaseProductUnit;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.industrial.erp.modules.base.mapper.BaseProductUnitMapper;
import com.industrial.erp.modules.production.entity.PrdOrder;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * v1.1.142: 「规格 / 单位」派生值的唯一口径出口 (工单、成品入库单、半成品入库单共用).
 *
 * 背景: prd_order.spec / unit_id / unit_name 是开单时从商品拷的快照, 老单常年为空 ——
 *   - base_product.spec 本身没填, 规格写在 model 里 (带鱼袋 dyd975007: spec='', model='9*75*0.07');
 *   - 单位只存在于 base_product_unit (base_product 上没有 unit_id, 只有 main_unit_id),
 *     开单时没人往 prd_order 写过单位 → 工单上 unit_id/unit_name 全 NULL.
 * 结果: App 成品入库页选完工单, 规格显示「—」、单位显示「该商品未配置单位」,
 *       而商品主数据其实好好地配了主单位「只」; 存下的入库单与 inv_ledger 也跟着落 NULL 单位.
 *
 * 口径 (只补空值, 绝不覆盖单据上已有的快照):
 *   规格 = 单据快照 → base_product.spec → base_product.model → 工单快照
 *   单位 = 单据快照 → base_product_unit 主单位 (is_main=1, deleted=0) → 工单快照
 *   编码/名称 = 单据快照 → base_product → 工单快照
 */
@Component
public class ProductDeriveService {

    private final BaseProductMapper productMapper;
    private final BaseProductUnitMapper productUnitMapper;

    public ProductDeriveService(BaseProductMapper productMapper, BaseProductUnitMapper productUnitMapper) {
        this.productMapper = productMapper;
        this.productUnitMapper = productUnitMapper;
    }

    /** 批量取主单位 (分页注入用, 避免每行一次查询). 无主单位的产品不会出现在 map 里. */
    public Map<Long, BaseProductUnit> mainUnits(Collection<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) return Collections.emptyMap();
        Set<Long> ids = productIds.stream().filter(java.util.Objects::nonNull).collect(Collectors.toCollection(HashSet::new));
        if (ids.isEmpty()) return Collections.emptyMap();
        List<BaseProductUnit> rows = productUnitMapper.selectList(new LambdaQueryWrapper<BaseProductUnit>()
                .in(BaseProductUnit::getProductId, ids)
                .eq(BaseProductUnit::getIsMain, 1));
        return rows.stream().filter(u -> u != null && StrUtil.isNotBlank(u.getUnitName()))
                .collect(Collectors.toMap(BaseProductUnit::getProductId, u -> u, (a, b) -> a));
    }

    public BaseProductUnit mainUnit(Long productId) {
        if (productId == null) return null;
        BaseProductUnit u = productUnitMapper.selectMainUnit(productId);
        return (u != null && StrUtil.isNotBlank(u.getUnitName())) ? u : null;
    }

    public BaseProduct product(Long productId) {
        return productId == null ? null : productMapper.selectById(productId);
    }

    /** 快照为空时按 商品 spec → 商品 model 兜底; 两者都空则原样返回. */
    public String spec(String snapshot, BaseProduct p) {
        if (StrUtil.isNotBlank(snapshot)) return snapshot;
        if (p == null) return snapshot;
        if (StrUtil.isNotBlank(p.getSpec())) return p.getSpec();
        return p.getModel();
    }

    /**
     * 单据快照上「有没有单位」的判定 —— 只认 unit_name.
     * unit_id=0 是 base_product_unit 的历史脏行 (指向不存在的 base_unit), 与 null 同等对待 (v1.1.117 实测),
     * 所以有名字就算有单位, 回填 unitId 时再单独挡掉 0.
     */
    public boolean hasUnit(String unitName) {
        return StrUtil.isNotBlank(unitName);
    }

    /** 入库单该带的 商品/规格/单位 快照 (已按优先级解析完毕, 调用方可直接覆盖写回). */
    public static class InboundFields {
        public Long productId;
        public String productCode;
        public String productName;
        public String spec;
        public Long unitId;
        public String unitName;
    }

    /**
     * 解析一张入库单 (成品/半成品) 应当携带的 商品/规格/单位: 入参是客户端送来的快照,
     * 空位按 商品主数据 → 关联工单快照 兜底; 非空位原样返回, 不覆盖已有快照.
     * order 可为 null (无工单的独立入库单).
     */
    public InboundFields inboundFields(Long productId, String productCode, String productName,
                                       String spec, Long unitId, String unitName, PrdOrder order) {
        InboundFields f = new InboundFields();
        if (productId == null && order != null) productId = order.getProductId();
        f.productId = productId;
        BaseProduct p = product(productId);
        BaseProductUnit u = mainUnit(productId);
        f.productCode = firstNonBlank(productCode, p == null ? null : p.getProductCode(),
                order == null ? null : order.getProductCode());
        f.productName = firstNonBlank(productName, p == null ? null : p.getProductName(),
                order == null ? null : order.getProductName());
        f.spec = spec(productId, spec, order == null ? null : order.getSpec(), p);
        f.unitId = unitId;
        f.unitName = unitName;
        if (!hasUnit(f.unitName)) {
            if (u != null) {
                f.unitName = u.getUnitName();
                f.unitId = validUnitId(u.getUnitId(), unitId);
            } else if (order != null && hasUnit(order.getUnitName())) {
                f.unitName = order.getUnitName();
                f.unitId = validUnitId(order.getUnitId(), unitId);
            }
        } else if (f.unitId == null || f.unitId <= 0L) {
            f.unitId = validUnitId(u == null ? null : u.getUnitId(), unitId);
        }
        return f;
    }

    /** 规格解析: 单据快照 → 商品 spec → 商品 model → 工单快照; 全空则原样返回. */
    public String spec(Long productId, String snapshot, String orderSpec, BaseProduct p) {
        if (StrUtil.isNotBlank(snapshot)) return snapshot;
        String fromProduct = spec(snapshot, p);
        if (StrUtil.isNotBlank(fromProduct)) return fromProduct;
        return StrUtil.isNotBlank(orderSpec) ? orderSpec : snapshot;
    }

    /** 只接受真实存在的 base_unit 主键; null / 0 (脏行) 都视为无, 返回 null 让调用方保持原值. */
    private Long validUnitId(Long candidate, Long snapshot) {
        if (candidate != null && candidate > 0L) return candidate;
        return snapshot != null && snapshot > 0L ? snapshot : null;
    }

    /** 按优先级取第一个非空白值; 全空则返回第一个入参 (通常是单据快照, 保持原样). */
    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (StrUtil.isNotBlank(c)) return c;
        }
        return candidates.length > 0 ? candidates[0] : null;
    }
}
