package com.industrial.erp.modules.base.label;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TsplLabelRendererTest {

    private final TsplLabelRenderer renderer = new TsplLabelRenderer();

    private RenderResult canvas() {
        RenderResult r = new RenderResult();
        r.setWidthDots(640); r.setHeightDots(480); r.setDpi(203);
        r.setGapMm(3); r.setDirection("REVERSE"); r.setCopies(1);
        r.setElements(List.of());
        return r;
    }

    private ResolvedElement el(String type, int x, int y, String content, int size) {
        ResolvedElement e = new ResolvedElement();
        e.setType(type); e.setX(x); e.setY(y); e.setContent(content); e.setFontSize(size);
        return e;
    }

    private RenderResult of(ResolvedElement... es) {
        RenderResult r = canvas();
        r.setElements(List.of(es));
        return r;
    }

    @Test
    @DisplayName("头部：SIZE/GAP 用毫米（80×60），DIRECTION 与 CODEPAGE 各一行，CLS 清缓冲")
    void writesHeaderInMm() {
        RenderResult r = canvas();
        r.setCopies(2);
        String out = renderer.renderCommandText(r);
        assertThat(out).startsWith("SIZE 80 mm,60 mm\r\nGAP 3 mm,0 mm\r\nDIRECTION 1\r\nCODEPAGE 936\r\nCLS\r\n");
        assertThat(out).endsWith("PRINT 2,1\r\n");
    }

    @Test
    @DisplayName("DIRECTION 由模板决定：REVERSE→1（实测转 180°，三次复现）、NORMAL→0（坐标与肉眼一致）")
    void directionFollowsTemplate() {
        RenderResult reverse = canvas();
        reverse.setDirection("REVERSE");
        assertThat(renderer.renderCommandText(reverse)).contains("DIRECTION 1\r\n");

        RenderResult normal = canvas();
        normal.setDirection("NORMAL");
        assertThat(renderer.renderCommandText(normal)).contains("DIRECTION 0\r\n");

        RenderResult blank = canvas();
        blank.setDirection(null);
        assertThat(renderer.renderCommandText(blank)).contains("DIRECTION 0\r\n");
    }

    @Test
    @DisplayName("ASCII 走窄字体，横向按字号放大：24 点 → x 倍 2、y 倍 1")
    void asciiTextUsesNarrowFont() {
        assertThat(renderer.renderCommandText(of(el("text", 20, 40, "PFI202611040001", 24))))
                .contains("TEXT 20,40,\"2\",0,2,1,\"PFI202611040001\"\r\n");
    }

    @Test
    @DisplayName("含中文走内置中文字库，倍率按 24 点基准算")
    void chineseTextUsesBuiltinCjkFont() {
        assertThat(renderer.renderCommandText(of(el("text", 20, 40, "批号 B20261104", 24))))
                .contains("TEXT 20,40,\"TSS24.BF2\",0,1,1,\"批号 B20261104\"\r\n");
        assertThat(renderer.renderCommandText(of(el("text", 20, 40, "成品卷标", 48))))
                .contains("TEXT 20,40,\"TSS24.BF2\",0,2,2,\"成品卷标\"\r\n");
    }

    @Test
    @DisplayName("TSPL 没有 bold：同一行右移 1 点再打一遍当加粗")
    void boldIsOffsetOverprint() {
        ResolvedElement e = el("text", 20, 40, "合格", 24);
        e.setBold(true);
        String out = renderer.renderCommandText(of(e));
        assertThat(out).contains("TEXT 20,40,\"TSS24.BF2\",0,1,1,\"合格\"\r\n");
        assertThat(out).contains("TEXT 21,40,\"TSS24.BF2\",0,1,1,\"合格\"\r\n");
    }

    @Test
    @DisplayName("二维码：cell 按字号 ÷6 夹在 2..8（形参顺序以 docs/34 ④ 打成功那条为准）")
    void qrCommand() {
        assertThat(renderer.renderCommandText(of(el("qrcode", 20, 60, "PFI0001", 24))))
                .contains("QRCODE 20,60,L,4,0,A,\"PFI0001\"\r\n");
    }

    @Test
    @DisplayName("条码：CODE128，高度取元素 h（没给就 10 点），人眼可读=2")
    void barcodeCommand() {
        ResolvedElement e = el("barcode", 20, 320, "PFI0001", 24);
        e.setH(60);
        assertThat(renderer.renderCommandText(of(e)))
                .contains("BARCODE 20,320,\"128\",60,2,0,2,2,\"PFI0001\"\r\n");
        ResolvedElement e2 = el("barcode", 20, 320, "PFI0001", 24);
        assertThat(renderer.renderCommandText(of(e2))).contains("\"128\",10,");
    }

    @Test
    @DisplayName("line = 一条实心 BAR；rect = 四条 BAR 描边（TSPL 没有画矩形指令）")
    void drawsLineAndRect() {
        ResolvedElement line = el("line", 20, 240, null, 24);
        line.setW(600);
        assertThat(renderer.renderCommandText(of(line))).contains("BAR 20,240,600,2\r\n");

        ResolvedElement rect = el("rect", 300, 40, null, 24);
        rect.setW(100); rect.setH(40);
        String out = renderer.renderCommandText(of(rect));
        assertThat(out).contains("BAR 300,40,100,2\r\n");
        assertThat(out).contains("BAR 300,78,100,2\r\n");
        assertThat(out).contains("BAR 300,40,2,40\r\n");
        assertThat(out).contains("BAR 398,40,2,40\r\n");
    }

    @Test
    @DisplayName("内容里的双引号换成单引号 —— 不转义会把指令的字符串参数截断")
    void sanitizesQuotesInContent() {
        assertThat(renderer.renderCommandText(of(el("text", 20, 40, "他说\"你好\"", 24))))
                .contains("TEXT 20,40,\"TSS24.BF2\",0,1,1,\"他说'你好'\"\r\n");
    }

    @Test
    @DisplayName("空内容不出 TEXT 行；required 缺值照样出纸（§5：不能因为一个代号空就停下不打）")
    void blankContentDoesNotBlockPrint() {
        RenderResult r = canvas();
        r.setElements(List.of(el("text", 20, 40, "", 24)));
        assertThat(renderer.renderCommandText(r)).doesNotContain("TEXT ");

        ResolvedElement miss = el("text", 20, 40, "", 24);
        miss.setMissing(true); miss.setField("inspector_code");
        RenderResult r2 = canvas();
        r2.setElements(List.of(miss, el("text", 20, 100, "批号 B1", 24)));
        String out = renderer.renderCommandText(r2);
        assertThat(out).doesNotContain("TEXT 20,40");
        assertThat(out).contains("TEXT 20,100").endsWith("PRINT 1,1\r\n");
    }

    @Test
    @DisplayName("renderBytes 就是按取证编码的真字节：中文一条指令的字节数 > 字符数，且能原样解回")
    void encodesBytesWithProbedCharset() {
        RenderResult r = of(el("text", 20, 40, "舟山市鹏程包装有限公司", 24));
        String text = renderer.renderCommandText(r);
        byte[] bytes = renderer.renderBytes(r);
        Charset cs = TsplLabelRenderer.charset();
        assertThat(new String(bytes, cs)).isEqualTo(text);
        assertThat(bytes.length).isGreaterThan(text.length());
    }

    // ---- 超宽拆行（docs/34 硬约束 2：固件对超出纸宽的尾巴**静默丢弃**，只有渲染器能兜）----

    @Test
    @DisplayName("超宽 ASCII 行按 640 点上限拆两行：首行正好是 ② 那张纸印出来的 25 个字符")
    void splitsOverwideAsciiLineInsteadOfLosingTheTail() {
        // 探针原文 36 字符；字体"2"@24 点 ×2 放大 = 24 点/字符，起点 40 ⇒ 一行最多 (640−40)/24 = 25 字符。
        String probe = "IP803 PROBE ASCII 0123456789 abcdefg";
        String out = renderer.renderCommandText(of(el("text", 40, 40, probe, 24)));
        assertThat(out).contains("TEXT 40,40,\"2\",0,2,1,\"IP803 PROBE ASCII 0123456\"\r\n");
        assertThat(out).contains("TEXT 40,60,\"2\",0,2,1,\"789 abcdefg\"\r\n");
    }

    @Test
    @DisplayName("正好放得下（40+25×24=640）不拆行 —— 拆早了同一模板会莫名多出一行")
    void doesNotSplitLineThatExactlyFits() {
        String fits = "IP803 PROBE ASCII 0123456";
        assertThat(fits).hasSize(25);
        String out = renderer.renderCommandText(of(el("text", 40, 40, fits, 24)));
        assertThat(out).contains("TEXT 40,40,\"2\",0,2,1,\"IP803 PROBE ASCII 0123456\"\r\n");
        assertThat(out).doesNotContain("TEXT 40,60");
    }

    @Test
    @DisplayName("中文按 TSS24.BF2 全额 24 点/字量宽：48 点字号 = 48 点/字 ⇒ 一行 12 字，第二行下移一个行高")
    void splitsOverwideCjkLine() {
        String name = "舟山市鹏程包装有限公司舟山市鹏程包装有限公司";
        String out = renderer.renderCommandText(of(el("text", 20, 40, name, 48)));
        assertThat(out).contains("TEXT 20,40,\"TSS24.BF2\",0,2,2,\"" + name.substring(0, 12) + "\"\r\n");
        assertThat(out).contains("TEXT 20,88,\"TSS24.BF2\",0,2,2,\"" + name.substring(12) + "\"\r\n");
    }

    @Test
    @DisplayName("拆行之后的加粗仍然成立：每一段都各自配一条 x+1 的重复行")
    void boldOverprintSurvivesTheSplit() {
        ResolvedElement e = el("text", 40, 40, "IP803 PROBE ASCII 0123456789 abcdefg", 24);
        e.setBold(true);
        String out = renderer.renderCommandText(of(e));
        assertThat(out).contains("TEXT 40,40,\"2\",0,2,1,\"IP803 PROBE ASCII 0123456\"\r\n");
        assertThat(out).contains("TEXT 41,40,\"2\",0,2,1,\"IP803 PROBE ASCII 0123456\"\r\n");
        assertThat(out).contains("TEXT 40,60,\"2\",0,2,1,\"789 abcdefg\"\r\n");
        assertThat(out).contains("TEXT 41,60,\"2\",0,2,1,\"789 abcdefg\"\r\n");
    }

    @Test
    @DisplayName("起点已在纸外（x ≥ 纸宽）时不崩不循环：原样出一行，越界归模板保存闸管")
    void keepsSingleLineWhenStartIsAlreadyOffThePaper() {
        String out = renderer.renderCommandText(of(el("text", 700, 40, "AB", 24)));
        assertThat(out).contains("TEXT 700,40,\"2\",0,2,1,\"AB\"\r\n");
        assertThat(out).doesNotContain("TEXT 700,60");
    }

    @Test
    @DisplayName("声明了 w 就按 w 换行、不按纸宽：w=100 ⇒ 一行 4 个 24 点的字（种子模板的三条 multiline 都走这条）")
    void declaredWidthIsTheWrapBudget() {
        ResolvedElement e = el("multiline", 20, 40, "一二三四五六七八九十", 24);
        e.setW(100);
        String out = renderer.renderCommandText(of(e));
        assertThat(out).contains("TEXT 20,40,\"TSS24.BF2\",0,1,1,\"一二三四\"\r\n");
        assertThat(out).contains("TEXT 20,64,\"TSS24.BF2\",0,1,1,\"五六七八\"\r\n");
        assertThat(out).contains("TEXT 20,88,\"TSS24.BF2\",0,1,1,\"九十\"\r\n");
        assertThat(out).doesNotContain("TEXT 20,112");
    }

    @Test
    @DisplayName("同样的内容不声明 w ⇒ 按纸宽 620 点排，一行放得下就还是 1 行")
    void paperWidthIsTheFallbackBudget() {
        String out = renderer.renderCommandText(of(el("multiline", 20, 40, "一二三四五六七八九十", 24)));
        assertThat(out).contains("TEXT 20,40,\"TSS24.BF2\",0,1,1,\"一二三四五六七八九十\"\r\n");
        assertThat(out).doesNotContain("TEXT 20,64");
    }
}
