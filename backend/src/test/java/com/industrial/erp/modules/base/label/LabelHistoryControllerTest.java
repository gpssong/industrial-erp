package com.industrial.erp.modules.base.label;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.industrial.erp.common.R;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.controller.BaseLabelTemplateController;
import com.industrial.erp.modules.base.entity.BaseLabelTemplateHist;
import com.industrial.erp.modules.base.service.BaseLabelTemplateService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Min;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 7 Step 5 的**防回归**钉子：注解口径（只有 template、没有旧 GET 的 list OR）、
 * 路径与信封形状、body 校验注解。真实鉴权（非超管账号的 HTTP/R code 正反例）在 Task 13。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LabelHistoryControllerTest {

    @Mock private BaseLabelTemplateService service;

    private BaseLabelTemplateController controller;

    @BeforeEach
    void setUp() {
        controller = new BaseLabelTemplateController(service);
    }

    private static Method method(Class<?> type, String name, int paramCount) {
        for (Method m : type.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == paramCount) return m;
        }
        throw new AssertionError("控制器上没有方法 " + name + "/" + paramCount + " 参");
    }

    // ────────────────────────── 委托与信封 ──────────────────────────

    @Test
    @DisplayName("GET /{id}/history 委托 service.history，R 信封带摘要列表")
    void historyDelegates() {
        LabelHistorySummary s = new LabelHistorySummary();
        s.setSeq(2);
        s.setCreateTime(LocalDateTime.now());
        when(service.history(7L)).thenReturn(List.of(s));

        R<List<LabelHistorySummary>> r = controller.history(7L);

        assertThat(r.getCode()).isEqualTo(200);
        assertThat(r.getData()).containsExactly(s);
        verify(service).history(7L);
    }

    @Test
    @DisplayName("GET /{id}/history/{seq} 委托 service.historyOne，返回含 elements 的单版全量")
    void historyOneDelegates() {
        BaseLabelTemplateHist h = new BaseLabelTemplateHist();
        h.setSeq(3);
        h.setElements("[]");
        when(service.historyOne(7L, 3)).thenReturn(h);

        R<BaseLabelTemplateHist> r = controller.historyOne(7L, 3);

        assertThat(r.getCode()).isEqualTo(200);
        assertThat(r.getData()).isSameAs(h);
        verify(service).historyOne(7L, 3);
    }

    @Test
    @DisplayName("POST /{id}/rollback 只把 seq 交给服务；成功回空信封，不回整张历史列表")
    void rollbackDelegatesWithEmptyEnvelope() {
        R<Void> r = controller.rollback(7L, new LabelRollbackRequest(2));

        assertThat(r.getCode()).isEqualTo(200);
        assertThat(r.getData()).isNull();
        verify(service).rollback(eq(7L), eq(2));
    }

    @Test
    @DisplayName("服务 mock 拒绝（403/404）时原样上抛，控制器不吞、不再碰别的东西")
    void serviceRejectionPropagates() {
        when(service.history(7L)).thenThrow(BizException.of(403, "无权限: base:label:template"));
        assertThatThrownBy(() -> controller.history(7L)).isInstanceOf(BizException.class)
                .hasMessageContaining("无权限");

        org.mockito.Mockito.doThrow(BizException.of(404, "标签模板不存在或已删除: id=7"))
                .when(service).rollback(eq(7L), any());
        assertThatThrownBy(() -> controller.rollback(7L, new LabelRollbackRequest(1)))
                .isInstanceOf(BizException.class).hasMessageContaining("不存在");
        verify(service, org.mockito.Mockito.never()).list(any(), any());
    }

    // ────────────────────────── 注解口径（Step 3 原文） ──────────────────────────

    @Test
    @DisplayName("三个新端点都是 @SaCheckPermission(value=template 单独, orRole=admin)，绝不抄旧 GET 的 list OR")
    void annotationsAreTemplateOnly() {
        for (Method m : List.of(
                method(BaseLabelTemplateController.class, "history", 1),
                method(BaseLabelTemplateController.class, "historyOne", 2),
                method(BaseLabelTemplateController.class, "rollback", 2))) {
            SaCheckPermission ann = m.getAnnotation(SaCheckPermission.class);
            assertThat(ann).as(m.getName() + " 必须带 @SaCheckPermission").isNotNull();
            assertThat(ann.value()).as(m.getName() + " 权限点只许 template（旧 list OR 不许抄进来）")
                    .containsExactly("base:label:template");
            assertThat(ann.mode()).as(m.getName() + " 不许 SaMode.OR").isEqualTo(SaMode.AND);
            assertThat(ann.orRole()).containsExactly("admin");
        }
    }

    @Test
    @DisplayName("端点路径钉死：GET /{id}/history、GET /{id}/history/{seq}、POST /{id}/rollback")
    void pathsAreFixed() {
        GetMapping h = method(BaseLabelTemplateController.class, "history", 1).getAnnotation(GetMapping.class);
        GetMapping h1 = method(BaseLabelTemplateController.class, "historyOne", 2).getAnnotation(GetMapping.class);
        PostMapping rb = method(BaseLabelTemplateController.class, "rollback", 2).getAnnotation(PostMapping.class);
        assertThat(h.value()).containsExactly("/{id}/history");
        assertThat(h1.value()).containsExactly("/{id}/history/{seq}");
        assertThat(rb.value()).containsExactly("/{id}/rollback");
    }

    @Test
    @DisplayName("rollback 的 body 参数带 @Valid + @RequestBody —— 校验注解不生效就没有 400 一说")
    void rollbackBodyIsValidated() {
        Method m = method(BaseLabelTemplateController.class, "rollback", 2);
        Parameter body = m.getParameters()[1];
        assertThat(body.isAnnotationPresent(RequestBody.class)).isTrue();
        assertThat(body.getAnnotation(jakarta.validation.Valid.class))
                .as("必须显式 @Valid，否则 LabelRollbackRequest 上的约束没人执行").isNotNull();
    }

    // ────────────────────────── LabelRollbackRequest 契约 ──────────────────────────

    @Test
    @DisplayName("LabelRollbackRequest 只有一个 seq 分量，约束随访问器生效（NotNull/Min(1)，Integer）")
    void requestHasOnlyPositiveSeq() throws Exception {
        assertThat(LabelRollbackRequest.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName).containsExactly("seq");
        // record 分量上的注解按 JLS 传播到字段/访问器/构造器参数，RecordComponent.getAnnotation 本身拿不到
        java.lang.reflect.Method accessor = LabelRollbackRequest.class.getMethod("seq");
        assertThat(accessor.getAnnotation(jakarta.validation.constraints.NotNull.class)).isNotNull();
        Min min = accessor.getAnnotation(Min.class);
        assertThat(min).isNotNull();
        assertThat(min.value()).isEqualTo(1);
        assertThat(accessor.getReturnType()).isEqualTo(Integer.class);
    }

    @Test
    @DisplayName("真实 Validator：null/0/-1 都违规，1 合法 —— Controller 的 @Valid 因此能给出 400")
    void validatorRejectsNonPositiveSeq() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        Set<ConstraintViolation<LabelRollbackRequest>> nullSeq =
                validator.validate(new LabelRollbackRequest(null));
        Set<ConstraintViolation<LabelRollbackRequest>> zeroSeq =
                validator.validate(new LabelRollbackRequest(0));
        Set<ConstraintViolation<LabelRollbackRequest>> negativeSeq =
                validator.validate(new LabelRollbackRequest(-1));
        Set<ConstraintViolation<LabelRollbackRequest>> okSeq =
                validator.validate(new LabelRollbackRequest(1));

        assertThat(nullSeq).hasSize(1);
        assertThat(zeroSeq).hasSize(1);
        assertThat(negativeSeq).hasSize(1);
        assertThat(okSeq).isEmpty();
    }
}
