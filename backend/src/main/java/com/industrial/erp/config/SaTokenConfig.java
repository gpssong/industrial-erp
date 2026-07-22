package com.industrial.erp.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
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

    /**
     * 业务拦截器: 默认拦截全部, 白名单 (登录/captcha/上传静态资源) 放行, 其余强制登录.
     *
     * <p>Knife4j / Swagger / Actuator 路径已从此白名单移除, 改由 {@link #knife4jAuthInterceptor()}
     * 单独拦截 (要求登录 + 超级管理员), 防止公网暴露接口文档.
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> SaRouter
                .match("/**")
                .notMatch(
                        // 认证 (登录、登出由前端 store 处理, me 要求登录故不放行)
                        // 注意: /auth/setpwd 已从白名单移除, 必须登录并是超管才能调用
                        "/auth/login",
                        "/auth/captcha",
                        // 上传文件 (上传 API 单独鉴权, 静态资源访问放行)
                        "/system/upload/**",
                        "/upload/**",
                        // 静态资源与错误页 (Knife4j 改为登录后访问, 见 knife4jAuthInterceptor)
                        "/favicon.ico",
                        "/error"
                )
                .check(r -> StpUtil.checkLogin())
        )).addPathPatterns("/**");

        // Knife4j / Swagger / Actuator 单独拦截: 必须登录 + 超级管理员 (roles 包含 SUPER_ADMIN)
        registry.addInterceptor(knife4jAuthInterceptor())
                .addPathPatterns(
                        "/doc.html",
                        "/v3/api-docs",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-resources/**",
                        "/webjars/**",
                        "/actuator/**"
                );
    }

    /**
     * Knife4j / Swagger / Actuator 鉴权拦截器.
     * <p>检查 session.roles 是否含 SUPER_ADMIN; 不通过返 401 JSON.
     * <p>实现说明: 直接读 Sa-Token Session (登录时已写入 roles), 不查 DB, 性能 O(1).
     */
    @Bean
    public HandlerInterceptor knife4jAuthInterceptor() {
        return new HandlerInterceptor() {
            @Override
            public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
                                     jakarta.servlet.http.HttpServletResponse response,
                                     Object handler) throws java.io.IOException {
                if (StpUtil.isLogin()) {
                    Object rolesObj = StpUtil.getSession().get("roles");
                    if (rolesObj instanceof List<?> roles) {
                        boolean isSuper = roles.stream()
                                .anyMatch(r -> "SUPER_ADMIN".equals(String.valueOf(r)));
                        if (isSuper) return true;
                    }
                }
                response.setStatus(401);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(
                        "{\"code\":401,\"msg\":\"需要超级管理员权限才能访问 API 文档\",\"data\":null}");
                return false;
            }
        };
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
