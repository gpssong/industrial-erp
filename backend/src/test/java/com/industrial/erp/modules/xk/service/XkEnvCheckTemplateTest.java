package com.industrial.erp.modules.xk.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.1.151 GB 31603-2015 生产环境检查项模板 —— 口径与快照文本的回归保护.
 *
 * <p>PC XK 生产环境检查页与 App 开工确认共用这份清单, 清单内容一变两侧同时变,
 * 因此这里锁住「项数 / 工序专项项 / 落库分组 / 未确认判定」四件事.
 */
class XkEnvCheckTemplateTest {

    @Test
    void blowListHasTenCommonPlusBlowSpecial() {
        List<XkEnvCheckTemplate.Item> items = XkEnvCheckTemplate.items(XkEnvCheckTemplate.STAGE_BLOW);
        assertThat(items).hasSize(11);
        assertThat(items.get(10).code()).isEqualTo("ENV_11");
        assertThat(items.get(10).name()).contains("风环");
    }

    @Test
    void bagListSwapsSpecialItem() {
        List<XkEnvCheckTemplate.Item> items = XkEnvCheckTemplate.items(XkEnvCheckTemplate.STAGE_BAG);
        assertThat(items).hasSize(11);
        assertThat(items.get(10).name()).contains("热封刀");
    }

    @Test
    void unknownStageFallsBackToBlow() {
        assertThat(XkEnvCheckTemplate.items(null)).hasSize(11);
        assertThat(XkEnvCheckTemplate.items("bAg")).isEqualTo(XkEnvCheckTemplate.items(XkEnvCheckTemplate.STAGE_BAG));
        assertThat(XkEnvCheckTemplate.normalizeStage("")).isEqualTo(XkEnvCheckTemplate.STAGE_BLOW);
    }

    @Test
    void onlyOneItemGoesToTempHumidityRecord() {
        List<XkEnvCheckTemplate.Item> items = XkEnvCheckTemplate.items("BLOW");
        assertThat(items.stream().filter(i -> XkEnvCheckTemplate.TYPE_TEMP_HUMIDITY.equals(i.envType())).count())
                .isEqualTo(1);
        assertThat(items.stream().filter(i -> XkEnvCheckTemplate.TYPE_CLEANLINESS.equals(i.envType())).count())
                .isEqualTo(10);
    }

    @Test
    void everyItemCarriesClauseBasis() {
        for (XkEnvCheckTemplate.Item item : XkEnvCheckTemplate.items("BLOW")) {
            assertThat(item.basis()).startsWith("GB 31603-2015");
            assertThat(item.requirement()).isNotBlank();
        }
    }

    @Test
    void problemsTreatsMissingAndFalseAsBad() {
        Map<String, Boolean> results = new HashMap<>();
        results.put("ENV_01", true);
        results.put("ENV_02", false);
        List<XkEnvCheckTemplate.Item> bad = XkEnvCheckTemplate.problems("BAG", results);
        // 11 项里 1 项合格 → 其余 10 项(含未确认)都算问题
        assertThat(bad).hasSize(10);
        assertThat(bad).extracting(XkEnvCheckTemplate.Item::code).doesNotContain("ENV_01");
        assertThat(bad).extracting(XkEnvCheckTemplate.Item::code).contains("ENV_02", "ENV_11");
    }

    @Test
    void formatLineCarriesVerdictAndNote() {
        XkEnvCheckTemplate.Item item = XkEnvCheckTemplate.items("BLOW").get(0);
        assertThat(XkEnvCheckTemplate.formatLine(1, item, true, null))
                .isEqualTo("1. " + item.name() + " — 符合");
        assertThat(XkEnvCheckTemplate.formatLine(2, item, false, " 外包装破损 "))
                .isEqualTo("2. " + item.name() + " — 不符合（外包装破损）");
        assertThat(XkEnvCheckTemplate.formatLine(3, item, false, null))
                .isEqualTo("3. " + item.name() + " — 不符合");
    }

    @Test
    void headerNamesStandardStageAndBillNo() {
        String header = XkEnvCheckTemplate.header("制袋车间", "BAG", "PD202611010001", "食品袋 20cm", 11);
        assertThat(header).contains("GB 31603-2015", "制袋车间", "制袋开工前", "11 项",
                "PD202611010001", "食品袋 20cm");
    }
}
