package com.industrial.erp.modules.base.label.context;

import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseProduct;
import com.industrial.erp.modules.base.entity.BaseProductUnit;
import com.industrial.erp.modules.base.label.LabelBizType;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.industrial.erp.modules.base.mapper.BaseProductUnitMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商品标 (GOODS_LABEL)：数据源 = 商品主数据 {@code base_product}，一个商品一张标（不挂任何单据）。
 *
 * <p>它和另外四类的根本差别是**没有「单」**：bizId 就是 product_id，所以 {@code lineIds} 无意义、
 * 也没有可核对的数量/批号/日期。这些字段在这里一律不给 —— 主数据上没有的列上了纸就是永久空白栏，
 * 而永久空白栏在模板上看起来像「版式没排好」，没人会去查数据源。
 *
 * <p>闸也刻意比合格证松：{@code status=0}（停用）的商品照样出标 —— 停用只代表不再新生产，
 * 在库的那批还得贴。唯一拦的是商品查不到（品名都没有）。
 */
@Component
public class GoodsLabelContextLoader implements LabelContextLoader {

    public GoodsLabelContextLoader(BaseProductMapper productMapper, BaseProductUnitMapper productUnitMapper) {
        this.productMapper = productMapper;
        this.productUnitMapper = productUnitMapper;
    }

    private final BaseProductMapper productMapper;
    private final BaseProductUnitMapper productUnitMapper;

    @Override
    public LabelBizType bizType() { return LabelBizType.GOODS_LABEL; }

    @Override
    public List<Map<String, Object>> load(Long bizId, List<Long> lineIds) {
        BaseProduct p = productMapper.selectById(bizId);
        if (p == null) throw BizException.of(404, "商品不存在或已删除: id=" + bizId);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productName", p.getProductName());
        m.put("productCode", p.getProductCode());
        m.put("spec", p.getSpec());
        m.put("material", p.getMaterial());
        m.put("unitName", unitNameOf(bizId));
        m.put("gramWeight", p.getGramWeight());
        m.put("width", widthMmOf(p.getWidth()));
        m.put("thickness", thicknessMmOf(p.getThickness()));
        m.put("colorNo", p.getColorNo());
        m.put("barcode", p.getBarcode());
        m.put("qrcode", p.getQrcode());
        m.put("shelfLifeDays", p.getShelfLifeDays());
        return List.of(m);
    }

    @Override
    public List<Map<String, Object>> sample() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("productName", "背心袋 30×45cm");
        row.put("productCode", "FG001");
        row.put("spec", "30×45cm");
        row.put("material", "LDPE");
        row.put("unitName", "个");
        row.put("gramWeight", new java.math.BigDecimal("8"));
        row.put("width", "30.0mm");
        row.put("thickness", "0.025mm");
        row.put("colorNo", "本色");
        row.put("barcode", "6901234567890");
        row.put("qrcode", "https://www.93gushi.com/p/FG001");
        row.put("shelfLifeDays", 365);
        return List.of(row);
    }

    /** base_product 只有 main_unit_id，单位名在 base_product_unit 的主单位行上。 */
    private String unitNameOf(Long productId) {
        BaseProductUnit u = productUnitMapper.selectMainUnit(productId);
        return u == null ? null : u.getUnitName();
    }

    /** 幅宽列存的是 cm×10（如 82.5cm 存 825），换算成 mm 印上纸面：825 → "82.5mm"。
     *  列注释写的是 mm，与实存不符；空值/0 一律留白（返回 null），别印个「0mm」。
     *  精度取 6 位小数，保证 cm×10 ÷10 与 丝 ÷1000 的极小值不被抹成 0。 */
    private String widthMmOf(BigDecimal cm) {
        if (cm == null) return null;
        BigDecimal mm = cm.divide(BigDecimal.TEN, 6, RoundingMode.HALF_UP);
        if (mm.signum() == 0) return null;
        return mm.stripTrailingZeros().toPlainString() + "mm";
    }

    /** 厚度列存的是丝（1丝 = 0.01mm = 1/100 mm，如 0.11mm 存 110 丝），换算成 mm：110 丝 → "0.11mm"。
     *  即 ÷1000。与幅宽同理，空值/0 留白。 */
    private String thicknessMmOf(BigDecimal sili) {
        if (sili == null) return null;
        BigDecimal mm = sili.divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
        if (mm.signum() == 0) return null;
        return mm.stripTrailingZeros().toPlainString() + "mm";
    }
}
