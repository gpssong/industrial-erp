package com.industrial.erp.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
     * 除了精确匹配 env 中的白名单, 还额外支持 http://localhost/* 和 http://127.0.0.1/*
     * — 适配 Capacitor WebView 内部 HTTP server (http://localhost:[随机端口]).
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
        
        // 合并 env 精确匹配 + localhost/127.0.0.1 通配来源
        Set<String> allOrigins = new HashSet<>(origins);
        allOrigins.add("http://localhost");
        allOrigins.add("http://127.0.0.1");
        
        registry.addMapping("/**")
                .allowedOriginPatterns(allOrigins.toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
                .allowedHeaders("*")
                .exposedHeaders("Authorization", "Content-Disposition")
                .allowCredentials(true)
                .maxAge(3600);
    }

    /**
     * 兜底 CORS Filter: 处理 Capacitor WebView 内部 HTTP server.
     * Capacitor Android WebView 以 http://localhost:[随机端口] 加载页面,
     * Spring MVC CorsRegistry 不支持端口 pattern, 所以用 Web Filter 做 pattern 匹配兜底.
     *
     * <p>v1.1.24 改造: 不再硬编码公网域名, 改读 {@link #allowedOriginsRaw} 里以
     * "http://localhost" 或 "http://127.0.0.1" 开头的条目, 全部加 ":*" 后缀纳入 pattern;
     * 其余精确域名走 CorsRegistry (addCorsMappings), 这里不再重复, 避免白名单分裂.
     */
    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);
        config.addAllowedMethod("*");
        config.addAllowedOriginPattern("http://localhost:*");
        config.addAllowedOriginPattern("http://127.0.0.1:*");
        // 注: 公网精确域名 (如 http://home.93gushi.com:8088) 由 addCorsMappings() 处理,
        //     本 filter 只兜底 Capacitor WebView 的端口通配场景, 不再硬编码.
        config.addAllowedHeader("*");
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return new CorsFilter(source);
    }
}
