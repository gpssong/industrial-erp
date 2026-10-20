# 0001. 高频/合规审计同步直写, 不用 `@Async @EventListener`

- **状态**: 采纳 (Accepted)
- **首次落地**: v1.1.79 hotfix-1 (MCP 审计) — 后续 XK/维保/MES 报工全沿用
- **关联**: `McpAuditService` / `XkAuditService.writeAudit` / `SysOperLogMapper.insert`

## 背景

早期审计日志走 Spring 事件 + `@Async @EventListener` 异步写库。在 Hermes 高频调 MCP 建维保单场景下实测：**接口返回 200，但 `sys_oper_log` 0 行落库**——异步线程池竞争 + 事务上下文问题导致事件丢失。XK16-204 合规要求审计日志防篡改、100% 留存，这类"高频"场景丢一行都不可接受。

## 决定

- 合规模块（XK / MCP / MES 报工 / 维保 / 卫生检查）的关键动作**同步直写**审计表：方法体里直接 `SysOperLogMapper.insert(...)` 或 `XkAuditService.writeAudit(...)`，`try/catch` 失败仅 `log.warn` 不阻塞主流程。
- 唯一保留的异步写库是 `OperLogEventListener` 写**操作日志** `sys_oper_log`（非合规硬约束、容忍度高的最高频记录）——通用业务操作仍走 `@Async @EventListener`，合规审计走同步。

## 备选

- 全异步：省 CPU 但合规审计会漏行（实测教训），弃。
- 全同步：合规 100% 落库，代价是高频操作日志阻塞主流程——对合规动作可接受（一次 1 行 INSERT 开销可忽略）。

## 后果

- 新增合规模块时**注释必须显式声明**"同步直写, 不走 @Async"（已遍布 20+ 处）。
- 判定法沉淀：某审计表 `0 行落库而主流程 200` → 立刻怀疑异步漏写（见 `erp-mcp-audit-async-bug` memory）。
