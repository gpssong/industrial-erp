package com.industrial.erp.modules.base.label;

/** 保存模板时的一条问题。index 是 elements 数组下标（从 0 起），给前端指到具体行。 */
public record ValidationIssue(int index, String field, String message) {}
