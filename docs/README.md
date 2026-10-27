# 文档总览

完整目录与阅读路径见 [00_文档索引.md](00_文档索引.md)。

| 文档 | 说明 |
|---|---|
| [00_文档索引.md](00_文档索引.md) | 全部文档目录 / 阅读路径 / 文档版本 |
| [01_业务逻辑说明.md](01_业务逻辑说明.md) | 库存/成本/生产/往来 核心业务详解 |
| [02_部署方案.md](02_部署方案.md) | 生产部署 / Nginx / 监控 / 升级 |
| [03_API接口文档.md](03_API接口文档.md) | 全部 REST 接口列表 |
| [26_运行手册.md](26_运行手册.md) | 当前拓扑 / 巡检 / 发布回滚 / 备份恢复 / 主备切换 |
| [CHANGELOG.md](CHANGELOG.md) | 版本变更历史 (倒序, 当前 v1.1.129) |
| [security.md](security.md) | 安全基线与加固清单 |

## 项目目录
```
erp-system/
├── backend/    # SpringBoot 后端
├── pc-web/     # Vue3 + Vite + Element Plus PC 后台
├── electron/   # Electron 桌面客户端
├── app/        # uni-app 移动端 (Android/iOS/小程序)
├── sql/        # MySQL 数据库 DDL
└── docs/       # 文档
```

## 快速开始
1. 初始化数据库: 按编号顺序执行 `sql/*.sql`
2. 启动后端: `cd backend && mvn spring-boot:run`
3. 启动前端: `cd pc-web && npm install && npm run dev`
4. 浏览器打开: http://localhost:5173

登录说明: 初始账号来自 `sql/` 种子数据; v1.1.128 起首次登录强制修改默认密码, 生产账号由运维分配 (见 [26_运行手册.md](26_运行手册.md) 第二节)。
