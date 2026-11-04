package com.industrial.erp.modules.base.label;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FeieLabelRendererTest {

    private ResolvedElement el(String type, int x, int y, String content, boolean bold) {
        ResolvedElement e = new ResolvedElement();
        e.setType(type); e.setX(x); e.setY(y); e.setContent(content); e.setFontSize(24); e.setBold(bold);
        return e;
    }

    @Test
    @DisplayName("按 y 分行、行内按 x 排序 —— 飞鹅小票机是流式点阵，绝对定位到不了，只能退化成行")
    void groupsIntoRowsByY() {
        RenderResult r = new RenderResult();
        r.setCopies(1);
        r.setElements(List.of(el("text", 110, 20, "PE 平口袋", false),
                el("text", 20, 20, "品名", false),
                el("text", 20, 60, "批号 B20261104", false)));

        String out = new FeieLabelRenderer().render(r);

        // 同一行（y 差 < 20 点）内按 x 排序拼一行；每行结尾 <BR>，整段结尾 <CUT>
        assertThat(out).isEqualTo("品名 PE 平口袋<BR>\n批号 B20261104<BR>\n<CUT>\n");
    }

    @Test
    @DisplayName("二维码走 <QR>，行首整行加粗用 <B>，末尾必须 <CUT>")
    void carriesQrBoldAndCut() {
        RenderResult r = new RenderResult();
        r.setElements(List.of(el("text", 20, 20, "成品入库单", true), el("qrcode", 20, 60, "PFI0001", false)));

        String out = new FeieLabelRenderer().render(r);

        assertThat(out).contains("<B>成品入库单</B><BR>");
        assertThat(out).contains("<QR>PFI0001</QR><BR>");
        assertThat(out).endsWith("<CUT>\n");
    }

    @Test
    @DisplayName("条码 (barcode) 在飞鹅通道退化成明文 + 说明行：飞鹅云小票的 1D 指令未验证，不假装支持")
    void barcodeDegradesToText() {
        RenderResult r = new RenderResult();
        r.setElements(List.of(el("barcode", 20, 320, "PFI0001", false)));
        assertThat(new FeieLabelRenderer().render(r)).contains("(条码 PFI0001)");
    }

    @Test
    @DisplayName("份数 > 1 时整段重复 copies 遍")
    void repeatsForCopies() {
        RenderResult r = new RenderResult();
        r.setCopies(3);
        r.setElements(List.of(el("text", 0, 0, "A", false)));
        assertThat(new FeieLabelRenderer().render(r).split("<CUT>", -1)).hasSize(4);
    }
}
