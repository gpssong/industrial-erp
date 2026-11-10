package com.industrial.erp.modules.base.label;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.industrial.erp.modules.base.entity.BaseLabelTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * 元素级结构化预览 (docs/32 §6.3)：后端只回「解析后的文本/条码内容 + 点坐标 + 码图」，
 * 画布由前端用绝对定位 HTML 等比画出来。
 *
 * <p>**A9 裁定 R1**：本服务只拿模板干活，不认识 templateId —— 模板（显式 id 还是该类型默认位）
 * 由 Controller 用 A8 的 detail/defaultOf 解析后把 {@link BaseLabelTemplate} 传进来。
 *
 * <p>⚠ 预览忠实于**版式**，不忠实于**打印机中文字体**（真机中文由 §9 取证决定）。
 * PC/App 页面上必须把这句话写出来。
 */
@Service
public class LabelPreviewService {

    public LabelPreviewService(LabelContextService contextService, LabelTemplateRenderer renderer) {
        this.contextService = contextService;
        this.renderer = renderer;
    }

    private final LabelContextService contextService;
    private final LabelTemplateRenderer renderer;

    /**
     * CODE128 示意 PNG 的写死宽度（点）。resolve 画布上那条条码的示意宽用**同一个数** ——
     * 两个端点各抄一份 300，改一处就会出现「预览和画布同一个元素两种宽窄」(docs/36 R11)。
     * 它是示意，不等于打印机按内容自适应的实际编码宽。
     */
    static final int CODE_ILLUSTRATIVE_WIDTH_DOTS = 300;

    /**
     * 一张标签一条 {@link RenderResult}，qrcode/barcode 元素的 {@code svg} 里填好 PNG data URL
     * （键名叫 svg，装的确实是 PNG，见 {@link ResolvedElement#getSvg()}）。
     * RenderResult 直接序列化给前端，不另造 DTO。
     *
     * <p>码图那段与 resolve 共用 {@link #attachCodeImages}：同一个元素在两个端点上必须是同一张图。
     */
    public List<RenderResult> preview(BaseLabelTemplate tpl, Long bizId, List<Long> lineIds) {
        List<Map<String, Object>> rows = contextService.load(tpl.getBizType(), bizId, lineIds);
        List<RenderResult> out = new ArrayList<>();
        for (Map<String, Object> ctx : rows) {
            RenderResult r = renderer.render(tpl, ctx);
            attachCodeImages(r);
            dedupeMissingRequired(r);
            out.add(r);
        }
        return out;
    }

    /**
     * preview 循环里那段码图附加**原样**抽出的包级方法（docs/35 §3.5 的「两个端点共享渲染器」）。
     * 抽出来只为了不再有第二份 PNG 参数；那两个参数一个都不许改去迁就新画布 —— 改了就会出现
     * 「预览和画布同一个元素两种图」，而两边都只是示意 (docs/36 R11)。
     */
    void attachCodeImages(RenderResult r) {
        for (ResolvedElement e : r.getElements()) {
            // 封顶 1024 点：size 来自**存进库的模板**，是用户可存的数据，预览不许因为它吃满堆
            // （二维码 size 40000 就是 1,000,000 点见方；OOM 是 Error，encodePng 的
            // catch (Exception) 拦不住 ⇒ 整页 500）；1024 点足够屏显。
            // 正常模板（size 24 ⇒ 600 点）行为不变；条码宽是写死的 300，不需要夹。
            int qrPx = Math.min(1024, Math.max(4, e.getFontSize()) * 25);
            if ("qrcode".equals(e.getType())) e.setSvg(encodePng(e.getContent(), BarcodeFormat.QR_CODE,
                    qrPx, qrPx, true));
            else if ("barcode".equals(e.getType())) e.setSvg(encodePng(e.getContent(), BarcodeFormat.CODE_128,
                    CODE_ILLUSTRATIVE_WIDTH_DOTS, Math.min(1024, Math.max(20, e.getFontSize())), false));
        }
    }

    /**
     * **A9 裁定（A7 遗留）**：{@code missingRequired} 是 A7 渲染器按元素顺序 {@code missing.add(e.getField())}
     * 攒出来的，两份必填元素引用同一个字段名时会产出**重复项**，A11 会画成两枚一模一样的红牌。
     * 这里按首次出现顺序去重（渲染器是 A7 的已评审文件，不动它）。
     * 元素级的 {@code missing} 标记一个不少地保留 ⇒「哪个元素空了」的信息没有丢，去重的只是那张汇总条。
     */
    private static void dedupeMissingRequired(RenderResult r) {
        List<String> missing = r.getMissingRequired();
        if (missing.size() < 2) return;
        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(missing));
        if (distinct.size() != missing.size()) r.setMissingRequired(distinct);
    }

    /**
     * **A9 裁定 R5**：CODE_128 装不下非 ASCII；zxing 各版本抛的异常类型不同（3.5.x 是
     * {@code IllegalArgumentException}，老版本是 {@code WriterException}），所以自己先判，别依赖它。
     * 抽成包级静态方法是为了让这条预检能被单测直接钉住 —— 留在 {@code encodePng} 里时，
     * 删掉它测试仍然绿（zxing 自己抛错也落进同一个 catch），这条测试就保护不了 R5。
     */
    static boolean asciiOnly(String s) {
        return s.chars().noneMatch(c -> c > 127);
    }

    /** 编不出来（空内容 / 中文进 CODE128）就回 null，让前端显示文本 —— 预览不能因为码图失败而整页 500。 */
    private String encodePng(String content, BarcodeFormat format, int w, int h, boolean qr) {
        if (content == null || content.isBlank()) return null;
        // **A9 裁定 R5**：不赌 zxing 抛不抛 —— CODE128 本质上只支持 ASCII，而 3.5.x 对非 ASCII 的行为
        // 在不同版本里不一致（多在超出 CODE128B 字符域时抛，也有走 CODE_C 把字节塞进去的）。
        // 自己先判 ⇒「编不出来 → svg 留空 + content 照旧给前端显示」这条行为成立，而不是碰巧成立。
        // QR 不判：二维码按 UTF-8 装得下中文。
        if (!qr && !asciiOnly(content)) return null;
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            if (qr) hints.put(EncodeHintType.MARGIN, 1);
            if (qr) hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            BitMatrix m = new MultiFormatWriter().encode(content, format, w, h, hints);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(m, "PNG", bos);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (Exception e) {
            // **有意**的静默降级（不是漏了日志）：预览页不能因为一张码图编不出来就 500，
            // svg 留空后前端按 content 显示文本即可。
            return null;
        }
    }
}
