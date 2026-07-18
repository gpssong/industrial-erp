package com.industrial.erp.config;

import freemarker.template.Configuration;
import freemarker.template.TemplateExceptionHandler;
import org.springframework.context.annotation.Bean;

import java.io.File;
import java.io.IOException;

/**
 * Freemarker 配置 (飞鹅云打印模板渲染用)
 *
 * <p>模板路径: {@code classpath:templates/}, 文件编码: UTF-8
 *
 * <p>{@link com.industrial.erp.modules.production.service.FeiePrintService} 注入 {@link Configuration} 后,
 * 会再调用 {@code setNewBuiltinClassResolver(ALLOWS_NOTHING_RESOLVER)} 关闭 Class 解析以保证安全.
 *
 * <p>用 {@code ClassTemplateLoader} 直接读 classpath:templates/ 下的 .ftl,
 * 避免 Spring {@code FreeMarkerConfigurationFactoryBean} 在 bean 初始化时机上的坑.
 */
@org.springframework.context.annotation.Configuration
public class FreemarkerConfig {

    @Bean(name = "feieFreemarkerConfig")
    public Configuration feieFreemarkerConfig() throws IOException {
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_32);
        cfg.setTemplateLoader(new freemarker.cache.ClassTemplateLoader(FreemarkerConfig.class, "/templates"));
        cfg.setDefaultEncoding("UTF-8");
        cfg.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        cfg.setWhitespaceStripping(true);
        cfg.setClassicCompatible(true);
        return cfg;
    }
}