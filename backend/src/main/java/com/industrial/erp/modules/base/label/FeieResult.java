package com.industrial.erp.modules.base.label;

import cn.hutool.json.JSONObject;

/**
 * 飞鹅云返回的成败判定 (docs/33 A10-R1)。
 *
 * <p>抽出来只为一件事：让「只有 `ret == 0` 算云端接受」这条口径**能被单测证伪**。
 * `FeiePrintClient.printMsg` 业务失败**不抛异常**（脱机 / 错 UKEY / 欠费一律 HTTP 200 + `ret≠0`，
 * 见 `FeiePrintClient.java:132-142` 只在传输/解析失败时才抛），所以「没抛异常」不等于「打印成功」。
 */
public final class FeieResult {

    private FeieResult() {}

    /** @return null 表示云端已接受下发；否则是给操作员看的失败原因（落 `error_msg`，截断由 `LabelPrintService` 管）。 */
    public static String failureOf(JSONObject resp) {
        if (resp == null) return "飞鹅无响应";
        Integer ret = resp.getInt("ret");
        if (ret != null && ret == 0) return null;
        return "飞鹅未接受: ret=" + ret + ", msg=" + resp.getStr("msg", "");
    }
}
