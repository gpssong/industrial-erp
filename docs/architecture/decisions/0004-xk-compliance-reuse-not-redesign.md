# 0004. XK 合规中心复用既有 module, 只加 2 表 1 module, 不重造一套

- **状态**: 采纳 (Accepted)
- **首次落地**: v1.1.91
- **关联**: `modules/xk` / `xk_safety_check` / `xk_audit_log` / XK16-204

## 背景

XK16-204（食品接触塑料生产许可）审核需要合规中心（台账/审计/追溯/检验聚合）。数据大多已在既有 module 里（mes 追溯、qms 检验、em 维保、workflow 卫生、purchase 进货物证）。重造一套独立表会数据双写、口径漂移。

## 决定

- XK 只新增 **1 module (`modules/xk`) + 2 表**（`xk_safety_check` 卫生检查单、`xk_audit_log` 合规流水 SHA256 防篡改 + OSS 路径 + 2 年到期）。
- 其余维度**聚合自**既有 module：dashboard 走 report+mes+qms+wf+em，trace 走 `MesReportService.batchTrace`，material 走 `PurReceiptService`，maintenance 走 `WfMaintain+EquipmentService`。
- 审计直写同步（见 [0001](0001-audit-sync-direct-write.md)）。

## 后果

- 后续 XK 相关表/perm 全在 sql/104、105、115、116 里，围绕 2 张 XK 表 + 既有表加列展开，不散落重造。
- 审计留存 `XK_AUDIT_RETENTION_YEARS = 2` 走 `sys_config`，XK16-204 要求 ≥2 年不允许物理删除。
