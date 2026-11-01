package com.industrial.erp.modules.qms;

import java.util.Set;

/**
 * v1.1.149 — 本公司不做印刷: 达因/印刷质量相关的 4 项允许判「不适用」(pass_flag=2),
 * 其余检验项不得用不适用规避实测。保存与签核两条路径共用这份清单。
 */
public final class QmsItemCodes {
    private QmsItemCodes() {
    }

    public static final Set<String> NA_ONLY_ITEMS = Set.of(
            "PAT_CORONA", "OQC_FILM_CORONA", "BAG_PRINT", "OQC_BAG_PRINT");

    public static boolean isNaAllowed(String itemCode) {
        return NA_ONLY_ITEMS.contains(itemCode);
    }
}
