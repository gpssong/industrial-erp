package com.industrial.erp.modules.qms;

import com.industrial.erp.common.Constants;

import java.util.Set;

/**
 * 「不适用」(pass_flag=2) 的放行清单.
 *
 * <p>v1.1.149 — 本公司不做印刷: 达因/印刷质量相关的 4 项允许判「不适用」,
 * 其余检验项不得用不适用规避实测。工序检/出厂检 (FAI/PAT/OQC/OQC_RETURN)
 * 属 XK 许可固定清单, 这条防线到 v1.1.184 仍然保留。</p>
 *
 * <p>v1.1.184 — 进货检验 (IQC) 的「不适用」是另一个语义: 指「公司入库不检验」
 * (现场没有检测条件, 例如熔指/密度/重金属, 由供方报告覆盖)。这类项服务端建单时
 * 默认就写 pass_flag=2, 保存时必须认账 —— 此前保存闸不分检验类型, 导致 App
 * 「保存检验记录」必然报「不能判不适用」。</p>
 */
public final class QmsItemCodes {
    private QmsItemCodes() {
    }

    /** 工序/出厂检验唯一可判不适用的四项 (本公司不做印刷). */
    public static final Set<String> NA_ONLY_ITEMS = Set.of(
            "PAT_CORONA", "OQC_FILM_CORONA", "BAG_PRINT", "OQC_BAG_PRINT");

    /** 进货检验里「公司入库不检验」的项目 (建单默认不适用). */
    public static final Set<String> IQC_NA_ITEMS = Set.of(
            "MFR_DENSITY", "MFR", "DENSITY", "FOOD_CONTACT_HEAVY_METAL");

    public static boolean isNaAllowed(String itemCode) {
        return NA_ONLY_ITEMS.contains(itemCode);
    }

    /**
     * 按检验类型判放行范围: 进货检验放行 IQC_NA_ITEMS, 工序/出厂检验只放行 NA_ONLY_ITEMS.
     *
     * @param inspectionType IQC / FAI / PAT / OQC / OQC_RETURN
     */
    public static boolean isNaAllowed(String itemCode, String inspectionType) {
        if (NA_ONLY_ITEMS.contains(itemCode)) return true;
        return Constants.INSPECTION_IQC.equals(inspectionType) && IQC_NA_ITEMS.contains(itemCode);
    }
}
