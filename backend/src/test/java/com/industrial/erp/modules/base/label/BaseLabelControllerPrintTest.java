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

    private BaseLabelController controller;
    private BaseLabelTemplate tpl;

    @BeforeEach
    void setUp() {
        controller = new BaseLabelController(masterService, templateService, previewService, contextService,
                printService, new FeieLabelRenderer(), feieClient,
                null, null, null, null, null, printerMapper);
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
    }
}
