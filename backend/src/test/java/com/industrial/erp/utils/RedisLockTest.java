package com.industrial.erp.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1-4: Redis 锁必须在事务提交后才释放, 否则下一个线程可能在旧事务提交前
 * 拿到锁并读到旧数据, 库存依然可能超卖.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedisLockTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    @AfterEach
    void cleanup() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("有事务时: 业务结束不立即解锁, 事务完成后才解锁")
    void releasesAfterTransactionCompletion() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);

        RedisLock lock = new RedisLock(redis);
        TransactionSynchronizationManager.initSynchronization();
        try {
            String result = lock.executeWithLock("stock:1", 1, 30, () -> "ok");

            assertThat(result).isEqualTo("ok");
            verify(redis, never()).execute(any(RedisScript.class), anyList(), anyString());

            List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs).hasSize(1);
            syncs.get(0).afterCompletion(TransactionSynchronization.STATUS_COMMITTED);

            verify(redis).execute(any(RedisScript.class), anyList(), anyString());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("无事务时: 业务结束后立即解锁")
    void releasesImmediatelyWithoutTransaction() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);

        RedisLock lock = new RedisLock(redis);
        lock.executeWithLock("stock:2", 1, 30, () -> "ok");

        verify(redis).execute(any(RedisScript.class), anyList(), anyString());
    }
}
