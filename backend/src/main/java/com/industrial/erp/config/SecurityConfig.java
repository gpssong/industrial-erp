package com.industrial.erp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security 配置。
 *
 * <p>本系统的鉴权统一由 <b>Sa-Token</b> 完成（参见 {@link SaTokenConfig} 中的
 * Sa-Token 拦截器 + {@code @SaCheckPermission} 注解）。这里只把 Spring Security 默认
 * 的表单登录 / CSRF 等机制关掉，避免它拦截我们的 REST 接口。<b>不要在此处声明具体的权限规则</b>，
 * 否则会误导读者以为这些规则生效了——真正的拦截在 Sa-Token 那一层。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(c -> c.disable())
            .headers(h -> h.frameOptions(frame -> frame.disable()))
            .formLogin(f -> f.disable())
            .httpBasic(b -> b.disable())
            .logout(l -> l.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
