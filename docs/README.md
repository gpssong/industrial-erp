# 文档总览

| 文档 | 说明 |
|---|---|
| [01_业务逻辑说明.md](01_业务逻辑说明.md) | 库存/成本/生产/往来 核心业务详解 |
| [02_部署方案.md](02_部署方案.md) | 生产部署 / Nginx / 监控 / 升级 |
| [03_API接口文档.md](03_API接口文档.md) | 全部 REST 接口列表 |

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
1. 初始化数据库: `mysql < sql/*.sql`
2. 启动后端: `cd backend && mvn spring-boot:run`
3. 启动前端: `cd pc-web && npm install && npm run dev`
4. 浏览器打开: http://localhost:5173, 账号 admin / admin123
