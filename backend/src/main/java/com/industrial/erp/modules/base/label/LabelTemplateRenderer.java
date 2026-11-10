package com.industrial.erp.modules.base.label;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseLabelTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
    /** 这些键必须是整数点：小数值默默截断 = 元素自己在纸上挪了半个字，没人看得出来。 */
    private static final List<String> INTEGRAL_KEYS = List.of("x", "y", "w", "h", "size", "mulX", "mulY", "cell");

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 元素 JSON → 元素清单，**容量天花板在这一层**（docs/36 §0.3）：画布与 resolve 后面要按元素分配盒子数组，
     * 一个 10 MB 的 elements 或一个 50 万字的 text 不该把打印线程拖死。超限一律可读 400，绝不 500。
     */
    public List<LabelElement> parse(String elementsJson) {
        if (elementsJson == null || elementsJson.isBlank()) {
            throw BizException.of(400, "模板元素为空");
        }
        if (elementsJson.getBytes(StandardCharsets.UTF_8).length > LabelGeometry.MAX_ELEMENTS_JSON_BYTES) {
            throw BizException.of(400, "模板元素 JSON 超过 256 KiB，请删掉一些元素或缩短文本");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(elementsJson);
        } catch (Exception e) {
            // 车间的人看不了 Jackson 的整段回显，只留首行并砍掉 [Source: 之后
            throw BizException.of(400, "模板元素 JSON 解析失败: " + jacksonBrief(e.getMessage()));
        }
        if (root == null || !root.isArray()) {
            throw BizException.of(400, "模板元素必须是一个数组（每个元素一个 {…}）");
        }
        if (root.size() > LabelGeometry.MAX_ELEMENTS) {
            throw BizException.of(400, "模板元素不能超过 " + LabelGeometry.MAX_ELEMENTS + " 个");
        }
        List<LabelElement> out = new ArrayList<>(root.size());
        for (int i = 0; i < root.size(); i++) {
            JsonNode node = root.get(i);
            if (node == null || node.isNull()) {
                // 空元素必须点名拒绝：跳过会让后面的下标整体左移，A11 的行号就对不上真实 JSON 了。
                throw BizException.of(400, "模板元素数组第 " + (i + 1) + " 项是空的");
            }
            requireIntegral(node, i);
            LabelElement e;
            try {
                e = objectMapper.treeToValue(node, LabelElement.class);
            } catch (Exception ex) {
                throw BizException.of(400, "模板元素 JSON 解析失败: " + jacksonBrief(ex.getMessage()));
            }
            requireBoundedContent(i, e.getText());
            out.add(e);
        }
        return out;
    }

    private static void requireIntegral(JsonNode element, int position) {
        for (String key : INTEGRAL_KEYS) {
            JsonNode v = element.get(key);
            if (v == null || v.isNull() || !v.isNumber() || v.isIntegralNumber()) continue;
            String unit = "x".equals(key) || "y".equals(key) ? "坐标必须是整数点" : "必须是整数点";
            throw BizException.of(400, "模板元素数组第 " + (position + 1) + " 项的 " + key + " "
                    + unit + "，当前 " + v.asText());
        }
    }

    /** 内容长度的天花板：闸、渲染、画布都按「一个字符一个框」算行数，无界文本就是无界循环。 */
    private static void requireBoundedContent(int position, String content) {
        if (content == null) return;
        if (content.codePointCount(0, content.length()) > LabelGeometry.MAX_CONTENT_CODEPOINTS) {
            throw BizException.of(400, "第 " + (position + 1) + " 个元素内容超过 "
                    + LabelGeometry.MAX_CONTENT_CODEPOINTS + " 字符");
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
            // x=0 贴在打印头左边界外，实纸会静默丢字（2026-11-08 取证）⇒ 起点至少 1 点；y=0 仍可打印，故 y 只拒负。
            if (e.getX() <= 0) issues.add(new ValidationIssue(i, e.getField(),
                    "x 必须大于 0（打印头左边界不在 x=0，贴边会静默丢失），当前 " + e.getX()));
            if (e.getY() < 0) issues.add(new ValidationIssue(i, e.getField(), "y 不能为负，当前 " + e.getY()));
            // 越界判定的唯一出口在 overflowMessages —— 它收的是**与渲染同一种几何输入**，
            // 唯一差别是内容：这里拿字面量（保存时没有单据上下文），渲染那趟拿取值后的真实值。
            ResolvedElement g = geometry(e, type, hasText ? e.getText() : null);
            for (String msg : overflowMessages(g, canvasW, canvasH)) {
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
        for (int i = 0; i < elements.size(); i++) {
            LabelElement e = elements.get(i);
            String type = e.getType() == null ? "" : e.getType().trim().toLowerCase(Locale.ROOT);
            boolean hasField = e.getField() != null && !e.getField().isBlank();
            // **R1**：声明了 field 就只取上下文值，取不到是空串 —— 不回落 text。
            // docs/32 §3 里 text 与 field 是「二选一」，回落会把「批号：」这种标题当值打出去，
            // 还会让 §5 要求的 required 预览标红永远不触发（合规缺口被抹平）。
            String content = hasField ? stringify(ctx.get(e.getField())) : nullToEmpty(e.getText());
            // 真实值的长度只有这一趟知道：无界业务文本同样要在分配盒子数组之前拦住 (docs/36 §0.3)。
            requireBoundedContent(i, content);
            if (e.isRequired() && content.isEmpty() && hasField) {
                // **R3**：清单只收字段名。required 挂在纯字面量元素上是配错，
                // 标该元素（missing=true）可以，往 missingRequired 里塞一个空串会变成界面上一枚空白红牌。
                missing.add(e.getField());
            }
            ResolvedElement r = geometry(e, type, content);
            r.setAlign(e.getAlign() == null ? "LEFT" : e.getAlign());
            r.setMissing(e.isRequired() && content.isEmpty());
            // **R2 / A6 I-3 / B3b**：越界判定与 A6 保存闸同一个函数、同一种几何输入；
            // 这里传的是**取值后的** content，值的长度只有 render 侧知道，validate 侧只能拿字面量。
            r.setOverflow(!overflowMessages(r, canvasW, canvasH).isEmpty());
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
     * 元素 → 参与计算的几何（docs/36 §0.3、§0.4）。闸与渲染都从这里出发，所以「闸说放得下」和
     * 「画布/纸面画在哪」不可能再算出两套数。
     *
     * <p>坐标与尺寸**夹进数学**（int 相加溢出会把天大的越界判成合格，A6 I-2），但库里存的声明值不动，
     * 负坐标仍由闸按**原始值**点名；`mulX/mulY/cell` 是原始声明，**不夹**、不回写（Task 2 的口径：
     * 0 表示沿用旧字号回落）。
     */
    private static ResolvedElement geometry(LabelElement e, String type, String content) {
        ResolvedElement r = new ResolvedElement();
        r.setType(type);
        r.setX(LabelGeometry.clampCoordDots(e.getX()));
        r.setY(LabelGeometry.clampCoordDots(e.getY()));
        r.setW(LabelGeometry.clampExtentDots(e.getW()));
        r.setH(LabelGeometry.clampExtentDots(e.getH()));
        r.setFontSize(e.getSize());
        r.setMulX(e.getMulX());
        r.setMulY(e.getMulY());
        r.setCell(e.getCell());
        r.setBold(e.isBold());
        r.setField(e.getField());
        r.setContent(content);
        return r;
    }

    /**
     * 元素在纸面上放不放得下的**唯一判定**（docs/33 A6 I-3 / A7 R2、R6 那条「唯一出口」的规矩，B3b 续上）：
     * validate 拿它拼话术，render 拿它的 `isEmpty()` 标橙。返回空表 = 这条放得下。
     *
     * <p>入参是 {@link #geometry} 归一化之后的 ResolvedElement —— 闸构造的那一个和内容字面量，
     * 渲染构造的那一个和取值后的真实值，除此之外两者没有区别。
     *
     * <p>**text/multiline 按拆行之后的实际盒判**（盒子来自 {@link LabelLayout#segmentsFor}，
     * 与指令、画布同一个出口），不按「一行横着塞不塞得下」判：渲染器会把超宽的行拆成多行往下压
     * （docs/34 硬约束 2，固件对行尾静默丢弃），所以横向超宽本身不是错 —— 按一行判会误杀本来打得好的模板，
     * 还会漏掉真错误。真错误只有两种：① 右侧连一个字符都放不下（那种情况渲染器**不拆**，尾巴被吞）；
     * ② 拆完的总高越过下边界。
     *
     * <p>**其余类型**：line/rect 按 {@link LabelLayout#shapeBox} 的真实外框查（渲染器发的就是那四条 BAR）；
     * 条码竖向按 {@link LabelGeometry#barcodeHeightDots} 查，二维码免估算 —— 模块数随内容长度变，
     * 没有实测数据不猜；两者的横向只认显式 `w`（A7 裁定 R2，TSPL 按内容自适应）。
     * 未知类型不判 —— 渲染器对它什么都不发，判成越界就是把没印的东西说成印歪了。
     *
     * <p>所有比较**升 long**：int 相加溢出成负数会把天大的越界判成合格（A6 I-2 / A7 R5）。
     */
    private static List<String> overflowMessages(ResolvedElement e, int canvasW, int canvasH) {
        String type = e.getType() == null ? "" : e.getType();
        if ("text".equals(type) || "multiline".equals(type)) return textOverflow(e, canvasW, canvasH);
        if ("line".equals(type) || "rect".equals(type)) return shapeOverflow(e, canvasW, canvasH);
        if ("barcode".equals(type) || "qrcode".equals(type)) return codeOverflow(e, canvasW, canvasH);
        return List.of();
    }

    private static List<String> textOverflow(ResolvedElement e, int canvasW, int canvasH) {
        // 空与纯空格都不判越界：渲染侧先 sanitize/trim，trim 后为空就根本不发 TEXT 行
        // (TsplLabelRenderer 的 text 分支)。判定口径比打印口径严 = 把打得干净的模板拦在闸外。
        String content = LabelLayout.sanitize(e.getContent());
        if (content.isEmpty()) return List.of();
        LabelGeometry.Font font = LabelGeometry.effectiveMul(content, e.getFontSize(), e.getMulX(), e.getMulY());
        int available = LabelGeometry.wrapBudgetDots(e.getX(), e.getW(), canvasW);
        if (available < font.perCharDots()) {
            // 这一条**不能**走盒子：连一个字符都放不下时 `lineSegments` 故意不拆（见其守卫），
            // 按盒子判就成了「放得下」，而固件会把尾巴静默吞掉。
            return List.of("从 x=" + e.getX() + " 起只剩 " + Math.max(0, available) + " 点，放不下一个 "
                    + font.perCharDots() + " 点的字符（字体 " + font.name() + " ×" + font.mulX()
                    + "），起点请往左挪");
        }
        List<LabelLayout.Box> boxes = LabelLayout.segmentsFor(e, canvasW);
        LabelLayout.Box last = boxes.get(boxes.size() - 1);
        long beyondY = (long) last.y() + last.h() - canvasH;
        if (beyondY > 0) {
            long height = (long) boxes.size() * font.lineHeightDots();
            return List.of("这段要拆成 " + boxes.size() + " 行 × " + font.lineHeightDots() + " 点 = "
                    + height + " 点高，从 y=" + e.getY() + " 起越过下边界 " + canvasH + " 点，越界 " + beyondY + " 点");
        }
        return List.of();
    }

    /** line/rect：闸查的外框就是渲染器发出去的那四条 BAR 的外框，包含线粗与最小外框兜底。 */
    private static List<String> shapeOverflow(ResolvedElement e, int canvasW, int canvasH) {
        LabelLayout.Box frame = LabelLayout.shapeBox(e);
        List<String> out = new ArrayList<>(2);
        out.addAll(heightOverflow(e.getY(), frame.h(), canvasH));
        out.addAll(widthOverflow(e.getX(), frame.w(), canvasW));
        return out;
    }

    private static List<String> codeOverflow(ResolvedElement e, int canvasW, int canvasH) {
        // 条码的竖向占位是**可算的**（渲染器就按同一条公式发），所以这里不能免检：
        // 种子模板两条 barcode 只写 size:60 不写 h，按「没声明 h 就不查」的旧口径 ⇒ 永不报越界，纸面下沿却被切。
        // 二维码仍免估算 —— 模块数随内容长度变，没有实测数据不猜。
        int vExtent = "barcode".equals(e.getType())
                ? LabelGeometry.barcodeHeightDots(e.getH(), e.getFontSize())
                : Math.max(e.getH(), 1);
        List<String> out = new ArrayList<>(2);
        out.addAll(heightOverflow(e.getY(), vExtent, canvasH));
        if (e.getW() > 0) out.addAll(widthOverflow(e.getX(), e.getW(), canvasW));
        return out;
    }

    private static List<String> heightOverflow(int y, int h, int canvasH) {
        long beyond = (long) y + h - canvasH;
        return beyond > 0 ? List.of("y=" + y + " 起高 " + h + " 点，越过下边界 " + canvasH + " 点，越界 "
                + beyond + " 点") : List.of();
    }

    private static List<String> widthOverflow(int x, int w, int canvasW) {
        long beyond = (long) x + w - canvasW;
        return beyond > 0 ? List.of("从 x=" + x + " 起宽 " + w + " 点，越过右边界 " + canvasW + " 点，越界 "
                + beyond + " 点") : List.of();
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
