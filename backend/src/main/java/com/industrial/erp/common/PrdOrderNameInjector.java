package com.industrial.erp.common;

import com.industrial.erp.modules.production.entity.PrdOrder;
import com.industrial.erp.modules.production.mapper.PrdOrderMapper;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 给单据列表注入「关联工单的商品名称」工具类。
 * <p>
 * 背景: 排程 (mes_schedule)、米重采样 (mes_weight_sample) 等表只冗余存了 {@code prd_order_no}
 * 工单号, 没有商品名称。前端列表显示工单号时看不出是哪个商品, 车间人员得再去查工单单据。
 * <p>
 * 模式: 拿到列表后 collect 所有非空 {@code getPrdOrderId()} → {@code prdOrderMapper.selectBatchIds}
 * 一次查 → 注入 {@code setProductName(prd_order.product_name)}. 缺失/已删工单 → 保持 null,
 * 前端回退显示编号。
 *
 * <pre>{@code
 * // Entity 必备字段 (例 MesSchedule):
 * @TableField(exist = false)
 * private String productName;
 * public String getProductName() { return productName; }
 * public void setProductName(String productName) { this.productName = productName; }
 *
 * // Service 调用:
 * PrdOrderNameInjector.inject(prdOrderMapper, page.getRecords(),
 *         MesSchedule::getPrdOrderId, MesSchedule::setProductName);
 * }</pre>
 *
 * @param <T> 列表元素类型 (实体类)
 */
public final class PrdOrderNameInjector<T> {

    private final PrdOrderMapper prdOrderMapper;
    private final Function<T, Long> getter;
    private final BiConsumer<T, String> setter;

    private PrdOrderNameInjector(PrdOrderMapper prdOrderMapper,
                                 Function<T, Long> getter,
                                 BiConsumer<T, String> setter) {
        this.prdOrderMapper = prdOrderMapper;
        this.getter = getter;
        this.setter = setter;
    }

    public static <T> PrdOrderNameInjector<T> of(PrdOrderMapper prdOrderMapper,
                                                Function<T, Long> getter,
                                                BiConsumer<T, String> setter) {
        return new PrdOrderNameInjector<>(prdOrderMapper, getter, setter);
    }

    /**
     * 批量注入商品名称。records 为 null 或空时直接返回, 不做任何查询。
     */
    public void inject(Collection<T> records) {
        if (records == null || records.isEmpty()) return;
        Set<Long> orderIds = new HashSet<>();
        for (T row : records) {
            Long oid = getter.apply(row);
            if (oid != null) orderIds.add(oid);
        }
        if (orderIds.isEmpty()) return;
        Map<Long, String> nameMap = new HashMap<>(orderIds.size() * 2);
        for (PrdOrder o : prdOrderMapper.selectBatchIds(orderIds)) {
            if (o != null && o.getId() != null) {
                nameMap.put(o.getId(), resolveName(o));
            }
        }
        for (T row : records) {
            Long oid = getter.apply(row);
            if (oid != null) {
                setter.accept(row, nameMap.get(oid));
            }
        }
    }

    /**
     * 商品显示名: 优先 {@code productName}, 为空回退 {@code productCode}。
     * <p>
     * 历史工单存在 product_name 为空的情况 (建单时未填), 回退编码保证前端不显示空白。
     */
    static String resolveName(PrdOrder o) {
        String name = o.getProductName();
        if (name == null || name.trim().isEmpty()) {
            name = o.getProductCode();
        }
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        return name;
    }

    /**
     * 静态便捷方法。{@code records} 为 null 或空时直接返回, 不做任何查询。
     */
    public static <T> void inject(PrdOrderMapper prdOrderMapper,
                                  Collection<T> records,
                                  Function<T, Long> getter,
                                  BiConsumer<T, String> setter) {
        if (records == null || records.isEmpty()) return;
        of(prdOrderMapper, getter, setter).inject(records);
    }
}
