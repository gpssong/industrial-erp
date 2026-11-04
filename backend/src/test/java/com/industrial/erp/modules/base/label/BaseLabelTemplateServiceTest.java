package com.industrial.erp.modules.base.label;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseLabelTemplate;
import com.industrial.erp.modules.base.mapper.BaseLabelTemplateMapper;
import com.industrial.erp.modules.base.service.BaseLabelTemplateService;
import com.industrial.erp.security.PermissionService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BaseLabelTemplateServiceTest {

    @Mock private BaseLabelTemplateMapper mapper;
    @Mock private PermissionService permService;

    /**
     * **A8 裁定 R1（照抄仓库既有做法，别自己发明）：** `LambdaUpdateWrapper.set(BaseLabelTemplate::getIsDefault, 0)`
     * 会**立刻**把 lambda 解析成列名，而解析要读 MyBatis-Plus 的实体 TableInfo 缓存 —— 纯 Mockito 单测里没有
     * SqlSessionFactory，缓存是空的，`setDefaultClearsSiblings` 会直接抛
     * `MybatisPlusException: can not find lambda cache for this entity`，看起来像实现的错、其实是测试环境的错。
     * （`LambdaQueryWrapper.eq/orderBy` 是**惰性**的，所以 A5 那几个 loader 测试没撞到这条。）
     * 仓库先例：`QmsIqcItemServiceTest.java:34`、`SalOrderServiceTest.java:67`；
     * 反例注释见 `SysFeiePrintTemplateServiceTest.java:101`（它当年是绕过去的，这里正面补上）。
     */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "base-label-template-test"),
                BaseLabelTemplate.class);
    }

    private BaseLabelTemplate good() {
        BaseLabelTemplate t = new BaseLabelTemplate();
        t.setTemplateCode("FG_TEST");
        t.setTemplateName("测试卷标");
        t.setBizType("FG_ROLL");
        t.setWidthMm(80); t.setHeightMm(60); t.setGapMm(3); t.setDpi(203);
        t.setDirection("NORMAL"); t.setCopies(1);
        t.setElements("[{\"type\":\"text\",\"x\":0,\"y\":0,\"field\":\"batchNo\",\"size\":24}]");
        return t;
    }

    private BaseLabelTemplateService svc() {
        return new BaseLabelTemplateService(mapper, new LabelTemplateRenderer(), permService);
    }

    @Test
    @DisplayName("未知字段名的模板存不进去，且一条 INSERT 都不发")
    void saveGateRejectsUnknownField() {
        BaseLabelTemplate t = good();
        t.setElements("[{\"type\":\"text\",\"x\":0,\"y\":0,\"field\":\"nonsense\",\"size\":24}]");
        when(mapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> svc().add(t)).isInstanceOf(BizException.class)
                .hasMessageContaining("nonsense");
        verify(mapper, never()).insert(any(BaseLabelTemplate.class));
    }

    @Test
    @DisplayName("template_code 在活行里重复 → 拒绝（软删行不占位，所以不靠库里的 UNIQUE）")
    void rejectsDuplicateCodeAmongLiveRows() {
        when(mapper.selectCount(any())).thenReturn(1L);
        assertThatThrownBy(() -> svc().add(good())).isInstanceOf(BizException.class)
                .hasMessageContaining("已存在");
    }

    @Test
    @DisplayName("设为默认时先把同 biz_type 其它行的 is_default 清零（同类型至多一条默认）")
    void setDefaultClearsSiblings() {
        BaseLabelTemplate t = good();
        t.setId(9L);
        when(mapper.selectById(9L)).thenReturn(t);

        svc().setDefault(9L);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<BaseLabelTemplate>> cap =
                ArgumentCaptor.forClass(
                        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(mapper).update(any(), cap.capture());
        assertThat(cap.getValue().getSqlSet()).contains("is_default");
        verify(mapper).updateById(t);
        assertThat(t.getIsDefault()).isEqualTo(1);
    }

    @Test
    @DisplayName("缺省字段回填：dpi=203 / width=80 / height=60 / gap=3 / copies=1 / status=1")
    void fillsDefaults() {
        BaseLabelTemplate t = good();
        t.setWidthMm(null); t.setHeightMm(null); t.setDpi(null); t.setCopies(null); t.setStatus(null);
        when(mapper.selectCount(any())).thenReturn(0L);

        svc().add(t);

        assertThat(t.getWidthMm()).isEqualTo(80);
        assertThat(t.getDpi()).isEqualTo(203);
        assertThat(t.getCopies()).isEqualTo(1);
        assertThat(t.getStatus()).isEqualTo(1);
        verify(mapper).insert(t);
    }

    @Test
    @DisplayName("PUT 时把 bizType 改成别的类型：闸必须按**库里那一行**的类型校元素，改类型这件事本身不生效 (A8 裁定 R2)")
    void updateCannotRetypeTemplate() {
        BaseLabelTemplate stored = good();          // 库里是 FG_ROLL
        stored.setId(5L);
        when(mapper.selectById(5L)).thenReturn(stored);

        BaseLabelTemplate payload = good();
        payload.setId(5L);
        payload.setBizType("CARTON");               // 客户端偷改类型
        payload.setElements("[{\"type\":\"text\",\"x\":0,\"y\":0,\"field\":\"customerName\",\"size\":24}]");

        // customerName 是 CARTON 的合法字段、FG_ROLL 字典里没有 ⇒ 按 FG_ROLL 校必须拦。
        // 若先 gate 再回写 bizType，这条就顺着 CARTON 字典过了闸，落库却仍是 FG_ROLL 行
        // —— 打印时 A7 取不到值、§5 的必填标红又只在预览里，等于保存闸撒了一回谎。
        assertThatThrownBy(() -> svc().update(payload)).isInstanceOf(BizException.class)
                .hasMessageContaining("customerName");
        verify(mapper, never()).updateById(any(BaseLabelTemplate.class));
    }

    @Test
    @DisplayName("dpi=0 / width=0 在表头就被拦下，不许退化成「每个元素都越界」(A8 裁定 R3)")
    void nonPositiveHeaderFailsBeforeGate() {
        BaseLabelTemplate t = good();
        t.setDpi(0);
        when(mapper.selectCount(any())).thenReturn(0L);
        assertThatThrownBy(() -> svc().add(t)).isInstanceOf(BizException.class)
                .hasMessageContaining("DPI 必须大于 0");

        BaseLabelTemplate w = good();
        w.setWidthMm(0);
        assertThatThrownBy(() -> svc().add(w)).isInstanceOf(BizException.class)
                .hasMessageContaining("标签宽/高必须大于 0");
        verify(mapper, never()).insert(any(BaseLabelTemplate.class));
    }
}
