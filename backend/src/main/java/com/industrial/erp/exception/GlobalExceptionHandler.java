package com.industrial.erp.exception;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.industrial.erp.common.R;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public R<?> handleBizException(BizException e, HttpServletRequest req) {
        log.warn("[BizException] {} {} -> {}", req.getMethod(), req.getRequestURI(), e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<R<?>> handleNotLogin(NotLoginException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(R.unauthorized("未登录或登录已过期"));
    }

    @ExceptionHandler({NotPermissionException.class, NotRoleException.class})
    public ResponseEntity<R<?>> handleNoPermission(Exception e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(R.forbidden("无权限访问"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<?> handleValidException(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return R.fail(400, msg);
    }

    @ExceptionHandler(BindException.class)
    public R<?> handleBindException(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return R.fail(400, msg);
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public R<?> handleDuplicate(DuplicateKeyException e) {
        log.warn("重复数据: {}", e.getMessage());
        return R.fail("数据已存在, 请检查编码/名称是否重复");
    }

    /**
     * v1.1.186: 请求方法不支持 (405) 单独回, 不再落到兜底 handler 被说成「系统繁忙」。
     * 现场报「保存检验记录失败: 系统繁忙, 请稍后再试: Request method 'PUT' is not supported」——
     * 兜底文案把"调错了端点"伪装成"系统忙", 排查成本极高。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public R<?> handleMethodNotSupported(HttpRequestMethodNotSupportedException e, HttpServletRequest req) {
        StringBuilder msg = new StringBuilder("请求方法不支持: ")
                .append(req.getMethod()).append(' ').append(req.getRequestURI());
        if (e.getSupportedHttpMethods() != null) {
            msg.append(" (该接口只接受 ").append(e.getSupportedHttpMethods()).append(')');
        }
        log.warn("[MethodNotAllowed] {}", msg);
        return R.fail(405, msg.toString());
    }

    @ExceptionHandler(Exception.class)
    public R<?> handleException(Exception e, HttpServletRequest req) {
        log.error("[SystemException] {} {}", req.getMethod(), req.getRequestURI(), e);
        return R.fail(500, "系统繁忙, 请稍后再试: " + e.getMessage());
    }
}
