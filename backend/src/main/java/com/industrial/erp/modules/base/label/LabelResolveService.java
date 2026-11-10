package com.industrial.erp.modules.base.label;

import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseLabelTemplate;
import com.industrial.erp.modules.base.service.BaseLabelTemplateService;
import com.industrial.erp.security.PermissionService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 草稿元素的几何解析 (docs/35 §3.2 / docs/36 Task 5)：画布正在拖的那份 elements 还没落库，
 * 这里收原文 JSON，回「拆行之后的盒子 + 逐元素问题 + 可点选外框」。
 *
 * <p>**为什么不是 `/preview` 的第二个版本**：预览必须有真单据、要出张数、要塞 PNG；resolve 服务编辑期，
 * 默认走示例上下文。两者共用 {@link LabelTemplateRenderer#render} 与 {@link LabelLayout#segmentsFor}
 * ——排版数学不许有第二处（全局约束 5）——但不共用请求/响应 DTO。
 *
 * <p>**闸的角色变了、强度没变**：{@code renderer.validate} 的结果原样回 issues，并按 index 把对应元素标橙，
 * 保存那边 {@code BaseLabelTemplateService.gate} 一个字没改 ⇒ 画布提示替代不了服务端最终裁判 (docs/35 §5.5)。
 */
@Service
public class LabelResolveService {

    /** 版本 1 的二维码是 21 模块 —— 这是**最小**可能尺寸，不是「这张码实际多大」（模块数随内容变，无实测数据）。 */
    private static final int QR_MIN_MODULES = 21;
    private static final String QR_HINT = "二维码尺寸示意，最终以实纸为准";
    /** 条码示意宽 = {@link LabelPreviewService} 那张 PNG 写死的同一个常数（引用它，不再抄一遍 300）：
     *  两处同值才不会同一个元素在预览和画布上宽窄不同。它是示意，不等于打印机按内容自适应的实际编码宽 (docs/36 R11)。 */
    private static final int CODE_ILLUSTRATIVE_WIDTH_DOTS = LabelPreviewService.CODE_ILLUSTRATIVE_WIDTH_DOTS;
    /** 未知类型画布上没有可画的东西，但仍要一枚能点中、能删掉的框。 */
    private static final int UNKNOWN_FALLBACK_DOTS = 24;

    private final LabelTemplateRenderer renderer;
    private final LabelContextService contextService;
    private final LabelPreviewService previewService;
    private final PermissionService permService;

    public LabelResolveService(LabelTemplateRenderer renderer, LabelContextService contextService,
                               LabelPreviewService previewService, PermissionService permService) {
        this.renderer = renderer;
        this.contextService = contextService;
        this.previewService = previewService;
        this.permService = permService;
    }

    public LabelResolveResult resolve(LabelResolveRequest request) {
        permService.requirePerm(BaseLabelTemplateService.PERM);
        if (request == null || request.bizType() == null || request.bizType().isBlank()) {
            throw BizException.of(400, "请选择标签类型");
        }
        BaseLabelTemplate template = paperOf(request);
        int widthDots = LabelGeometry.dots(template.getWidthMm(), template.getDpi());
        int heightDots = LabelGeometry.dots(template.getHeightMm(), template.getDpi());
        List<ValidationIssue> issues = renderer.validate(template.getElements(), template.getBizType(),
                template.getWidthMm(), template.getHeightMm(), template.getDpi());
        if (issues.stream().anyMatch(issue -> issue.index() < 0)) {
            // 整份 JSON 坏了 / 超容量（256 KiB、128 个）时没有「第几个元素」可指，也不许再往下排盒子 (§0.3)。
            return new LabelResolveResult(widthDots, heightDots, issues, List.of());
        }
        List<Map<String, Object>> contexts = !Boolean.FALSE.equals(request.sample())
                ? contextService.loadSample(template.getBizType())
                : realBillContexts(request, template);
        List<LabelResolveResult.Label> labels = new ArrayList<>(contexts.size());
        for (Map<String, Object> ctx : contexts) {
            labels.add(new LabelResolveResult.Label(elementsOf(template, ctx, widthDots, issues)));
        }
        return new LabelResolveResult(widthDots, heightDots, issues, labels);
    }

    /** renderer.render 的入参是**模板实体**而不是四个标量，所以纸头必须逐项搬进一个 BaseLabelTemplate。 */
    private static BaseLabelTemplate paperOf(LabelResolveRequest request) {
        LabelResolveRequest.Header header =
                request.header() == null ? LabelResolveRequest.EMPTY_HEADER : request.header();
        BaseLabelTemplate t = new BaseLabelTemplate();
        t.setBizType(LabelBizType.of(request.bizType()).getCode());
        t.setElements(request.elements());
        t.setWidthMm(header.widthMm());
        t.setHeightMm(header.heightMm());
        t.setGapMm(header.gapMm());
        t.setDpi(header.dpi());
        t.setDirection(header.direction());
        t.setCopies(header.copies());
        // 纸头的默认值与合法性必须是保存用的那两个函数 (docs/35 §3.2 要点 2)：resolve 自带一套更宽的判断，
        // 就会出现「画布上合规、点保存被拦」。也别拿假名称/假编码去过 applyDefaults 那三必填 —— 草稿没有名称编码。
        BaseLabelTemplateService.applyPaperDefaults(t);
        BaseLabelTemplateService.validatePaper(t);
        return t;
    }

    private List<Map<String, Object>> realBillContexts(LabelResolveRequest request, BaseLabelTemplate template) {
        // sample=false 走的就是 /preview 那条既有路由：按单据取数、按明细行展开，一行一张标签。
        // 不新建第二条取数路径，也不许在缺 bizId 时退回示例值 —— 示例值骗得过画布，骗不过操作工。
        if (request.bizId() == null) throw BizException.of(400, "请选择业务单据");
        return contextService.load(template.getBizType(), request.bizId(), request.lineIds());
    }

    private List<LabelResolveResult.Element> elementsOf(BaseLabelTemplate template, Map<String, Object> ctx,
                                                        int widthDots, List<ValidationIssue> issues) {
        RenderResult rendered = renderer.render(template, ctx);
        previewService.attachCodeImages(rendered);
        List<ResolvedElement> resolved = rendered.getElements();
        List<LabelResolveResult.Element> out = new ArrayList<>(resolved.size());
        for (int i = 0; i < resolved.size(); i++) {
            ResolvedElement e = resolved.get(i);
            List<LabelLayout.Box> segments = segmentsIfPrintable(e, widthDots);
            LabelGeometry.Font font = LabelGeometry.effectiveMul(LabelLayout.sanitize(e.getContent()),
                    e.getFontSize(), e.getMulX(), e.getMulY());
            List<String> mine = new ArrayList<>();
            for (ValidationIssue issue : issues) {
                if (issue.index() == i) mine.add(issue.message());
            }
            boolean hasField = e.getField() != null && !e.getField().isBlank();
            // 标橙 = 「这一条会被保存闸拦」，来源只有 validate 的 issues 和 render 实测越界两件事；
            // estimated 的码图示意框一个都不许参与，否则 resolve 就成了第二条（还更宽的）保存闸 (R11、裁定 4)。
            out.add(new LabelResolveResult.Element(i, e.getType(), e.getX(), e.getY(), e.getContent(),
                    e.getField(), font.mulX(), font.mulY(),
                    "qrcode".equals(e.getType()) ? LabelGeometry.qrCell(e.getFontSize(), e.getCell()) : 0,
                    e.isBold(), e.isOverflow() || !mine.isEmpty(), e.isMissing(),
                    e.getContent().isEmpty() && hasField ? LabelFieldDict.label(e.getField()) : null,
                    e.getSvg(), segments, displayBounds(e, widthDots, segments, font), mine));
        }
        return out;
    }

    /**
     * 只有渲染器**真的会发指令**的六类才回盒子。未知类型走 {@link LabelLayout#segmentsFor} 的默认分支时
     * 会按字面量排出盒子（保存闸那边已经判它非法了），但画布是按 segments 画线的 —— 那等于把打印机
     * 一条都没发的内容画成"会印成这样" (docs/35 §3.2)。
     */
    private static List<LabelLayout.Box> segmentsIfPrintable(ResolvedElement e, int widthDots) {
        String type = e.getType() == null ? "" : e.getType();
        return switch (type) {
            case "text", "multiline", "line", "rect", "barcode", "qrcode" -> LabelLayout.segmentsFor(e, widthDots);
            default -> List.of();
        };
    }

    private static LabelResolveResult.DisplayBounds displayBounds(ResolvedElement e, int paperW,
                                                                  List<LabelLayout.Box> segments,
                                                                  LabelGeometry.Font font) {
        String type = e.getType() == null ? "" : e.getType();
        switch (type) {
            case "qrcode" -> {
                int side = QR_MIN_MODULES * LabelGeometry.qrCell(e.getFontSize(), e.getCell());
                // 声明的 w/h 也要标示意：打印机实际编码宽按内容变，模块数没有实测数据 (R11)。
                return new LabelResolveResult.DisplayBounds(e.getX(), e.getY(),
                        e.getW() > 0 ? e.getW() : side, e.getH() > 0 ? e.getH() : side, true, QR_HINT);
            }
            case "barcode" -> {
                int w = e.getW() > 0 ? e.getW()
                        : Math.max(1, Math.min(CODE_ILLUSTRATIVE_WIDTH_DOTS, paperW - e.getX()));
                return new LabelResolveResult.DisplayBounds(e.getX(), e.getY(), w,
                        LabelGeometry.barcodeHeightDots(e.getH(), e.getFontSize()), true, null);
            }
            case "line", "rect" -> {
                LabelLayout.Box frame = LabelLayout.shapeBox(e);
                return new LabelResolveResult.DisplayBounds(frame.x(), frame.y(), frame.w(), frame.h(), false, null);
            }
            default -> { }
        }
        if ("text".equals(type) || "multiline".equals(type)) {
            if (!segments.isEmpty()) return union(segments);
            // 空内容（含必填缺值）打印侧一条 TEXT 都不发，画布上却仍要有枚能点中的框：
            // 按服务端有效字体给一字宽、一行高。空串没有 CJK 可判，字体只能按 ASCII 字库走 —— 这是占位，不是打印宽度。
            return new LabelResolveResult.DisplayBounds(e.getX(), e.getY(),
                    font.perCharDots(), font.lineHeightDots(), true, null);
        }
        // 未知类型：渲染器不发指令 ⇒ segments 是空的（见 segmentsIfPrintable），但画布仍要一枚能点中、能删掉的框。
        return new LabelResolveResult.DisplayBounds(e.getX(), e.getY(),
                e.getW() > 0 ? e.getW() : UNKNOWN_FALLBACK_DOTS,
                e.getH() > 0 ? e.getH() : UNKNOWN_FALLBACK_DOTS, true, null);
    }

    private static LabelResolveResult.DisplayBounds union(List<LabelLayout.Box> boxes) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (LabelLayout.Box b : boxes) {
            minX = Math.min(minX, b.x());
            minY = Math.min(minY, b.y());
            maxX = Math.max(maxX, b.x() + b.w());
            maxY = Math.max(maxY, b.y() + b.h());
        }
        return new LabelResolveResult.DisplayBounds(minX, minY, maxX - minX, maxY - minY, false, null);
    }
}
