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
        // 不设置 TTL: 单号序列一旦被淘汰会从 1 重新计数, 与当天已生成单号重复.
        // Redis 淘汰策略固定为 volatile-lru (只淘汰带 TTL 的缓存), 无 TTL 的序列键不会被回收.
        // 键按日期分段, 历史键数量 = 单据前缀数 × 天数, 体量可忽略.
        return prefix + LocalDate.now().format(FMT) + String.format("%04d", seq);
    }
}
