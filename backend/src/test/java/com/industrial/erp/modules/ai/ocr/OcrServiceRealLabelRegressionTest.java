package com.industrial.erp.modules.ai.ocr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.1.165 — 2026-11-03 在飞牛 canary 用**当天真实入库单 RKP202611030002 的两张标签照片**跑 TextIn
 * 得到的逐行文字, 锁住三个当时实测到的错误:
 * <ol>
 *   <li>品名「赛科0220kj」和标签上的牌号 {@code LL0220KJ} 之间没有分隔符, 只按空格/标点断词
 *       → 永远锚不到, 定向识别形同没做;</li>
 *   <li>锚到以后 {@code candidates[0]} 是**牌号**而不是批号 (两者挤在同一行), 前端直接填进批号框;</li>
 *   <li>「扬巴<b>ldpe</b>2426h」里的 ldpe 会锚到<b>赛科</b>那张标签的 LLDPE 行
 *       → 把赛科的批号当成扬巴的答案 (一单多原料最贵的一类串号).</li>
 * </ol>
 *
 * <p>文字与识别顺序照抄 canary 那次调用的 {@code rawText}; 坐标是我按「同一张标签逐行往下排」补的
 * (那次探针只留了文字没留 position 数组). 断言只依赖同一张标签内部的相对距离, 不依赖具体数值.
 */
@DisplayName("OcrService: canary 真实两张标签 (赛科/扬巴) 定向识别")
class OcrServiceRealLabelRegressionTest {

    private static final String BAYER_LINES = "{\"code\":200,\"result\":{\"lines\":["
            + line("A-M/HRR", 120, 60, 520, 60, 520, 130, 120, 130) + ","
            + line("H9Pb2", 130, 150, 400, 150, 400, 220, 130, 220) + ","
            + line("R69／夏", 130, 240, 380, 240, 380, 310, 130, 310) + ","
            + line("BASF", 130, 330, 460, 330, 460, 400, 130, 400)
            + "]}}";

    private static final String SECCO_LINES = "{\"code\":200,\"result\":{\"lines\":["
            + line("LLDPE", 150, 70, 460, 70, 460, 140, 150, 140) + ","
            + line("LL0220KJ P SK216923C1402249", 150, 160, 1500, 160, 1500, 230, 150, 230) + ","
            + line("上海赛科石油化工有限责任公司", 150, 250, 1200, 250, 1200, 320, 150, 320) + ","
            + line("Shanghai SECCO Petrochemical Co.Ltd.", 150, 340, 1300, 340, 1300, 410, 150, 410)
            + "]}}";

    /**
     * canary 真实那张扬巴照片的行距 (逐行间距远大于 4 倍行高): 品名 {@code BASF} 锚到了,
     * 但 {@code H9Pb2} 落在标签块半径之外 → 第一趟空手. 旧实现在这里一路退到「所有图的候选」,
     * 把赛科的 LL0220KJ / SK216923C1402249 也塞进了扬巴那一行.
     */
    private static final String FAR_APART_BAYER_LINES = "{\"code\":200,\"result\":{\"lines\":["
            + line("A-M/HRR", 120, 60, 520, 60, 520, 130, 120, 130) + ","
            + line("H9Pb2", 130, 150, 400, 150, 400, 220, 130, 220) + ","
            + line("R69／夏", 130, 600, 380, 600, 380, 670, 130, 670) + ","
            + line("BASF", 130, 900, 460, 900, 460, 970, 130, 970)
            + "]}}";

    private static String line(String text, int... position) {
        StringBuilder sb = new StringBuilder("{\"text\":\"").append(text)
                .append("\",\"score\":0.98,\"type\":\"text\",\"position\":[");
        for (int i = 0; i < position.length; i++) {
            sb.append(i > 0 ? "," : "").append(position[i]);
        }
        return sb.append("]}").toString();
    }

    private static List<OcrService.TextLine> basf() {
        return OcrService.parseTextinLines(BAYER_LINES);
    }

    private static List<OcrService.TextLine> basfFarApart() {
        return OcrService.parseTextinLines(FAR_APART_BAYER_LINES);
    }

    private static List<OcrService.TextLine> secco() {
        return OcrService.parseTextinLines(SECCO_LINES);
    }

    /**
     * 2026-11-12 真机「框选识别区域」后送进去的那张小图: 裁出来只剩牌号+批号这一行,
     * 所以既没有品名行可锚、也没有「批号」字样 —— 定向识别和字段名两条降级线索同时失效.
     */
    private static List<OcrService.TextLine> seccoCropped() {
        return OcrService.parseTextinLines("{\"code\":200,\"result\":{\"lines\":["
                + line("LL0220KJ P SK216A08C1102431", 40, 60, 1160, 60, 1160, 175, 40, 175)
                + "]}}");
    }

    @Test
    @DisplayName("赛科那一行: 首选是真批号 SK216923C1402249, 不是同一行里的牌号 LL0220KJ")
    void seccoRowPrefersBatchOverGrade() {
        Set<String> r = OcrService.photoCandidates(List.of(basf(), secco()), "赛科0220kj");
        assertThat(r).isNotEmpty();
        assertThat(r.iterator().next()).isEqualTo("SK216923C1402249");
    }

    @Test
    @DisplayName("赛科那一行: 另一张 (扬巴) 标签上的噪声不许混进来")
    void seccoRowIgnoresOtherLabel() {
        assertThat(OcrService.photoCandidates(List.of(basf(), secco()), "赛科0220kj"))
                .doesNotContain("H9Pb2");
    }

    @Test
    @DisplayName("通用树脂名 ldpe 不能当锚点: 扬巴那行绝不返回赛科的批号")
    void yangbaRowNeverStealsSeccoBatchNo() {
        Set<String> r = OcrService.photoCandidates(List.of(basf(), secco()), "扬巴ldpe2426h");
        assertThat(r).doesNotContain("SK216923C1402249", "LL0220KJ");
        // 这张照片只拍到标签一角, 认到的内容里确实没有批号行 —— 只能给出同张标签上最像的串, 且不越界到别张标签
        assertThat(r).containsExactly("H9Pb2");
    }

    @Test
    @DisplayName("粘着中文前缀的牌号 (0220kj / 2426h) 要能单独当锚点键")
    void anchorKeysSplitAtCjkAndLetterDigitBoundary() {
        // 断不了词 → keys 只有「赛科0220kj」整串 → 锚不到 LL0220KJ, 定向识别形同没做
        assertThat(OcrService.photoCandidates(List.of(secco()), "赛科0220kj"))
                .first().isEqualTo("SK216923C1402249");
        // 「扬巴」靠中文简称→basf 别名、2426h 靠字母/数字断词才锚得到
        assertThat(OcrService.photoCandidates(List.of(basf()), "扬巴ldpe2426h"))
                .containsExactly("H9Pb2");
    }

    @Test
    @DisplayName("不传品名 = v1.1.158 老行为: 整图候选, 两张标签的都给")
    void noHintKeepsWholeImageBehaviour() {
        Set<String> r = OcrService.photoCandidates(List.of(basf(), secco()), "");
        assertThat(r).contains("SK216923C1402249", "LL0220KJ", "H9Pb2");
    }

    @Test
    @DisplayName("锚到标签但批号行在半径外 (canary 真实行距): 退到**这张图**, 不许借隔壁标签的批号")
    void farApartLabelFallsBackToOwnPhotoOnly() {
        // 第一趟 (标签块半径内) 在这张图上取不到批号, 旧实现直接跳到「所有图摊开」→ 三个候选混在一起
        assertThat(OcrService.photoCandidates(List.of(basfFarApart(), secco()), "扬巴ldpe2426h"))
                .containsExactly("H9Pb2");
    }

    @Test
    @DisplayName("品名只是树脂名 (lldpe) 时, 同一行的牌号 LL0220KJ 也不许顶掉批号")
    void resinOnlyHintStillPrefersBatchOverGrade() {
        // 2026-11-12 真机框选实测: 厂里这一行料在采购单上就叫「lldpe」, 断词后没有可用锚点键,
        // v1.1.165 那条「含原料名片段 → 降级」的口径整个失效, candidates[0] 又变回牌号。
        assertThat(OcrService.photoCandidates(List.of(seccoCropped()), "lldpe"))
                .first().isEqualTo("SK216A08C1102431");
    }

    @Test
    @DisplayName("写了「批号」的行按字段顺序认, 不把行内第一个候选当牌号降级")
    void labelledLineKeepsFieldOrder() {
        List<OcrService.TextLine> lines = OcrService.parseTextinLines(
                "{\"code\":200,\"result\":{\"lines\":["
                        + line("批号：SK216A08C1 102431", 100, 100, 900, 100, 900, 170, 100, 170)
                        + "]}}");
        assertThat(OcrService.photoCandidates(List.of(lines), "lldpe"))
                .first().isEqualTo("SK216A08C1");
    }
}
