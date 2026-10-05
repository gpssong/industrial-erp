package com.industrial.erp.modules.ai.agent;

import cn.hutool.json.JSONUtil;

/**
 * v1.1.75 — 把任意 POJO/集合转 JSON 文本喂给模型的小工具 (Hutool JSONUtil 封装).
 */
public final class JSONLite {

    private JSONLite() {}

    /** 单个对象 → JSON 字符串 (null 安全). */
    public static String obj(Object o) {
        return o == null ? "null" : JSONUtil.toJsonStr(o);
    }

    /** 集合 → JSON 数组字符串 (null 安全). */
    public static String list(Object o) {
        return o == null ? "[]" : JSONUtil.toJsonStr(o);
    }
}
