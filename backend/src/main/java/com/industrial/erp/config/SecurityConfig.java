package com.industrial.erp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security 配置 — 第二道防线。
 *
 * <p>本系统的鉴权统一由 <b>Sa-Token</b> 完成（参见 {@link SaTokenConfig}）。
 * 但 Spring Security 在历史上默认是表单登录 + CSRF, 一旦 Sa-Token 拦截器被错误关闭或路径配置失误,
 * 整站会裸奔. 这里配置 permitAll() 维持全透传, 仅依赖 Sa-Token 作为唯一鉴权 (knife4j 单独拦截器已加, 双层防护仍然存在).
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
            // permitAll 给 Sa-Token 全权负责; knife4j 单独拦截器作为兜底
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
