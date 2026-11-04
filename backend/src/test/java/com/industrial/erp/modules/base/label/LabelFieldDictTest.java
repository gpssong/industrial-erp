package com.industrial.erp.modules.base.label;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.1.167 字段字典 (A4)。A6 的保存闸会把每个元素字段拿到这里判合法，
 * 所以「名单里有什么」「大小写怎么写」「未知 bizType 回什么」必须钉死在测试里，
 * 不能只靠一次性探针。
 */
class LabelFieldDictTest {

    @Test
    @DisplayName("每个 bizType = 业务字段 ∪ 6 个主数据字段")
    void fieldsAreBizListUnionMaster() {
        assertThat(LabelFieldDict.fields("FG_ROLL")).hasSize(19).contains("batchNo", "gramWeight");
        assertThat(LabelFieldDict.fields("RAW_PALLET")).hasSize(15).contains("supplierName");
        assertThat(LabelFieldDict.fields("CARTON")).hasSize(17).contains("customerName");
        assertThat(LabelFieldDict.fields("CERT")).hasSize(14).doesNotContain("supplierName");
        assertThat(LabelFieldDict.fields("FG_ROLL")).containsAll(LabelFieldDict.MASTER_FIELDS);
    }

    @Test
    @DisplayName("bizType 大小写不敏感 —— 枚举 LabelBizType.of 宽容，字典不能比枚举严")
    void bizTypeIsCaseInsensitive() {
        assertThat(LabelFieldDict.fields("fg_roll")).isEqualTo(LabelFieldDict.fields("FG_ROLL"));
        assertThat(LabelFieldDict.isKnown("Fg_Roll", "batchNo")).isTrue();
        assertThat(LabelFieldDict.isKnown("fg_roll", "supplierName")).isFalse();
    }

    @Test
    @DisplayName("未知 bizType 只回主数据字段、不抛；认得出的字段回 false")
    void unknownBizTypeDegradesToMasterOnly() {
        assertThat(LabelFieldDict.fields("NO_SUCH")).isEqualTo(LabelFieldDict.MASTER_FIELDS);
        assertThat(LabelFieldDict.isKnown("NO_SUCH", "batchNo")).isFalse();
        assertThat(LabelFieldDict.isKnown("NO_SUCH", "producerName")).isTrue();
        assertThat(LabelFieldDict.isKnown("FG_ROLL", "notAField")).isFalse();
    }

    @Test
    @DisplayName("label() 认得的给中文，认不出的原样回 —— 报错信息不能变成 null")
    void labelFallsBackToFieldName() {
        assertThat(LabelFieldDict.label("batchNo")).isEqualTo("批号");
        assertThat(LabelFieldDict.label("inspectorCode")).isEqualTo("检验员代号");
        assertThat(LabelFieldDict.label("someUnknown")).isEqualTo("someUnknown");
    }
}
