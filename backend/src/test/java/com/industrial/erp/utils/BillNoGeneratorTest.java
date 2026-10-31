package com.industrial.erp.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v1.1.143 — 单号计数器落后于库内最大号时的自愈对账.
 *
 * <p>触发场景: 主备复制把主库生成的单据带进备库, 或 Redis 被清空 —— 本站计数器不知道这些行,
 * 再发号就撞 uniq bill_no, 建单直接报「数据已存在」。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BillNoGeneratorTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> ops;
    @Mock
    private JdbcTemplate jdbc;

    @InjectMocks
    private BillNoGenerator generator;

    private String date;
    private String key;

    @BeforeEach
    void setUp() {
        date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        key = "erp:billno:PFI:" + date;
        when(redis.opsForValue()).thenReturn(ops);
    }

    @Test
    @DisplayName("计数器没落后 (redis 已领先库内最大号) 时不抬升, 直接发号")
    void aheadOfDbKeepsRedisCounter() {
        when(ops.increment(key)).thenReturn(6L);
        when(jdbc.queryForObject(anyString(), eq(String.class), anyString()))
                .thenReturn("PFI" + date + "0004");

        assertThat(generator.generate("PFI")).isEqualTo("PFI" + date + "0006");
        verify(ops, never()).set(eq(key), anyString());
    }

    @Test
    @DisplayName("计数器落后 (Redis 被清空 / 主备复制带进别人的单号) 时抬升后重发, 不撞唯一索引")
    void behindDbBumpsCounterAndReissues() {
        // 第一次 incr 拿到 1, 库里当天已经到 0004
        when(ops.increment(key)).thenReturn(1L, 5L);
        when(jdbc.queryForObject(anyString(), eq(String.class), eq("PFI" + date + "%")))
                .thenReturn("PFI" + date + "0004");

        String billNo = generator.generate("PFI");

        assertThat(billNo).isEqualTo("PFI" + date + "0005");
        verify(ops).set(key, "4");
    }

    @Test
    @DisplayName("库里当天还没有同号段时正常从 1 开始")
    void emptyDayStartsAtOne() {
        when(ops.increment(key)).thenReturn(1L);
        when(jdbc.queryForObject(anyString(), eq(String.class), anyString())).thenReturn(null);

        assertThat(generator.generate("PFI")).isEqualTo("PFI" + date + "0001");
    }

    @Test
    @DisplayName("未登记归属表的前缀不查库, 保持纯 Redis 行为")
    void unknownPrefixSkipsDb() {
        when(ops.increment("erp:billno:ZZ:" + date)).thenReturn(2L);

        assertThat(generator.generate("ZZ")).isEqualTo("ZZ" + date + "0002");
        verify(jdbc, never()).queryForObject(anyString(), eq(String.class), anyString());
    }

    @Test
    @DisplayName("对账查库失败不能挡住开单, 退回 Redis 计数")
    void dbFailureFallsBackToRedis() {
        when(ops.increment(key)).thenReturn(3L);
        when(jdbc.queryForObject(anyString(), eq(String.class), anyString()))
                .thenThrow(new RuntimeException("Unknown column"));

        assertThat(generator.generate("PFI")).isEqualTo("PFI" + date + "0003");
    }
}
