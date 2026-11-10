package com.industrial.erp.modules.base.label;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * rollback 请求体 (docs/36 Task 7)。只有正整数 seq —— 回滚到哪一版是唯一要说的事；
 * id 在路径上，别的什么都不许客户端顺带塞。
 *
 * <p>Controller 的 @Valid 只管 HTTP 入口，服务直接调用还有 requireSeq 一道，两边都不能省。
 */
public record LabelRollbackRequest(@NotNull @Min(1) Integer seq) {}
