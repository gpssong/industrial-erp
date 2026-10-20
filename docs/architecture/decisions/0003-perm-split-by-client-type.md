# 0003. perm 按端 (PC/App) 分流, 后端按 `X-Client-Type` 分表下发

- **状态**: 采纳 (Accepted, 持续维护)
- **首次落地**: v1.1.55；R9 设计原则
- **关联**: `perms_by_user_client(uid, X-Client-Type)` / `sys_role_menu.client_type`

## 背景

同一用户 PC 与 App 端能操作的功能集合不同（如 App 不上工作流、飞鹅打印 PC-only）。若后端把 PC+App 两端的 perm 混成一个数组下发，**App 本地 storage 会残留** PC 端 perm，导致 App 上不该出现的按钮误显（v1.1.55 hotfix-3 实测）。

## 决定

- 授权存储 `sys_role_menu` 带 `client_type` 列（PC / APP）。
- 后端登录/刷新时**按请求头 `X-Client-Type` 只下发该端的 perm 子集**（R9）。
- 前端 App 端 `canCheck/canUncheck` 等判定走**纯 APP perm**（`getAppPermissions()`），绝不走混合端 `getPermissions()`（R8）。

## 后果

- seed 一个新 perm 必须**同时给 6 内置角色 × PC + APP 双授权**，漏一端则该端 dashboard 少入口（v1.1.103 教训，巡检：`SELECT client_type, COUNT(*) FROM sys_role_menu GROUP BY client_type`，期望 PC:APP 约 5–10 倍）。
- 老库 `client_type` 列可能退化（2 列），seed 用 `information_schema` 探测 + IF/PREPARE/EXECUTE 兼容（见 `erp-scan-in-403-app-perm`）。
