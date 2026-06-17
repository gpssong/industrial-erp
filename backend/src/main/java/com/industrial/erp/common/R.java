package com.industrial.erp.common;

import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serializable;

@Schema(description = "统一返回结果")
public class R<T> implements Serializable {
    private int code;
    private String msg;
    private T data;
    private long ts = System.currentTimeMillis();

    public static <T> R<T> ok() { return ok(null); }

    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.code = 200; r.msg = "操作成功"; r.data = data; return r;
    }

    public static <T> R<T> ok(String msg, T data) {
        R<T> r = new R<>();
        r.code = 200; r.msg = msg; r.data = data; return r;
    }

    public static <T> R<T> fail(int code, String msg) {
        R<T> r = new R<>(); r.code = code; r.msg = msg; return r;
    }

    public static <T> R<T> fail(String msg) { return fail(500, msg); }
    public static <T> R<T> unauthorized(String msg) { return fail(401, msg); }
    public static <T> R<T> forbidden(String msg) { return fail(403, msg); }

    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }
    public String getMsg() { return msg; }
    public void setMsg(String msg) { this.msg = msg; }
    public T getData() { return data; }
    public void setData(T data) { this.data = data; }
    public long getTs() { return ts; }
    public void setTs(long ts) { this.ts = ts; }
}
