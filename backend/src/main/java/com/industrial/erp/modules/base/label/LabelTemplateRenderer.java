package com.industrial.erp.modules.base.label;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseLabelTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * v1.1.167 元素 → 取值/校验 的厂商无关内核 (docs/32 §3 §4 §5)。
 *
 * <p>思路照 v1.1.162 飞鹅模板保存语法闸：**错误在保存那一刻拦，不留到打印那一刻**。
 * 区别是这里元素是 JSON 不是 FreeMarker，所以没有 ParseException extends IOException 那种坑，
 * 但「catch 宽一点、把原因写进 msg」这条要一样守住。
 */
@Component
public class LabelTemplateRenderer {

    private static final Set<String> KNOWN_TYPES = Set.of("text", "multiline", "barcode", "qrcode", "line", "rect");
    private static final Set<String> NEED_CONTENT = Set.of("text", "multiline", "barcode", "qrcode");

    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<LabelElement> parse(String elementsJson) {
        if (elementsJson == null || elementsJson.isBlank()) {
            throw BizException.of(400, "模板元素为空");
        }
        try {
            LabelElement[] arr = objectMapper.readValue(elementsJson, LabelElement[].class);
            List<LabelElement> out = new ArrayList<>();
            for (int i = 0; i < arr.length; i++) {
                if (arr[i] == null) {
                    // 空元素必须点名拒绝：跳过会让后面的下标整体左移，A11 的行号就对不上真实 JSON 了。
                    throw BizException.of(400, "模板元素数组第 " + (i + 1) + " 项是空的");
                }
                out.add(arr[i]);
            }
            return out;
        } catch (BizException e) {
            throw e;   // 语义拒绝（空元素等）原样上抛，别塞进「JSON 解析失败」的壳里
        } catch (Exception e) {
            // 车间的人看不了 Jackson 的整段回显，只留首行并砍掉 [Source: 之后
            throw BizException.of(400, "模板元素 JSON 解析失败: " + jacksonBrief(e.getMessage()));
        }
    }

    public List<ValidationIssue> validate(String elementsJson, String bizTypeCode,
                                         int widthMm, int heightMm, int dpi) {
        List<ValidationIssue> issues = new ArrayList<>();
        // 先把 bizType 过一遍枚举：写错的类型必须是「不支持的标签类型」，
        // 不能靠字典 getOrDefault(…, List.of()) 静默退化成「每个业务字段都不存在」——那是把用户的注意力指错地方。
        String bizType = LabelBizType.of(bizTypeCode).getCode();
        int canvasW = LabelGeometry.dots(widthMm, dpi);
        int canvasH = LabelGeometry.dots(heightMm, dpi);
        List<LabelElement> elements;
        try {
            elements = parse(elementsJson);
        } catch (BizException e) {
            return List.of(new ValidationIssue(-1, null, e.getMessage()));
        }
        for (int i = 0; i < elements.size(); i++) {
            LabelElement e = elements.get(i);
            // Locale.ROOT 与同包的 LabelFieldDict.key() 一致：不带 Locale 的 toLowerCase 在 tr 环境下
            // 会把 "MULTILINE" 折成 "multıline"（点less i），误杀成「未知元素类型」。
            String type = e.getType() == null ? "" : e.getType().trim().toLowerCase(Locale.ROOT);
            if (!KNOWN_TYPES.contains(type)) {
                // 未知元素类型: null 甩给车间看没意义，缺 type 就直说是缺 type
                String typeLabel = e.getType() == null ? "（缺少 type）" : e.getType();
                issues.add(new ValidationIssue(i, type, "未知元素类型: " + typeLabel
                        + "（可用 text / multiline / barcode / qrcode / line / rect）"));
                continue;
            }
            boolean hasText = e.getText() != null && !e.getText().isBlank();
            boolean hasField = e.getField() != null && !e.getField().isBlank();
            if (NEED_CONTENT.contains(type) && !hasText && !hasField) {
                issues.add(new ValidationIssue(i, e.getField(), type + " 元素既没有 text 也没有 field"));
            }
            if (hasField && !LabelFieldDict.isKnown(bizType, e.getField())) {
                issues.add(new ValidationIssue(i, e.getField(),
                        "该标签类型不存在字段: " + e.getField()));
            }

            // --- 越界检测 (docs/32 §5: 保存时报错并指出越界的元素序号与越界量) ---
            if (e.getX() < 0) issues.add(new ValidationIssue(i, e.getField(), "x 不能为负，当前 " + e.getX()));
            if (e.getY() < 0) issues.add(new ValidationIssue(i, e.getField(), "y 不能为负，当前 " + e.getY()));
            // 越界判定的唯一出口在 overflowMessages —— 预览标橙用的是同一个函数，两边不可能算出两套结论。
            for (String msg : overflowMessages(type, e.getX(), e.getY(), e.getW(), e.getH(),
                    e.getSize(), e.getText(), canvasW, canvasH)) {
                issues.add(new ValidationIssue(i, e.getField(), msg));
            }
        }
        return issues;
    }

    /**
     * 取值 + 格式化 + 必填/越界标记。ctx 里缺字段 ⇒ 空串（保存闸已保证新模板不会缺）。
     * 不抛「必填缺失」异常 —— 车间不能因为一个代号空就停下不出标签 (docs/32 §5)。
     */
    public RenderResult render(BaseLabelTemplate tpl, Map<String, Object> ctx) {
        int dpi = tpl.getDpi() == null ? 203 : tpl.getDpi();
        int canvasW = LabelGeometry.dots(tpl.getWidthMm() == null ? 80 : tpl.getWidthMm(), dpi);
        int canvasH = LabelGeometry.dots(tpl.getHeightMm() == null ? 60 : tpl.getHeightMm(), dpi);

        List<LabelElement> elements = parse(tpl.getElements());
        List<ResolvedElement> resolved = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (LabelElement e : elements) {
            String type = e.getType() == null ? "" : e.getType().trim().toLowerCase(Locale.ROOT);
            boolean hasField = e.getField() != null && !e.getField().isBlank();
            // **R1**：声明了 field 就只取上下文值，取不到是空串 —— 不回落 text。
            // docs/32 §3 里 text 与 field 是「二选一」，回落会把「批号：」这种标题当值打出去，
            // 还会让 §5 要求的 required 预览标红永远不触发（合规缺口被抹平）。
            String content = hasField ? stringify(ctx.get(e.getField())) : nullToEmpty(e.getText());
            if (e.isRequired() && content.isEmpty() && hasField) {
                // **R3**：清单只收字段名。required 挂在纯字面量元素上是配错，
                // 标该元素（missing=true）可以，往 missingRequired 里塞一个空串会变成界面上一枚空白红牌。
                missing.add(e.getField());
            }
            ResolvedElement r = new ResolvedElement();
            r.setType(type);
            r.setX(e.getX());
            r.setY(e.getY());
            r.setContent(content);
            r.setFontSize(e.getSize());
            r.setBold(e.isBold());
            r.setAlign(e.getAlign() == null ? "LEFT" : e.getAlign());
            r.setW(e.getW());
            r.setH(e.getH());
            r.setField(e.getField());
            r.setMissing(e.isRequired() && content.isEmpty());
            // **R2 / A6 I-3 / B3b**：越界判定与 A6 保存闸同一个函数 —— 这里只是把**取值后的** content
            // 传进去（值的长度只有 render 侧知道，validate 侧只能拿字面量）。
            r.setOverflow(!overflowMessages(type, e.getX(), e.getY(), e.getW(), e.getH(),
                    e.getSize(), content, canvasW, canvasH).isEmpty());
            resolved.add(r);
        }

        RenderResult out = new RenderResult();
        out.setElements(resolved);
        out.setWidthDots(canvasW);
        out.setHeightDots(canvasH);
        out.setMissingRequired(missing);
        out.setCopies(tpl.getCopies() == null ? 1 : tpl.getCopies());
        out.setGapMm(tpl.getGapMm() == null ? 3 : tpl.getGapMm());
        out.setDpi(dpi);
        out.setDirection(tpl.getDirection() == null ? "NORMAL" : tpl.getDirection());
        return out;
    }

    /** 数字去尾零、日期一律 yyyy-MM-dd（标签上没有时分秒的位置）。 */
    public String stringify(Object v) {
        if (v == null) return "";
        if (v instanceof BigDecimal b) return b.stripTrailingZeros().toPlainString();
        if (v instanceof LocalDateTime dt) return dt.toLocalDate().toString();
        if (v instanceof LocalDate d) return d.toString();
        return String.valueOf(v);
    }

    /**
     * 元素在纸面上放不放得下的**唯一判定**（docs/33 A6 I-3 / A7 R2、R6 那条「唯一出口」的规矩，B3b 续上）：
     * validate 拿它拼话术，render 拿它的 `isEmpty()` 标橙。返回空表 = 这条放得下。
     *
     * <p>**text/multiline 按拆行之后的实际盒判**，不按「一行横着塞不塞得下」判：渲染器会把超宽的行
     * 拆成多行往下压（docs/34 硬约束 2，固件对行尾静默丢弃），所以横向超宽本身不是错 ——
     * 按一行判会误杀本来打得好的模板，还会漏掉真错误。真错误只有两种：
     * ① 右侧连一个字符都放不下（那种情况渲染器**不拆**，尾巴被吞，见 `lineSegments` 的守卫）；
     * ② 拆完的总高越过下边界。
     *
     * <p>**其余类型**：line/rect 两个轴都按声明尺寸查；条码的竖向按 {@link LabelGeometry#barcodeHeightDots}
     * 查（渲染器发的就是那个高度），二维码免估算 —— 它的模块数随内容长度变，没有实测数据不猜；
     * 两者的横向只认显式 `w`（A7 裁定 R2，TSPL 按内容自适应）。
     *
     * <p>所有比较**升 long**：int 相加溢出成负数会把天大的越界判成合格（A6 I-2 / A7 R5）。
     *
     * <p>validate 侧传的是元素自己的 `text` 字面量：声明了 `field` 的**文本/多行**在这里必然拿不到值，
     * 空内容不出 `TEXT` 行、谈不上越界（返回空表），值的长度由 render 侧那一次调用负责。
     * 其余类型（line/rect/barcode/qrcode）不看内容，几何越界照旧判 —— 所以带 `field` 的条码仍会因
     * 「y + 高度越过下边界」被拦，这是想要的行为：那条越界与内容是什么无关。
     */
    private static List<String> overflowMessages(String type, int x, int y, int declaredW, int declaredH,
                                                 int size, String content, int canvasW, int canvasH) {
        if ("text".equals(type) || "multiline".equals(type)) {
            // 空与纯空格都不判越界：渲染侧 `safe()` 先 trim，trim 后为空就根本不发 TEXT 行
            // (TsplLabelRenderer 的 text 分支)。判定口径比打印口径严 = 把打得干净的模板拦在闸外。
            if (content == null || content.isBlank()) return List.of();
            LabelGeometry.Font font = LabelGeometry.fontFor(content, size);
            int available = LabelGeometry.wrapBudgetDots(x, declaredW, canvasW);
            if (available < font.perCharDots()) {
                return List.of("从 x=" + x + " 起只剩 " + Math.max(0, available) + " 点，放不下一个 "
                        + font.perCharDots() + " 点的字符（字体 " + font.name() + " ×" + font.mulX()
                        + "），起点请往左挪");
            }
            List<String> lines = LabelGeometry.lineSegments(content, font.perCharDots(), available);
            int height = lines.size() * font.lineHeightDots();
            long beyondY = (long) y + height - canvasH;
            if (beyondY > 0) {
                return List.of("这段要拆成 " + lines.size() + " 行 × " + font.lineHeightDots() + " 点 = "
                        + height + " 点高，从 y=" + y + " 起越过下边界 " + canvasH + " 点，越界 " + beyondY + " 点");
            }
            return List.of();
        }

        List<String> out = new ArrayList<>();
        // 条码的竖向占位是**可算的**（渲染器就按同一条公式发），所以这里不能免检：
        // 种子模板两条 barcode 只写 size:60 不写 h，按老口径 vExtent=1 ⇒ 永不报越界，纸面下沿却被切。
        // 二维码仍免估算 —— 模块数随内容长度变，没有实测数据不猜（本函数上方原注）。
        int vExtent = "barcode".equals(type) ? LabelGeometry.barcodeHeightDots(declaredH, size)
                                             : Math.max(declaredH, 1);
        long beyondY = (long) y + vExtent - canvasH;
        if (beyondY > 0) {
            out.add("y=" + y + " 起高 " + vExtent + " 点，越过下边界 " + canvasH + " 点，越界 " + beyondY + " 点");
        }
        // 横向只查「尺寸是确定的」那两类：line/rect 的 w 就是它自己声明的；条码/二维码只有显式声明才查。
        Integer codeW;
        if ("line".equals(type) || "rect".equals(type)) {
            codeW = Math.max(declaredW, 0);
        } else {
            codeW = declaredW > 0 ? declaredW : null;
        }
        if (codeW != null) {
            long beyondX = (long) x + codeW - canvasW;
            if (beyondX > 0) {
                out.add("从 x=" + x + " 起宽 " + codeW + " 点，越过右边界 " + canvasW + " 点，越界 " + beyondX + " 点");
            }
        }
        return out;
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    /** Jackson 原始异常太长，只留首行、砍掉 [Source: 之后的回显，别甩给车间的人看。 */
    private static String jacksonBrief(String raw) {
        if (raw == null) return "";
        String first = raw;
        int nl = first.indexOf('\n');
        if (nl >= 0) first = first.substring(0, nl);
        int src = first.indexOf("[Source:");
        if (src >= 0) first = first.substring(0, src);
        return first.trim();
    }
}
