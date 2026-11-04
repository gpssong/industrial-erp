package com.industrial.erp.modules.base.label;

import com.industrial.erp.modules.base.entity.BaseLabelPrintLog;
import com.industrial.erp.modules.base.entity.BaseLabelTemplate;
import com.industrial.erp.modules.base.mapper.BaseLabelPrintLogMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LabelPrintServiceTest {

    @Mock private BaseLabelPrintLogMapper logMapper;

    private BaseLabelTemplate tpl() {
        BaseLabelTemplate t = new BaseLabelTemplate();
        t.setId(1167000000000000001L);
        t.setTemplateCode("FG_ROLL_DEFAULT");
        t.setBizType("FG_ROLL");
        return t;
    }

    /** ① 成功落一行：status=SUCCESS，contentHash 是 sha256(content) 的 hex（列宽就是 64）。 */
    @Test
    @DisplayName("logSuccess 落 SUCCESS + sha256 hex 内容指纹（长度 64）")
    void logSuccessWritesSuccessRowWithSha256() {
        String content = "品名 PE 平口袋<BR>\n<CUT>\n";

        new LabelPrintService(logMapper).logSuccess("FG_ROLL", 5L, "PFI0001", tpl(),
                "FEIE", "车间 1 号机", null, 1, content);

        ArgumentCaptor<BaseLabelPrintLog> cap = ArgumentCaptor.forClass(BaseLabelPrintLog.class);
        verify(logMapper).insert(cap.capture());
        BaseLabelPrintLog row = cap.getValue();
        assertThat(row.getStatus()).isEqualTo("SUCCESS");
        assertThat(row.getContentHash()).isEqualTo(sha256Hex(content));
        assertThat(row.getContentHash()).hasSize(64);
        assertThat(row.getBizType()).isEqualTo("FG_ROLL");
        assertThat(row.getBizId()).isEqualTo(5L);
        assertThat(row.getBizNo()).isEqualTo("PFI0001");
        assertThat(row.getChannel()).isEqualTo("FEIE");
        assertThat(row.getPrinterName()).isEqualTo("车间 1 号机");
        assertThat(row.getCopies()).isEqualTo(1);
        assertThat(row.getTemplateId()).isEqualTo(1167000000000000001L);
        assertThat(row.getTemplateCode()).isEqualTo("FG_ROLL_DEFAULT");
        assertThat(row.getErrorMsg()).isNull();
        // 审计列由 MetaObjectHandler 回填，服务不许自己 set
        assertThat(row.getCreateBy()).isNull();
        assertThat(row.getCreateTime()).isNull();
    }

    /** ② error_msg 列是 VARCHAR(512)：超长的失败原因截到 500 + "…"，不能因为超长落库失败。 */
    @Test
    @DisplayName("logFail 把超长 errorMsg 截到 500 + …（列宽 512，不许因超长落不进去）")
    void logFailTruncatesLongErrorMsg() {
        String longMsg = "飞鹅 API 请求异常: ".repeat(60);   // 远超 512
        assertThat(longMsg.length()).isGreaterThan(512);

        new LabelPrintService(logMapper).logFail("CARTON", 9L, "SO0009", tpl(),
                "BT_TSPL", "便携打印机", "AA:BB:CC:DD:EE:FF", 2, "<CUT>\n", longMsg);

        ArgumentCaptor<BaseLabelPrintLog> cap = ArgumentCaptor.forClass(BaseLabelPrintLog.class);
        verify(logMapper).insert(cap.capture());
        BaseLabelPrintLog row = cap.getValue();
        assertThat(row.getStatus()).isEqualTo("FAIL");
        assertThat(row.getErrorMsg()).hasSize(501);
        assertThat(row.getErrorMsg()).isEqualTo(longMsg.substring(0, 500) + "…");
        assertThat(row.getErrorMsg()).doesNotContain("飞鹅 API 请求异常: ".repeat(60));
    }

    /** ③ content / printerMac 原样入库（日志是 XK 检查的自证材料，快照不许被加工）。 */
    @Test
    @DisplayName("content 与 printerMac 原样入库，模板为空时两列快照留空")
    void storesContentAndMacVerbatim() {
        String content = "原料 PE 保护膜<BR>\n(条码 B20261104)<BR>\n<CUT>\n";

        new LabelPrintService(logMapper).logSuccess("RAW_PALLET", 7L, "PR0007", null,
                "BT_TSPL", "汉印 N31", "12:34:56:78:9A:BC", 3, content);

        ArgumentCaptor<BaseLabelPrintLog> cap = ArgumentCaptor.forClass(BaseLabelPrintLog.class);
        verify(logMapper).insert(cap.capture());
        BaseLabelPrintLog row = cap.getValue();
        assertThat(row.getContent()).isEqualTo(content);
        assertThat(row.getPrinterMac()).isEqualTo("12:34:56:78:9A:BC");
        assertThat(row.getCopies()).isEqualTo(3);
        // tpl == null 时不写模板快照，但日志必须照落
        assertThat(row.getTemplateId()).isNull();
        assertThat(row.getTemplateCode()).isNull();
    }

    /**
     * ④ A10-R2：落库失败不许把打印流程带崩（一次打 20 张，第 3 张日志写不进去不能连累后 17 张）。
     * 被验的是生产代码里的 catch —— mock 只是把「数据库拒收」这个真实条件造出来。
     */
    @Test
    @DisplayName("A10-R2：insert 抛异常时 logSuccess/logFail 都不上抛（落日志失败不能带崩打印）")
    void insertFailureIsSwallowed() {
        when(logMapper.insert(any(BaseLabelPrintLog.class))).thenThrow(new RuntimeException("Duplicate entry for key 'PRIMARY'"));
        LabelPrintService svc = new LabelPrintService(logMapper);

        assertThatCode(() -> svc.logSuccess("FG_ROLL", 1L, "PFI0001", tpl(),
                "FEIE", "车间 1 号机", null, 1, "A<BR>\n<CUT>\n")).doesNotThrowAnyException();
        assertThatCode(() -> svc.logFail("FG_ROLL", 1L, "PFI0001", tpl(),
                "FEIE", "车间 1 号机", null, 1, "A<BR>\n<CUT>\n", "打印机离线")).doesNotThrowAnyException();
    }

    /**
     * limit 是调用方传来的（PC 那个「最近 N 条」下拉），不夹取就等于允许一次全表扫。
     * MP 3.5.9 的 wrapper 不暴露 `getLast()`，把夹取本身抽成包级静态方法直接验
     * （与 A9 把 ASCII 预检抽成 `LabelPreviewService.asciiOnly` 同一手法）。
     */
    @Test
    @DisplayName("limit 夹在 1..100：0/负数按 1，5000 按 100")
    void limitIsClamped() {
        assertThat(LabelPrintService.clampLimit(0)).isEqualTo(1);
        assertThat(LabelPrintService.clampLimit(-5)).isEqualTo(1);
        assertThat(LabelPrintService.clampLimit(30)).isEqualTo(30);
        assertThat(LabelPrintService.clampLimit(100)).isEqualTo(100);
        assertThat(LabelPrintService.clampLimit(5000)).isEqualTo(100);
    }

    @Test
    @DisplayName("recent 把查询交给 mapper 并把结果原样回传")
    void recentDelegatesToMapper() {
        List<BaseLabelPrintLog> rows = List.of(new BaseLabelPrintLog());
        when(logMapper.selectList(any())).thenReturn(rows);
        LabelPrintService svc = new LabelPrintService(logMapper);

        assertThat(svc.recent("FG_ROLL", 1L, 30)).isSameAs(rows);
        assertThat(svc.recent(null, null, 0)).isSameAs(rows);
        assertThat(svc.recent("  ", 1L, 5000)).isSameAs(rows);
    }

    /**
     * `/print/log` 的三个字符串是**手机上报的**（系统边界），列宽只有 64/128/32（sql/155:43,47-48）。
     * 超宽不截 ⇒ insert 被数据库拒收 ⇒ 正好落进 A10-R2 那个 catch ⇒ App 收到 R.ok、库里没这行，
     * 只剩一条服务端 warn。这张表存在的意义就是「一行都不丢」，所以在写入口按列宽截齐。
     */
    @Test
    @DisplayName("App 上报的 bizNo/printerName/printerMac 按 DDL 列宽截断（超宽不能把整行丢给 catch）")
    void wideAppSuppliedStringsAreCutToColumnWidth() {
        new LabelPrintService(logMapper).logSuccess("CARTON", 9L, "S".repeat(200), tpl(),
                "BT_TSPL", "P".repeat(300), "M".repeat(100), 1, "<CUT>\n");

        ArgumentCaptor<BaseLabelPrintLog> cap = ArgumentCaptor.forClass(BaseLabelPrintLog.class);
        verify(logMapper).insert(cap.capture());
        BaseLabelPrintLog row = cap.getValue();
        assertThat(row.getBizNo()).hasSize(64);
        assertThat(row.getPrinterName()).hasSize(128);
        assertThat(row.getPrinterMac()).hasSize(32);
    }

    private static String sha256Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
