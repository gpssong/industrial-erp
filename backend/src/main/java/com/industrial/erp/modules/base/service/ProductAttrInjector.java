package com.industrial.erp.modules.base.service;

import com.industrial.erp.modules.base.entity.BaseProduct;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 商品属性批量注入工具.
 * <p>原本每个 Service / BillLoader 内部循环 productMapper.selectById 触发 N+1 查询,
 * 现在通过 selectBatchIds 一次查所有用到的 productId, 然后在内存里逐行写入.
 *
 * <p>使用示例 (明细行有 productId 字段, 想注入 pColorNo):
 * <pre>{@code
 *   ProductAttrInjector.injectColorNo(
 *       productMapper,
 *       details,
 *       SalDeliveryDetail::getProductId,
 *       SalDeliveryDetail::setPColorNo,
 *       BaseProduct::getColorNo
 *   );
 * }</pre>
 *
 * <p>注意: 当 productId 列表为空时会 short-circuit 返回, 不发 DB.
 *
 * @since v1.0.5+
 */
public final class ProductAttrInjector {

    private ProductAttrInjector() {}

    /**
     * 通用方法: 从行集合抽出 productId, 批量查 BaseProduct, 然后逐行 setter 注入.
     *
     * @param mapper          BaseProductMapper
     * @param rows            待注入的行集合
     * @param productIdGetter 行 -> productId
     * @param setter          行 -> setter (接收 BaseProduct.colorNo 等值)
     * @param attrGetter      BaseProduct -> 属性
     * @param <ROW>           行类型 (SalDeliveryDetail / PurReceiptDetail 等)
     * @param <VALUE>         属性类型 (String / BigDecimal ...)
     */
    public static <ROW, VALUE> void inject(BaseProductMapper mapper,
                                            List<ROW> rows,
                                            Function<ROW, Long> productIdGetter,
                                            BiConsumer<ROW, VALUE> setter,
                                            Function<BaseProduct, VALUE> attrGetter) {
        if (rows == null || rows.isEmpty()) return;
        Set<Long> ids = new HashSet<>();
        for (ROW row : rows) {
            Long pid = productIdGetter.apply(row);
            if (pid != null) ids.add(pid);
        }
        if (ids.isEmpty()) return;
        // 一次性批量查全部产品
        List<BaseProduct> prods = mapper.selectBatchIds(ids);
        if (prods == null || prods.isEmpty()) return;
        Map<Long, BaseProduct> byId = prods.stream()
                .collect(Collectors.toMap(BaseProduct::getId, p -> p, (a, b) -> a));
        // 内存里逐行注入
        for (ROW row : rows) {
            Long pid = productIdGetter.apply(row);
            if (pid == null) continue;
            BaseProduct p = byId.get(pid);
            if (p == null) continue;
            setter.accept(row, attrGetter.apply(p));
        }
    }

    /**
     * 便捷方法: 注入色号 (colorNo) 到行集合的 pColorNo transient 字段.
     *
     * <p>调用方约定行类型有 setPColorNo(String) / getProductId() 方法.
     */
    @SuppressWarnings("unchecked")
    public static void injectColorNo(BaseProductMapper mapper,
                                     List<?> rows,
                                     java.util.function.Function<Object, Long> productIdGetter,
                                     java.util.function.BiConsumer<Object, String> setter) {
        if (rows == null || rows.isEmpty()) return;
        Set<Long> ids = new HashSet<>();
        for (Object row : rows) {
            Long pid = productIdGetter.apply(row);
            if (pid != null) ids.add(pid);
        }
        if (ids.isEmpty()) return;
        List<BaseProduct> prods = mapper.selectBatchIds(ids);
        if (prods == null || prods.isEmpty()) return;
        Map<Long, BaseProduct> byId = prods.stream()
                .collect(Collectors.toMap(BaseProduct::getId, p -> p, (a, b) -> a));
        for (Object row : rows) {
            Long pid = productIdGetter.apply(row);
            if (pid == null) continue;
            BaseProduct p = byId.get(pid);
            if (p != null) setter.accept(row, p.getColorNo());
        }
    }
}
