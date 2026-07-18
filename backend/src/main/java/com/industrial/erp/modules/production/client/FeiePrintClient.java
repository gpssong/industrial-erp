package com.industrial.erp.modules.production.client;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 飞鹅云打印机 OpenAPI 客户端
 *
 * <p>官方文档: https://help.feieyun.com/
 * <p>公开 host: {@code https://api.feieyun.cn/Api/Open/}
 * <p>签名: {@code SHA1(user + ukey + stime)} 转**小写** 40 位
 *
 * <p>返回格式: {@code {"ret": 0, "msg": "ok", "data": "..."}}, ret=0 表示成功.
 */
@Component
public class FeiePrintClient {

    private static final String API_URL = "https://api.feieyun.cn/Api/Open/";

    /**
     * 计算飞鹅 API 签名
     *
     * <p>规则: {@code SHA1(user + ukey + stime)} 转**小写** 40 位
     */
    public String sign(String user, String ukey, String stime) {
        String signStr = user + ukey + stime;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] hash = md.digest(signStr.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash); // 小写
        } catch (Exception e) {
            throw new RuntimeException("计算飞鹅签名失败: " + e.getMessage(), e);
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
     * 打印小票 (Open_printMsg)
     *
     * @param user    飞鹅云账号
     * @param ukey    飞鹅云 UKEY
     * @param sn      打印机编号
     * @param content 打印内容 (飞鹅标签格式: <CB>...</CB> <BR> <QR>...</QR> <CUT>)
     * @param times   打印次数
     * @return 飞鹅返回 JSON
     */
    public JSONObject printMsg(String user, String ukey, String sn, String content, int times) {
        String stime = String.valueOf(System.currentTimeMillis() / 1000);
        String sig = sign(user, ukey, stime);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("user", user);
        params.put("stime", stime);
        params.put("sig", sig);
        params.put("apiname", "Open_printMsg");
        params.put("sn", sn);
        params.put("content", content);
        params.put("times", times);

        return post(params);
    }

    /**
     * 查询订单状态 (Open_queryOrderState)
     */
    public JSONObject queryOrderState(String user, String ukey, String orderid) {
        String stime = String.valueOf(System.currentTimeMillis() / 1000);
        String sig = sign(user, ukey, stime);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("user", user);
        params.put("stime", stime);
        params.put("sig", sig);
        params.put("apiname", "Open_queryOrderState");
        params.put("orderid", orderid);

        return post(params);
    }

    /**
     * 查询打印机状态 (Open_queryPrinterStatus)
     */
    public JSONObject queryPrinterStatus(String user, String ukey, String sn) {
        String stime = String.valueOf(System.currentTimeMillis() / 1000);
        String sig = sign(user, ukey, stime);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("user", user);
        params.put("stime", stime);
        params.put("sig", sig);
        params.put("apiname", "Open_queryPrinterStatus");
        params.put("sn", sn);

        return post(params);
    }

    /**
     * 删除打印订单 (Open_delPrinterSqs)
     */
    public JSONObject delPrinterSqs(String user, String ukey, String sn) {
        String stime = String.valueOf(System.currentTimeMillis() / 1000);
        String sig = sign(user, ukey, stime);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("user", user);
        params.put("stime", stime);
        params.put("sig", sig);
        params.put("apiname", "Open_delPrinterSqs");
        params.put("sn", sn);

        return post(params);
    }

    /**
     * 通用 POST 请求 (form-urlencoded)
     */
    private JSONObject post(Map<String, Object> params) {
        try (HttpResponse response = HttpRequest.post(API_URL)
                .form(params)
                .timeout(15000)
                .execute()) {
            String body = response.body();
            return JSONUtil.parseObj(body);
        } catch (Exception e) {
            throw new RuntimeException("飞鹅 API 请求异常: " + e.getMessage(), e);
        }
    }
}