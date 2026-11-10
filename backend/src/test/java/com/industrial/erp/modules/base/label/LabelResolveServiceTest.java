package com.industrial.erp.modules.base.label;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.controller.BaseLabelController;
import com.industrial.erp.modules.base.entity.BaseLabelTemplate;
import com.industrial.erp.modules.base.mapper.BaseLabelTemplateHistMapper;
import com.industrial.erp.modules.base.mapper.BaseLabelTemplateMapper;
import com.industrial.erp.modules.base.service.BaseLabelTemplateService;
import com.industrial.erp.modules.system.mapper.SysUserMapper;
import com.industrial.erp.security.PermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 草稿 resolve (docs/35 §3.2 / docs/36 Task 5) 的行为钉子。
 *
 * <p>排版链路（renderer / layout / TSPL）一律用**真实实现** —— 这一层存在的意义就是画布盒子与
 * 打印指令同一个出口（全局约束 5），mock 掉任何一环都等于把要对齐的两边拆成各说一套。
 * 只有上下文取数（示例与真实单据）、权限与模板表是 mock。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LabelResolveServiceTest {

    @Mock private LabelContextService contextService;
    @Mock private PermissionService permService;
    @Mock private BaseLabelTemplateMapper templateMapper;
    /** 历史 mapper 只在 update 的快照路径上用；本类只走 add 与 resolve，因此永不触发。 */
    @Mock private BaseLabelTemplateHistMapper histMapper;
    /** Task 7 起服务构造器多一个 SysUserMapper；本类不碰 history 注入路径，保持空 mock。 */
    @Mock private SysUserMapper userMapper;
    @Mock private LabelMasterService masterService;

    private final LabelTemplateRenderer renderer = new LabelTemplateRenderer();
    private final TsplLabelRenderer tspl = new TsplLabelRenderer();
    private LabelPreviewService previewService;
    private LabelResolveService service;
    private BaseLabelTemplateService saveService;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** brief Step 1 的元素原文（Java 字面量里 \" 只是源码转义，实际字符串就是 brief 的字节）。 */
    private static final String BRIEF_ELEMENTS =
            "[{\"type\":\"text\",\"x\":20,\"y\":40,\"text\":\"甲乙丙丁戊\",\"w\":96,\"size\":24,\"mulX\":1,\"mulY\":1}]";
    private static final String FIELD_BATCHNO_ELEMENTS =
            "[{\"type\":\"text\",\"x\":1,\"y\":0,\"field\":\"batchNo\",\"size\":24}]";
    private static final LabelResolveRequest.Header PAPER =
            new LabelResolveRequest.Header(80, 60, 3, 203, "NORMAL", 1);
    private static final String BRIEF_JSON = "{\"bizType\":\"FG_ROLL\",\"elements\":" + jsonString(BRIEF_ELEMENTS)
            + ",\"header\":{\"widthMm\":80,\"heightMm\":60,\"dpi\":203,\"gapMm\":3,"
            + "\"direction\":\"NORMAL\",\"copies\":1},\"sample\":true}";

    /** 把一段原文安全地嵌进外层 JSON 的字符串值里 —— 不许手抄两遍 brief 元素导致两处漂移。 */
    private static String jsonString(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @BeforeEach
    void setUp() {
        previewService = new LabelPreviewService(contextService, renderer);
        service = new LabelResolveService(renderer, contextService, previewService, permService);
        saveService = new BaseLabelTemplateService(templateMapper, renderer, permService, histMapper, userMapper);
    }

    private static LabelResolveRequest sampleReq(String elements) {
        return new LabelResolveRequest("FG_ROLL", elements, PAPER, true, null, null);
    }

    /** 保存侧对照用的模板：纸头与 resolve 的 PAPER 同值，名称编码给全 —— 让失败只可能出在元素上。 */
    private static BaseLabelTemplate template(String elements) {
        BaseLabelTemplate t = new BaseLabelTemplate();
        t.setBizType("FG_ROLL"); t.setTemplateName("测试卷标"); t.setTemplateCode("FG_TEST");
        t.setWidthMm(80); t.setHeightMm(60); t.setGapMm(3); t.setDpi(203);
        t.setDirection("NORMAL"); t.setCopies(1);
        t.setElements(elements);
        return t;
    }

    @Test
    @DisplayName("brief 样例：640×480、1 张标签、元素 0 拆两盒 (20,40,96,24,甲乙丙丁)/(20,64,24,24,戊)，有效倍率 1/1")
    void briefSampleResolvesToWrappedBoxes() throws Exception {
        when(contextService.loadSample("FG_ROLL")).thenReturn(List.of(Map.of()));

        LabelResolveResult r = service.resolve(sampleReq(BRIEF_ELEMENTS));

        assertThat(r.widthDots()).isEqualTo(640);
        assertThat(r.heightDots()).isEqualTo(480);
        assertThat(r.issues()).isEmpty();
        assertThat(r.labels()).hasSize(1);
        List<LabelResolveResult.Element> es = r.labels().get(0).elements();
        assertThat(es).hasSize(1);
        LabelResolveResult.Element e = es.get(0);
        assertThat(e.index()).isZero();
        assertThat(e.type()).isEqualTo("text");
        assertThat(e.content()).isEqualTo("甲乙丙丁戊");
        assertThat(e.mulX()).isEqualTo(1);
        assertThat(e.mulY()).isEqualTo(1);
        assertThat(e.overflow()).isFalse();
        assertThat(e.issues()).isEmpty();
        assertThat(e.segments()).containsExactly(
                new LabelLayout.Box(20, 40, 96, 24, "甲乙丙丁"),
                new LabelLayout.Box(20, 64, 24, 24, "戊"));
        // 文本的可点选外框 = segments 的联合外框
        assertThat(e.displayBounds()).isEqualTo(new LabelResolveResult.DisplayBounds(20, 40, 96, 48, false, null));

        // 全局约束 2/8：响应里没有 rotate、issues 里没有 level —— 断的是**序列化后的对外外形**，不是 Java 类型
        String json = JSON.writeValueAsString(r);
        assertThat(json).doesNotContain("\"rotate\"");
        assertThat(json).doesNotContain("\"level\"");
    }

    @Test
    @DisplayName("mulX=2：CJK 一字 48 点 ⇒ 三盒、尾盒 (20,88,48,24,戊)；同一份 RenderResult 的 TSPL 也恰好三条 TEXT")
    void declaredMulXSplitsTheSameWayAsTheCommand() {
        String elements = BRIEF_ELEMENTS.replace("\"mulX\":1", "\"mulX\":2");
        when(contextService.loadSample("FG_ROLL")).thenReturn(List.of(Map.of()));

        LabelResolveResult.Element e = service.resolve(sampleReq(elements)).labels().get(0).elements().get(0);

        assertThat(e.mulX()).isEqualTo(2);
        assertThat(e.segments()).containsExactly(
                new LabelLayout.Box(20, 40, 96, 24, "甲乙"),
                new LabelLayout.Box(20, 64, 96, 24, "丙丁"),
                new LabelLayout.Box(20, 88, 48, 24, "戊"));

        String text = tspl.renderCommandText(renderer.render(template(elements), Map.of()));
        assertThat(text)
                .contains("TEXT 20,40,\"TSS24.BF2\",0,2,1,\"甲乙\"\r\n")
                .contains("TEXT 20,64,\"TSS24.BF2\",0,2,1,\"丙丁\"\r\n")
                .contains("TEXT 20,88,\"TSS24.BF2\",0,2,1,\"戊\"\r\n");
        // 三盒 = 三条 TEXT：行数不一致意味着画布和纸各排各的（全局约束 5 的唯一出口失守）
        assertThat(text.split("TEXT ", -1)).hasSize(4);
    }

    @Test
    @DisplayName("粗体：resolve 把 bold 回给画布做重复绘制，盒子与外框不变，TSPL 仍只多一条 x+1")
    void boldReachesTheCanvasWithoutChangingTheLayout() throws Exception {
        String bold = BRIEF_ELEMENTS.replace("\"mulY\":1}", "\"mulY\":1,\"bold\":true}");
        when(contextService.loadSample("FG_ROLL")).thenReturn(List.of(Map.of()));

        LabelResolveResult.Element e = service.resolve(sampleReq(bold)).labels().get(0).elements().get(0);
        LabelResolveResult.Element plain = service.resolve(sampleReq(BRIEF_ELEMENTS)).labels().get(0).elements().get(0);

        // 断的是序列化后的对外外形：画布读的就是这份 JSON，Java 访问器存在不代表线上回了这个键
        assertThat(JSON.writeValueAsString(e)).contains("\"bold\":true");
        assertThat(JSON.writeValueAsString(plain)).contains("\"bold\":false");
        assertThat(e.segments()).isEqualTo(plain.segments());
        assertThat(e.displayBounds()).isEqualTo(plain.displayBounds());

        String text = tspl.renderCommandText(renderer.render(template(bold), Map.of()));
        assertThat(text).contains("TEXT 21,40,\"TSS24.BF2\",0,1,1,\"甲乙丙丁\"\r\n");
        assertThat(text.split("TEXT ", -1)).hasSize(5);
    }

    @Test
    @DisplayName("表1：空 elements 数组 resolve 出一张空画布且 issues 空；同一份模板保存仍然 400")
    void emptyArrayResolvesWhileSaveStillRejects() {
        when(contextService.loadSample("FG_ROLL")).thenReturn(List.of(Map.of("batchNo", "PH1")));

        LabelResolveResult r = service.resolve(sampleReq("[]"));

        assertThat(r.issues()).isEmpty();
        assertThat(r.labels()).hasSize(1);
        assertThat(r.labels().get(0).elements()).isEmpty();
        assertThat(r.widthDots()).isEqualTo(640);

        assertThatThrownBy(() -> saveService.add(template("[]")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("模板里还没有任何元素");
    }

    @Test
    @DisplayName("表2：坏 JSON / 顶层 null / null 元素 ⇒ resolve 200 带 index=-1 的解析 issue、labels 空；保存 400；无未捕获异常")
    void malformedJsonBecomesParseIssue() {
        for (String bad : List.of("{not json", "null", "[null]", "   ", "[{\"type\":\"text\"")) {
            LabelResolveResult r = service.resolve(sampleReq(bad));
            assertThat(r.issues()).as("issue of: " + bad).hasSize(1);
            assertThat(r.issues().get(0).index()).as("index of: " + bad).isEqualTo(-1);
            assertThat(r.labels()).as("labels of: " + bad).isEmpty();

            assertThatThrownBy(() -> saveService.add(template(bad)))
                    .as("save of: " + bad)
                    .isInstanceOf(BizException.class);
        }
    }

    @Test
    @DisplayName("表3：field=notAField 保留元素下标，元素级 issues 带字典错误、标橙；保存按同一文案拒绝")
    void unknownFieldKeepsIndexAndMarksOrange() {
        String elements = "[{\"type\":\"text\",\"x\":1,\"y\":0,\"field\":\"notAField\",\"size\":24}]";
        when(contextService.loadSample("FG_ROLL")).thenReturn(List.of(Map.of()));

        LabelResolveResult r = service.resolve(sampleReq(elements));
        LabelResolveResult.Element e = r.labels().get(0).elements().get(0);

        assertThat(e.index()).isZero();
        assertThat(e.field()).isEqualTo("notAField");
        assertThat(e.overflow()).isTrue();
        assertThat(e.issues()).containsExactly("该标签类型不存在字段: notAField");
        assertThatThrownBy(() -> saveService.add(template(elements)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("该标签类型不存在字段: notAField");
    }

    @Test
    @DisplayName("表4：必填字段取值为空只标 missing；不新增任何 issue；保存通过、TSPL 不发 TEXT 也不抛")
    void missingValueOnlyMarksElement() {
        String elements = "[{\"type\":\"text\",\"x\":1,\"y\":0,\"field\":\"batchNo\",\"size\":24,\"required\":true}]";
        when(contextService.loadSample("FG_ROLL")).thenReturn(List.of(Map.of()));

        LabelResolveResult r = service.resolve(sampleReq(elements));
        assertThat(r.issues()).isEmpty();
        LabelResolveResult.Element e = r.labels().get(0).elements().get(0);
        assertThat(e.missing()).isTrue();
        assertThat(e.overflow()).isFalse();
        assertThat(e.issues()).isEmpty();
        assertThat(e.content()).isEmpty();
        // 缺值时画布显示字段标签；它不是打印内容 —— content 仍是空串
        assertThat(e.displayLabel()).isEqualTo("批号");

        saveService.add(template(elements));
        verify(templateMapper).insert(any(BaseLabelTemplate.class));

        String text = tspl.renderCommandText(renderer.render(template(elements), Map.of()));
        assertThat(text).doesNotContain("TEXT ").contains("PRINT 1,1\r\n");
    }

    @Test
    @DisplayName("表5：越界的盒子与 TEXT 一致、标橙，元素级 issues 的字面量文案与 validate 逐字相同")
    void overflowCopyAndBoxesMatchValidateAndTspl() {
        String line1 = "甲".repeat(25);
        String line2 = "甲".repeat(15);
        String tall = "[{\"type\":\"text\",\"x\":20,\"y\":440,\"text\":\"" + line1 + line2 + "\",\"size\":24}]";
        String right = "[{\"type\":\"text\",\"x\":630,\"y\":10,\"text\":\"甲乙\",\"size\":24}]";
        when(contextService.loadSample("FG_ROLL")).thenReturn(List.of(Map.of()));

        LabelResolveResult.Element t = service.resolve(sampleReq(tall)).labels().get(0).elements().get(0);
        assertThat(t.overflow()).isTrue();
        assertThat(t.issues()).containsExactly(
                "这段要拆成 2 行 × 24 点 = 48 点高，从 y=440 起越过下边界 480 点，越界 8 点");
        assertThat(t.issues()).containsExactlyElementsOf(
                renderer.validate(tall, "FG_ROLL", 80, 60, 203).stream().map(ValidationIssue::message).toList());
        assertThat(t.segments()).containsExactly(
                new LabelLayout.Box(20, 440, 600, 24, line1),
                new LabelLayout.Box(20, 464, 360, 24, line2));
        String tallTspl = tspl.renderCommandText(renderer.render(template(tall), Map.of()));
        assertThat(tallTspl)
                .contains("TEXT 20,440,\"TSS24.BF2\",0,1,1,\"" + line1 + "\"\r\n")
                .contains("TEXT 20,464,\"TSS24.BF2\",0,1,1,\"" + line2 + "\"\r\n");

        LabelResolveResult.Element x = service.resolve(sampleReq(right)).labels().get(0).elements().get(0);
        assertThat(x.overflow()).isTrue();
        assertThat(x.issues()).containsExactly(
                "从 x=630 起只剩 10 点，放不下一个 24 点的字符（字体 TSS24.BF2 ×1），起点请往左挪");
        // 「连一个字符都放不下」时不拆行（lineSegments 守卫），尾巴交给固件吞 —— 盒子与 TEXT 仍是同一条
        assertThat(x.segments()).containsExactly(new LabelLayout.Box(630, 10, 48, 24, "甲乙"));
        String rightTspl = tspl.renderCommandText(renderer.render(template(right), Map.of()));
        assertThat(rightTspl).contains("TEXT 630,10,\"TSS24.BF2\",0,1,1,\"甲乙\"\r\n");
        assertThat(rightTspl.split("TEXT ", -1)).hasSize(2);
    }

    @Test
    @DisplayName("表6：widthMm=0 时 resolve 只回「标签宽/高必须大于 0 mm」——不要求名称编码、不查任何上下文")
    void zeroWidthHeaderReportsOnlyPaperCopy() {
        LabelResolveRequest q = new LabelResolveRequest("FG_ROLL", BRIEF_ELEMENTS,
                new LabelResolveRequest.Header(0, null, null, null, null, null), true, null, null);

        assertThatThrownBy(() -> service.resolve(q))
                .isInstanceOf(BizException.class)
                .hasMessage("标签宽/高必须大于 0 mm")
                .hasMessageNotContaining("名称")
                .hasMessageNotContaining("编码");
        verifyNoInteractions(contextService);
    }

    @Test
    @DisplayName("表7：sample=false 缺 bizId → 400「请选择业务单据」，且不许偷偷退回示例值")
    void realModeNeedsBizId() {
        LabelResolveRequest q = new LabelResolveRequest("FG_ROLL", BRIEF_ELEMENTS, PAPER, false, null, null);

        assertThatThrownBy(() -> service.resolve(q))
                .isInstanceOf(BizException.class)
                .hasMessage("请选择业务单据");
        verify(contextService, never()).loadSample(any());
    }

    @Test
    @DisplayName("表8：sample=false + RAW_PALLET 两行 ⇒ 保持 load 返回顺序，两张标签各用该行的真实值")
    void realModeOneLabelPerLineInLoaderOrder() {
        String elements = "[{\"type\":\"text\",\"x\":1,\"y\":0,\"field\":\"batchNo\",\"size\":24}]";
        when(contextService.load("RAW_PALLET", 9L, List.of(2L, 1L))).thenReturn(List.of(
                new java.util.LinkedHashMap<>(Map.of("batchNo", "PR-A")),
                new java.util.LinkedHashMap<>(Map.of("batchNo", "PR-B"))));
        LabelResolveRequest q = new LabelResolveRequest("RAW_PALLET", elements, PAPER, false, 9L, List.of(2L, 1L));

        LabelResolveResult r = service.resolve(q);

        assertThat(r.labels()).hasSize(2);
        assertThat(r.labels().get(0).elements().get(0).content()).isEqualTo("PR-A");
        assertThat(r.labels().get(1).elements().get(0).content()).isEqualTo("PR-B");
        verify(contextService, never()).loadSample(any());
    }

    @Test
    @DisplayName("表9：sample=true 附带 bizId ⇒ 忽略 bizId 只走示例，一行业务表都不许查")
    void sampleTrueIgnoresBizId() {
        when(contextService.loadSample("FG_ROLL")).thenReturn(List.of(Map.of("batchNo", "SAMPLE-PH")));

        LabelResolveResult r = service.resolve(
                new LabelResolveRequest("FG_ROLL", FIELD_BATCHNO_ELEMENTS, PAPER, true, 5L, null));

        assertThat(r.labels().get(0).elements().get(0).content()).isEqualTo("SAMPLE-PH");
        verify(contextService, never()).load(any(), any(), any());
    }

    @Test
    @DisplayName("表10：CERT 在 loader 路由就明确 400（不是空白成功）；bizType 缺失/未知同样点名")
    void certFailsExplicitlyAtTheLoaderRoute() {
        LabelContextService realContext = new LabelContextService(List.of(), masterService);
        LabelResolveService realRoute = new LabelResolveService(renderer, realContext,
                new LabelPreviewService(realContext, renderer), permService);
        LabelResolveRequest cert = new LabelResolveRequest("CERT",
                "[{\"type\":\"text\",\"x\":1,\"y\":0,\"text\":\"甲\",\"size\":24}]", PAPER, true, null, null);

        assertThatThrownBy(() -> realRoute.resolve(cert))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("该标签类型还没有数据加载器: CERT");

        assertThatThrownBy(() -> service.resolve(new LabelResolveRequest("  ", "[]", PAPER, true, null, null)))
                .isInstanceOf(BizException.class)
                .hasMessage("请选择标签类型");
        assertThatThrownBy(() -> service.resolve(new LabelResolveRequest("NOPE", "[]", PAPER, true, null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不支持的标签类型: NOPE");
    }

    @Test
    @DisplayName("§0.3 容量：129 个元素 / 超 256 KiB ⇒ index=-1 容量 issue + 空 labels，不进布局、不读上下文")
    void capacityLimitsStopBeforeLayout() {
        String one = "{\"type\":\"text\",\"x\":1,\"y\":0,\"text\":\"A\"}";
        String many = "[" + String.join(",", Collections.nCopies(129, one)) + "]";
        LabelResolveResult r = service.resolve(sampleReq(many));
        assertThat(r.issues()).hasSize(1);
        assertThat(r.issues().get(0).index()).isEqualTo(-1);
        assertThat(r.issues().get(0).message()).contains("模板元素不能超过 128 个");
        assertThat(r.labels()).isEmpty();

        String huge = "[{\"type\":\"text\",\"x\":1,\"y\":0,\"text\":\"" + "A".repeat(262_144) + "\"}]";
        LabelResolveResult big = service.resolve(sampleReq(huge));
        assertThat(big.issues()).hasSize(1);
        assertThat(big.issues().get(0).index()).isEqualTo(-1);
        assertThat(big.issues().get(0).message()).contains("模板元素 JSON 超过 256 KiB");
        assertThat(big.labels()).isEmpty();

        verifyNoInteractions(contextService);
    }

    @Test
    @DisplayName("§0.3 单元素解析后内容超 4096 ⇒ 明确 400（不截断业务字段冒充完整内容）")
    void overlongResolvedValueIsBadRequest() {
        when(contextService.loadSample("FG_ROLL"))
                .thenReturn(List.of(Map.of("batchNo", "A".repeat(4097))));

        assertThatThrownBy(() -> service.resolve(sampleReq(FIELD_BATCHNO_ELEMENTS)))
                .isInstanceOf(BizException.class)
                .hasMessage("第 1 个元素内容超过 4096 字符");
    }

    @Test
    @DisplayName("displayBounds 逐类约定：QR 21×cell 示意框带提示、条码示意宽 300、形状用真实框、未知类型 24×24、空文本给一字宽")
    void displayBoundsPerConvention() {
        String elements = "["
                + "{\"type\":\"qrcode\",\"x\":10,\"y\":20,\"size\":24,\"text\":\"PH2026\"},"
                + "{\"type\":\"qrcode\",\"x\":5,\"y\":5,\"size\":24,\"w\":100,\"h\":100,\"text\":\"PH2026\"},"
                + "{\"type\":\"barcode\",\"x\":20,\"y\":30,\"size\":60,\"text\":\"123456123456\"},"
                + "{\"type\":\"barcode\",\"x\":20,\"y\":30,\"size\":60,\"w\":150,\"h\":40,\"text\":\"123456123456\"},"
                + "{\"type\":\"rect\",\"x\":50,\"y\":60,\"w\":200,\"h\":80},"
                + "{\"type\":\"hexagon\",\"x\":7,\"y\":9,\"text\":\"戊\"},"
                + "{\"type\":\"text\",\"x\":300,\"y\":300,\"size\":24,\"field\":\"batchNo\"}"
                + "]";
        when(contextService.loadSample("FG_ROLL")).thenReturn(List.of(Map.of()));

        List<LabelResolveResult.Element> es = service.resolve(sampleReq(elements)).labels().get(0).elements();
        String qrHint = "二维码尺寸示意，最终以实纸为准";

        // QR：没声明 w/h ⇒ 21×有效 cell（size 24 ⇒ cell 4 ⇒ 84 点）；声明了就用声明值 —— 两者都只是示意 (R11)
        assertThat(es.get(0).cell()).isEqualTo(4);
        assertThat(es.get(0).displayBounds()).isEqualTo(
                new LabelResolveResult.DisplayBounds(10, 20, 84, 84, true, qrHint));
        assertThat(es.get(1).displayBounds()).isEqualTo(
                new LabelResolveResult.DisplayBounds(5, 5, 100, 100, true, qrHint));
        // 条码：示意宽 = max(1, min(300, 纸宽-x))；高 = barcodeHeightDots（size 60 ⇒ 60；声明 h=40 ⇒ 40）
        assertThat(es.get(2).displayBounds()).isEqualTo(
                new LabelResolveResult.DisplayBounds(20, 30, 300, 60, true, null));
        assertThat(es.get(3).displayBounds()).isEqualTo(
                new LabelResolveResult.DisplayBounds(20, 30, 150, 40, true, null));
        // 形状：真实框，与渲染器发的 BAR 同一个出口，不算示意
        assertThat(es.get(4).segments()).containsExactly(new LabelLayout.Box(50, 60, 200, 80, ""));
        assertThat(es.get(4).displayBounds()).isEqualTo(
                new LabelResolveResult.DisplayBounds(50, 60, 200, 80, false, null));
        // 未知类型：24×24 错误选择框；issue 保留原文与原下标，cell=0、不声称能打印
        assertThat(es.get(5).displayBounds()).isEqualTo(
                new LabelResolveResult.DisplayBounds(7, 9, 24, 24, true, null));
        assertThat(es.get(5).index()).isEqualTo(5);
        assertThat(es.get(5).cell()).isZero();
        assertThat(es.get(5).issues()).containsExactly(
                "未知元素类型: hexagon（可用 text / multiline / barcode / qrcode / line / rect）");
        // 未知类型**带内容**也不许给 segments：渲染器对它一条指令都不发，画布按 segments 画线就是画打印机没打的东西。
        // （displayBounds 仍给 24×24，那是「能点中、能删掉」的选择框，不是打印位置。）
        assertThat(es.get(5).segments()).isEmpty();
        // 空文本：segments 仍空，但画布要有可点选的占位框 —— 服务端有效字体的一字宽/一行高
        assertThat(es.get(6).segments()).isEmpty();
        assertThat(es.get(6).displayBounds()).isEqualTo(
                new LabelResolveResult.DisplayBounds(300, 300, 24, 20, true, null));
        assertThat(es.get(6).displayLabel()).isEqualTo("批号");
    }

    @Test
    @DisplayName("同一个元素在 /preview 与 resolve 上必须是同一张码图；svg 仍是 data:image/png，不注入 HTML")
    void codeImagesAreIdenticalToPreview() throws Exception {
        String elements = "[{\"type\":\"qrcode\",\"x\":10,\"y\":20,\"size\":24,\"field\":\"batchNo\"},"
                + "{\"type\":\"barcode\",\"x\":20,\"y\":300,\"size\":60,\"field\":\"batchNo\"}]";
        List<Map<String, Object>> rows = List.of(Map.of("batchNo", "PFI202611060001"));
        when(contextService.loadSample("FG_ROLL")).thenReturn(rows);
        when(contextService.load(eq("FG_ROLL"), eq(5L), isNull())).thenReturn(rows);

        LabelResolveResult r = service.resolve(sampleReq(elements));
        List<RenderResult> previews = previewService.preview(template(elements), 5L, null);

        List<LabelResolveResult.Element> es = r.labels().get(0).elements();
        assertThat(es.get(0).svg()).startsWith("data:image/png;base64,")
                .isEqualTo(previews.get(0).getElements().get(0).getSvg());
        assertThat(es.get(1).svg()).startsWith("data:image/png;base64,")
                .isEqualTo(previews.get(0).getElements().get(1).getSvg());

        String json = JSON.writeValueAsString(r);
        assertThat(json).contains("data:image/png;base64,");
        assertThat(json).doesNotContain("<svg");

        // brief Step 5 的对外契约对照 (docs/36 R14)：旧 /preview 的 RenderResult 外形一个字段都不许少；
        // 旧请求（不带任何新参数的 JSON）现在也照样能绑上 —— 本轮没给 LabelPreviewRequest 加过字段。
        String previewJson = JSON.writeValueAsString(previews.get(0));
        for (String oldKey : List.of("elements", "widthDots", "heightDots", "missingRequired",
                "copies", "gapMm", "dpi", "direction", "content", "svg")) {
            assertThat(previewJson).contains("\"" + oldKey + "\"");
        }
        LabelPreviewRequest oldReq = JSON.readValue(
                "{\"bizType\":\"FG_ROLL\",\"bizId\":5,\"lineIds\":[7]}", LabelPreviewRequest.class);
        assertThat(oldReq.getBizType()).isEqualTo("FG_ROLL");
        assertThat(oldReq.getTemplateId()).isNull();
        assertThat(oldReq.getLineIds()).containsExactly(7L);
    }

    @Test
    @DisplayName("brief 的 JSON 原文能原样绑定成请求 record（嵌套 Header 同值）")
    void briefJsonBindsToRequest() throws Exception {
        LabelResolveRequest q = JSON.readValue(BRIEF_JSON, LabelResolveRequest.class);

        assertThat(q.bizType()).isEqualTo("FG_ROLL");
        assertThat(q.elements()).isEqualTo(BRIEF_ELEMENTS);
        assertThat(q.sample()).isTrue();
        assertThat(q.bizId()).isNull();
        assertThat(q.lineIds()).isNull();
        assertThat(q.header()).isEqualTo(PAPER);
    }

    @Test
    @DisplayName("brief Step 5 的「正常 UTF-8 JSON」：resolve 结果按 UTF-8 编解码原样往返，中文不落成 \\u 转义")
    void resolveJsonIsPlainUtf8() throws Exception {
        when(contextService.loadSample("FG_ROLL")).thenReturn(List.of(Map.of()));

        String text = JSON.writeValueAsString(service.resolve(sampleReq(BRIEF_ELEMENTS)));
        // 边界说明：HTTP 的 charset 由 Spring 的 MappingJackson2HttpMessageConverter 决定，不在这个类的射程里；
        // 这里钉的是**响应体自己**——中文必须以 UTF-8 原样出去，画布拿到的 content 与 elements 不许变成转义串。
        byte[] utf8 = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String back = new String(utf8, java.nio.charset.StandardCharsets.UTF_8);

        assertThat(back).isEqualTo(text);
        assertThat(back).contains("甲乙丙丁戊");
        assertThat(back).doesNotContain("\\u");
        assertThat(JSON.readTree(back).path("labels").get(0).path("elements").get(0)
                .path("content").asText()).isEqualTo("甲乙丙丁戊");
    }

    @Test
    @DisplayName("header 省略 = 空 Header 走纸头默认（80×60/203 ⇒ 640×480）；sample 省略 = true 走示例")
    void omittedHeaderAndSampleUseDefaults() {
        when(contextService.loadSample("FG_ROLL")).thenReturn(List.of(Map.of()));

        LabelResolveResult r = service.resolve(
                new LabelResolveRequest("FG_ROLL", BRIEF_ELEMENTS, null, null, null, null));

        assertThat(r.widthDots()).isEqualTo(640);
        assertThat(r.heightDots()).isEqualTo(480);
        assertThat(r.labels()).hasSize(1);
        verify(contextService).loadSample("FG_ROLL");
    }

    @Test
    @DisplayName("权限先判：requirePerm 一抛，一个上下文都不许读（403 明说权限点）")
    void permissionIsCheckedFirst() {
        doThrow(BizException.of(403, "无权限访问: base:label:template"))
                .when(permService).requirePerm(BaseLabelTemplateService.PERM);

        assertThatThrownBy(() -> service.resolve(sampleReq(BRIEF_ELEMENTS)))
                .isInstanceOf(BizException.class)
                .hasMessage("无权限访问: base:label:template");
        verifyNoInteractions(contextService);
    }

    @Test
    @DisplayName("控制器只做转发：R.getData() 是服务返回的同一实例；/resolve 的路径与权限点照 docs/36 全局约束 4")
    void controllerForwardsToResolveService() throws Exception {
        LabelResolveService resolveMock = mock(LabelResolveService.class);
        BaseLabelController c = new BaseLabelController(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, resolveMock);
        LabelResolveRequest q = sampleReq("[]");
        LabelResolveResult stub = new LabelResolveResult(640, 480, List.of(), List.of());
        when(resolveMock.resolve(q)).thenReturn(stub);

        assertThat(c.resolve(q).getData()).isSameAs(stub);

        Method m = BaseLabelController.class.getMethod("resolve", LabelResolveRequest.class);
        assertThat(m.getAnnotation(cn.dev33.satoken.annotation.SaCheckPermission.class).value())
                .containsExactly("base:label:template");
        assertThat(m.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class).value())
                .containsExactly("/resolve");
        // @RequestBody 也在钉子里：直接 new controller 的单测绕过了 HTTP 绑定，去掉它线上会「200 但全字段 null」——
        // 反射断一下，别把 brief 允许的「不用 MockMvc」变成少钉一条契约。
        assertThat(m.getParameters()[0]
                .isAnnotationPresent(org.springframework.web.bind.annotation.RequestBody.class)).isTrue();
    }
}
