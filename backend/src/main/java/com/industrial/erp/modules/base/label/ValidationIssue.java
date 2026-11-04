package com.industrial.erp.modules.base.label;

/**
 * 保存模板时的一条问题。index 是 elements 数组下标（从 0 起），给前端指到具体行。
 *
 * <p>{@code field} 有三种含义（A11 按这个决定点哪一列）：未知类型时放**类型名**、
 * 字段不存在时放**字段名**、越界时为 **null**。
 */
public record ValidationIssue(int index, String field, String message) {}
