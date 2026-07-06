package com.industrial.erp.modules.system.aspect;

import com.industrial.erp.modules.system.annotation.OperLog;
import com.industrial.erp.modules.system.entity.SysOperLog;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * 操作日志切面
 *
 * 通过 {@link OperLogPublisher} 发布事件, 由 {@link OperLogEventListener} 异步写库.
 * 这样解决原版 @Async 自身调用不生效的问题 (around() 调到自身 saveLog, 代理链没生效).
 */
@Aspect
@Component
public class OperLogAspect {

    private final OperLogPublisher publisher;

    public OperLogAspect(OperLogPublisher publisher) {
        this.publisher = publisher;
    }

    @Around("@annotation(com.industrial.erp.modules.system.annotation.OperLog)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        long t1 = System.currentTimeMillis();
        Object result = null;
        Throwable error = null;
        try {
            result = pjp.proceed();
            return result;
        } catch (Throwable e) {
            error = e;
            throw e;
        } finally {
            long cost = System.currentTimeMillis() - t1;
            try {
                capture(pjp, result, error, cost);
            } catch (Exception ignore) {
                // 日志失败不影响主流程
            }
        }
    }

    private void capture(ProceedingJoinPoint pjp, Object result, Throwable error, long cost) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Method method = sig.getMethod();
        OperLog ann = method.getAnnotation(OperLog.class);
        if (ann == null) return;

        Object argsJson = null;
        if (ann.saveParam()) {
            Object[] args = pjp.getArgs();
            if (args != null && args.length > 0) {
                Object[] safe = Arrays.stream(args)
                        .filter(a -> !(a instanceof MultipartFile))
                        .toArray();
                argsJson = safe;
            }
        }

        publisher.publish(
                ann.module(),
                ann.businessType(),
                pjp.getTarget().getClass().getName() + "." + method.getName(),
                argsJson,
                ann.saveResult() ? result : null,
                error,
                cost
        );
    }
}
