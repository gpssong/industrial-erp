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
 * 整站会裸奔. 这里配置 denyAll() 作为兜底: 即使 Sa-Token 失效, Spring Security 也会拒绝所有未授权请求.
 *
 * <p>注意: denyAll() 是在 Sa-Token 拦截器之后的兜底, 不影响正常流程 (Sa-Token 已先放行才会到这).
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
            // 第二道防线: 即使 Sa-Token 拦截器配错, Spring Security 仍兜底拒绝 (401)
            .authorizeHttpRequests(auth -> auth.anyRequest().denyAll());
        return http.build();
    }
}
