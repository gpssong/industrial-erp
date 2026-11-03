package com.industrial.erp.modules.ai.ocr;

import com.industrial.erp.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * v1.1.165 — TextIn 通用文字识别接进 OCR 链后的两条核心口径:
 * ① 响应解析 (含 TextIn 「HTTP 200 + body code != 200」 的错误约定);
 * ② 一单多原料时靠坐标框把候选锚到目标原料那张标签 (视觉模型那条路径靠提示词, TextIn 没有).
 *
 * <p>RESPONSE 是 2026-11-03 用真实 TextIn 账号跑一张「两张原料标签并排」的合成图得到的原文
 * (左列 赛科0220kj / 右列 扬巴LDPE2426H), 只保留了 lines 结构, 文字与坐标未改 ——
 * 所以这些断言是对真实服务商响应形状的描述, 不是我凭空设想的.
 */
@DisplayName("OcrService: TextIn 解析 + 按坐标锚定批号候选")
class OcrServiceTextinTest {

    /** 真实响应 (图 1200x900): 左列赛科, 右列扬巴, 各带一行「批号：」. */
    private static final String RESPONSE = "{\"code\":200,\"message\":\"success\",\"result\":{\"width\":1200,\"height\":900,\"lines\":["
            + line("食品包装用聚乙烯树脂", 43, 50, 386, 50, 386, 87, 43, 87) + ","
            + line("品名：LLDPE 赛科0220kj", 44, 107, 429, 111, 428, 151, 44, 147) + ","
            + line("批号：L20261028A3", 43, 170, 347, 170, 347, 207, 43, 207) + ","
            + line("生产日期：2026-10-28", 43, 230, 375, 230, 375, 267, 43, 267) + ","
            + line("净重：25000 kg", 42, 289, 273, 291, 272, 332, 41, 329) + ","
            + line("上海赛科石油化工", 44, 350, 318, 350, 318, 388, 44, 388) + ","
            + line("低密度聚乙烯", 641, 509, 850, 507, 850, 547, 642, 548) + ","
            + line("品名：LDPE扬巴2426H", 643, 570, 1010, 570, 1010, 607, 643, 607) + ","
            + line("批号：B2426H-261017", 643, 630, 982, 630, 982, 667, 643, 667) + ","
            + line("生产日期：2026-10-17", 643, 688, 974, 688, 974, 727, 643, 727) + ","
            + line("净重：25000kg", 642, 748, 873, 753, 872, 792, 641, 788) + ","
            + line("扬子-BASF 石化", 642, 808, 893, 810, 892, 849, 641, 847)
            + "]}}";

    private static String line(String text, int... position) {
        StringBuilder sb = new StringBuilder("{\"text\":\"").append(text).append("\",\"score\":0.99,\"type\":\"text\",\"position\":[");
        for (int i = 0; i < position.length; i++) {
            sb.append(i > 0 ? "," : "").append(position[i]);
        }
        return sb.append("]}").toString();
    }

    // ===== ① 响应解析 =====

    @Test
    @DisplayName("真实响应 → 12 行文字 + 每行坐标 (w/h 必须 > 0, 否则锚定退化成整图)")
    void parseRealResponse() {
        List<OcrService.TextLine> lines = OcrService.parseTextinLines(RESPONSE);
        assertThat(lines).hasSize(12);
        assertThat(lines.get(1).text).isEqualTo("品名：LLDPE 赛科0220kj");
        assertThat(lines.get(1).w).isGreaterThan(300);
        assertThat(lines.get(1).h).isBetween(40D, 50D);
    }

    @Test
    @DisplayName("缺 position 的行照样收 (退化成无坐标行), 空 text 的行丢掉")
    void tolerateMissingPositionAndBlankText() {
        String json = "{\"code\":200,\"result\":{\"lines\":["
                + "{\"text\":\"批号：X1\",\"type\":\"text\"},"
                + "{\"text\":\"   \",\"position\":[1,1,2,1,2,2,1,2]}"
                + "]}}";
        List<OcrService.TextLine> lines = OcrService.parseTextinLines(json);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).h).isZero();
    }

    @Test
    @DisplayName("TextIn 出错也回 HTTP 200, 靠 body code 判: 40306 要翻成「稍等再点一次」")
    void businessErrorThrowsWithHint() {
        assertThatThrownBy(() -> OcrService.parseTextinLines("{\"code\":40306,\"message\":\"qps limit\"}"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("40306")
                .hasMessageContaining("并发");
        assertThatThrownBy(() -> OcrService.parseTextinLines("<html>502</html>"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无法解析");
    }

    // ===== ② 候选口径 =====

    @Test
    @DisplayName("带内部连字符的批号不再被切成两半 (老正则 [A-Za-z0-9]{4,30} 会切)")
    void hyphenatedBatchNoStaysWhole() {
        List<OcrService.TextLine> lines = OcrService.parseTextinLines(RESPONSE);
        Set<String> all = OcrService.photoCandidates(List.of(lines), null);
        assertThat(all).contains("B2426H-261017");
        assertThat(all).doesNotContain("261017");
    }

    @Test
    @DisplayName("不设品名: 两个批号都是候选, 「批号」行优先, 日期/净重的数字绝不混进来")
    void noHintGivesBothBatchNos() {
        Set<String> all = OcrService.photoCandidates(
                List.of(OcrService.parseTextinLines(RESPONSE)), null);
        assertThat(all).containsExactly("L20261028A3", "B2426H-261017");
    }

    @Test
    @DisplayName("定向赛科: 只给左列那张标签的批号, 右列扬巴的批号不许抢位")
    void anchorTargetsSaike() {
        Set<String> r = OcrService.photoCandidates(
                List.of(OcrService.parseTextinLines(RESPONSE)), "赛科0220kj");
        assertThat(r).containsExactly("L20261028A3");
    }

    @Test
    @DisplayName("定向扬巴: 品名带空格/简称也能锚到右列, 拿到 261017 那个批号")
    void anchorTargetsYangba() {
        Set<String> r = OcrService.photoCandidates(
                List.of(OcrService.parseTextinLines(RESPONSE)), "扬巴 ldpe2426h");
        assertThat(r).containsExactly("B2426H-261017");
    }

    @Test
    @DisplayName("品名在这张标签上根本没有 → 退到整图候选, 两个批号都摆出来让人挑")
    void unknownProductFallsBackToWholePhoto() {
        List<OcrService.TextLine> lines = OcrService.parseTextinLines(RESPONSE);
        assertThat(OcrService.photoCandidates(List.of(lines), "中石化 M"))
                .containsExactly("L20261028A3", "B2426H-261017");
    }

    @Test
    @DisplayName("视觉大模型那条路径 (纯文本无坐标) 行为不变: 不按空间过滤, 仍排除噪声行")
    void textOnlyLinesSkipSpatialFilter() {
        List<OcrService.TextLine> lines = OcrService.parseTextinLines(
                "{\"code\":200,\"result\":{\"lines\":[{\"text\":\"批号：A1234567\"},{\"text\":\"生产日期：2026-10-28\"}]}}");
        assertThat(lines).hasSize(2);
        assertThat(OcrService.photoCandidates(List.of(lines), "随便什么品名")).containsExactly("A1234567");
    }

    @Test
    @DisplayName("行判定: 「批 号」这种排版也算批号行; 净重/保质期行是噪声行")
    void lineClassification() {
        assertThat(OcrService.isBatchLabelLine("批 号 : L2026")).isTrue();
        assertThat(OcrService.isBatchLabelLine("LOT L2026")).isTrue();
        assertThat(OcrService.isBatchLabelLine("品名：LLDPE")).isFalse();
        assertThat(OcrService.isNoiseLine("净重：25000 kg")).isTrue();
        assertThat(OcrService.isNoiseLine("保质期：24 个月")).isTrue();
        assertThat(OcrService.isNoiseLine("批号：L20261028A3")).isFalse();
    }
}
