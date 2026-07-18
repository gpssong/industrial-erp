package com.industrial.erp.modules.production.client;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.industrial.erp.exception.BizException;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 飞鹅云打印机 REST API 客户端
 * 文档: https://help.feieyun.com/
 * API 地址: https://cloud.feieyun.cn
 */
@Component
public class FeiePrintClient {

    private static final String API_HOST = "https://cloud.feieyun.cn";

    /**
     * 计算飞鹅 API 签名
     * 规则: MD5(method + path + timestamp + ukey) → 转大写
     */
    public String sign(String method, String path, String timestamp, String ukey) {
        String signStr = method + path + timestamp + ukey;
        try {
            Mac mac = Mac.getInstance("MD5");
            mac.init(new SecretKeySpec(signStr.getBytes(StandardCharsets.UTF_8), "MD5"));
            byte[] hash = mac.doFinal();
            return bytesToHex(hash).toUpperCase();
        } catch (Exception e) {
            throw BizException.of("计算飞鹅签名失败: " + e.getMessage());
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * HTML 内容打印 (推荐用于生产单等复杂布局)
     * POST /REST/print/html
     */
    public JSONObject printHtml(String ukey, String deviceSn, String htmlContent) {
        String path = "/REST/print/html";
        String timestamp = String.valueOf(System.currentTimeMillis());
        String signature = sign("POST", path, timestamp, ukey);

        JSONObject body = new JSONObject();
        body.put("ukey", ukey);
        body.put("timestamp", timestamp);
        body.put("signature", signature);
        body.put("content", htmlContent);
        if (deviceSn != null && !deviceSn.isEmpty()) {
            body.put("deviceSn", deviceSn);
        }

        try (HttpResponse response = HttpRequest.post(API_HOST + path)
                .body(body.toJSONString(), "application/json")
                .timeout(15000)
                .execute()) {
            String result = response.body();
            JSONObject json = JSONUtil.parseObj(result);
            Integer code = json.getInt("code");
            if (code == null || code != 1) {
                throw BizException.of("飞鹅打印失败: " + json.getStr("msg", "未知错误"));
            }
            return json;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw BizException.of("飞鹅打印请求异常: " + e.getMessage());
        }
    }

    /**
     * 文本内容打印 (简单场景)
     * POST /REST/print/simple
     */
    public JSONObject printSimple(String ukey, String deviceSn, String content) {
        String path = "/REST/print/simple";
        String timestamp = String.valueOf(System.currentTimeMillis());
        String signature = sign("POST", path, timestamp, ukey);

        JSONObject body = new JSONObject();
        body.put("ukey", ukey);
        body.put("timestamp", timestamp);
        body.put("signature", signature);
        body.put("content", content);
        if (deviceSn != null && !deviceSn.isEmpty()) {
            body.put("deviceSn", deviceSn);
        }

        try (HttpResponse response = HttpRequest.post(API_HOST + path)
                .body(body.toJSONString(), "application/json")
                .timeout(15000)
                .execute()) {
            String result = response.body();
            JSONObject json = JSONUtil.parseObj(result);
            Integer code = json.getInt("code");
            if (code == null || code != 1) {
                throw BizException.of("飞鹅打印失败: " + json.getStr("msg", "未知错误"));
            }
            return json;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw BizException.of("飞鹅打印请求异常: " + e.getMessage());
        }
    }

    /**
     * 获取设备列表
     * GET /REST/device/list
     */
    public JSONArray listDevices(String ukey) {
        String path = "/REST/device/list";
        String timestamp = String.valueOf(System.currentTimeMillis());
        String signature = sign("GET", path, timestamp, ukey);

        String url = API_HOST + path
                + "?ukey=" + ukey
                + "&timestamp=" + timestamp
                + "&signature=" + signature;

        try (HttpResponse response = HttpRequest.get(url)
                .timeout(10000)
                .execute()) {
            String result = response.body();
            JSONObject json = JSONUtil.parseObj(result);
            Integer code = json.getInt("code");
            if (code == null || code != 1) {
                throw BizException.of("获取飞鹅设备列表失败: " + json.getStr("msg", "未知错误"));
            }
            return json.getJSONArray("data");
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw BizException.of("获取设备列表异常: " + e.getMessage());
        }
    }
}
