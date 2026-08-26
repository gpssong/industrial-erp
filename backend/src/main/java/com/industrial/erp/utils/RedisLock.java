package com.industrial.erp.utils;

import com.industrial.erp.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Redis 分布式锁
 *
 * <p>v1.1.24+ token 改用 {@link SecureRandom} 生成 (之前用 {@code currentTimeMillis() + Math.random()}
 * 在高并发下可被预测, 极端场景下其他线程能伪造 token 误释放锁). SecureRandom 熵由系统级 RNG 提供,
 * 性能损耗可忽略.
 */
@Component
public class RedisLock {

    private static final Logger log = LoggerFactory.getLogger(RedisLock.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

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
        // 16 字节熵 → 32 字符 hex, 足够安全且便于日志排查
        String token = randomToken();
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
            } catch (Exception e) {
                // 解锁失败不应影响主流程 (锁本身有 lease 自动过期),
                // 但需打 warn 便于排查 Redis 抖动 / 集群切换等场景
                log.warn("[RedisLock] 解锁失败: key={}, token={}, err={}", lockKey, token, e.getMessage());
            }
        }
    }

    /**
     * 生成 32 字符 hex 随机 token, 用 SecureRandom 避免可预测.
     */
    private static String randomToken() {
        byte[] bytes = new byte[16];
        SECURE_RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
