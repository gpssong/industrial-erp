package com.industrial.erp.modules.sales.service;

import com.baomidou.mybatisplus.annotation.Version;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.industrial.erp.modules.sales.entity.SalDelivery;
import com.industrial.erp.modules.sales.mapper.SalDeliveryMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * v1.1.24: 销售出库单乐观锁测试
 *
 * <p>验证 @Version 注解在并发场景下的保护机制:
 * <ul>
 *   <li>并发更新同一单据应导致 UpdateException</li>
 *   <li>不同单据的更新应互不干扰</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SalDeliveryVersionLockTest {

    @Mock
    private SalDeliveryMapper salDeliveryMapper;

    @InjectMocks
    private SalDeliveryService salDeliveryService;

    @Test
    @DisplayName("并发更新同一单据应触发乐观锁异常")
    void testConcurrentUpdateShouldFail() {
        Long id = 1L;
        SalDelivery delivery = new SalDelivery();
        delivery.setId(id);
        delivery.setBillNo("SD20240101001");
        delivery.setVersion(0);
        delivery.setTotalAmount(new BigDecimal("100.00"));

        // 第一次更新成功
        when(salDeliveryMapper.selectById(id)).thenReturn(delivery);
        when(salDeliveryMapper.updateById(any(SalDelivery.class))).thenReturn(1);

        // 模拟另一个事务更新了 version (从 0 → 1)
        SalDelivery concurrentUpdate = new SalDelivery();
        concurrentUpdate.setId(id);
        concurrentUpdate.setVersion(1); // 外部修改了 version
        concurrentUpdate.setTotalAmount(new BigDecimal("200.00"));

        // 第二次更新应该失败 (version mismatch)
        when(salDeliveryMapper.updateById(concurrentUpdate)).thenReturn(0);

        // 正常流程
        ReflectionTestUtils.invokeMethod(salDeliveryService, "update", delivery);

        // 并发更新应该抛出异常 (version 不匹配)
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(salDeliveryService, "update", concurrentUpdate))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("更新新单据应成功")
    void testNewDeliveryUpdateSuccess() {
        Long id = 1L;
        SalDelivery delivery = new SalDelivery();
        delivery.setId(id);
        delivery.setBillNo("SD20240101001");
        delivery.setVersion(0);
        delivery.setTotalAmount(new BigDecimal("100.00"));

        when(salDeliveryMapper.selectById(id)).thenReturn(delivery);
        when(salDeliveryMapper.updateById(any(SalDelivery.class))).thenReturn(1);

        // 第一次更新应该成功
        ReflectionTestUtils.invokeMethod(salDeliveryService, "update", delivery);

        verify(salDeliveryMapper, times(1)).updateById(any(SalDelivery.class));
    }

    @Test
    @DisplayName("并发更新不同单据应互不干扰")
    void testConcurrentDifferentDeliveriesShouldSucceed() {
        Long id1 = 1L;
        Long id2 = 2L;

        SalDelivery delivery1 = new SalDelivery();
        delivery1.setId(id1);
        delivery1.setVersion(0);
        delivery1.setTotalAmount(new BigDecimal("100.00"));

        SalDelivery delivery2 = new SalDelivery();
        delivery2.setId(id2);
        delivery2.setVersion(0);
        delivery2.setTotalAmount(new BigDecimal("200.00"));

        when(salDeliveryMapper.selectById(id1)).thenReturn(delivery1);
        when(salDeliveryMapper.selectById(id2)).thenReturn(delivery2);
        when(salDeliveryMapper.updateById(any(SalDelivery.class))).thenReturn(1);

        // 两个不同单据的更新都应该成功
        ReflectionTestUtils.invokeMethod(salDeliveryService, "update", delivery1);
        ReflectionTestUtils.invokeMethod(salDeliveryService, "update", delivery2);

        verify(salDeliveryMapper, times(2)).updateById(any(SalDelivery.class));
    }

    @Test
    @DisplayName("@Version 注解应正确标记 version 字段")
    void testVersionAnnotationPresent() {
        java.lang.reflect.Field versionField = null;
        try {
            versionField = SalDelivery.class.getDeclaredField("version");
        } catch (NoSuchFieldException e) {
            // 如果没有 version 字段，测试失败
            org.junit.jupiter.api.Assertions.fail("SalDelivery 应该有 version 字段");
        }

        assertThat(versionField).isNotNull();
        assertThat(versionField.isAnnotationPresent(Version.class))
                .as("version 字段应该有 @Version 注解")
                .isTrue();
    }
}
