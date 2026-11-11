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
        // v1.1.178 合格证：检验单侧 9（品名/编码/规格/批号/合格数量/单位…含批号回查的生产日期与顺源单的生产单号）
        // + 商品主数据补缺 4（材质/条码/保质期/单位）+ 6 主数据 = 19
        assertThat(LabelFieldDict.fields("CERT")).hasSize(19)
                .contains("inspectionBillNo", "inspectionDate", "material", "shelfLifeDays")
                .doesNotContain("supplierName", "customerName", "deliveryBillNo");
        // v1.1.178 商品标：主数据没有数量/批号/日期 ⇒ 名单里也不许有，否则保存闸会放行一个永久空白栏
        assertThat(LabelFieldDict.fields("GOODS_LABEL")).hasSize(18)
                .contains("barcode", "qrcode", "gramWeight")
                .doesNotContain("qty", "batchNo", "productionDate", "orderNo");
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
    @DisplayName("null bizType 只回主数据字段 —— 修前 Map.getOrDefault(null, …) 是直接 NPE")
    void nullBizTypeDegradesToMasterOnly() {
        assertThat(LabelFieldDict.fields(null)).isEqualTo(LabelFieldDict.MASTER_FIELDS);
        assertThat(LabelFieldDict.isKnown(null, "producerName")).isTrue();
    }

    @Test
    @DisplayName("label() 认得的给中文，认不出的原样回 —— 报错信息不能变成 null")
    void labelFallsBackToFieldName() {
        assertThat(LabelFieldDict.label("batchNo")).isEqualTo("批号");
        assertThat(LabelFieldDict.label("inspectorCode")).isEqualTo("检验员代号");
        // v1.1.178 新增：检验单号不能和采购那边「检验报告号」混用同一个键名，否则两个含义共用一份中文
        assertThat(LabelFieldDict.label("inspectionBillNo")).isEqualTo("检验单号");
        assertThat(LabelFieldDict.label("inspectionDate")).isEqualTo("检验日期");
        assertThat(LabelFieldDict.label("shelfLifeDays")).isEqualTo("保质期(天)");
        assertThat(LabelFieldDict.label("someUnknown")).isEqualTo("someUnknown");
    }
}
