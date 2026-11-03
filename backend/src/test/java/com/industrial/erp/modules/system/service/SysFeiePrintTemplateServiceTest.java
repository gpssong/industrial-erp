package com.industrial.erp.modules.system.service;

import com.industrial.erp.config.FreemarkerConfig;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.system.entity.SysFeiePrintTemplate;
import com.industrial.erp.modules.system.mapper.SysFeiePrintTemplateMapper;
import freemarker.template.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * v1.1.162: 飞鹅打印模板保存前的 FreeMarker 语法校验.
 *
 * <p>回归现场: 生产单模板写了 {@code ${d.qty!0''}} (多打了一对单引号) 和
 * {@code <#list bill.details as d>} 且漏了 {@code </#list>}, 保存时无人拦,
 * 直到用户点打印/预览才炸出 "Syntax error in template \"custom\" in line 22, column 45",
 * 而界面上看不出错在哪一行.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SysFeiePrintTemplateServiceTest {

    @Mock
    private SysFeiePrintTemplateMapper mapper;

    private SysFeiePrintTemplateService service;

    @BeforeEach
    void setUp() throws Exception {
        Configuration cfg = new FreemarkerConfig().feieFreemarkerConfig();
        service = new SysFeiePrintTemplateService(mapper, cfg);
    }

    private SysFeiePrintTemplate template(String content) {
        SysFeiePrintTemplate t = new SysFeiePrintTemplate();
        t.setId(1L);
        t.setName("生产单");
        t.setBizType("PRD_ORDER");
        t.setPrinterConfigId(1L);
        t.setContent(content);
        t.setPaperWidth(58);
        t.setStatus(1);
        return t;
    }

    @Test
    @DisplayName("非法默认值写法 ${d.qty!0''} 保存被拒, 报错带行号与该行原文")
    void rejectsBadDefaultSyntax() {
        String content = "<CB>生产单</CB><BR>\n单号：${order.billNo!''}<BR>\n"
                + "${d.lineNo!''}｜${d.productName!''}｜${d.qty!0''}｜${d.batchNo!''}";

        assertThatThrownBy(() -> service.save(template(content)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("模板语法有误")
                .hasMessageContaining("第 3 行")
                .hasMessageContaining("d.qty!0''");

        verify(mapper, never()).insert(any(SysFeiePrintTemplate.class));
    }

    @Test
    @DisplayName("<#list> 少了 </#list> 保存被拒")
    void rejectsUnclosedList() {
        String content = "<#list details as d>\n${d.productName!''}<BR>\n";

        assertThatThrownBy(() -> service.update(template(content)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("模板语法有误");

        verify(mapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("修正后的模板 (details 根变量 + 合法默认值 + 闭合 list) 可以保存")
    void acceptsCorrectedTemplate() {
        String content = "<CB>生产单</CB><BR>\n单号：${order.billNo!''}<BR>\n数量：${order.planQty!0}<BR>\n"
                + "<#list details as d>\n${d.lineNo!''}｜${d.productName!''}｜${d.qty!0}｜${d.batchNo!''}<BR>\n</#list>\n<CUT>";

        assertThatCode(() -> service.save(template(content))).doesNotThrowAnyException();
        verify(mapper).insert(any(SysFeiePrintTemplate.class));
    }

    @Test
    @DisplayName("bill 与 details 都是合法根变量写法 (主表别名 + 明细根变量)")
    void acceptsBillAliasAndDetailsRoot() {
        String content = "单号：${bill.billNo!''}<BR>\n<#list details as d>${d.qty!0}<BR></#list>";

        // 走 save 而不是 update: update 里的 LambdaUpdateWrapper 需要 MyBatis-Plus 的实体 lambda 缓存, 纯单测没有
        assertThatCode(() -> service.save(template(content))).doesNotThrowAnyException();
        verify(mapper).insert(any(SysFeiePrintTemplate.class));
    }
}
