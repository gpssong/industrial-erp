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
├── app/                           # 移动 App (uni-app + Capacitor)
│   ├── src/pages/
│   │   ├── system/users.vue       # 用户管理 (管理员)
│   │   ├── dashboard/index.vue    # 工作台 (权限过滤)
│   │   ├── profile/index.vue      # 我的 (管理员入口)
│   │   ├── scan/in.vue            # 扫码入库 (原生扫码)
│   │   ├── scan/out.vue           # 扫码出库 (原生扫码)
│   │   └── count/index.vue        # 外勤盘点 (原生扫码)
│   ├── capacitor.config.json      # Capacitor 配置
│   └── android/                   # Android 原生工程
├── sql/                           # MySQL 8.0 数据库 (10 文件, 60+ 表)
│   ├── 01_schema_system.sql       # 含 sys_print_template, sys_backup_record
│   ├── 05_schema_inventory.sql    # 含 inv_ledger (warehouse_name)
│   ├── 10_add_return_menus.sql    # 采购退货/销售退货菜单
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

### 方式 4: Android APK
```bash
cd app
npm install
npm run build:h5              # 构建 H5 资源
npx cap sync android          # 同步到 Android 工程
cd android && ./gradlew assembleDebug  # 构建 debug APK
# 产物: android/app/build/outputs/apk/debug/app-debug.apk
```
前置要求: Java 17+ (推荐 brew install openjdk@17), Android SDK ($ANDROID_HOME)

## 📊 业务模块

### 系统管理 ✅
- 用户/部门/角色/菜单 ✅ 按钮权限/数据范围
- **右上角下拉: 修改密码 / 退出登录** ✅ — 用户自助改密码(校验旧密码, ≥6 位)
- **系统设置 (el-tabs 四标签页)**
  - 系统参数 ✅ — 含**价税分离开关**，影响采购/销售/生产/入库单据的显示
  - **打印模板** ✅ — 支持销售出库/采购入库/生产单，自定义 `{{field}}` 文本插值 + `{{#details}}` 明细循环，实时预览
  - **数据备份** ✅ — 自动备份 + 手动备份 + 90 天前清理
  - **操作日志** ✅ — 含登录日志子 tab; 删除操作存整对象 JSON 快照(主子表), "查看快照"弹窗
- 数据字典

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
- **移动端 App 兼容性修复** — `api/index.js` 增加 H5 浏览器 `fetch` fallback; 扫码/弹窗等 uni-app API 兼容浏览器环境
- **移动端预览补全** — 新增扫码入库/出库、销售订单、采购订单、盘点、报表共 6 个页面, 全部接入真实后端 API
- **底部菜单栏修复** — tabbar 位置错乱/登录页误显示/层级问题

### v1.0.3 (2026-06-21) — 部署包
- **群晖 Web Station 网页端部署包** — 基于 DSM 内置 Web Station + Docker Compose, 零 SSH 全程网页操作 (150 MB)
- **群晖 DS918+ Docker 部署包** — 离线 amd64 镜像 + 一键脚本 (427 MB)
- **官方无架构后缀镜像** — `mysql:8.0` / `redis:7-alpine` / `eclipse-temurin:17-jre`, Docker daemon 自动选架构
- **Windows 桌面客户端** — NSIS 安装包 (74 MB)
- **macOS 桌面客户端** — DMG arm64 + x64 (187 MB)

### v1.0.4 (2026-06-24)
- **销售出库单位选择** — 新增出库单时, 单位列从纯文本改为下拉选择器, 显示商品配置的所有可用单位 (主单位/辅助单位), 默认选主单位
- **中文编码全面修复** — JDBC 连接编码从 `utf8` 改为 `UTF-8`, 解决 MySQL 8.0 下中文双重编码乱码问题
- **数据库 schema 漂移修复** — `sys_print_template` 新增 `template_config` 列 (HTML 模式打印配置); `sys_config` 新增 `PRICE_TAX_SEPARATION` 价税分离开关
- **响应编码强制 UTF-8** — `application.yml` 新增 `server.servlet.encoding.charset=UTF-8 force=true`, 确保所有 API 响应 `Content-Type` 带 `charset=utf-8`
- **CORS 白名单扩展** — 新增 `http://home.93gushi.com:8088` / `http://192.168.0.150:8088` 等群晖反代端口
- **main.js 布局优化** — MainLayout 重构, 支持更灵活的菜单/面包屑/标签页布局
- **request.js 调试增强** — 请求拦截器新增 baseURL / 完整 URL 日志输出, 便于排查 404/403 问题

### v1.0.5 (2026-06-25)
- **销售出库编辑 ID 精度修复** — 移除 `Number()` 转换, 19位雪花ID保持字符串类型, 解决编辑时客户/仓库下拉框显示错误的问题 (JS 安全整数仅 2^53=9007199254740992, 16位以上会丢精度)
- **移动端开单输入框标签** — 销售开单页面数量/单价/备注输入框添加明确标签, 替代仅 placeholder 提示, 提升移动端操作体验

### v1.0.6 (2026-06-25)
- **Android APK 独立打包** — 基于 Capacitor 6 + Gradle 本地构建, 不再依赖 HBuilderX 云打包; 支持原生 BarcodeScanner 扫码
- **移动端用户管理** — 管理员可在 App 端查看/新增/编辑/删除用户, 分配角色, 重置密码
- **移动端权限控制** — 工作台快捷菜单根据用户权限过滤显示; 登录时保存权限/菜单数据
- **PC 端菜单权限** — 左侧菜单栏根据用户权限过滤, 管理员看全部, 普通用户只看有权限的菜单
- **采购退货/销售退货菜单** — 数据库补充 403/503 菜单记录, 修复权限系统缺失
- **扫码精确匹配** — 扫码入库/出库/盘点改为 keyword 搜索 + productCode/barcode 精确匹配, 避免条码不对误匹配商品
- **移动端 Capacitor 适配** — API 地址自动识别 Capacitor 环境使用绝对路径; 网络安全配置允许 HTTP 明文; 原生相机权限授权

### v1.0.7 (2026-07-03)
- **商品字段标签优化** — 表单字段 `厚度/幅宽/密度` 改为 `长度/宽度/厚度`; 三个价格合并为单一 `价格` 字段, 去掉批发价/大客户价展示
- **商品增加克重字段** — `gram_weight` (g/m²), 用于描述薄膜/纸张等商品的克重
- **商品管理增加备注** — 文本域, 最多 500 字
- **PC 商品图片上传** — 后端新增 `/system/upload/file` 接口, 前端商品管理支持多图上传/预览/删除, 表格显示缩略图
- **打印模板规格属性** — 销售出库/采购入库/退货等明细模板增加 `长度/宽度/厚度/克重/材质` 列, 生产加工单模板支持打印规格
- **生产加工单规格显示** — PrdOrderService 注入商品规格属性, 打印时表格展示完整商品信息 (商品/规格/数量/宽度/长度/克重/备注)
- **打印模板 Map 数据支持** — PrintTemplateEngine.getFieldValue 支持 Map 类型字段取值, 兼容伪明细行渲染
- **权限简化模型** — PermissionService.hasPerm 调整为拥有 `:list` 权限即自动获得同模块 `:add`/`:edit`/`:delete` 权限, 简化角色权限分配
- **打印模板字段参考补全** — 生产单类型增加"明细"和"表尾"标签页, 右侧字段列表加入 thickness/width/density/gramWeight/material/remark 字段
- **采购退货/销售退货菜单补全** — 数据库补充 403/503 菜单记录 (SQL 迁移脚本 `10_add_return_menus.sql`)
- **nginx 反代 `/upload/` 路由** — 支持后端静态资源外网访问 (PC 端图片加载)
- **Sa-Token 白名单** — `/upload/**` 和 `/system/upload/**` 加入匿名白名单, 避免被登录拦截

### v1.0.8 (2026-07-04)
- **商品单位价格简化** — 批发价/大客户价合并为 价格+成本价, 数据库 drop 旧列
- **数字字段小数位灵活** — 长度/宽度/厚度/克重/价格/成本价/税率/安全库存/换算率 改用 `el-input type="number"`, 不再强制显示尾随 0
- **克重单位** — 改为 g/个
- **生产加工单支持删除** — 后端 `PrdOrderService.delete()`, 仅允许删除草稿状态单据, 前端操作列新增"删除"按钮
- **App 端商品搜索修复** — 之前用 `stockPage` 查商品 (因无库存记录查不到), 改用 `productPage` 直接查商品表
- **涉及页面**: 手机开单 (`sales/quick.vue`)、扫码入库/出库、盘点 全部统一用 productPage

### v1.0.9 (2026-07-05)
- **生产加工单支持编辑** — 后端新增 `@PutMapping` 更新端点和 `PrdOrderService.update()` 方法 (仅草稿状态可编辑), PC 端 Order.vue 操作列增加"编辑"按钮
- **生产单打印显示 BOM 备注** — PrdOrder 实体新增 `bomRemark` transient 字段, PrdOrderService.detail() 注入 BOM 备注, 打印模板合并显示生产单 + BOM 备注
- **生产单打印去掉数字尾随 0** — prd_order.ftl 模板新增 `<#macro trimNum>` 自定义宏, 格式化 `planQty/actualQty/goodQty/lossQty/lossRate` 等数字字段
- **App 端扫码入库真实提交** — 之前 onSubmit() 只是显示 toast, 现在真正调用 `api.purchaseReceiptAdd()` 创建入库单 (含 supplierId/details/warehouseId)
- **App 端扫码入库供应商选择** — 新增供应商选择器 (从 `/base/supplier/list` 加载, 自建模态弹窗)
- **App 端新增 `/base/product/app-search` 接口** — App 专用商品搜索, 不检查 `base:product:list` 权限 (影响扫描入库/出库/开单/盘点)
- **App 端登录表单清空** — 之前默认填 `admin/admin123`, 现在为空白让用户自己输入
- **App 端 tabBar 改为 4 项** — 工作台 / 库存 / 扫码入库 / 我的
- **App 端缓存修复** — 强制从 localStorage 重新读取用户数据, 避免先登录 admin 再登普通账号显示全部功能的问题
- **App 端功能按 PC 端菜单权限过滤** — 工作台快捷功能从后端 `/me` 返回的 `menus` 动态映射, 管理员看全部, 普通用户看 PC 端分配的菜单
- **Windows 桌面端安装包** — `工业ERP-Setup-1.0.2-Win64.exe` (74 MB, NSIS 安装器, 含 Vue 静态资源)
- **Mac 桌面端安装包** — `工业ERP-1.0.2-Mac-arm64.dmg` (91 MB, Apple Silicon, 含 Vue 静态资源)
- **生产单打印支持 App 端规格属性** — 厚度/宽度/克重/材质/克重统一从 `base_product` JOIN 显示

### v1.1.1 (2026-07-05) — 群晖 NAS 部署热修复
- **CORS 白名单补全** — `http://home.93gushi.com:8088` (Electron 客户端登录 403 修复)
- **数据备份修复** — Dockerfile 安装 `default-mysql-client` 提供 mysqldump; BackupService 增加 `-h/-P` 跨容器连接配置 (`ERP_DB_HOST=mysql` / `ERP_DB_PORT=3306`)
- **v1.0.4 schema 漂移迁移** — 新增 `sql/11_add_template_config.sql` (`sys_print_template.template_config` 列 + `PRICE_TAX_SEPARATION` 配置项) 与 `sql/12_add_gram_weight.sql` (`base_product.gram_weight` 列), 并同步到基线 `01_schema_system.sql` / `02_schema_base.sql`
- **历史双重 UTF-8 乱码修复** — `sql/fix_double_utf8_mojibake.sql` + `scripts/scan_mojibake.sh` 一键生成/批量修复旧数据 (sys_menu / sys_dept / sys_role / sys_user / base_product / base_customer / base_supplier / base_warehouse / pur_order / sal_order), 修复后右上角部门负责人/菜单名等中文显示恢复正常
- **生产单打印规格/备注修复** — `PrintService.renderPrdOrder` 优先取 PrdOrder.spec 快照, 缺则回落到 `PrintDataLoader.findProductSpec` 实时从 `base_product` 取; `printTemplateEngine.isTextField` 白名单 + `isNumericField` 判断, 避免纯数字规格被错误格式化为 `55213341.0000`
- **生产单打印显示 BOM 备注** — `PrintDataLoader.findPrdOrder` JOIN `prd_bom.remark` → PrdOrder.bomRemark; 渲染层 `prdDetail` Map.put("bomRemark", ...); `isTextField` 增加 `f.contains("remark")`, 避免 `13413445.0000` 的错误格式化
- **打印模板编辑面板补全** — `pc-web` 生产单字段参考列表新增 `BOM 备注 (bomRemark)` 可点击插入, 与 `生产单备注 (remark)` 拆分为两个独立条目

### v1.1.2 (2026-07-06) — 操作日志 + 自服务修改密码
- **系统设置 → 操作日志 tab** (含登录日志子 tab)
  - 后端: `SysLoginLog` 实体/Mapper/Controller 完整化(原是返回空分页的空壳), `AuthService.login` 写登录成功/失败日志(含 IP + UA 解析的浏览器/系统)
  - 后端: `OperLogEvent` + `OperLogPublisher` + `OperLogEventListener` 用 ApplicationEventPublisher 实现真正异步写库(原 `@Async` 自身调用不生效已修)
  - 后端: `sys_oper_log` 表加 `snapshot_json LONGTEXT` 字段(ALTER TABLE 已跑), 删除前存整对象 + 子表完整 JSON
  - 后端: 17 个 `service.delete()` 全部改 `mapper.update set deleted=1` 软删除 + `@Transactional` + 写快照, 顺便修 `SalOrderService.delete` 不级联删 detail 的 bug
  - 前端: `OperationLog.vue` 双 tab (操作日志 + 登录日志) + JSON 弹窗 + 清理 90 天前按钮
- **右上角"修改密码"按钮**
  - 后端: `PUT /api/system/user/me/password` — 用户改自己密码, 校验旧密码, 强校验"新密码不能与原密码相同"
  - 前端: `MainLayout.vue` dropdown 加 🔑 修改密码项(在退出登录之上), 弹窗输入, 改成功后强制登出重新登录
- **ResizeObserver 警告静默** — `pc-web/src/main.js` 全局过滤 `console.error` + `window.onerror` 里包含 "ResizeObserver loop" 的消息(无害但污染 console)
- **安全加固**: `application.yml` 移除 `erp_root_pwd` 默认兜底, CORS 默认仅 localhost; `docker-compose.yml` 用 `:?` 必填语法; `backup.sh`/`restore.sh` 从 `.env` 读 `MYSQL_ROOT_PASSWORD`(不再硬编码)
- **NAS 清理**: 释放 2.6GB 孤儿镜像 + 1.62GB 构建缓存, 删孤儿容器 `vibrant_euler` / `epic_benz`
- **前端文档**: `docs/21_操作日志与登录日志功能.md` + `docs/22_自服务修改密码与前端调优.md`

### v1.1.6 (2026-07-06) — 数字自动去尾 + 打印修复

**全局数字去尾 0**
- 33 处 `<el-input-number>` 加 `:step-strictly="false"`,用户输入 1.5 → 显示 1.5(不显示 1.5000)
- 不改数据库精度,只改显示行为,影响 13 个 view 文件

**生产单表单去尾(深一层修复)**
- `:step-strictly="false"` 对装载值不生效,EP 装载 v-model 时仍按 precision=N 显示
- 改用 EP 的 `:formatter` + `:parser` hook 接管显示,真正去尾 0
- planQty 740 → 显示 740(不是 740.0000);lossRate 0 → 显示 0(不是 0.00)

**打印模板数字去尾**
- 后端 `PrintTemplateEngine.fmtNum` 之前 `setScale(4)` 强制 4 位 → "740.0000"
- 改为 setScale(4) 后追加 `stripTrailingZeros() + toPlainString()`
- 740 → "740", 31.4 → "31.4", 避免科学计数法

**生产单打印显示商品主单位**
- 模板里 `{{unitName}}` 之前从 PrdOrder.unitName 取,新建场景可能空
- PrdOrderService.renderPrdOrder 注入 `unitName` + `mainUnitName`
- 优先 PrdOrder.unitName,缺则查 `BaseProductUnitMapper.selectMainUnit(productId)`(is_main=1)

**打印预览自动唤起打印对话框**
- 后端 `PrintTemplateEngine.buildFromTemplate` 在 `</body>` 前注入:
  `<script>window.addEventListener('load',function(){setTimeout(function(){try{window.print()}catch(e){}},300)});</script>`
- 用户点"打印" → 新窗口打开 → 自动弹出系统打印机选择对话框

### v1.1.5 (2026-07-06) — UI 简化 + 打印鉴权兼容 + EP 3.0 兼容

**UI 简化 (按用户反馈)**
- 客户管理: 删除"类型"和"价格等级"两列 + 表单"价格等级"选择器
- 供应商管理: 删除"类型"列
- 仓库管理: 删除"类型"列 + 表单"类型"选择器(原材料/半成品/成品/普通)
- 后端实体/数据库字段保留,数据兼容

**打印鉴权兼容 (修复 401)**
- `pc-web/src/composables/usePrintUrl.js` 把 query 参数从 `?token=` 改成 `?Authorization=`
- Sa-Token `is-read-query: true` 模式下,query 参数名必须等于 `tokenName` (=Authorization),否则 401
- 实测对比: `?token=xxx` → 401, `?Authorization=xxx` → 200

**Element Plus 3.0 兼容**
- 5 个文件 15 处 `el-radio` 的 `label=` 写法改为 `value=`
- 涉及: PageTemplate.vue / Dept.vue / User.vue / Role.vue / Menu.vue (含静态 `label="M"`/`label="B"`)
- `el-option :label=` 保留(语义不同,EP 不废弃)
- 消除 console 红色 deprecation 警告

### v1.1.4 (2026-07-06) — 操作日志补全

**根因**
- v1.1.2 引入的 `OperLogAspect` 通过 `@annotation(@OperLog)` 截获,但业务 Service 没有任何方法标了 `@OperLog`,所以切面从未触发,`sys_oper_log` 仅有 3 条删除日志(`publishDeleteSnapshot` 手动发布)。登录日志 (`AuthService.recordLogin`) 写库正常,与乱码无关。

**修复**
- 全量扫描 17 个业务 Service,给 `add` / `update` / `edit` / `save` / `import` / `assign` 等写操作批量加 `@OperLog(module="...", businessType="ADD" 或 "EDIT")`
- 跳过 `delete` (已有 `publishDeleteSnapshot`) 和 `updatePassword` / `changeOwnPassword` / `resetPassword` (敏感)
- 覆盖模块: 商品/客户/供应商/仓库/单位/采购订单/采购入库/采购退货/销售订单/销售出库/销售退货/库存调拨/库存盘点/生产订单/BOM/领料/成品入库/应收应付/外协加工
- 写操作日志现在能正常累积,删除操作依然带完整 JSON 快照

**升级**
- 仅代码改动,无 SQL 迁移。重新构建后端 jar,重启容器即可

### v1.1.3 (2026-07-06) — 安全加固 + 健壮性修复

**接口鉴权 (CVE 级)**
- `/print/**` — 移除 SaToken 白名单, 类级 `@SaCheckPermission("print:use")`, 匿名访问拿不到单据 HTML
- `/report/**` — 类级 `@SaCheckPermission("report:view")`, dashboard/salesSummary 等经营数据需授权
- `/system/upload/file` — 类级 `@SaCheckLogin` + 后缀白名单移除 `.jsp`/`.html`/`.htm`/`.svg`(XSS 载荷 + 未鉴权上传)
- `/system/backup/{manual,restore,delete}` — `@SaCheckRole("admin")`, 仅 admin 可恢复数据库; `page/list` 改为 `@SaCheckLogin`

**事务补全**
- `PurReceiptService.check()` 加 `@Transactional(rollbackFor = Exception.class)` — 4 写无事务, 中途挂会库存已增但订单未收
- `FinArapController.cash` 加 `@Transactional(rollbackFor = Exception.class)` — 收付款 + 现金流水须同成功同失败

**NPE 防御 + 日志**
- `PrdOrderService:279` + `OutsourceService:92, 143` — `wh.getWarehouseName()` 加 null 保护(成品入库仓库被删不会崩)
- `AuthService:90` — `Integer.parseInt(failCount)` 包 try-catch, Redis 注入非数字不会阻塞登录
- `PrintDataLoader` — `catch (Exception ignore) {}` 改 `log.warn(..., e)`, 数据缺失不再静默

**前端健壮性 (13 处)**
- `request.js` — 删生产环境 `console.log` 噪音; 非 JSON 响应(502 HTML 页)不崩; 401 跳 `/login?redirect=<原路径>`, 登录后跳回; 网络错误文案按 `err.code` 区分
- `Login.vue` — 登录成功读 `redirect` query 跳回原页
- `MainLayout.vue` — avatar fallback `'U'` → `'用户'`
- 8 处 catch 改为 `ElMessage.error(e.message || ...)` 不再吞错误
- 11 处删除/保存加 `try/catch` 用户感知到失败

**CORS 默认域补全 (前置提交 bfc3a87)**
- `docker-compose.yml` `backend.environment` 新增 `ERP_CORS_ALLOWED_ORIGINS` 声明, 默认值含 `http://home.93gushi.com:8088` 等生产域
- `request.js` 拦截器对 HTTP 4xx/5xx 多打 `[HTTP_ERR] status url | code | msg`, 便于排查"Failed to load resource"类纯浏览器原生错误

**升级注意**
- **必须** 跑 `sql/seed/14_v113_permissions.sql` seed SQL — 给 admin 自动勾 `print:use` / `report:view`, 否则现有 admin 会失去打印/报表权限
- seed 给所有内置业务角色 (SUPER_ADMIN / PURCHASE_MGR / SALES_MGR / WAREHOUSE_MGR / PRODUCTION_MGR / FINANCE) 自动绑定新权限
- 升级后建议 admin 在 角色管理 review 自定义角色

**部署文档**
- `sql/seed/14_v113_permissions.sql` — 权限 seed
- `docs/20_v111_部署热修复专题.md` — 第 8 章 v1.1.3 安全与事务修复 (新增)

## 🔒 安全
- Sa-Token (JWT) + Redis 分布式会话
- 菜单/按钮/数据范围三级权限 (SCOPE_ALL / SCOPE_DEPT_SUB / SCOPE_DEPT / SCOPE_SELF)
- PC 端 + App 端菜单权限过滤 (管理员看全部, 普通用户按角色过滤)
- App 端用户管理 (仅管理员可见, 支持增删改查 + 角色分配 + 密码重置)
- 全部操作写 `sys_oper_log` (AOP自动)

## 📈 性能
- 库存查询走 `(warehouse_id, product_id, batch_no)` 唯一索引
- 分页最大500，10w+数据 < 100ms
- 字典/配置走 Redis 缓存 (30分钟TTL)
- 并发 100+ 无锁竞争 (Redis Lock 5s等待)
