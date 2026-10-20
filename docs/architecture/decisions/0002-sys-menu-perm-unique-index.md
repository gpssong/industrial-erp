# 0002. `sys_menu.perms` 加 UNIQUE 索引

- **状态**: 采纳 (Accepted)
- **首次落地**: v1.1.56 hotfix-1
- **关联**: R10 设计原则 / `sys_menu` / 角色权限树

## 背景

`sys_menu` 授权依赖 `perms` 去重。历史灌库用 `INSERT IGNORE` 但 `perms` 列无唯一约束，导致同一 perm 出现 **B（按钮）+ F（接口）两行并存**，前端权限树把同 perm 渲染成两个可勾节点，管理员双勾后保存静默丢失一条。

## 决定

- `sys_menu.perms` 建 UNIQUE 索引（配合软删 `deleted` 列，唯一键含 `deleted`）。
- seed 脚本统一走 `INSERT IGNORE` + `information_schema` 探测，靠唯一键防重。

## 后果

- 灌库幂等：重跑 seed 不产生重复 perm 行。
- 新增 perm 节点时若 `perms` 撞值会直接失败（好过静默双行）。
- 相关踩坑见 `erp-sysmenu-parent-id-trap` memory（F 型 perm 的 parent_id 必须挂在工作台 id=1 下）。
