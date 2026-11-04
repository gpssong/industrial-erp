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
            // 竖向按「声明了的高度」查：line/rect 的 h 是确定的，最后一个点落在 y+h-1。
            // text/qrcode/barcode 不猜字号排出来的真实行高（内置字库要 §9 真机取证），只查原点越界。
            int vExtent = Math.max(e.getH(), 1);
            // 越界算式一律升 long：int 相加溢出成负数会把天大的越界判成合格。
            long beyondY = (long) e.getY() + vExtent - canvasH;
            if (beyondY > 0) {
                issues.add(new ValidationIssue(i, e.getField(),
                        "y=" + e.getY() + (e.getH() > 0 ? " 起高 " + e.getH() + " 点" : "")
                        + " 越过下边界 " + canvasH + " 点，越界 " + beyondY + " 点"));
            }
            // estWidth 是 validate / render 同一个口径的唯一出口（docs/33 A6 I-3 / A7 R2、R6）。
            // validate 侧没有取值过程，传元素自己的 text 字面量估（取不到值时字面量就是最终值）。
            Integer estW = estWidth(type, e.getW(), e.getSize(), e.getText());
            if (estW != null) {
                long beyondX = (long) e.getX() + estW - canvasW;
                if (beyondX > 0) {
                    issues.add(new ValidationIssue(i, e.getField(),
                            "从 x=" + e.getX() + " 起宽约 " + estW + " 点，越过右边界 " + canvasW + " 点，越界 " + beyondX + " 点"));
                }
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
            // **R2 / A6 I-3 / 本任务 R6**：越界估算与 A6 保存闸同一个口径，统一走 estWidth
            // —— 那是唯一出口，这里只是把取值后的 content 作为第 4 个入参传进去。
            Integer estW = estWidth(type, e.getW(), e.getSize(), content);
            // **A6 I-2 / 本任务 R5**：升 long —— int 相加溢出成负数会把天大的越界判成合格。
            r.setOverflow(estW != null && (long) e.getX() + estW > canvasW);
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
     * est 宽口径的**唯一出口**（docs/33 A6 I-3 / A7 R2、R6）：validate 与 render 各调一次，
     * 两边只差第 4 个入参 —— validate 传元素自己的 text 字面量，render 传取值后的 content。
     * line/rect 用声明尺寸；barcode/qrcode 免估算（TSPL 按内容自适应），只认显式 w；
     * multiline 的 w 才是真换行宽度；text 的 w 盖不住估宽（TSPL TEXT 不换行，取 max）。
     * 返回 null = 这一条不查横向越界。
     */
    private static Integer estWidth(String type, int declaredW, int size, String text) {
        Integer estW;
        if ("line".equals(type) || "rect".equals(type)) {
            estW = Math.max(declaredW, 0);
        } else if ("barcode".equals(type) || "qrcode".equals(type)) {
            estW = declaredW > 0 ? declaredW : null;
        } else if ("multiline".equals(type)) {
            estW = declaredW > 0 ? declaredW : LabelGeometry.estWidthDots(text, size);
        } else {
            int est = LabelGeometry.estWidthDots(text, size);
            estW = declaredW > 0 ? Math.max(declaredW, est) : est;
        }
        return estW;
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
