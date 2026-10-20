# 架构决策记录 (ADR, v1.1.104 起)

> 本目录沉淀跨版本的**关键设计决策**：什么决定、为什么、不选什么、有哪些后果。
> 正文精简（背景 / 决定 / 备选 / 后果四段式），历史细节与部署记录指向 `docs/CHANGELOG.md` 对应版本段。
> 新决策沿用递增编号（当前到 0007）。编号不重用；被推翻的 ADR 不删，加 "Status: 已废弃 (superseded by 00XX)" 头。

## 索引

| 编号 | 决策 | 状态 | 首次落地 |
|---|---|---|---|
| [0001](0001-audit-sync-direct-write.md) | 高频/合规审计同步直写, 不用 `@Async @EventListener` | 采纳 (Accepted) | v1.1.79 hotfix-1 |
| [0002](0002-sys-menu-perm-unique-index.md) | `sys_menu.perms` 加 UNIQUE 索引 | 采纳 (Accepted) | v1.1.56 hotfix-1 |
| [0003](0003-perm-split-by-client-type.md) | perm 按端 (PC/App) 分流, 后端按 `X-Client-Type` 分表下发 | 采纳 (Accepted) | v1.1.55 / 持续 |
| [0004](0004-xk-compliance-reuse-not-redesign.md) | XK 合规中心复用既有 module, 只加 2 表 1 module, 不重造一套 | 采纳 (Accepted) | v1.1.91 |
| [0005](0005-canary-first-deploy.md) | 双站部署: 飞牛 canary 先行 24h, 再同步 home | 采纳 (长期规则) | v1.1.79 |
| [0006](0006-baked-jar-dockercp-two-track.md) | 后端 jar 双站双轨部署: home baked-in / 飞牛 runtime mount | 采纳 (持续) | v1.1.65 |
| [0007](0007-app-runtime-probe-and-cors-origin.md) | App 运行时探测单一权威源 (`utils/runtime.js`) + CORS 覆盖 Capacitor 无端口 origin | 采纳 (持续) | v1.1.105 |
