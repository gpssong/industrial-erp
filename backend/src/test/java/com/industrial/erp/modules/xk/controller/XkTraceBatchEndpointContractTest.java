package com.industrial.erp.modules.xk.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.industrial.erp.modules.mes.controller.MesController;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.1.189 — 批号可能含 "/" (如 251/WQ), 放进 URL 路径会被 Tomcat 以「非法编码斜杠」
 * 拒掉 (400 + HTML 错误页), 前端拿到非 JSON 只能报「服务暂不可用」。
 * ⇒ 批次溯源必须提供「批号走查询参数」的端点, 且权限与路径版一致。
 */
class XkTraceBatchEndpointContractTest {

    @Test
    void xkTraceFullExposesQueryParamVariantAndKeepsPathVariant() throws Exception {
        Method byQuery = XkController.class.getMethod("traceFullByQuery", String.class);
        assertThat(byQuery.getAnnotation(GetMapping.class).value()).containsExactly("/trace/full");
        RequestParam param = byQuery.getParameters()[0].getAnnotation(RequestParam.class);
        assertThat(param).isNotNull();
        assertThat(param.value()).isEqualTo("batchNo");
        assertThat(param.required()).isTrue();

        SaCheckPermission auth = byQuery.getAnnotation(SaCheckPermission.class);
        assertThat(auth.value()).containsExactlyInAnyOrder("production:report:trace", "production:report:list");
        assertThat(auth.mode()).isEqualTo(SaMode.OR);

        Method byPath = XkController.class.getMethod("traceFull", String.class);
        assertThat(byPath.getAnnotation(GetMapping.class).value()).containsExactly("/trace/full/{batchNo}");
    }

    @Test
    void mesBatchTraceExposesQueryParamVariants() throws Exception {
        Method trace = MesController.class.getMethod("traceByQuery", String.class, int.class);
        assertThat(trace.getAnnotation(GetMapping.class).value()).containsExactly("/batch/trace");
        assertThat(paramName(trace, 0)).isEqualTo("batchNo");

        Method traceFull = MesController.class.getMethod("traceFullByQuery", String.class);
        assertThat(traceFull.getAnnotation(GetMapping.class).value()).containsExactly("/batch/trace-full");
        assertThat(paramName(traceFull, 0)).isEqualTo("batchNo");

        for (Method m : new Method[]{trace, traceFull}) {
            SaCheckPermission auth = m.getAnnotation(SaCheckPermission.class);
            assertThat(auth.value()).containsExactlyInAnyOrder("production:report:trace", "production:report:list");
            assertThat(auth.mode()).isEqualTo(SaMode.OR);
        }
    }

    private static String paramName(Method m, int idx) {
        Parameter p = m.getParameters()[idx];
        RequestParam rp = p.getAnnotation(RequestParam.class);
        assertThat(rp).isNotNull();
        return rp.value();
    }
}
