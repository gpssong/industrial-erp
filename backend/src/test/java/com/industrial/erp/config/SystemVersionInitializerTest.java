package com.industrial.erp.config;

import com.industrial.erp.modules.system.entity.SysConfig;
import com.industrial.erp.modules.system.mapper.SysConfigMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.UncategorizedSQLException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v1.1.183 — 只读备库上 backend 必须能启动。
 *
 * <p>触发场景: .32 飞牛链备与阿里云热备都带 super_read_only=ON, 本站 backend 一重启,
 * 这个 ApplicationRunner 的 upsert 就拿到 ERROR 1290。写不进去只是「系统参数」页版本号
 * 少一行, 让它把异常抛出去则是整站起不来 —— 接管时照老流程直接黑屏。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SystemVersionInitializerTest {

    @Mock
    private SysConfigMapper configMapper;

    @Mock
    private ApplicationArguments args;

    /** 备库拒绝写的真实形状: MyBatis 把 MySQL 1290 包成 UncategorizedSQLException。 */
    private static UncategorizedSQLException superReadOnly() {
        return new UncategorizedSQLException(
                "### Error updating database.  Cause: java.sql.SQLException: "
                        + "The MySQL server is running with the --super-read-only option",
                "UPDATE sys_config SET config_value=? WHERE id=?",
                new SQLException("The MySQL server is running with the --super-read-only option "
                        + "so it cannot execute this statement", "HY000", 1290));
    }

    private SystemVersionInitializer newInitializer() {
        return new SystemVersionInitializer(configMapper);
    }

    @Test
    @DisplayName("备库 super_read_only 拒绝更新版本行时, 启动不得中断")
    void updateRejectedByReadOnlyDoesNotAbortStartup() {
        SysConfig existing = new SysConfig();
        existing.setConfigKey("SYSTEM_VERSION_INFO");
        existing.setConfigValue("{\"version\":\"1.1.182\"}");
        when(configMapper.selectOne(any())).thenReturn(existing);
        when(configMapper.updateById(any(SysConfig.class))).thenThrow(superReadOnly());

        assertThatCode(() -> newInitializer().run(args)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("版本行还不存在且 INSERT 被只读拒绝时, 启动同样不得中断")
    void insertRejectedByReadOnlyDoesNotAbortStartup() {
        when(configMapper.selectOne(any())).thenReturn(null);
        when(configMapper.insert(any(SysConfig.class))).thenThrow(superReadOnly());

        assertThatCode(() -> newInitializer().run(args)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("连查询版本行本身都失败时, 启动不得中断")
    void selectFailureDoesNotAbortStartup() {
        when(configMapper.selectOne(any())).thenThrow(superReadOnly());

        assertThatCode(() -> newInitializer().run(args)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("主库可写时仍然照常 upsert, 兜异常不能把正常写入一起吞掉")
    void normalWriteStillHappens() throws Exception {
        SysConfig existing = new SysConfig();
        existing.setConfigKey("SYSTEM_VERSION_INFO");
        existing.setConfigValue("{\"version\":\"1.1.182\"}");
        when(configMapper.selectOne(any())).thenReturn(existing);
        when(configMapper.updateById(any(SysConfig.class))).thenReturn(1);

        newInitializer().run(args);

        verify(configMapper).updateById(any(SysConfig.class));
    }
}
