package com.industrial.erp.modules.base.label;

import com.industrial.erp.exception.BizException;

/** 标签业务类型 (docs/32 §3.2)。v1.1.178 补上 CERT（挂 OQC 出厂检验单）与 GOODS_LABEL（挂商品主数据）。 */
public enum LabelBizType {
    FG_ROLL("FG_ROLL", "成品卷标", "production:finished-in:list"),
    RAW_PALLET("RAW_PALLET", "原料垛标", "purchase:receipt:list"),
    CARTON("CARTON", "箱标", "sales:delivery:list"),
    /** 权限点从占位时的 `report:view` 改成真的源单权限 —— 见 docs/36 全局约束 4：能打印的人本来就能查这张单。 */
    CERT("CERT", "合格证", "quality:inspection:list"),
    GOODS_LABEL("GOODS_LABEL", "商品标", "base:product:list");

    LabelBizType(String code, String displayName, String sourcePerm) {
        this.code = code; this.displayName = displayName; this.sourcePerm = sourcePerm;
    }
    private final String code;
    private final String displayName;
    private final String sourcePerm;

    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }
    /** 取这张单的上下文本来就该有的权限点 —— 标签接口的 @SaCheckPermission 要 OR 上它。 */
    public String getSourcePerm() { return sourcePerm; }

    public static LabelBizType of(String code) {
        for (LabelBizType t : values()) if (t.code.equalsIgnoreCase(code)) return t;
        throw BizException.of(400, "不支持的标签类型: " + code);
    }
}
