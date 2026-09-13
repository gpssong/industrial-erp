package com.industrial.erp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * v1.1.49: 启动期强制校验 JWT secret 强度, 防止 .env 留占位符就上生产.
 *
 * <p>历史踩坑: 2026-09-13 项目审计发现 .env 留 "erp_jwt_secret_2026_gpssong_xxxx"
 * 占位值 (16 字节 ASCII, 可猜测). 任何拿到源码的人都能伪造 token.
 * 加这条防线后, 启动期就报错退出, 强制要求 openssl rand -hex 32.
 *
 * <p>校验规则 (中等强度):
 * <ul>
 *   <li>非 null + 长度 ≥ {@value #MIN_SECRET_LEN} 字符</li>
 *   <li>不在已知弱密钥黑名单中</li>
 * </ul>
 *
 * <p>测试豁免: {@code @Profile("!test")} 让测试 profile 不加载, 不影响 mvn test.
 */
@Component
@Profile("!test")
public class SecurityPreflightValidator {

    private static final Logger log = LoggerFactory.getLogger(SecurityPreflightValidator.class);

    /**
     * openssl rand -hex 32 = 64 字符; openssl rand -base64 32 ≈ 44 字符.
     * 取下限 32 字符以兼容 base64 输出. 真实 openssl 输出远超此值.
     */
    private static final int MIN_SECRET_LEN = 32;

    /**
     * 已知弱密钥黑名单 (从仓库 .env / application-test.yml / docker-compose 注释里抓出来的占位值).
     * 包括:
     * - .env.example 历史示例
     * - application-test.yml 的默认值
     * - 通用占位符
     */
    private static final String[] WEAK_SECRETS = {
        "industrial-erp-jwt-test-p2-fix",
        "erp_jwt_secret_2026_gpssong_xxxx",
        "erp_jwt_secret_2026_gpssong",
        "changeme",
        "change-me",
        "secret",
        "default",
        "test",
        "12345678",
        "password"
    };

    @Value("${sa-token.jwt-secret-key}")
    private String jwtSecretKey;

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        int len = jwtSecretKey == null ? 0 : jwtSecretKey.length();

        // 1. 长度校验
        if (len < MIN_SECRET_LEN) {
            throw new IllegalStateException(
                "【安全预检】SA_TOKEN_JWT_SECRET_KEY 太短 (当前 " + len + " 字符, 至少 " + MIN_SECRET_LEN + ")." +
                " 生成命令: openssl rand -hex 32" +
                " 修复: 编辑 .env 把 SA_TOKEN_JWT_SECRET_KEY 替换为上述命令的输出, 然后 docker restart erp-backend");
        }

        // 2. 黑名单校验
        for (String weak : WEAK_SECRETS) {
            if (weak.equals(jwtSecretKey)) {
                throw new IllegalStateException(
                    "【安全预检】SA_TOKEN_JWT_SECRET_KEY 是已知弱密钥 '" + weak + "', 拒绝启动." +
                    " 生成命令: openssl rand -hex 32" +
                    " 修复: 编辑 .env 把 SA_TOKEN_JWT_SECRET_KEY 替换为上述命令的输出, 然后 docker restart erp-backend");
            }
        }

        log.info("【安全预检】JWT secret 通过校验 (长度 {} 字符, 不在弱密钥黑名单)", len);
    }
}
