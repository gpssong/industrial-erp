package com.industrial.erp.modules.base.label;

import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNull;

class FeieResultTest {

    @Test
    @DisplayName("ret=0 是唯一的成功条件")
    void zeroIsSuccess() {
        assertNull(FeieResult.failureOf(JSONUtil.parseObj("{\"ret\":0,\"msg\":\"ok\"}")));
    }

    @Test
    @DisplayName("HTTP 200 + ret≠0（脱机/错 UKEY/欠费）必须判失败 —— 判反了就是把失败记成 SUCCESS")
    void nonZeroIsFailure() {
        assertThat(FeieResult.failureOf(JSONUtil.parseObj("{\"ret\":-1,\"msg\":\"打印机离线\"}"))).contains("打印机离线");
        assertThat(FeieResult.failureOf(JSONUtil.parseObj("{\"msg\":\"没有 ret 字段\"}"))).contains("ret=null");
    }

    @Test
    @DisplayName("整个响应为 null 也算失败，不许 NPE")
    void nullResponseIsFailure() {
        assertThat(FeieResult.failureOf(null)).isEqualTo("飞鹅无响应");
    }
}
