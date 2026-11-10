package com.industrial.erp.modules.base.label;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseLabelTemplate;
import com.industrial.erp.modules.base.mapper.BaseLabelTemplateHistMapper;
import com.industrial.erp.modules.base.mapper.BaseLabelTemplateMapper;
import com.industrial.erp.modules.base.service.BaseLabelTemplateService;
import com.industrial.erp.modules.system.mapper.SysUserMapper;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BaseLabelTemplateServiceTest {

    @Mock private BaseLabelTemplateMapper mapper;
    /** 历史 mapper 只被 update 的快照路径消费；本类钉的是闸、默认值与默认位，不碰历史。 */
    @Mock private BaseLabelTemplateHistMapper histMapper;
    @Mock private PermissionService permService;
    /** Task 7 起服务构造器多一个 SysUserMapper（history 的姓名注入用）；本类不碰历史读路径，保持空 mock。 */
    @Mock private SysUserMapper userMapper;

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
        t.setElements("[{\"type\":\"text\",\"x\":1,\"y\":0,\"field\":\"batchNo\",\"size\":24}]");
        return t;
    }

    private BaseLabelTemplateService svc() {
        return new BaseLabelTemplateService(mapper, new LabelTemplateRenderer(), permService, histMapper, userMapper);
    }

    @Test
    @DisplayName("未知字段名的模板存不进去，且一条 INSERT 都不发")
    void saveGateRejectsUnknownField() {
        BaseLabelTemplate t = good();
        t.setElements("[{\"type\":\"text\",\"x\":1,\"y\":0,\"field\":\"nonsense\",\"size\":24}]");
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

    // ↓↓↓ A8 修补轮（round 1）新增的 5 条，逐条对应上面的裁定 R6 与两条小口径 ↓↓↓

    @Test
    @DisplayName("新建时客户端偷带 isDefault=1 也不许落成默认 —— 默认位只能走 setDefault (A8 裁定 R6)")
    void addNeverCreatesADefaultRow() {
        BaseLabelTemplate t = good();
        t.setIsDefault(1);                       // 客户端这么写
        when(mapper.selectCount(any())).thenReturn(0L);

        svc().add(t);

        // 不拦就是同 bizType 里第二条默认：setDefault 清的是兄弟行，管不到正在 INSERT 的这一行。
        ArgumentCaptor<BaseLabelTemplate> cap = ArgumentCaptor.forClass(BaseLabelTemplate.class);
        verify(mapper).insert(cap.capture());
        assertThat(cap.getValue().getIsDefault()).isEqualTo(0);
    }

    @Test
    @DisplayName("整模板级错误（坏 JSON / 空数组）只有一条消息，且不带「第 N 个元素」前缀")
    void wholeTemplateIssueHasNoElementPrefix() {
        BaseLabelTemplate t = good();
        t.setElements("{not json");              // parse 失败 → validate 回且只回一条 index=-1
        when(mapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> svc().add(t)).isInstanceOf(BizException.class)
                .hasMessageContaining("模板校验未通过")
                .hasMessageNotContaining("个元素");
    }

    @Test
    @DisplayName("PUT 也查模板编码重复（selfId 排除自己那一步只能验到「查没查」）")
    void updateAlsoChecksCodeUniqueness() {
        BaseLabelTemplate stored = good();
        stored.setId(7L);
        when(mapper.selectById(7L)).thenReturn(stored);
        when(mapper.selectForUpdate(7L)).thenReturn(stored);   // update 走行锁读，两种读法都桩住，本用例只盯编码重复
        when(mapper.selectCount(any())).thenReturn(1L);   // 另一个活行占用了同一编码

        assertThatThrownBy(() -> svc().update(stored)).isInstanceOf(BizException.class)
                .hasMessageContaining("已存在");
        verify(mapper, never()).updateById(any(BaseLabelTemplate.class));
    }

    @Test
    @DisplayName("零元素模板存不进去：[] 和全空白都算（否则能存成默认、打出一张空白标签）")
    void emptyElementsRejected() {
        BaseLabelTemplate blank = good();
        blank.setElements("[]");
        when(mapper.selectCount(any())).thenReturn(0L);
        assertThatThrownBy(() -> svc().add(blank)).isInstanceOf(BizException.class)
                .hasMessageContaining("任何元素");

        BaseLabelTemplate spaces = good();
        spaces.setElements("   ");
        assertThatThrownBy(() -> svc().add(spaces)).isInstanceOf(BizException.class)
                .hasMessageContaining("任何元素");
    }

    @Test
    @DisplayName("PUT 不带 id → 400「缺少模板 id」，不是「不存在或已删除: id=null」那种把人引偏的 404")
    void updateWithoutIdIsClientError() {
        BaseLabelTemplate t = good();            // 没有 id

        assertThatThrownBy(() -> svc().update(t)).isInstanceOf(BizException.class)
                .hasMessageContaining("缺少模板 id");
        verify(mapper, never()).selectById(any());
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
        when(mapper.selectForUpdate(5L)).thenReturn(stored);   // 同上：本用例盯的是 bizType 钉死，不是读法

        BaseLabelTemplate payload = good();
        payload.setId(5L);
        payload.setBizType("CARTON");               // 客户端偷改类型
        payload.setElements("[{\"type\":\"text\",\"x\":1,\"y\":0,\"field\":\"customerName\",\"size\":24}]");

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

    // ↓↓↓ 纸头默认与纸头闸拆成 applyPaperDefaults/validatePaper 后，resolve 与保存必须共用同一套数 ↓↓↓

    /** 只有纸头与元素的「草稿形状」：没有名称编码 —— resolve 消费的就是这种对象 (docs/35 §3.2 要点 2)。 */
    private static BaseLabelTemplate paper() {
        BaseLabelTemplate t = new BaseLabelTemplate();
        t.setBizType("FG_ROLL");
        t.setElements("[{\"type\":\"text\",\"x\":1,\"y\":0,\"field\":\"batchNo\",\"size\":24}]");
        return t;
    }

    @Test
    @DisplayName("applyPaperDefaults 只填纸头：名称/编码/status 一个都不碰，也不抛 —— 草稿没有这两项")
    void applyPaperDefaultsFillsOnlyPaper() {
        BaseLabelTemplate t = paper();

        BaseLabelTemplateService.applyPaperDefaults(t);

        assertThat(t.getWidthMm()).isEqualTo(80);
        assertThat(t.getHeightMm()).isEqualTo(60);
        assertThat(t.getGapMm()).isEqualTo(3);
        assertThat(t.getDpi()).isEqualTo(203);
        assertThat(t.getCopies()).isEqualTo(1);
        assertThat(t.getDirection()).isEqualTo("NORMAL");
        assertThat(t.getStatus()).isNull();
        assertThat(t.getTemplateName()).isNull();
        assertThat(t.getTemplateCode()).isNull();
    }

    @Test
    @DisplayName("validatePaper 的容量闸按**换算后的点数**判 (docs/36 §0.3)：203dpi 下 125000mm 正好一百万点，放行")
    void validatePaperCapsCanvasAtOneMillionDots() {
        BaseLabelTemplate fits = paper();
        fits.setWidthMm(125000);
        fits.setHeightMm(60);
        fits.setDpi(203);
        assertThatCode(() -> BaseLabelTemplateService.validatePaper(fits)).doesNotThrowAnyException();

        BaseLabelTemplate over = paper();
        over.setWidthMm(125000);
        over.setHeightMm(133333);
        over.setDpi(203);
        assertThatThrownBy(() -> BaseLabelTemplateService.validatePaper(over))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("标签画布不能超过 " + LabelGeometry.MAX_GEOMETRY_DOTS + " 点");

        BaseLabelTemplate noDpi = paper();
        noDpi.setWidthMm(80);
        noDpi.setHeightMm(60);
        noDpi.setDpi(0);
        assertThatThrownBy(() -> BaseLabelTemplateService.validatePaper(noDpi))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("DPI 必须大于 0");
        // 静态纸头闸不碰数据库：resolve 调它时根本没有 mapper
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("拆分之后 add 的报错顺序照旧：类型→名称→编码→正宽高→正 dpi→容量")
    void addKeepsTheOriginalHeaderErrorOrder() {
        BaseLabelTemplate noType = new BaseLabelTemplate();
        noType.setElements("[]");
        assertThatThrownBy(() -> svc().add(noType)).isInstanceOf(BizException.class)
                .hasMessageContaining("请选择标签类型");

        BaseLabelTemplate noName = paper();
        assertThatThrownBy(() -> svc().add(noName)).isInstanceOf(BizException.class)
                .hasMessageContaining("请填写模板名称");

        BaseLabelTemplate named = paper();
        named.setTemplateName("测试卷标");
        named.setWidthMm(0);                       // 故意同时把宽高写坏：编码那条必须仍然先响
        assertThatThrownBy(() -> svc().add(named)).isInstanceOf(BizException.class)
                .hasMessageContaining("请填写模板编码");

        BaseLabelTemplate coded = paper();
        coded.setTemplateName("测试卷标");
        coded.setTemplateCode("FG_TEST");
        coded.setWidthMm(0);
        coded.setDpi(0);
        when(mapper.selectCount(any())).thenReturn(0L);
        assertThatThrownBy(() -> svc().add(coded)).isInstanceOf(BizException.class)
                .hasMessageContaining("标签宽/高必须大于 0 mm");

        BaseLabelTemplate sized = paper();
        sized.setTemplateName("测试卷标");
        sized.setTemplateCode("FG_TEST");
        sized.setDpi(0);
        assertThatThrownBy(() -> svc().add(sized)).isInstanceOf(BizException.class)
                .hasMessageContaining("DPI 必须大于 0");
    }

    @Test
    @DisplayName("纸头超容量的模板在表头就被拦下，不许先进闸被元素越界归罪 (A8 裁定 R3 的续条)")
    void oversizedPaperHeaderFailsBeforeGate() {
        BaseLabelTemplate t = good();
        t.setWidthMm(133333);                      // dpi 仍是 203 ⇒ 1,066,664 点 > 1,000,000
        when(mapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> svc().add(t)).isInstanceOf(BizException.class)
                .hasMessageContaining("标签画布不能超过 " + LabelGeometry.MAX_GEOMETRY_DOTS + " 点")
                .hasMessageNotContaining("越界");
        verify(mapper, never()).insert(any(BaseLabelTemplate.class));
    }
}
