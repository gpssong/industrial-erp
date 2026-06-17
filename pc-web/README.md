# 企业级工业 ERP - PC 管理后台 (Vue3 + Vite + Element Plus)

## 技术栈
- Vue 3.4 / Vite 5
- Element Plus 2.6
- Pinia (状态)
- Vue Router 4
- Axios + Sa-Token (JWT)
- ECharts 5
- NProgress (进度条)

## 启动
```bash
npm install
npm run dev   # 开发, http://localhost:5173
npm run build # 生产构建
```

## 目录结构
```
src/
├── api/          # 接口封装 (按模块)
├── router/       # 路由
├── store/        # Pinia 状态
├── views/        # 页面
│   ├── Login.vue
│   ├── dashboard/        # 工作台
│   ├── system/           # 系统管理
│   ├── base/             # 基础资料
│   ├── purchase/         # 采购
│   ├── sales/            # 销售
│   ├── inventory/        # 库存
│   ├── production/       # 生产
│   ├── finance/          # 财务
│   └── report/           # 报表
├── layouts/      # 布局
├── components/   # 通用组件
├── styles/       # 样式
└── utils/        # 工具
```

## 默认账号
- admin / admin123

## 代理
开发环境已配置 `/api` -> `http://localhost:8080`
