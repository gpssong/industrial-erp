package com.industrial.erp.modules.base.label;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import cn.hutool.json.JSONUtil;
import com.industrial.erp.common.R;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.controller.BaseLabelController;
import com.industrial.erp.modules.base.entity.BaseLabelPrintLog;
import com.industrial.erp.modules.base.entity.BaseLabelTemplate;
import com.industrial.erp.modules.base.mapper.BaseLabelPrintLogMapper;
import com.industrial.erp.modules.base.service.BaseLabelTemplateService;
import com.industrial.erp.modules.production.client.FeiePrintClient;
import com.industrial.erp.modules.system.entity.SysFeiePrinterConfig;
import com.industrial.erp.modules.system.mapper.SysFeiePrinterConfigMapper;
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

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A10 Step 5：飞鹅端点 + 日志端点的行为钉子。
 * 不接真飞鹅 —— `FeiePrintClient` 全程 mock，`ret` 的口径只经 `FeieResult` 一处解释 (A10-R1)。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BaseLabelControllerPrintTest {

    @Mock private LabelMasterService masterService;
    @Mock private BaseLabelTemplateService templateService;
    @Mock private LabelPreviewService previewService;
    @Mock private LabelContextService contextService;
    @Mock private LabelPrintService printService;
    @Mock private BaseLabelPrintLogMapper logMapper;
    @Mock private SysFeiePrinterConfigMapper printerMapper;
    @Mock private FeiePrintClient feieClient;
    @Mock private LabelResolveService resolveService;

    private BaseLabelController controller;
    private BaseLabelTemplate tpl;

    /** TSPL 用**真渲染器**（它是纯函数，B2 已单测）：mock 它就等于把「字节真能往返」这条最要紧的断言架空。 */
    private final TsplLabelRenderer tspl = new TsplLabelRenderer();

    @BeforeEach
    void setUp() {
        controller = new BaseLabelController(masterService, templateService, previewService, contextService,
                printService, new FeieLabelRenderer(), tspl, feieClient,
                null, null, null, null, null, printerMapper, resolveService);
        tpl = new BaseLabelTemplate();
        tpl.setId(1167000000000000001L);
        tpl.setBizType("FG_ROLL");
        tpl.setTemplateCode("FG_ROLL_DEFAULT");
    }

    private SysFeiePrinterConfig cfg() {
        SysFeiePrinterConfig c = new SysFeiePrinterConfig();
        c.setId(1L); c.setPrinterName("车间 1 号机"); c.setUkey("ukey-1");
        c.setDeviceSn("SN123"); c.setUser("acc@example.com"); c.setStatus(1);
        return c;
    }

    /** 一张标签：一行文本 ⇒ "A<BR>\n<CUT>\n"（与 FeieLabelRendererTest 同一口径）。 */
    private RenderResult rr(String content) {
        ResolvedElement e = new ResolvedElement();
        e.setType("text"); e.setX(20); e.setY(20); e.setContent(content);
        RenderResult r = new RenderResult();
        r.setElements(List.of(e));
        return r;
    }

    private LabelPreviewRequest req(String bizType, Long templateId, Long bizId) {
        LabelPreviewRequest q = new LabelPreviewRequest();
        q.setBizType(bizType); q.setTemplateId(templateId); q.setBizId(bizId);
        return q;
    }

    private void stubOneLabelFGRoll() {
        when(printerMapper.selectOne(any())).thenReturn(cfg());
        when(templateService.defaultOf("FG_ROLL")).thenReturn(tpl);
        when(previewService.preview(any(), eq(5L), isNull())).thenReturn(List.of(rr("A")));
        when(contextService.loadFirst(eq("FG_ROLL"), eq(5L), isNull()))
                .thenReturn(new HashMap<>(Map.of("billNo", "PFI0001")));
    }

    @Test
    @DisplayName("ret=0：云端已接受 ⇒ 落 SUCCESS 日志，channel=FEIE，printerMac 留空")
    void acceptedIsSuccess() {
        stubOneLabelFGRoll();
        when(feieClient.printMsg("acc@example.com", "ukey-1", "SN123", "A<BR>\n<CUT>\n", 1))
                .thenReturn(JSONUtil.parseObj("{\"ret\":0,\"msg\":\"ok\"}"));

        R<Map<String, Object>> out = controller.feiePrint(req("FG_ROLL", null, 5L));

        assertThat(out.getData()).containsEntry("total", 1).containsEntry("success", 1)
                .containsEntry("failed", 0);
        verify(printService).logSuccess("FG_ROLL", 5L, "PFI0001", tpl, "FEIE", "车间 1 号机", null, 1, "A<BR>\n<CUT>\n");
        verify(printService, never()).logFail(any(), any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any());
    }

    /**
     * A10-R1：HTTP 200 + ret≠0 不抛异常，但**必须**记 FAIL。
     * 断的是 Controller 交给日志的文案 = `FeieResult.failureOf` 的原文 ⇒ 说明 `ret` 只在那一处被解释，
     * 谁在 Controller 里内联一个自己的 `ret` 判定（换了文案/判反了），这条就红。
     */
    @Test
    @DisplayName("A10-R1：ret≠0（不抛异常）判 FAIL，文案取 FeieResult.failureOf 原文")
    void businessFailureIsLoggedAsFail() {
        stubOneLabelFGRoll();
        when(feieClient.printMsg(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(JSONUtil.parseObj("{\"ret\":-1,\"msg\":\"打印机离线\"}"));

        R<Map<String, Object>> out = controller.feiePrint(req("FG_ROLL", null, 5L));

        assertThat(out.getData()).containsEntry("total", 1).containsEntry("success", 0).containsEntry("failed", 1);
        assertThat(out.getData().get("failedItems"))
                .isEqualTo(List.of("第 1 张: 飞鹅未接受: ret=-1, msg=打印机离线"));
        verify(printService).logFail("FG_ROLL", 5L, "PFI0001", tpl, "FEIE", "车间 1 号机", null, 1,
                "A<BR>\n<CUT>\n", "飞鹅未接受: ret=-1, msg=打印机离线");
        verify(printService, never()).logSuccess(any(), any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    @DisplayName("传输/解析失败（printMsg 抛异常）也落 FAIL，原因用异常 message")
    void transportFailureIsLoggedAsFail() {
        stubOneLabelFGRoll();
        when(feieClient.printMsg(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new RuntimeException("飞鹅 API 请求异常: connect timed out"));

        R<Map<String, Object>> out = controller.feiePrint(req("FG_ROLL", null, 5L));

        assertThat(out.getData().get("failedItems"))
                .isEqualTo(List.of("第 1 张: 飞鹅 API 请求异常: connect timed out"));
        verify(printService).logFail("FG_ROLL", 5L, "PFI0001", tpl, "FEIE", "车间 1 号机", null, 1,
                "A<BR>\n<CUT>\n", "飞鹅 API 请求异常: connect timed out");
    }

    /**
     * A10-R1 的最后一道缝：`failMsg` 这一个变量同时承担「有没有失败」和「失败原因」。
     * 异常**没有 message**（裸 `new IllegalStateException()`、部分三方库的异常）时，
     * `e.getMessage()` 回 null ⇒ 原判空逻辑把「根本没发出去」当成「云端已接受」，
     * 一张纸都没出的标签就在 XK 检查的自证材料里成了 SUCCESS —— 那是假账，不是 bug 级别的问题。
     */
    @Test
    @DisplayName("异常没有 message 时仍落 FAIL（哨兵值不许被空 message 骗成成功）")
    void messagelessExceptionIsLoggedAsFail() {
        stubOneLabelFGRoll();
        when(feieClient.printMsg(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new IllegalStateException());

        R<Map<String, Object>> out = controller.feiePrint(req("FG_ROLL", null, 5L));

        assertThat(out.getData()).containsEntry("success", 0).containsEntry("failed", 1);
        verify(printService).logFail("FG_ROLL", 5L, "PFI0001", tpl, "FEIE", "车间 1 号机", null, 1,
                "A<BR>\n<CUT>\n", "java.lang.IllegalStateException");
        verify(printService, never()).logSuccess(any(), any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    @DisplayName("批量：单张失败落 FAIL 后继续发下一张，不许中断整批")
    void continuesAfterSingleLabelFailure() {
        when(printerMapper.selectOne(any())).thenReturn(cfg());
        when(templateService.defaultOf("FG_ROLL")).thenReturn(tpl);
        when(previewService.preview(any(), eq(5L), isNull())).thenReturn(List.of(rr("A"), rr("B")));
        when(contextService.loadFirst(eq("FG_ROLL"), eq(5L), isNull()))
                .thenReturn(new HashMap<>(Map.of("billNo", "PFI0001")));
        when(feieClient.printMsg(eq("acc@example.com"), eq("ukey-1"), eq("SN123"),
                eq("A<BR>\n<CUT>\n"), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(JSONUtil.parseObj("{\"ret\":-2,\"msg\":\"欠费\"}"));
        when(feieClient.printMsg(eq("acc@example.com"), eq("ukey-1"), eq("SN123"),
                eq("B<BR>\n<CUT>\n"), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(JSONUtil.parseObj("{\"ret\":0,\"msg\":\"ok\"}"));

        R<Map<String, Object>> out = controller.feiePrint(req("FG_ROLL", null, 5L));

        assertThat(out.getData()).containsEntry("total", 2).containsEntry("success", 1).containsEntry("failed", 1);
        verify(feieClient, times(2)).printMsg(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
        verify(printService).logFail("FG_ROLL", 5L, "PFI0001", tpl, "FEIE", "车间 1 号机", null, 1,
                "A<BR>\n<CUT>\n", "飞鹅未接受: ret=-2, msg=欠费");
        verify(printService).logSuccess("FG_ROLL", 5L, "PFI0001", tpl, "FEIE", "车间 1 号机", null, 1, "B<BR>\n<CUT>\n");
    }

    @Test
    @DisplayName("没有启用的飞鹅打印机 ⇒ 400 且指明去哪配（有意与 FeiePrintService.getActiveConfig 不同的一条文案）")
    void missingPrinterConfigIsActionable400() {
        when(printerMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> controller.feiePrint(req("FG_ROLL", null, 5L)))
                .isInstanceOf(BizException.class)
                .hasMessage("未配置飞鹅打印机，请到「系统设置-飞鹅打印」添加");
    }

    @Test
    @DisplayName("/feie/preview 回渲染后的指令文本（含退化说明要落日志的同一份内容）")
    void feiePreviewReturnsInstructionText() {
        when(templateService.defaultOf("FG_ROLL")).thenReturn(tpl);
        when(previewService.preview(any(), eq(5L), isNull())).thenReturn(List.of(rr("A"), rr("B")));

        R<List<String>> out = controller.feiePreview(req("FG_ROLL", null, 5L));

        assertThat(out.getData()).containsExactly("A<BR>\n<CUT>\n", "B<BR>\n<CUT>\n");
    }

    /** A10-R3：这是记账入口，不是出标签入口 —— 模板快照取不到就留空，日志照落。 */
    @Test
    @DisplayName("A10-R3：/print/log 模板解析不出来（detail 抛 404）也不拒日志，templateId 留空")
    void logPrintToleratesUnresolvableTemplate() {
        when(templateService.detail(9L)).thenThrow(BizException.of(404, "标签模板不存在或已删除: id=9"));
        LabelPrintLogRequest q = new LabelPrintLogRequest();
        q.setBizType("CARTON"); q.setBizId(9L); q.setBizNo("SO0009");
        q.setTemplateId(9L); q.setPrinterName("汉印 N31"); q.setPrinterMac("12:34:56:78:9A:BC");
        q.setContent("客户 张三<BR>\n<CUT>\n"); q.setStatus("SUCCESS");

        controller.logPrint(q);

        // copies 没报（原始 int = 0）⇒ 按 1 落，别落个 0 份
        verify(printService).logSuccess("CARTON", 9L, "SO0009", null, "BT_TSPL", "汉印 N31",
                "12:34:56:78:9A:BC", 1, "客户 张三<BR>\n<CUT>\n");
    }

    @Test
    @DisplayName("A10-R3：bizType 是 NOT NULL 列，缺了确实是真 400（不许被 A10-R2 的 warn 吃掉）")
    void logPrintRejectsMissingBizType() {
        LabelPrintLogRequest q = new LabelPrintLogRequest();
        q.setBizType("  "); q.setStatus("FAIL"); q.setErrorMsg("蓝牙连接失败");

        assertThatThrownBy(() -> controller.logPrint(q))
                .isInstanceOf(BizException.class)
                .hasMessage("缺少 bizType，无法记这笔打印日志");
        verify(printService, never()).logFail(any(), any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any());
    }

    @Test
    @DisplayName("/print/log 的 FAIL 走 logFail，份数按 App 报的落")
    void logPrintRecordsFailure() {
        when(templateService.defaultOf("RAW_PALLET")).thenReturn(tpl);
        LabelPrintLogRequest q = new LabelPrintLogRequest();
        q.setBizType("RAW_PALLET"); q.setBizId(7L); q.setBizNo("PR0007");
        q.setPrinterName("汉印 N31"); q.setPrinterMac("AA:BB:CC:DD:EE:FF");
        q.setCopies(3); q.setContent("原料<BR>\n<CUT>\n"); q.setStatus("FAIL"); q.setErrorMsg("打印机断开");

        controller.logPrint(q);

        verify(printService).logFail("RAW_PALLET", 7L, "PR0007", tpl, "BT_TSPL", "汉印 N31",
                "AA:BB:CC:DD:EE:FF", 3, "原料<BR>\n<CUT>\n", "打印机断开");
    }

    @Test
    @DisplayName("/logs 走 recent，limit 由服务层夹")
    void logsDelegatesToRecent() {
        List<BaseLabelPrintLog> rows = List.of(new BaseLabelPrintLog());
        when(printService.recent("FG_ROLL", 5L, 30)).thenReturn(rows);

        assertThat(controller.logs("FG_ROLL", 5L, 30).getData()).isSameAs(rows);
    }

    /**
     * A11：/fields 是模板编辑器字段下拉的唯一来源 —— 必须与 LabelFieldDict 同源同序，
     * 不能是第二份手抄名单（v1.1.162 编辑器教用户写 bill.details 就是名单和代码分家的结果）。
     */
    @Test
    @DisplayName("/fields: 按 LabelFieldDict 顺序逐字段出 {name,label}，label 是中文名")
    void fieldsMirrorsLabelFieldDict() {
        List<String> dict = LabelFieldDict.fields("FG_ROLL");

        List<Map<String, String>> out = controller.fields("FG_ROLL").getData();

        assertThat(out).hasSize(dict.size());
        assertThat(out).extracting(m -> m.get("name")).containsExactlyElementsOf(dict);
        assertThat(out).extracting(m -> m.get("label"))
                .containsExactlyElementsOf(dict.stream().map(LabelFieldDict::label).toList());
        // 首项中文名落地 ⇒ LABELS 表真的被查了，不是把字段名原样回显
        assertThat(out.get(0)).containsEntry("name", "productName").containsEntry("label", "产品名称");
    }

    @Test
    @DisplayName("四个新端点的路径与权限点（飞鹅/日志写口要 print 权限，读口 list OR print）")
    void endpointContracts() throws Exception {
        assertThat(BaseLabelController.class.getMethod("feiePreview", LabelPreviewRequest.class)
                .getAnnotation(PostMapping.class).value()).containsExactly("/feie/preview");
        assertThat(BaseLabelController.class.getMethod("feiePrint", LabelPreviewRequest.class)
                .getAnnotation(PostMapping.class).value()).containsExactly("/feie/print");
        assertThat(BaseLabelController.class.getMethod("logPrint", LabelPrintLogRequest.class)
                .getAnnotation(PostMapping.class).value()).containsExactly("/print/log");
        assertThat(BaseLabelController.class.getMethod("logs", String.class, Long.class, int.class)
                .getAnnotation(GetMapping.class).value()).containsExactly("/logs");

        for (String name : List.of("feiePrint", "logPrint")) {
            SaCheckPermission auth = (SaCheckPermission) BaseLabelController.class
                    .getMethod(name, name.equals("logPrint") ? LabelPrintLogRequest.class : LabelPreviewRequest.class)
                    .getAnnotation(SaCheckPermission.class);
            assertThat(auth.value()).containsExactly("base:label:print");
            assertThat(auth.mode()).isEqualTo(SaMode.OR);
            assertThat(auth.orRole()).containsExactly("admin");
        }
        SaCheckPermission logs = BaseLabelController.class
                .getMethod("logs", String.class, Long.class, int.class)
                .getAnnotation(SaCheckPermission.class);
        assertThat(logs.value()).containsExactly("base:label:list", "base:label:print");

        // 预览是读口（要给打印中心选模板用），和 /preview 同一组：print OR list 都放行
        SaCheckPermission feiePreview = BaseLabelController.class
                .getMethod("feiePreview", LabelPreviewRequest.class)
                .getAnnotation(SaCheckPermission.class);
        assertThat(feiePreview.value()).containsExactly("base:label:print", "base:label:list");
        assertThat(feiePreview.mode()).isEqualTo(SaMode.OR);
        assertThat(feiePreview.orRole()).containsExactly("admin");

        // A11：/fields 挂模板管理那组（template OR list），路径 /base/label/fields
        SaCheckPermission fields = BaseLabelController.class
                .getMethod("fields", String.class)
                .getAnnotation(SaCheckPermission.class);
        assertThat(fields.value()).containsExactly("base:label:template", "base:label:list");
        assertThat(fields.mode()).isEqualTo(SaMode.OR);
        assertThat(fields.orRole()).containsExactly("admin");
        assertThat(BaseLabelController.class.getMethod("fields", String.class)
                .getAnnotation(GetMapping.class).value()).containsExactly("/fields");
    }

    // ============================ B3: /tspl 导出标准 TSPL ============================

    /**
     * 80×60mm @203dpi = 640×480 点（docs/34 实测口径）。元素内容是**真中文**，
     * 因为这条测试要证的正是「GBK 字节流出去还能原样解回来」。
     */
    private RenderResult tsplLabel(String content, int copies) {
        ResolvedElement e = new ResolvedElement();
        e.setType("text"); e.setX(20); e.setY(40); e.setContent(content); e.setFontSize(24);
        RenderResult r = new RenderResult();
        r.setElements(List.of(e));
        r.setWidthDots(640); r.setHeightDots(480); r.setDpi(203); r.setGapMm(3);
        r.setDirection("NORMAL"); r.setCopies(copies);
        return r;
    }

    private void stubFgRoll(RenderResult... labels) {
        when(templateService.defaultOf("FG_ROLL")).thenReturn(tpl);
        when(previewService.preview(any(), eq(5L), isNull())).thenReturn(List.of(labels));
        when(contextService.loadFirst(eq("FG_ROLL"), eq(5L), isNull()))
                .thenReturn(new HashMap<>(Map.of("billNo", "PFI202611040001")));
    }

    @Test
    @DisplayName("/tspl: base64 解出来就是 GBK 原始指令字节，首行 SIZE、末行 PRINT，且与 previewText 同一份内容")
    void tsplBytesRoundTripInGbk() {
        stubFgRoll(tsplLabel("批号 B20261104", 1));

        Map<String, Object> d = controller.tspl(req("FG_ROLL", null, 5L)).getData();

        // 契约的键一个不多一个不少（前端按键取值，多一个键没人接、少一个键就是 undefined）
        assertThat(d).containsOnlyKeys("labels", "copies", "encoding", "fileName",
                "contentsBase64", "previewText", "note");
        assertThat(d).containsEntry("encoding", "GBK").containsEntry("labels", 1).containsEntry("copies", 1);

        @SuppressWarnings("unchecked")
        List<String> b64 = (List<String>) d.get("contentsBase64");
        assertThat(b64).hasSize(1);
        // 本任务交付的就是**字节**：解回来必须是指令原文，中文不是 mojibake
        byte[] raw = Base64.getDecoder().decode(b64.get(0));
        String text = new String(raw, TsplLabelRenderer.charset());
        assertThat(text).startsWith("SIZE 80 mm,60 mm\r\n").endsWith("PRINT 1,1\r\n");
        assertThat(text).contains("TEXT 20,40,\"TSS24.BF2\",0,1,1,\"批号 B20261104\"");
        assertThat(text).doesNotContain("\uFFFD");
        // 反证：同一批字节按 UTF-8 解就是乱码 ⇒ 编码出口只有 GBK 一个（B2 的 charset() 是唯一出口）
        assertThat(new String(raw, StandardCharsets.UTF_8)).doesNotContain("批号");
        // base64 里不许有换行：getMimeEncoder 每 76 字符插 \r\n，PC 侧 atob 直接抛 InvalidCharacterError
        assertThat(b64.get(0)).doesNotContain("\r").doesNotContain("\n");
        // 文本与字节是同一份内容的两种编码，不能各说一套
        assertThat(text).isEqualTo(d.get("previewText"));
        assertThat((String) d.get("note")).contains("GBK");
    }

    @Test
    @DisplayName("/tspl: fileName 带单据号与模板码（label_<单号>_<模板码>.tspl），多张拼进同一个文件")
    void tsplFileNameCarriesBizNoAndTemplateCode() {
        stubFgRoll(tsplLabel("成品卷标", 1), tsplLabel("批号 B2", 1));

        Map<String, Object> d = controller.tspl(req("FG_ROLL", null, 5L)).getData();

        assertThat(d.get("fileName")).isEqualTo("label_PFI202611040001_FG_ROLL_DEFAULT.tspl");
        @SuppressWarnings("unchecked")
        List<String> b64 = (List<String>) d.get("contentsBase64");
        assertThat(b64).hasSize(1);
        String text = new String(Base64.getDecoder().decode(b64.get(0)), TsplLabelRenderer.charset());
        // 两张标签 = 两段完整指令（SIZE 出现两次），PRINT 只以最后一段收尾
        assertThat(text.split("SIZE 80 mm,60 mm", -1)).hasSize(3);
        assertThat(text).endsWith("PRINT 1,1\r\n");
    }

    /** copies 是「总共出几张纸」= 各张份数之和；labels 是「几种标签」。两者不等时才看得出实现把两个混成了一个。 */
    @Test
    @DisplayName("/tspl: copies 是各张份数之和（2+3=5），labels 是张数（2）")
    void tsplCopiesIsTheSumNotTheLabelCount() {
        stubFgRoll(tsplLabel("成品卷标", 2), tsplLabel("批号 B2", 3));

        Map<String, Object> d = controller.tspl(req("FG_ROLL", null, 5L)).getData();

        assertThat(d).containsEntry("labels", 2).containsEntry("copies", 5);
        String text = new String(Base64.getDecoder().decode(
                (String) ((List<?>) d.get("contentsBase64")).get(0)), TsplLabelRenderer.charset());
        assertThat(text).contains("PRINT 2,1\r\n").endsWith("PRINT 3,1\r\n");
    }

    /** 用户拿这份文本去别的机器打了，账上必须看得见 ⇒ 导出也是一次打印，channel=EXPORT。 */
    @Test
    @DisplayName("/tspl: 以 channel=EXPORT 落一条 SUCCESS 日志，份数是合计、内容是指令原文")
    void tsplLogsTheExportChannel() {
        stubFgRoll(tsplLabel("成品卷标", 2), tsplLabel("批号 B2", 1));

        Map<String, Object> d = controller.tspl(req("FG_ROLL", null, 5L)).getData();

        // logSuccess 的形参位：bizType, bizId, bizNo, tpl, channel, printerName, printerMac, copies, content
        verify(printService).logSuccess("FG_ROLL", 5L, "PFI202611040001", tpl, "EXPORT", null, null, 3,
                (String) d.get("previewText"));
        verify(printService, never()).logFail(any(), any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any());
    }

    @Test
    @DisplayName("/tspl 的路径与权限点：print OR list + orRole=admin（与 /preview 同一组，导出不比预览更严）")
    void tsplEndpointContract() throws Exception {
        Method m = BaseLabelController.class.getMethod("tspl", LabelPreviewRequest.class);
        assertThat(m.getAnnotation(PostMapping.class).value()).containsExactly("/tspl");
        SaCheckPermission auth = m.getAnnotation(SaCheckPermission.class);
        assertThat(auth.value()).containsExactly("base:label:print", "base:label:list");
        assertThat(auth.mode()).isEqualTo(SaMode.OR);
        assertThat(auth.orRole()).containsExactly("admin");
    }

    /**
     * 负例：`lineIds` 传**空列表**（PC 那边「不选=全部行」就是这么发的）不许裸 NPE 成 500 ——
     * 空列表在 loader 口径里就是「全部行」，与 null 同义。同时钉住「单号取不到时 fileName 回落 bizType」：
     * bizNo 是日志上给人看的列，取不到就留空，不能因为它把整次导出拒掉。
     */
    @Test
    @DisplayName("/tspl: lineIds 为空列表时按「全部行」走，单号取不到也不 NPE（fileName 回落 bizType）")
    void emptyLineIdsIsAllLinesNotNpe() {
        LabelPreviewRequest q = req("FG_ROLL", null, 5L);
        q.setLineIds(List.of());
        when(templateService.defaultOf("FG_ROLL")).thenReturn(tpl);
        when(previewService.preview(any(), eq(5L), eq(List.of()))).thenReturn(List.of(tsplLabel("成品卷标", 1)));
        // 上下文里没有 billNo（草稿单/列没值都可能）：loadFirst 给一个空 Map
        when(contextService.loadFirst(eq("FG_ROLL"), eq(5L), eq(List.of()))).thenReturn(new HashMap<>());

        Map<String, Object> d = controller.tspl(q).getData();

        assertThat(d.get("fileName")).isEqualTo("label_FG_ROLL_FG_ROLL_DEFAULT.tspl");
        assertThat(d).containsEntry("labels", 1);
        verify(printService).logSuccess(eq("FG_ROLL"), eq(5L), isNull(), eq(tpl), eq("EXPORT"),
                isNull(), isNull(), eq(1), any());
    }

    /** 负例：还没灌 sql/155 的种子模板时，/tspl 要给一条能照着做的 400，不是 NPE/500。 */
    @Test
    @DisplayName("/tspl: 没有默认模板 ⇒ 可执行的 400，且一行日志都不记")
    void missingDefaultTemplateIsActionable400() {
        when(templateService.defaultOf("FG_ROLL")).thenReturn(null);

        assertThatThrownBy(() -> controller.tspl(req("FG_ROLL", null, 5L)))
                .isInstanceOf(BizException.class)
                .hasMessage("该标签类型还没有默认模板，请先到「标签模板」建一份");
        verify(printService, never()).logSuccess(any(), any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), any());
    }
}
