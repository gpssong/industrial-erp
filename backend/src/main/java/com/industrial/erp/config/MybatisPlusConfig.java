package com.industrial.erp.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.industrial.erp.security.SecurityContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

@Slf4j
@Configuration
public class MybatisPlusConfig {

    /**
     * 注册分页、乐观锁、防全表更新拦截器
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 分页 — P1-9: maxLimit=200 (之前 500). 防止前端误用 9999 一次拉所有数据导致 OOM.
        // overflow=false: 超出 pageSize * maxLimit 直接报错, 不再静默 offset 到最后
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(200L);
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);
        // 乐观锁
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        // 防全表 update/delete
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());
        return interceptor;
    }

    /**
     * 自动填充审计字段
     */
    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                Long userId = SecurityContext.getUserId();
                LocalDateTime now = LocalDateTime.now();
                strictInsertFill(metaObject, "createTime", LocalDateTime.class, now);
                strictInsertFill(metaObject, "updateTime", LocalDateTime.class, now);
                if (userId != null) {
                    strictInsertFill(metaObject, "createBy", Long.class, userId);
                    strictInsertFill(metaObject, "updateBy", Long.class, userId);
                }
                strictInsertFill(metaObject, "tenantId", Long.class, 1L);
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                Long userId = SecurityContext.getUserId();
                strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
                if (userId != null) {
                    strictUpdateFill(metaObject, "updateBy", Long.class, userId);
                }
            }
        };
    }
}
