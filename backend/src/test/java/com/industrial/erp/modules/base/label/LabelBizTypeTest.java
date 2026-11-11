package com.industrial.erp.modules.base.label;

import com.industrial.erp.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * v1.1.167 标签业务类型 (A4)。sourcePerm 是 A10 打印端点 @SaCheckPermission 要 OR 的权限点，
 * 写错就是线上 403，所以逐个钉住 —— 尤其 FG_ROLL 用的是成品入库单的权限，不是商品主数据。
 */
class LabelBizTypeTest {

    @Test
    @DisplayName("五个类型的 code / 中文名 / 取上下文本该有的权限点")
    void carriesCodeNameAndSourcePerm() {
        assertThat(LabelBizType.FG_ROLL.getCode()).isEqualTo("FG_ROLL");
        assertThat(LabelBizType.FG_ROLL.getDisplayName()).isEqualTo("成品卷标");
        assertThat(LabelBizType.FG_ROLL.getSourcePerm()).isEqualTo("production:finished-in:list");
        assertThat(LabelBizType.RAW_PALLET.getSourcePerm()).isEqualTo("purchase:receipt:list");
        assertThat(LabelBizType.CARTON.getSourcePerm()).isEqualTo("sales:delivery:list");
        // v1.1.178：CERT 有了真 loader，sourcePerm 从占位时的 report:view 换成出厂检验单自己的权限点
        assertThat(LabelBizType.CERT.getDisplayName()).isEqualTo("合格证");
        assertThat(LabelBizType.CERT.getSourcePerm()).isEqualTo("quality:inspection:list");
        assertThat(LabelBizType.GOODS_LABEL.getCode()).isEqualTo("GOODS_LABEL");
        assertThat(LabelBizType.GOODS_LABEL.getDisplayName()).isEqualTo("商品标");
        assertThat(LabelBizType.GOODS_LABEL.getSourcePerm()).isEqualTo("base:product:list");
        assertThat(LabelBizType.values()).hasSize(5);
    }

    @Test
    @DisplayName("of() 认 code 且大小写不敏感")
    void ofMatchesIgnoringCase() {
        assertThat(LabelBizType.of("FG_ROLL")).isEqualTo(LabelBizType.FG_ROLL);
        assertThat(LabelBizType.of("fg_roll")).isEqualTo(LabelBizType.FG_ROLL);
        assertThat(LabelBizType.of("Carton")).isEqualTo(LabelBizType.CARTON);
    }

    @Test
    @DisplayName("未知 code 与 null 都回 400，报错带原值 —— 别把脏参数变成 500")
    void ofRejectsUnknownAndNullWith400() {
        assertThatThrownBy(() -> LabelBizType.of("SEMI_PALLET"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("SEMI_PALLET")
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(400);
        assertThatThrownBy(() -> LabelBizType.of(null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不支持的标签类型");
    }
}
