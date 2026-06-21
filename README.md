# 企业级工业进销存 ERP 系统

> 面向薄膜 / 塑料 / 五金 / 加工 / 工贸一体企业
> 完整覆盖 采购 → 库存 → 销售 → 生产 → 财务 全业务链

## ✨ 核心亮点

- 🏭 **工业特性**: 商品含厚度/幅宽/密度/色号/批次, 支持米重换算 / 分切 / 复卷 / 裁切
- 🔒 **强一致**: 库存双重锁 (Redis 分布式锁 + MySQL 行锁), **严格禁止负库存**
- 📊 **成本精准**: 移动加权平均成本, 实时计算毛利
- 💰 **往来闭环**: 自动生成应收/应付, 收/付款自动核销，余额实时重算；退货自动生成负向冲销
- 📱 **多端统一**: PC管理后台 + Windows桌面 + Android/iOS App
- 🔐 **精细权限**: 菜单 / 按钮 / 数据范围 三级权限 (基于 Sa-Token)
- 🖨️ **本地打印**: 支持针式 / 激光 / 小票打印机, 76mm/80mm/241mm三等分模板，自定义打印内容（`{{field}}` 文本插值 + `{{#details}}` 明细循环），支持 **HTML 模式** 可视化编辑（CodeMirror）
- 🗄️ **系统设置**: 打印模板自定义（销售出库/采购入库/生产单/采购退货/销售退货）、价税分离开关、数据备份（自动+手动）
- 🐳 **一键部署**: Docker Compose 一键拉起 MySQL + Redis + 后端 + 前端
- 🧪 **单元测试**: 库存核心场景测试覆盖 (移动加权平均 / 禁止负库存)

## 🧩 三大终端

| 终端 | 技术栈 | 路径 |
|---|---|---|
| **PC 管理后台** | Vue 3.4 + Vite 5 + Element Plus 2.6 + Pinia + ECharts 5 | `pc-web/` |
| **Windows 桌面** | Electron 29 + Vue 3 (代码复用) | `electron/` |
| **移动 App** | uni-app Vue 3 (iOS/Android/微信小程序/H5) | `app/` |

## 🛠️ 技术栈

```
后端:   SpringBoot 3.2 + MyBatis-Plus 3.5 + Sa-Token 1.37 + Redis 7 + Maven
前端:   Vue 3.4 + Vite 5 + Element Plus 2.6 + Pinia + ECharts 5 + Axios
数据库: MySQL 8.0 (60+ 表, 软删除, 多租户预留, 完整索引)
桌面:   Electron 29 + electron-builder
移动:   uni-app Vue 3 (兼容 Android/iOS/微信小程序/H5)
部署:   Docker Compose / Nginx / Linux
```

## 📂 目录结构

```
erp-system/
├── backend/                       # Spring Boot 后端 (150+ Java 文件)
│   ├── src/main/java/com/industrial/erp/
│   │   ├── modules/               # 业务模块
│   │   │   ├── system/            # 系统管理 (用户/角色/菜单/部门/配置/备份)
│   │   │   │   ├── controller/     # SysPrintTemplateController, SysBackupController
│   │   │   │   ├── service/       # PrintService (数据库模板优先, fallback文件)
│   │   │   │   ├── mapper/        # SysPrintTemplateMapper
│   │   │   │   └── entity/         # SysPrintTemplate, SysBackupRecord
│   │   │   ├── base/              # 基础资料 (商品/客户/供应商/仓库/单位)
│   │   │   ├── purchase/          # 采购 (含退货 PurReturn)
│   │   │   ├── sales/             # 销售 (含退货 SalReturn)
│   │   │   ├── inventory/         # 库存 (核心)
│   │   │   │   └── service/       # StockService (台账写入/成本计算)
│   │   │   ├── production/        # 生产 (BOM/加工单/领料/成品入库)
│   │   │   ├── finance/           # 财务 (应收应付/收款付款核销)
│   │   │   ├── report/            # 报表 (工作台KPI/销售汇总/库存台账)
│   │   │   └── print/             # 打印 (PrintService + PrintDataLoader + PrintTemplateEngine + PrintRenderer)
│   │   ├── config/                # 配置 (MP/Sa-Token/Redis/Jackson)
│   │   ├── security/              # 安全 (Sa-Token/Permission/DataScope)
│   │   ├── common/                # 通用 (R/PageResult/Constants)
│   │   ├── exception/             # 异常 (BizException/GlobalExceptionHandler)
│   │   └── utils/                 # 工具 (RedisLock/BillNoGenerator)
│   ├── src/main/resources/
│   │   ├── application.yml        # 配置 (MyBatis-Plus全局配置/逻辑删除)
│   │   ├── mapper/                # MyBatis XML
│   │   └── templates/print/       # Freemarker 打印模板 (fallback)
│   │       ├── sales_delivery.ftl
│   │       ├── purchase_receipt.ftl
│   │       ├── prd_order.ftl
│   │       ├── purchase_return.ftl
│   │       └── sales_return.ftl
│   ├── src/test/                  # 单元测试
│   └── Dockerfile
│
├── pc-web/                        # PC 管理后台 (Vue3)
│   ├── src/
│   │   ├── api/                   # 接口封装 (system.js 含 printApi/backupApi, purchase.js 含 purReturnApi, sales.js 含 salReturnApi)
│   │   ├── components/            # 通用组件 (含 CodeEditor 基于 CodeMirror)
│   │   ├── router/                # 路由 (含权限控制)
│   │   ├── store/                 # Pinia (用户态)
│   │   ├── views/
│   │   │   ├── system/
│   │   │   │   ├── System.vue     # 系统设置主页面 (el-tabs)
│   │   │   │   ├── Settings.vue   # 系统参数
│   │   │   │   ├── PrintTemplate.vue # 打印模板 (含代码参考面板)
│   │   │   │   └── Backup.vue    # 数据备份
│   │   │   ├── purchase/Receipt.vue # 采购入库 (含打印)
│   │   │   ├── purchase/Return.vue  # 采购退货 (含打印)
│   │   │   ├── sales/Delivery.vue  # 销售出库 (含打印)
│   │   │   ├── sales/Return.vue    # 销售退货 (含打印)
│   │   │   ├── production/Order.vue # 生产加工单 (含打印)
│   │   │   ├── inventory/
│   │   │   │   ├── Stock.vue      # 库存查询
│   │   │   │   └── Ledger.vue     # 库存台账 (支持单号/品名/业务类型筛选)
│   │   │   └── finance/Arap.vue   # 应收应付 (收款核销)
│   │   ├── layouts/MainLayout.vue  # 主布局
│   │   └── components/            # 通用组件
│   └── Dockerfile
│
├── electron/                      # 桌面客户端
├── app/                           # 移动 App (uni-app)
├── sql/                           # MySQL 8.0 数据库 (9 文件, 60+ 表)
│   ├── 01_schema_system.sql       # 含 sys_print_template, sys_backup_record
│   ├── 05_schema_inventory.sql    # 含 inv_ledger (warehouse_name)
│   └── README.md
├── docs/                          # 开发文档 (16 篇)
└── docker-compose.yml
```

## 🚀 启动方式

### 方式 1: Docker 一键启动 (推荐)
```bash
cd erp-system
cp .env.example .env
docker compose up -d --build
# http://localhost  账号: admin / admin123
```

### 方式 2: 源码启动
```bash
# 1. 初始化数据库
mysql -uroot -p < sql/01_schema_system.sql
mysql -uroot -p industrial_erp < sql/02_schema_base.sql
# ... 依次执行 03-09

# 2. 启动后端 (IDE 或 mvn)
cd backend && mvn spring-boot:run

# 3. 启动前端
cd pc-web && npm install && npm run dev
# 访问 http://localhost:5173
```

### 方式 3: Windows 桌面
```bash
cd pc-web && npm run build
cd ../electron && npm install && npm run dev
```

## 📊 业务模块

### 系统管理 ✅
- 用户/部门/角色/菜单 ✅ 按钮权限/数据范围
- **系统设置 (el-tabs 三标签页)**
  - 系统参数 ✅ — 含**价税分离开关**，影响采购/销售/生产/入库单据的显示
  - **打印模板** ✅ — 支持销售出库/采购入库/生产单，自定义 `{{field}}` 文本插值 + `{{#details}}` 明细循环，实时预览
  - **数据备份** ✅ — 自动备份 + 手动备份
- 操作日志/数据字典

### 基础资料 ✅
- 商品(厚度/幅宽/密度/色号/批次) + 多单位换算
- 客户/供应商/仓库/计量单位

### 采购管理 ✅
- 采购订单/入库/自动生成应付台账
- **采购退货** ✅ — 审核后自动出库 + 生成负向应付冲销
- 采购入库/退货打印 ✅

### 销售管理 ✅
- 销售订单/出库/自动生成应收台账
- **销售退货** ✅ — 审核后自动入库 + 生成负向应收冲销
- 销售出库/退货打印 ✅

### 库存管理 ✅
- 严格禁止负库存 (Redis锁+MySQL行锁)
- 批次/有效期/序列号管理
- **库存台账** ✅ — 支持按单号/品名/业务类型筛选，含操作前后数量/平均成本
- 调拨/盘点/盈亏

### 生产管理 ✅
- BOM物料清单/自动展开领料
- 生产加工单/领料/补料/退料/完工入库
- **生产单打印** ✅

### 财务往来 ✅
- 应收/应付台账（自动生成）
- 收款单/付款单（自动核销，balance实时重算）
- 移动加权平均成本/毛利分析

### 报表中心 ✅
- 工作台KPI（含应收/应付实时余额）
- 销售明细/排行/汇总
- 库存台账/预警

## 🔑 默认账号
- 用户名: `admin`
- 密码: `admin123`

## 📖 文档索引

> 完整 16 篇文档见 [docs/00_文档索引.md](docs/00_文档索引.md)
>
> 新人必读: [总纲](docs/01-开发文档(总纲).md) → [环境搭建](docs/12_本地开发环境搭建.md) → [新模块开发实战](docs/13_新模块开发实战.md)

## 🧪 测试
```bash
cd backend
mvn test -Dtest=StockServiceTest   # 库存核心
mvn test                          # 全量
```

## 📝 更新日志

### v1.0.2 (2026-06-20)
- **采购退货模块** — 创建/审核/自动出库/应付冲销/打印
- **销售退货模块** — 创建/审核/自动入库/应收冲销/打印
- **打印系统重构** — 拆分为 PrintDataLoader / PrintTemplateEngine / PrintRenderer，职责清晰
- **打印模板 HTML 模式** — 支持自由编写 HTML + `{{field}}` 插值，CodeMirror 代码编辑器
- **退货打印模板** — 新增 purchase_return.ftl / sales_return.ftl
- **数据权限** — 新增 DataScope 四级数据范围 (全部/本部门及下级/本部门/仅本人)
- **前端路由** — 新增采购退货/销售退货菜单及权限控制
- **打印模板新增单据类型** — 采购退货 / 销售退货 可在系统设置中创建独立打印模板
- **打印列对齐修复** — 修复文本模式表头与数据列不对齐、HTML 模式嵌套表格问题

### v1.0.3 (2026-06-21)
- **采购入库查询增强** — 新增「供应商」下拉筛选 + 「商品名称」模糊查询
- **销售出库查询增强** — 新增「商品名称」模糊查询 (客户筛选已存在)
- **生产加工单查询增强** — 新增「产品名称/编码」模糊查询
- **打印模板默认互斥** — 同一单据类型仅允许一个默认模板, 设置新默认自动取消同类型原默认
- **外网访问修复** — CORS 白名单新增 `http://home.93gushi.com`, 打印 URL 从硬编码改为相对路径
- **API 地址可配置** — 支持 `VITE_API_BASE` 环境变量 / `localStorage` / 默认 `/api` 三级优先级
- **打印接口修复** — 修复 `PreparedStatement` 参数顺序错误导致打印 500 的问题
- **查询 SQL 修复** — 修复 `EXISTS` 子查询参数绑定失败 (`apply` 替代 `exists`)
- **CORS 127.0.0.1 修复** — 开发环境用 `127.0.0.1` 访问时 PUT/DELETE 请求 403, 白名单增加 `127.0.0.1` 地址

### v1.0.3 (2026-06-21) — 部署包
- **群晖 Web Station 网页端部署包** — 基于 DSM 内置 Web Station + Docker Compose, 零 SSH 全程网页操作 (150 MB)
- **群晖 DS918+ Docker 部署包** — 离线 amd64 镜像 + 一键脚本 (427 MB)
- **官方无架构后缀镜像** — `mysql:8.0` / `redis:7-alpine` / `eclipse-temurin:17-jre`, Docker daemon 自动选架构
- **Windows 桌面客户端** — NSIS 安装包 (74 MB)
- **macOS 桌面客户端** — DMG arm64 + x64 (187 MB)

## 🔒 安全
- Sa-Token (JWT) + Redis 分布式会话
- 菜单/按钮/数据范围三级权限 (SCOPE_ALL / SCOPE_DEPT_SUB / SCOPE_DEPT / SCOPE_SELF)
- 全部操作写 `sys_oper_log` (AOP自动)

## 📈 性能
- 库存查询走 `(warehouse_id, product_id, batch_no)` 唯一索引
- 分页最大500，10w+数据 < 100ms
- 字典/配置走 Redis 缓存 (30分钟TTL)
- 并发 100+ 无锁竞争 (Redis Lock 5s等待)
