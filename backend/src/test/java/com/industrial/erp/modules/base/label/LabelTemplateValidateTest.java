package com.industrial.erp.modules.base.label;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * v1.1.167 保存模板的语法闸 (A6, docs/32 §5)。
 *
 * <p>存在理由就是 v1.1.162 飞鹅模板那次的教训：模板带语法错误存进去了，等到操作工按下打印才炸。
 * 所以「字段名不存在」「元素越界」「元素形状不对」必须在 PC 模板编辑器保存那一刻就报出来，
 * 并且要能指到第几个元素。
 */
class LabelTemplateValidateTest {

    private final LabelTemplateRenderer renderer = new LabelTemplateRenderer();

    /** 80×60mm @203dpi ⇒ 640×480 点 */
    private List<ValidationIssue> v(String elements) {
        return renderer.validate(elements, "FG_ROLL", 80, 60, 203);
    }

    @Test
    @DisplayName("合法模板零问题")
    void acceptsGoodTemplate() {
        assertThat(v("[{\"type\":\"text\",\"x\":20,\"y\":20,\"field\":\"batchNo\",\"size\":24,\"required\":true},"
                + "{\"type\":\"qrcode\",\"x\":480,\"y\":200,\"size\":7,\"field\":\"billNo\"}]")).isEmpty();
    }

    @Test
    @DisplayName("引用上下文中不存在的 field → 保存时报错并指出下标 (docs/32 §5，别把错误留到打印那一刻)")
    void rejectsUnknownField() {
        List<ValidationIssue> issues = v("[{\"type\":\"text\",\"x\":0,\"y\":0,\"field\":\"nonsense\",\"size\":24}]");
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).index()).isZero();
        assertThat(issues.get(0).field()).isEqualTo("nonsense");
        assertThat(issues.get(0).message()).contains("不存在字段");
    }

    @Test
    @DisplayName("bizType 本身写错 → 报「不支持的标签类型」，不能让它退化成一屏「字段不存在」(A4 裁定：先 of() 再查字典)")
    void unknownBizTypeIsNotBlamedOnFields() {
        assertThatThrownBy(() -> renderer.validate("[{\"type\":\"text\",\"x\":0,\"y\":0,\"field\":\"batchNo\",\"size\":24}]",
                "NO_SUCH_TYPE", 80, 60, 203)).isInstanceOf(com.industrial.erp.exception.BizException.class)
                .hasMessageContaining("不支持的标签类型");
        // 大小写宽容的那条路仍由 LabelFieldDict.key() 兜住，不该在这里误杀
        assertThat(renderer.validate("[{\"type\":\"text\",\"x\":0,\"y\":0,\"field\":\"batchNo\",\"size\":24}]",
                "fg_roll", 80, 60, 203)).isEmpty();
    }

    @Test
    @DisplayName("主数据字段对任意 bizType 都合法（以后上合规要素只改模板，不改库不改后端）")
    void masterFieldsAlwaysKnown() {
        assertThat(v("[{\"type\":\"text\",\"x\":0,\"y\":0,\"field\":\"producerName\",\"size\":24}]")).isEmpty();
        assertThat(v("[{\"type\":\"text\",\"x\":0,\"y\":0,\"field\":\"xkNo\",\"size\":24}]")).isEmpty();
        assertThat(v("[{\"type\":\"text\",\"x\":0,\"y\":0,\"field\":\"inspectorCode\",\"size\":24}]")).isEmpty();
    }

    @Test
    @DisplayName("字面量文本越过右边界 → 报错带越界点数")
    void rejectsHorizontalOverflow() {
        List<ValidationIssue> issues = v("[{\"type\":\"text\",\"x\":600,\"y\":10,\"text\":\"批号批号批号\",\"size\":24}]");
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).message()).contains("越界 104 点");
    }

    @Test
    @DisplayName("line/rect 的 w 越界、任意元素 y 越下边界都要拦；条码宽度不拦（TSPL 按内容自适应）")
    void rejectsVerticalAndShapeOverflow() {
        assertThat(v("[{\"type\":\"line\",\"x\":0,\"y\":470,\"w\":0,\"h\":30}]")).hasSize(1);
        assertThat(v("[{\"type\":\"rect\",\"x\":0,\"y\":0,\"w\":700,\"h\":10}]")).hasSize(1);
        assertThat(v("[{\"type\":\"barcode\",\"x\":400,\"y\":320,\"field\":\"batchNo\",\"size\":60}]")).isEmpty();
    }

    @Test
    @DisplayName("未知元素类型 / 既无 text 又无 field / 非法 type 名 都被拦")
    void rejectsBadElementShape() {
        assertThat(v("[{\"type\":\"hexagon\",\"x\":0,\"y\":0,\"text\":\"x\"}]")).isNotEmpty();
        assertThat(v("[{\"type\":\"text\",\"x\":0,\"y\":0,\"size\":24}]")).isNotEmpty();
    }

    @Test
    @DisplayName("坏 JSON：parse 抛 400 带原因不是 500；validate 把它收成一条 index=-1 的问题（A8 的 gate 靠这个下标免拼「第 0 个元素」）")
    void badJsonIsClientError() {
        assertThatThrownBy(() -> renderer.parse("[{\"type\":\"text\","))
                .isInstanceOf(com.industrial.erp.exception.BizException.class)
                .hasMessageContaining("模板元素 JSON 解析失败");
        List<ValidationIssue> issues = v("[{\"type\":\"text\",");
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).index()).isEqualTo(-1);
        assertThat(issues.get(0).message()).contains("解析失败");
    }

    @Test
    @DisplayName("第二个元素出错，index 必须是 1 —— A11 靠它指行")
    void secondElementGetsSecondIndex() {
        List<ValidationIssue> issues = renderer.validate(
                "[{\"type\":\"text\",\"x\":0,\"y\":0,\"field\":\"batchNo\",\"size\":24},"
              + "{\"type\":\"text\",\"x\":600,\"y\":0,\"text\":\"批号批号批号\",\"size\":24}]",
                "FG_ROLL", 80, 60, 203);
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).index()).isEqualTo(1);
        assertThat(issues.get(0).message()).contains("越界 104 点");
    }

    @Test
    @DisplayName("数组里夹 null：按**原始下标**点名，不许悄悄压缩后把行号挪走")
    void nullEntryIsNamedByItsOwnPosition() {
        List<ValidationIssue> issues = renderer.validate(
                "[null,{\"type\":\"hexagon\",\"x\":0,\"y\":0}]", "FG_ROLL", 80, 60, 203);
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).index()).isEqualTo(-1);
        // null 是数组首项 ⇒ 点名「第 1 项」。计划文档这里写成「第 2 项」，但它给的样例 JSON 把 null 放在了下标 0，
        // 按「原始下标点名」的本意就是第 1 项；改成 1 才能同时满足样例输入与 parse 的实现（实测输出即「第 1 项是空的」）。
        assertThat(issues.get(0).message()).contains("第 1 项是空的");
    }

    @Test
    @DisplayName("超大坐标不许靠 int 溢出混过闸")
    void hugeCoordinatesDoNotWrapIntoACleanPass() {
        assertThat(renderer.validate(
                "[{\"type\":\"rect\",\"x\":2000000000,\"y\":2000000000,\"w\":2000000000,\"h\":2000000000}]",
                "FG_ROLL", 80, 60, 203)).isNotEmpty();
    }
}
