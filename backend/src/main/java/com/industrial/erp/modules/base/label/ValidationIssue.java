package com.industrial.erp.modules.base.label;

/**
 * 保存模板时的一条问题。index 是 elements 数组下标（从 0 起），给前端指到具体行。
 *
 * <p>{@code field} 的含义（A11 按这个决定点哪一列）：未知元素类型时放**类型名**；其余每条问题
 * （缺内容 / 字段不存在 / 越界）都放**该元素自己的 field**，纯字面量元素没有 field 时才是 **null**。
 */
public record ValidationIssue(int index, String field, String message) {}
