package com.industrial.erp.modules.system.aspect;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.industrial.erp.modules.system.annotation.OperLog;
import com.industrial.erp.modules.system.entity.SysOperLog;
import com.industrial.erp.modules.system.mapper.SysOperLogMapper;
import com.industrial.erp.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;

/**
 * 操作日志切面
 */
@Aspect
@Component
public class OperLogAspect {

    private final SysOperLogMapper operLogMapper;

    public OperLogAspect(SysOperLogMapper operLogMapper) {
        this.operLogMapper = operLogMapper;
    }

    private static final Logger log = LoggerFactory.getLogger(OperLogAspect.class);

    private final ObjectMapper om = new ObjectMapper();

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
                saveLog(pjp, result, error, cost);
            } catch (Exception e) {
                log.warn("保存操作日志失败: {}", e.getMessage());
            }
        }
    }

    @Async
    public void saveLog(ProceedingJoinPoint pjp, Object result, Throwable error, long cost) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Method method = sig.getMethod();
        OperLog ann = method.getAnnotation(OperLog.class);
        if (ann == null) return;

        HttpServletRequest req = null;
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) req = attrs.getRequest();
        } catch (Exception ignore) {}

        SysOperLog l = new SysOperLog();
        l.setModule(ann.module());
        l.setBusinessType(ann.businessType());
        l.setMethod(pjp.getTarget().getClass().getName() + "." + method.getName());
        if (req != null) {
            l.setRequestUrl(req.getRequestURI());
            l.setRequestMethod(req.getMethod());
            l.setIpAddress(getIp(req));
        }
        if (ann.saveParam()) l.setRequestParam(serializeArgs(pjp.getArgs()));
        if (ann.saveResult() && result != null) {
            try { l.setResponseData(om.writeValueAsString(result)); } catch (Exception ignore) {}
        }
        l.setUserId(SecurityContext.getUserId());
        l.setUsername(SecurityContext.getUsername());
        l.setCostTime(cost);
        l.setStatus(error == null ? 1 : 0);
        if (error != null) l.setErrorMsg(StrUtil.maxLength(error.getMessage(), 1000));
        l.setOperTime(LocalDateTime.now());
        operLogMapper.insert(l);
    }

    private String serializeArgs(Object[] args) {
        if (args == null || args.length == 0) return null;
        try {
            Object[] safe = Arrays.stream(args)
                    .filter(a -> !(a instanceof MultipartFile))
                    .toArray();
            return om.writeValueAsString(safe);
        } catch (JsonProcessingException e) { return null; }
    }

    private String getIp(HttpServletRequest req) {
        String ip = req.getHeader("X-Forwarded-For");
        if (StrUtil.isBlank(ip) || "unknown".equalsIgnoreCase(ip)) ip = req.getHeader("X-Real-IP");
        if (StrUtil.isBlank(ip) || "unknown".equalsIgnoreCase(ip)) ip = req.getRemoteAddr();
        return ip;
    }
}