package com.industrial.erp.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.List;

/**
 * Sa-Token 路由拦截器配置。
 *
 * <p>注意: <b>SaRouter 的 URL 匹配基于 {@code SaHolder.getRequest().getRequestPath()},
 * 该路径不含 Spring 的 context-path</b> (本项目 server.servlet.context-path = /api,
 * 所以匹配的是 /auth/login 而非 /api/auth/login)。
 *
 * <p>白名单采用 <code>match("/**").notMatch(...)</code> 的规范写法,
 * 避免之前 <code>match(path, r-&gt;{})</code> 这种"action 但仍触发 check"的写法。
 *
 * <p>Cookie 安全配置 (httpOnly/Secure/SameSite) 在 application.yml 的 {@code sa-token.cookie} 节点声明,
 * 由 sa-token-spring-boot-starter 自动绑定.
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> SaRouter
                // 1. 默认拦截所有请求
                .match("/**")
                // 2. 白名单 (匿名访问): 注意无 /api 前缀 (context-path)
                .notMatch(
                        // 认证 (登录、登出由前端 store 处理，me 要求登录故不放行)
                        // 注意: /auth/setpwd 已从白名单移除, 必须登录并是超管才能调用
                        "/auth/login",
                        "/auth/captcha",
                        // 上传文件 (上传 API 单独鉴权, 静态资源访问放行)
                        "/system/upload/**",
                        "/upload/**",
                        // 文档/监控 (Knife4j / Actuator)
                        "/doc.html",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-resources/**",
                        "/webjars/**",
                        // 静态资源与错误页
                        "/favicon.ico",
                        "/error"
                )
                // 3. 其余接口必须登录
                .check(r -> StpUtil.checkLogin())
        )).addPathPatterns("/**");
    }

    /**
     * 跨域白名单 (来自 application.yml 的 erp.cors.allowed-origins).
     * <p>原写法用 {@code allowedOriginPatterns("*")} 是通配, 任何域都能调 API, 存在 CSRF 风险.
     * 现限定到白名单, 默认包含生产域名 + 本地开发地址, 通过环境变量 ERP_CORS_ALLOWED_ORIGINS 覆盖.
     */
    @Value("${erp.cors.allowed-origins}")
    private String allowedOriginsRaw;

    private List<String> allowedOrigins() {
        return Arrays.stream(allowedOriginsRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> origins = allowedOrigins();
        registry.addMapping("/**")
                .allowedOrigins(origins.toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
                .allowedHeaders("*")
                .exposedHeaders("Authorization", "Content-Disposition")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
