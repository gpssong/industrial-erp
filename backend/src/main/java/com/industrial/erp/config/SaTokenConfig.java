package com.industrial.erp.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import com.industrial.erp.common.Constants;
import com.industrial.erp.modules.system.entity.SysUser;
import com.industrial.erp.modules.system.mapper.SysUserMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
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

    private final SysUserMapper sysUserMapper;
    private final StringRedisTemplate redis;

    public SaTokenConfig(SysUserMapper sysUserMapper, StringRedisTemplate redis) {
        this.sysUserMapper = sysUserMapper;
        this.redis = redis;
    }

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
                        // v1.1.119 设备上报 (ESP32 无登录态, Controller 内校验 X-Device-Key 头).
                        // ⚠️ 只能写精确路径, 绝不能写 /device/** —— 这个 notMatch 是全项目唯一的
                        //    登录态豁免口, 写通配等于把设备端所有端点裸奔到公网
                        "/device/weight/report",
                        // 静态资源与错误页 (Knife4j 改为登录后访问, 见 knife4jAuthInterceptor)
                        "/favicon.ico",
                        "/error"
                )
                .check(r -> StpUtil.checkLogin())
        )).addPathPatterns("/**");

        // 默认密码用户: 登录态有效, 但除改密/登出/me 外的 API 一律拦截
        registry.addInterceptor(mustChangePasswordInterceptor()).addPathPatterns("/**");

        // 账号禁用/删除即时失效: 已签发 token 不再等 30 天自然过期 (P1-2)
        registry.addInterceptor(userStatusInterceptor()).addPathPatterns("/**");

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

    /** 默认密码用户改密期间仍可访问的接口 */
    private static final Set<String> PWD_CHANGE_ALLOWED = Set.of(
            "/auth/login",
            "/auth/captcha",
            "/auth/logout",
            "/auth/me",
            "/system/user/me/password",
            "/error"
    );

    /**
     * 默认密码拦截器: 只放行登录/登出/改密/当前用户, 其余接口返回 403.
     * <p>仅靠前端弹窗拦截时, 拿到 token 仍可直接调 API; 这里做服务端强制。
     */
    @Bean
    public HandlerInterceptor mustChangePasswordInterceptor() {
        return new HandlerInterceptor() {
            @Override
            public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
                                     jakarta.servlet.http.HttpServletResponse response,
                                     Object handler) throws java.io.IOException {
                if (!StpUtil.isLogin()) return true;
                Object must = StpUtil.getSession().get("mustChangePassword");
                if (!Boolean.TRUE.equals(must)) return true;

                String path = request.getServletPath();
                if (PWD_CHANGE_ALLOWED.contains(path)
                        || path.startsWith("/system/upload/")
                        || path.startsWith("/upload/")) {
                    return true;
                }
                response.setStatus(403);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(
                        "{\"code\":403,\"msg\":\"请先修改默认密码\",\"data\":null}");
                return false;
            }
        };
    }

    /**
     * 账号状态拦截器: 每个请求最多每 60 秒查一次库, 已禁用/已删除账号立即失效.
     *
     * <p>Sa-Token 会话默认 30 天, 只在登录时校验状态时, 管理员禁用账号后
     * 旧 token 仍可继续调用接口 (P1-2). 这里在服务端每次请求兜底校验,
     * 禁用操作同时会调用 {@code StpUtil.kickout()} 主动踢下线.
     */
    @Bean
    public HandlerInterceptor userStatusInterceptor() {
        return new HandlerInterceptor() {
            @Override
            public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
                                     jakarta.servlet.http.HttpServletResponse response,
                                     Object handler) throws java.io.IOException {
                if (!StpUtil.isLogin()) return true;
                long userId;
                try {
                    userId = StpUtil.getLoginIdAsLong();
                } catch (Exception e) {
                    return true;
                }

                String cacheKey = Constants.REDIS_USER_STATUS + userId;
                Boolean active = null;
                try {
                    String cached = redis.opsForValue().get(cacheKey);
                    if (cached != null) active = "1".equals(cached);
                } catch (Exception ignore) {
                    // Redis 不可用时直接查库, 不因缓存故障放行
                }
                if (active == null) {
                    SysUser user = sysUserMapper.selectById(userId);
                    active = user != null
                            && Integer.valueOf(1).equals(user.getStatus())
                            && (user.getDeleted() == null || user.getDeleted() == 0);
                    try {
                        redis.opsForValue().set(cacheKey, active ? "1" : "0",
                                java.time.Duration.ofSeconds(60));
                    } catch (Exception ignore) {
                        // 缓存写入失败不影响本次判定
                    }
                }
                if (Boolean.TRUE.equals(active)) return true;

                try {
                    StpUtil.kickout(userId);
                } catch (Exception ignore) {
                    // JWT 无状态 token 下的兜底: 即使无法注销会话, 下面也会返回 401
                }
                response.setStatus(401);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(
                        "{\"code\":401,\"msg\":\"账号已被禁用或删除，请联系管理员\",\"data\":null}");
                return false;
            }
        };
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
        // v1.1.105: 补无端口形态。Capacitor WebView 的 origin 就是 http://localhost
        //          (androidScheme=http, 默认无端口), 只配 "http://localhost:*" 匹配不到,
        //          实测 GET/OPTIONS 均 403 → App 全端登录失败。
        config.addAllowedOriginPattern("http://localhost");
        config.addAllowedOriginPattern("http://localhost:*");
        config.addAllowedOriginPattern("http://127.0.0.1");
        config.addAllowedOriginPattern("http://127.0.0.1:*");
        // 注: 公网精确域名 (如 http://home.93gushi.com:8088) 由 addCorsMappings() 处理,
        //     本 filter 只兜底 Capacitor WebView 的端口通配场景, 不再硬编码.
        config.addAllowedHeader("*");
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return new CorsFilter(source);
    }
}
