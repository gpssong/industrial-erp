package com.industrial.erp.config;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * v1.1.49: SecurityPreflightValidator 单元测试 (纯 JVM, 无 Spring 上下文).
 *
 * <p>启动期校验逻辑用反射直接测, 避免启动整个 Spring 上下文.
 * test profile 下 Spring 不加载这个 validator (因为 @Profile("!test")),
 * 所以测试类本身不需要 @SpringBootTest.
 */
class SecurityPreflightValidatorTest {

    /**
     * 用反射调用 validate() (它是 private 改成 public 包级可见后, 这里走 setter 注入值).
     */
    private void runValidator(String secret) throws Exception {
        SecurityPreflightValidator v = new SecurityPreflightValidator();
        // jwtSecretKey 是 @Value private 字段, 用反射设
        Field f = SecurityPreflightValidator.class.getDeclaredField("jwtSecretKey");
        f.setAccessible(true);
        f.set(v, secret);
        // validate() 是 public, 直接调
        v.validate();
    }

    @Test
    void rejects_null_secret() {
        assertThatThrownBy(() -> runValidator(null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SA_TOKEN_JWT_SECRET_KEY");
    }

    @Test
    void rejects_short_secret() {
        assertThatThrownBy(() -> runValidator("tooshort"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("太短")
            .hasMessageContaining("32");
    }

    @Test
    void rejects_short_secret_even_if_looks_random() {
        // industrial-erp-jwt-test-p2-fix 长度 30, < 32 阈值, 应被长度检查拒绝
        assertThatThrownBy(() -> runValidator("industrial-erp-jwt-test-p2-fix"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("太短");
    }

    @Test
    void rejects_legacy_dev_secret() throws Exception {
        // "erp_jwt_secret_2026_gpssong_xxxx" 长度恰好 32, 通过长度检查, 应被黑名单拒绝
        assertThatThrownBy(() -> runValidator("erp_jwt_secret_2026_gpssong_xxxx"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("已知弱密钥");
    }

    @Test
    void rejects_generic_secrets() {
        for (String weak : new String[]{"changeme", "change-me", "secret", "default", "test", "12345678", "password"}) {
            assertThatThrownBy(() -> runValidator(weak))
                .as("弱密钥 '%s' 应当被拒绝", weak)
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void accepts_openssl_hex32() throws Exception {
        // openssl rand -hex 32 = 64 字符十六进制
        String openssl = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90";
        assertThat(openssl).hasSize(64);
        runValidator(openssl);
    }

    @Test
    void accepts_long_random_string() throws Exception {
        // 任何长度 ≥32 且不在黑名单的字符串都接受 (包括 base64)
        String longRandom = "ThisIsAVeryLongRandomStringWith32+CharsAndNoBlacklistMatch";
        assertThat(longRandom.length()).isGreaterThanOrEqualTo(32);
        runValidator(longRandom);
    }
}
