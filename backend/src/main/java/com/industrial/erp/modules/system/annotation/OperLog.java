package com.industrial.erp.modules.system.annotation;

import java.lang.annotation.*;

/**
 * 操作日志注解
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OperLog {
    String module() default "";
    String businessType() default "";
    boolean saveParam() default true;
    boolean saveResult() default false;
}
