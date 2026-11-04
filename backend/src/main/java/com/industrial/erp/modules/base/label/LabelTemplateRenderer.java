package com.industrial.erp.modules.base.label;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.industrial.erp.exception.BizException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
            for (LabelElement e : arr) if (e != null) out.add(e);
            return out;
        } catch (Exception e) {
            throw BizException.of(400, "模板元素 JSON 解析失败: " + e.getMessage());
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
                issues.add(new ValidationIssue(i, type, "未知元素类型: " + e.getType()
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
            int beyondY = e.getY() + vExtent - canvasH;
            if (beyondY > 0) {
                issues.add(new ValidationIssue(i, e.getField(),
                        "y=" + e.getY() + (e.getH() > 0 ? " 起高 " + e.getH() + " 点" : "")
                        + " 越过下边界 " + canvasH + " 点，越界 " + beyondY + " 点"));
            }
            // barcode 不查宽：TSPL BARCODE 宽度按内容与模块宽自适应，写死了反而会误杀
            if (!"barcode".equals(type)) {
                int estW;
                if ("line".equals(type) || "rect".equals(type)) {
                    estW = Math.max(e.getW(), 0);
                } else if (e.getW() > 0) {
                    estW = e.getW();               // multiline 等显式声明了可用宽
                } else {
                    estW = LabelGeometry.estWidthDots(e.getText(), e.getSize());
                }
                int beyondX = e.getX() + estW - canvasW;
                if (beyondX > 0) {
                    issues.add(new ValidationIssue(i, e.getField(),
                            "从 x=" + e.getX() + " 起宽约 " + estW + " 点，越过右边界 " + canvasW + " 点，越界 " + beyondX + " 点"));
                }
            }
        }
        return issues;
    }
}
