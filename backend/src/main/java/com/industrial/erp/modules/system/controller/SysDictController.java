package com.industrial.erp.modules.system.controller;

import com.industrial.erp.common.R;
import com.industrial.erp.security.SecurityContext;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 数据字典: 直接查 sys_dict_data 并缓存到 Redis
 */
@Tag(name = "数据字典")
@RestController
@RequestMapping("/system/dict")
public class SysDictController {

    public SysDictController(JdbcTemplate jdbcTemplate, StringRedisTemplate redis) {
        this.jdbcTemplate = jdbcTemplate;
        this.redis = redis;
    }

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redis;

    @GetMapping("/type/{dictType}")
    public R<List<Map<String, Object>>> listByType(@PathVariable String dictType) {
        if (!SecurityContext.isLogin()) return R.ok(List.of());
        // Redis 缓存
        String key = "erp:dict:" + dictType;
        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            try {
                return R.ok(new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(cached, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {}));
            } catch (Exception ignore) {}
        }
        // 查 DB
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            jdbcTemplate.query(
                "SELECT dict_label, dict_value, css_class, sort_no FROM sys_dict_data WHERE dict_type = ? AND deleted = 0 AND status = 1 ORDER BY sort_no",
                rs -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("label", rs.getString("dict_label"));
                    m.put("value", rs.getString("dict_value"));
                    m.put("cssClass", rs.getString("css_class"));
                    list.add(m);
                }, dictType
            );
        } catch (Exception ignore) {}
        // 缓存 30 分钟
        try {
            redis.opsForValue().set(key, new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(list), 30, TimeUnit.MINUTES);
        } catch (Exception ignore) {}
        return R.ok(list);
    }
}
