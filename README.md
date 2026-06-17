# 企业级工业进销存 ERP 系统

> 面向薄膜 / 塑料 / 五金 / 加工 / 工贸一体企业
> 完整覆盖 采购 → 库存 → 销售 → 生产 → 财务 全业务链

## ✨ 核心亮点

- 🏭 **工业特性**: 商品含厚度/幅宽/密度/色号/批次, 支持米重换算 / 分切 / 复卷 / 裁切
- 🔒 **强一致**: 库存双重锁 (Redis 分布式锁 + MySQL 行锁), **严格禁止负库存**
- 📊 **成本精准**: 移动加权平均成本, 实时计算毛利
- 💰 **往来闭环**: 自动生成应收/应付, 收/付款自动核销
- 📱 **多端统一**: PC管理后台 + Windows桌面 + Android/iOS App
- 🔐 **精细权限**: 菜单 / 按钮 / 数据范围 三级权限 (基于 Sa-Token)
- 🖨️ **本地打印**: 支持针式 / 激光 / 小票打印机, 80mm/58mm 模板
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
├── backend/                       # Spring Boot 后端 (149 Java 文件)
│   ├── src/main/java/com/industrial/erp/
│   │   ├── modules/               # 业务模块
│   │   │   ├── system/            # 系统管理 (用户/角色/菜单/部门/日志/字典)
│   │   │   ├── base/              # 基础资料 (商品/客户/供应商/仓库/单位)
│   │   │   ├── purchase/          # 采购
│   │   │   ├── sales/             # 销售
│   │   │   ├── inventory/         # 库存 (核心)
│   │   │   ├── production/        # 生产 (BOM/加工单/领料/成品入库)
│   │   │   ├── outsource/         # 委外
│   │   │   ├── finance/           # 财务
│   │   │   ├── report/            # 报表
│   │   │   └── print/             # 打印
│   │   ├── config/                # 配置 (MP/Sa-Token/Redis/OpenAPI)
│   │   ├── security/              # 安全 (Sa-Token/Permission)
│   │   ├── common/                # 通用 (R/PageResult/Constants)
│   │   ├── exception/             # 异常 (BizException/GlobalExceptionHandler)
│   │   └── utils/                 # 工具 (RedisLock/BillNoGenerator)
│   ├── src/main/resources/
│   │   ├── application.yml        # 配置
│   │   ├── mapper/                # MyBatis XML
│   │   └── templates/print/       # Freemarker 打印模板
│   ├── src/test/                  # 单元测试
│   └── Dockerfile
│
├── pc-web/                        # PC 管理后台 (Vue3)
│   ├── src/
│   │   ├── api/                   # 接口封装
│   │   ├── router/                # 路由 (含权限控制)
│   │   ├── store/                 # Pinia (用户态)
│   │   ├── views/                 # 页面 (按业务模块)
│   │   ├── layouts/MainLayout.vue # 主布局
│   │   └── components/            # 通用组件
│   ├── nginx.conf                 # Nginx 配置
│   └── Dockerfile
│
├── electron/                      # 桌面客户端
│   ├── src/main.js                # 主进程 (窗口/菜单/快捷键/打印)
│   ├── src/preload.js             # 预加载
│   └── package.json
│
├── app/                           # 移动 App (uni-app)
│   ├── pages/                     # 11 个页面
│   ├── api/                       # 接口
│   ├── manifest.json              # 打包配置
│   └── pages.json                 # 路由
│
├── sql/                           # MySQL 8.0 数据库 (9 文件, 60+ 表)
│   ├── 01_schema_system.sql
│   ├── 02_schema_base.sql         # 工业版 (厚度/幅宽/密度/批次)
│   ├── 03_schema_purchase.sql
│   ├── 04_schema_sales.sql
│   ├── 05_schema_inventory.sql    # 核心 (含行锁字段)
│   ├── 06_schema_production.sql
│   ├── 07_schema_outsource_finance.sql
│   ├── 08_schema_misc.sql
│   ├── 09_seed_data.sql
│   └── README.md                  # ER 图 + 表说明
│
├── docs/                          # 完整开发文档 (12 篇)
│   ├── 00_文档索引.md             # 文档导航 (从这开始)
│   ├── 01-开发文档(总纲).md        # 总览/架构/技术栈/业务全景
│   ├── 02-数据库设计.md            # ER 图/表结构/索引
│   ├── 03-API接口设计规范.md        # RESTful/统一返回/鉴权
│   ├── 01_业务逻辑说明.md          # 库存/成本/生产/往来 详解
│   ├── 02_部署方案.md              # Docker/Nginx/监控
│   ├── 03_API接口文档.md           # 全部 REST 接口
│   ├── 04_行业特性.md              # 薄膜/塑料/五金/加工
│   ├── 10_后端开发指南.md          # 后端代码规范/单测/Swagger
│   ├── 11_前端开发指南.md          # Vue3/Pinia/ECharts/打包
│   ├── 12_本地开发环境搭建.md      # Mac/Linux/Windows 环境
│   ├── 13_新模块开发实战.md        # 运输模块: SQL→前后端→上线
│   ├── 14_测试与调试指南.md        # 单测/接口/并发/常见 Bug
│   ├── 15_Git与协作规范.md         # 分支/Commit/Code Review
│   └── 16_常见问题FAQ.md           # 部署/启动/性能/业务
│
├── scripts/
│   └── start.sh                   # 一键启动
├── docker-compose.yml             # 容器编排
└── .env.example
```

## 🚀 三种启动方式

### 方式 1: 一键 Docker 启动 (推荐)
```bash
cd erp-system
cp .env.example .env
docker compose up -d --build
# 启动完成后: http://localhost
# 账号: admin / admin123
```

### 方式 2: 源码启动
```bash
# 1. 初始化数据库
mysql -uroot -p < sql/01_schema_system.sql
mysql -uroot -p industrial_erp < sql/02_schema_base.sql
# ... 依次 03-09

# 2. 启动后端
cd backend && mvn spring-boot:run

# 3. 启动前端 (新终端)
cd pc-web && npm install && npm run dev
# 访问 http://localhost:5173
```

### 方式 3: 桌面客户端
```bash
cd pc-web && npm run build
cd ../electron && npm install
npm run dev          # 开发
npm run build:win    # 打包 Windows
```

## 📊 完整业务模块 (12 大模块, 60+ 业务单据)

### 1. 系统管理
✅ 用户/部门/角色/菜单  ✅ 按钮权限/数据范围  
✅ 操作日志/登录日志  ✅ 系统配置/打印模板  
✅ 数据备份(自动+手动)  ✅ 数据字典  ✅ 审计字段

### 2. 基础资料 (工业版)
✅ 商品(厚度/幅宽/密度/色号/批次)  
✅ 多单位自动换算(卷/米/公斤/张/件/千克)  
✅ 客户/供应商/税率/价格等级(4级)  
✅ 仓库/库区/库位  ✅ 计量单位管理

### 3. 采购管理
✅ 采购订单/入库/退货/询价  
✅ 预付/货到付款/票到付款  
✅ 含税/不含税自动计算  
✅ 自动生成应付台账  
✅ 价格/数量/批次/序列号管理

### 4. 销售管理
✅ 销售订单/出库/退货/报价  
✅ 客户信用额度控制 (超额度禁止开单)  
✅ 多级售价(批发/零售/大客户/经销商)  
✅ 抹零/整单折扣  ✅ 扫码开单  
✅ 自动生成应收台账 + 实时计算毛利

### 5. 库存管理 (核心 - 工业版)
✅ 严格禁止负库存 (Redis锁 + MySQL行锁)  
✅ 批次/生产日期/有效期/序列号  
✅ 库存台账/明细/预警  
✅ 调拨/盘点/盈亏  ✅ 米重换算  
✅ 分切/复卷/裁切  ✅ 呆滞库存分析

### 6. 生产管理 (工业版)
✅ BOM 物料清单(主料/辅料/包材)  
✅ 自动按 BOM 展开领料单(损耗率)  
✅ 生产加工单/领料/补料/退料  
✅ 工序记录/损耗统计  
✅ 成本自动归集(良品分摊)

### 7. 委外加工
✅ 委外发料(原料发给外协厂)  
✅ 委外入库(成品收回,加工费结算)  
✅ 外协厂对账(供应商维护 isOutsource)

### 8. 财务往来
✅ 应收/应付台账(自动生成)  
✅ 收款单/付款单/转账/预收/预付  
✅ 往来对账/对账单  
✅ 应收应付预警  
✅ 移动加权平均成本核算  
✅ 毛利分析(按月/按客户)

### 9. 单据打印与导出
✅ 入库单/出库单/送货单/对账单/盘点单  
✅ 针式/激光/小票打印机(80mm/58mm)  
✅ Freemarker 模板引擎(数据库存储)  
✅ 批量导出 Excel / PDF

### 10. 报表中心
✅ 销售明细/排行/汇总  ✅ 库存明细/汇总/账龄  
✅ 应收应付台账/收支明细  ✅ 经营报表  
✅ 数据可视化大屏(ECharts)  
✅ 经营日报(`rpt_daily_snapshot`)

### 11. Windows 桌面端 (Electron)
✅ 本地高速打印(系统打印机)  
✅ 离线开单(IndexedDB 缓存)  
✅ 开机自启  ✅ 快捷键 (F8/F9/F10)  
✅ 美萍风格操作  ✅ 多窗口管理

### 12. 移动 App (uni-app)
✅ 手机开单(快速销售出库)  
✅ 查库存/查价格  ✅ 外勤盘点  
✅ 扫码入库/出库 (uni.scanCode)  
✅ 客户下单/订单查询  ✅ 库存预警推送  
✅ 兼容 iOS/Android/微信小程序/H5

## 🔑 默认账号

- 用户名: `admin`
- 密码: `admin123`

## 📖 文档

> 📚 **完整 16 篇文档导航见 [docs/00_文档索引.md](docs/00_文档索引.md)**
>
> 新人必读路径: [总纲](docs/01-开发文档(总纲).md) → [环境搭建](docs/12_本地开发环境搭建.md) → [后端指南](docs/10_后端开发指南.md) / [前端指南](docs/11_前端开发指南.md) → [模块开发实战](docs/13_新模块开发实战.md)

### 架构 / 业务
- [总纲 - 架构与技术栈](docs/01-开发文档(总纲).md) ⭐ 新人必读
- [数据库设计 (ER 图 + 60+ 表)](docs/02-数据库设计.md)
- [API 接口设计规范](docs/03-API接口设计规范.md)
- [业务逻辑说明 (库存/成本/生产/往来)](docs/01_业务逻辑说明.md)
- [行业特性 (薄膜/塑料/五金/加工)](docs/04_行业特性.md)

### 部署 / 运维
- [部署方案 (Docker / Nginx / 监控)](docs/02_部署方案.md)
- [常见问题 FAQ](docs/16_常见问题FAQ.md)

### API 文档
- [完整 REST 接口文档](docs/03_API接口文档.md)
- [SQL ER 图与表说明](sql/README.md)

### 开发指南(本批新增 / 更新)
- [本地开发环境搭建 (Mac/Linux/Windows)](docs/12_本地开发环境搭建.md)
- [后端开发指南](docs/10_后端开发指南.md)
- [前端开发指南](docs/11_前端开发指南.md)
- [新模块开发实战 (运输模块完整示例)](docs/13_新模块开发实战.md) ⭐ 强烈推荐
- [测试与调试指南](docs/14_测试与调试指南.md)
- [Git 与协作规范](docs/15_Git与协作规范.md)

## 🔒 安全与权限

- **认证**: Sa-Token (JWT) + Redis 分布式会话
- **登录保护**: 失败 5 次锁定 5 分钟, 图形验证码
- **权限**: 菜单/按钮/数据范围三级
- **数据权限**: 1=全部 2=本部门及下级 3=本部门 4=本人
- **审计**: 全部业务操作记录到 `sys_oper_log` (AOP 自动)

## 📈 性能

- 库存查询走 `(warehouse_id, product_id, batch_no)` 唯一索引, O(log N)
- 分页最大 500, 全表 10w+ 数据查询 < 100ms
- 字典/配置走 Redis 缓存 (30 分钟 TTL)
- 报表走汇总表, 复杂场景可扩展 ClickHouse
- 并发: 100+ 业务操作无锁竞争 (Redis Lock 5s 等待)

## 🧪 测试

```bash
cd backend
mvn test -Dtest=StockServiceTest   # 库存核心
mvn test                          # 全量
mvn test jacoco:report            # 覆盖率
```

## 🤝 贡献

- 阅读 [15_Git与协作规范.md](docs/15_Git与协作规范.md)
- 新增模块请参考 [13_新模块开发实战.md](docs/13_新模块开发实战.md)
- 提交前运行 `mvn -q clean verify` 与前端 lint

## 📄 许可证

本项目代码仅供学习与商用参考, 详细许可请联系作者。
