package com.industrial.erp.utils;

import com.industrial.erp.common.Constants;
import com.industrial.erp.exception.BizException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 单据号生成器 (Redis 自增)
 */
@Component
public class BillNoGenerator {

    @Autowired
    private StringRedisTemplate redis;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 生成单据号: prefix + yyyyMMdd + 4位自增
     */
    public String generate(String prefix) {
        String key = Constants.REDIS_BILL_NO + prefix + ":" + LocalDate.now().format(FMT);
        Long seq = redis.opsForValue().increment(key);
        if (seq == null) {
            throw BizException.of("单据号生成失败");
        }
        // 每日 0 点过期 (86400s + 留 buffer)
        redis.expire(key, java.time.Duration.ofSeconds(86400 + 60));
        return prefix + LocalDate.now().format(FMT) + String.format("%04d", seq);
    }
}
