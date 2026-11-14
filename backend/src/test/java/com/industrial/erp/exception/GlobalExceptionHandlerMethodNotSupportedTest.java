package com.industrial.erp.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v1.1.186 — 请求方法不支持 (405) 不该被兜底成「系统繁忙, 请稍后再试」。
 *
 * <p>现场报「保存检验记录失败: 系统繁忙, 请稍后再试: Request method 'PUT' is not supported」——
 * App 调了一个后端不存在的端点, 兜底处理器把话说成了"系统繁忙", 排查成本极高。
 */
class GlobalExceptionHandlerMethodNotSupportedTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void reportsMethodAndPathInsteadOfGenericBusyMessage() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn("PUT");
        when(req.getRequestURI()).thenReturn("/api/qms/inspection/2119676240634888193");
        HttpRequestMethodNotSupportedException e =
                new HttpRequestMethodNotSupportedException("PUT", java.util.List.of("GET", "POST"));

        var r = handler.handleMethodNotSupported(e, req);

        assertThat(r.getCode()).isEqualTo(405);
        assertThat(r.getMsg())
                .contains("PUT")
                .contains("/api/qms/inspection/2119676240634888193")
                .contains("不支持")
                .contains("GET");
        assertThat(r.getMsg()).doesNotContain("系统繁忙");
    }
}
