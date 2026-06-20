package com.industrial.erp.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 路由拦截器配置。
 *
 * <p>注意: <b>SaRouter 的 URL 匹配基于 {@code SaHolder.getRequest().getRequestPath()},
 * 该路径不含 Spring 的 context-path</b> (本项目 server.servlet.context-path = /api,
 * 所以匹配的是 /auth/login 而非 /api/auth/login)。
 *
 * <p>白名单采用 <code>match("/**").notMatch(...)</code> 的规范写法,
 * 避免之前 <code>match(path, r-&gt;{})</code> 这种"action 但仍触发 check"的写法。
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
                        "/auth/login",
                        "/auth/captcha",
                        "/auth/setpwd",
                        // 打印 (Electron 通过浏览器直接拉取 HTML 渲染)
                        "/print/**",
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

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("*")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
