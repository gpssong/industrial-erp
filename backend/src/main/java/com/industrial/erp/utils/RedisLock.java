package com.industrial.erp.utils;

import com.industrial.erp.exception.BizException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Redis 分布式锁
 */
@Component
public class RedisLock {

    private final StringRedisTemplate redis;

    public RedisLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    /**
     * 执行业务并加锁
     */
    public <T> T executeWithLock(String key, int waitSeconds, int leaseSeconds, Supplier<T> action) {
        String token = String.valueOf(System.currentTimeMillis() + (long)(Math.random() * 1_000_000));
        String lockKey = "erp:lock:" + key;
        long start = System.currentTimeMillis();
        try {
            while (true) {
                Boolean ok = redis.opsForValue().setIfAbsent(lockKey, token, leaseSeconds, TimeUnit.SECONDS);
                if (Boolean.TRUE.equals(ok)) {
                    return action.get();
                }
                if (System.currentTimeMillis() - start > waitSeconds * 1000L) {
                    throw BizException.of("操作过于频繁, 请稍后再试");
                }
                Thread.sleep(50);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BizException.of("操作被中断");
        } finally {
            try {
                redis.execute(UNLOCK_SCRIPT, Collections.singletonList(lockKey), token);
            } catch (Exception ignore) {}
        }
    }
}
