package com.industrial.erp.modules.system.controller;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.1.165 — 「对接方式」保存是整串 AI_INTEGRATION 覆盖, 所以一次只改 OCR 的保存会冲掉 LLM 已存的 key,
 * 反过来也一样 (TextIn 凭证存好后被 LLM 卡片保存抹掉). 本测试锁住服务端沿用逻辑.
 */
@DisplayName("SysIntegrationController: 保存时沿用库内已存密钥")
class IntegrationSecretMergeTest {

    // 占位假凭证 — 本测试只验「沿用」语义, 真凭证一律不进仓库.
    private static final String TI_APP_ID = "aaaabbbbccccdddd111122223333fffe";
    private static final String TI_SECRET = "deadbeefcafebabe0123456789abcdef";

    private static final String STORED = "{"
            + "\"llm\":{\"providerOrder\":\"agnes,minimax\",\"slot0\":{\"key\":\"sk-old-agnes-0001\",\"model\":\"m0\"},"
            + "                 \"slot1\":{\"key\":\"sk-old-minimax-02\",\"model\":\"m1\"}},"
            + "\"asr\":{\"key\":\"sk-old-asr-000003\"},"
            + "\"ocr\":{\"provider\":\"textin\",\"key\":\"sk-old-vl-000004\","
            +       "\"textinAppId\":\"" + TI_APP_ID + "\",\"textinSecretCode\":\"" + TI_SECRET + "\"}"
            + "}";

    private static JSONObject merge(String incoming) {
        return JSONUtil.parseObj(SysIntegrationController.mergeSecretsWithStored(incoming, STORED));
    }

    @Test
    @DisplayName("只发 llm 段 (老前端行为): OCR / ASR 的 key 与 TextIn 凭证一起保住")
    void missingSectionsCarryOver() {
        JSONObject out = merge("{\"llm\":{\"providerOrder\":\"agnes\",\"slot0\":{\"model\":\"new-model\"}}}");
        JSONObject ocr = out.getJSONObject("ocr");
        assertThat(ocr.getStr("textinAppId")).isEqualTo(TI_APP_ID);
        assertThat(ocr.getStr("textinSecretCode")).isEqualTo(TI_SECRET);
        assertThat(ocr.getStr("provider")).isEqualTo("textin");
        assertThat(out.getJSONObject("asr").getStr("key")).isEqualTo("sk-old-asr-000003");
        // 非密钥字段仍按提交值覆盖 (要能把 model 改回默认)
        assertThat(out.getJSONObject("llm").getJSONObject("slot0").getStr("model")).isEqualTo("new-model");
    }

    @Test
    @DisplayName("只发 ocr.provider: 段存在但没带 key, 照样沿用库里四把 key")
    void sectionPresentButKeyAbsent() {
        JSONObject out = merge("{\"ocr\":{\"provider\":\"llm\"}}");
        JSONObject ocr = out.getJSONObject("ocr");
        assertThat(ocr.getStr("provider")).isEqualTo("llm");     // 本次选的算数
        assertThat(ocr.getStr("key")).isEqualTo("sk-old-vl-000004");
        assertThat(ocr.getStr("textinAppId")).startsWith("aaaa");
    }

    @Test
    @DisplayName("填了新值就用新的; 传空串等于「不动」(前端留空就是不发这个字段)")
    void newSecretWins() {
        JSONObject out = merge("{\"ocr\":{\"textinAppId\":\"brand-new-app-id-1234567890\"}}");
        assertThat(out.getJSONObject("ocr").getStr("textinAppId")).isEqualTo("brand-new-app-id-1234567890");
        assertThat(out.getJSONObject("ocr").getStr("textinSecretCode")).startsWith("dead");
    }

    @Test
    @DisplayName("库里存过脱敏值 (历史脏数据) 不能被当凭证写回; 提交脱敏值也不算带了新值")
    void maskedValuesIgnored() {
        String dirty = "{\"ocr\":{\"textinAppId\":\"aaaa****fffe\"}}";
        JSONObject fromDirty = JSONUtil.parseObj(
                SysIntegrationController.mergeSecretsWithStored("{\"ocr\":{\"provider\":\"textin\"}}", dirty))
                .getJSONObject("ocr");
        assertThat(fromDirty.containsKey("textinAppId")).isFalse();

        JSONObject out = merge("{\"ocr\":{\"textinSecretCode\":\"dead****cdef\"}}");
        assertThat(out.getJSONObject("ocr").getStr("textinSecretCode"))
                .isEqualTo(TI_SECRET);
    }

    @Test
    @DisplayName("空提交 / 库里没东西 / 坏 JSON 都原样返回, 绝不挡住保存")
    void tolerant() {
        assertThat(SysIntegrationController.mergeSecretsWithStored("", STORED)).isEmpty();
        assertThat(SysIntegrationController.mergeSecretsWithStored("{\"ocr\":{}}", null)).isEqualTo("{\"ocr\":{}}");
        assertThat(SysIntegrationController.mergeSecretsWithStored("{\"ocr\":{}}", "not json")).isEqualTo("{\"ocr\":{}}");
    }
}
