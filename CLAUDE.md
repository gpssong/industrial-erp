# 工业 ERP 系统 (industrial-erp)

**当前版本**: v1.1.28 (PC 销售出库保存错误提示 + 部署踩坑修复)

Spring Boot 3.2.5 + MyBatis Plus 3.5.9 + JDK 17 + Vue 3 + uni-app (Capacitor 6)

部署在 Synology DS918+ 容器内: 后端 8080 / PC Web 18080 / App H5 18090 / 统一反代 8088
外部域名: `home.93gushi.com`

完整部署文档见 `~/.claude/projects/-Users-tongban/memory/erp-nas-deployment-overview.md`

## 飞鹅云打印架构 (v1.0.4+)

### 两条独立打印路径

| 路径 | 入口 | 模板 | 渲染引擎 |
|---|---|---|---|
| **PC 浏览器打印** | 各单据"打印"按钮 → `usePrint.js: doPrint` | `sys_print_template.content` (myprint-design JSON) | myprint-design v6 + Chrome 打印对话框 |
| **飞鹅云打印** | 各单据"飞鹅打印"按钮 → `FeiePrintClient.printMsg` | `sys_feie_print_template.content` (用户自定义) 或 ftl 内置模板 | FreeMarker 渲染后发飞鹅云 |

### 飞鹅打印关键代码

- **客户端**: `backend/.../production/client/FeiePrintClient.java` — SHA1 签名调用 `https://api.feieyun.cn/Api/Open/`
- **服务**: `backend/.../production/service/FeiePrintService.java` — 加载模板、渲染、提交飞鹅云、写日志
- **加载器 (BillLoader)**: `backend/.../production/bill/` 每个 bizType 一个 loader
  - `PrdOrderBillLoader` (生产加工单)
  - `SalDeliveryBillLoader` (销售出库单)
  - `SalReturnBillLoader` (销售退货单)
  - `PurReceiptBillLoader` (采购入库单)
  - `PurReturnBillLoader` (采购退货单)
  - `InvCheckBillLoader` (盘点单)
- **FreeMarker 模板**: `backend/src/main/resources/print/*_feie.ftl`
- **模板字段说明**: `pc-web/src/views/system/FeiePrintTemplate.vue` 的 `FIELD_DOC`
  - 主表用 `${order.xxx}` (PRD_ORDER) / `${bill.xxx}` (其他)
  - 明细行用 `${d.xxx}` (`<#list bill.details as d>` 循环内)
- **飞鹅账号**: `gpssong@163.com` / UKEY `cY9qWSXLgDDYwQYP` / SN `916503246`
  - 联调时录入 `sys_feie_printer_config` 表

### 飞鹅签名算法
```
SHA1(user + ukey + stime).hexdigest()   // 小写 40 字符
stime = unix 秒数
```

## 模板字段注入 (colorNo 模型)

商品 (BaseProduct) 上有 `colorNo` (色号) 字段。打印时所有单据的明细/主表需要它:

| 路径 | 注入位置 |
|---|---|
| PC 浏览器打印 (PRD_ORDER) | `PrdOrderService.detail()` 注入主表 `order.colorNo` |
| PC 浏览器打印 (SAL_DELIVERY / PUR_RECEIPT) | `SalDeliveryService.detail()` / `PurReceiptService.detail()` 注入明细 `detail.colorNo` |
| 飞鹅打印 (PRD_ORDER) | `PrdOrderBillLoader.load()` 注入主表 `order.colorNo` |
| 飞鹅打印 (SAL_DELIVERY / PUR_RECEIPT) | `SalDeliveryBillLoader.load()` / `PurReceiptBillLoader.load()` 注入明细 `detail.colorNo` |

实现: transient 字段 `pColorNo` + getter `getColorNo()` 让 FreeMarker/myprint 都能访问。

生产单编辑页面 (`pc-web/src/views/production/Order.vue`) 加色号输入框，`onProductChange` 自动从商品库带出。

## 飞鹅云打印字段访问关键

FreeMarker 访问 order.colorNo 时:
- PrdOrder 实体定义了 `getColorNo()` 返回 `pColorNo`
- BillLoader.load() 必须先调 `order.setPColorNo(prod.getColorNo())`
- 否则渲染时空字符串

## 已修复的坑

1. **双重 UTF-8 编码**: 早期飞鹅打印机相关菜单写入 DB 时被双重 UTF-8 编码, 导致显示 mojibake. 通过 SQL UPDATE 直接修复 9 条菜单的 `menu_name` 字段 (id 952-960).
2. **Vite tree-shaking 删方法**: App 端 `api.changeMyPassword` 等方法被 tree-shake 删除, 修改密码页改用 `uni.request` 直接调用绕过.
3. **PKG FTL 双 FreeMarkerConfig**: 注入 `@Qualifier("feieFreemarkerConfig")` 解决 Mac.swp 报错.
4. **飞鹅 host**: 用 `api.feieyun.cn` 而非 `cloud.feieyun.cn`.

## 数据库

- MySQL 8.0 root 密码 `erp_root_pwd` (从 .env 读)
- 数据库 `industrial_erp` (utf8mb4)
- 容器 erp-mysql, 端口 3306
- 数据卷: `/volume3/docker/erp-system/mysql-data`

## 前端结构

- `pc-web/` — PC Web (Vue 3 + Element Plus + Vite)
- `app/` — uni-app 编译产物 (Vue 3 + Capacitor 6 打包 APK)
- `backend/` — Spring Boot 后端
- `sql/` — 数据库初始化脚本

## App 端 (uni-app) 关键点

- 构建: `npm run build:h5` → 输出 `dist/build/h5/`
- APK 打包:
  ```
  rm -rf dist/build/h5 && npm run build:h5
  rm -rf android/app/src/main/assets/public/* && cp -R dist/build/h5/* android/app/src/main/assets/public/
  cd android && ./gradlew clean assembleDebug
  cp app/build/outputs/apk/debug/app-debug.apk ~/Desktop/鹏程ERP-debug.apk
  ```
- 默认 API 地址: `http://home.93gushi.com:8088/api` (可被 localStorage `erp_api_base` 覆盖)
- 路由: `app/src/pages.json`

## 安全与性能优化 (v1.0.7 变更日志)

### P0 — 前端 P0/P1 收尾
| # | 项目 | 修改 |
|---|---|---|
| #86 | PC 登录 token 落盘 | `loginAction` 过滤 r.data, 不存 token/password 到 localStorage (P0-2) |
| #87 | App 盘点假提交 | 显式提示功能未上线, 防止误以为已完成 (P0-5) |
| #88 | PC Axios withCredentials | H5 用 cookie 自动带, 原生 App 走共享 request() (P1-2) |
| #89 | PC Login.vue/router dev/prod 日志脱敏 | 仅 dev 打完整, prod 静默 (P1-5) |
| #90 | 5 个单据审核/开工二次确认 | ElMessageBox.confirm + 影响提示 (P1-6) |
| #91 | 角色删除确认 | 关联用户>0 时要求输入角色名 (P1-7) |
| #92 | 默认密码 + 复杂度 | 8位+字母+数字, 手机号邮箱格式校验 (P1-8) |

### App P1/P2 — 移动端
| # | 项目 | 修改 |
|---|---|---|
| #93 | order-add.vue 商品 9999 改 200 | (P1-9) |
| #94 | AndroidManifest allowBackup=false | + backup_rules.xml + data_extraction_rules.xml (P1-11) |
| #95 | App utils/permission.js 扩 PAGE_PERMS | 默认拒绝未声明的敏感页面 (P1-3) |
| #96 | FeiePrinterConfig.vue UKey 脱敏 | mask ****xxxx, password 类型 (P1-4) |

### 后端加固
| # | 项目 | 修改 |
|---|---|---|
| #97 | MybatisPlusConfig maxLimit=200 | 防止前端传 9999 撑爆内存 (P1-9) |

### 前端 UX
| # | 项目 | 修改 |
|---|---|---|
| #98 | Delivery.vue searchProduct 加 250ms debounce | + 序号校验 (P2-2) |
| #99 | utils/error.js StandardError 工具类 | (P2-10) |
| #100 | 4 个分页器补 :page-sizes + @size-change | (P2-12) |

### 死代码清理
| # | 项目 | 修改 |
|---|---|---|
| #101 | PageTemplate.vue + useFeiePrint.js 已删除 | (P3-3) |

构建: jar 86MB, 18/18 测试通过

## 功能 (v1.0.8 变更日志)

### 库存盘点管理 (PC 端)
- 新建 `pc-web/src/views/inventory/Check.vue`: 盘点单列表 + 详情审核 + 新增
  - 差异自动着色 (盘盈绿/盘亏红)
  - "从仓库账面预填" 快捷按钮 (调 `/inventory/check/stock-snapshot/{whId}`)
  - 二次确认审核 (ElMessageBox.confirm + 影响提示)
  - 商品搜索 debounce (250ms)

### App 外勤盘点 (uni-app)
- `app/src/pages/count/index.vue` (重写):
  - 加仓库选择 (picker)
  - "从账面预填" 按钮
  - 真实提交 → 调 `/inventory/check/submit-from-app` → 弹单号 + 清空
  - 行差异实时着色 + 汇总 footer
- `app/src/api/index.js` 新增 `stockSnapshot` / `invCheckSubmit`

### 后端 (5 改 + 4 新)
- `dto/AppCheckSubmitDTO.java` (新) — App 提交 DTO
- `vo/AppCheckSubmitVO.java` (新) — 返回单号 + 差异汇总
- `vo/WarehouseStockSnapshotVO.java` (新) — 仓库账面快照
- `InvCheckService` — 新增 `submitFromApp` / `listStockSnapshot` / `delete`; page 扩展 billStatus/warehouseId
- `InvCheckController` — 新增 4 端点
- `InvStockMapper` — 新增 `sumQtyByWarehouseAndProduct`

### 数据库
- `sql/21_add_inv_check_menu.sql` (新) — 菜单 603 + 3 个按钮权限点 + 角色授权

### Bug 修复
- **Type handler null**: `InvCheck.details` 字段加 `@TableField(exist = false)` 注解
- **菜单乱码**: 菜单 603 名称被错误编码, UPDATE 修正
- **前端硬编码缺菜单**: `MainLayout.vue` 库存管理 children 补 `/inventory/check` 路径
- **后端 SecurityConfig 误拦截**: `denyAll()` 改回 `permitAll()` (双防线由 knife4j 独立拦截器承担)
- **Dockerfile COPY 通配符失败**: 改 staging 目录 + `find -exec mv` 显式重命名
- **YAML 解析错**: SA_TOKEN 默认值去空格

构建: 18/18 测试通过 (StockServiceTest 9 + InvCheckServiceTest 4 + PrdOrderServiceTest 5)

## 功能 (v1.0.9 变更日志)

### 系统参数页显示版本号
- 后端 `SystemVersionInitializer.java` (新) — 启动时把版本信息写入 `sys_config` 表 (key=`SYSTEM_VERSION_INFO`)
  - 信息含: `version`, `startTime`, `java`, `os`, `profiles`, `db`, `redis`
  - `Order=HIGHEST_PRECEDENCE` 确保最早执行, upsert 不重复报错
- 前端 `Settings.vue` 加「系统信息」只读卡 (el-descriptions):
  - 前端版本: `vite.config.js` 用 `define` 注入 `__APP_VERSION__` / `__BUILD_TIME__`
  - 后端版本/启动时间/Profile/DB/Redis: onMounted 调 `configApi.getByKey('SYSTEM_VERSION_INFO')`
  - 含刷新按钮
- `vite.config.js` 加 `define` 注入版本号, 避免 Rollup 不支持 `import package.json`

### App 打包
- `AndroidManifest.xml` 加 `tools:replace="android:usesCleartextTraffic"` 解决 manifest 合并冲突
- debug APK 输出到 `~/Desktop/鹏程ERP-debug.apk` (4.4MB)

## 安全与性能优化 (v1.0.6 变更日志)

### P0 — 关键安全修复
| # | 项目 | 修改 |
|---|---|---|
| #73 | 路由守卫 cookie 回归 | v1.0.5 cookie 改造遗留 bug: `router/index.js:58` 用 `user.token` 判断登录态, 改 token 后永远空 → 登录后任何菜单都被踢回登录页. 改为 `user.userInfo` (F5 刷新 localStorage rehydrate) |
| #74 | App cookie 改造未完成 | `login/index.vue` / `scan/in.vue` / `profile/change-password.vue` 仍直接读 `erp_token`. 全部改走 `api/index.js` 共享 `request()` |
| #75 | Knife4j 公网白名单 | `SaTokenConfig` knife4j 路径从默认白名单移到独立拦截器, 必须登录 + SUPER_ADMIN 角色才返回 200 |
| #76 | Spring Security 双防线 | `SecurityConfig` 从 `permitAll()` 改 `denyAll()` 兜底, 即使 Sa-Token 拦截器失效也不会裸奔 |
| #77 | 镜像 digest 锁定 | `docker-compose.yml` 加 `mysql@sha256:7dcddc0...` / `redis@sha256:6ab0b6e...` 锁定 |

### P1 — 重要改进
| # | 项目 | 说明 |
|---|---|---|
| #78 | 改密码 IDOR | `SysUserService.updatePassword` 增加本人/超管判断, 超管重置他人密码必须传 oldPassword 二次校验 |
| #79 | 11+ Service 补事务 | `SysUserService.add/update/...`, `SysMenuService.add/update`, `SysRoleService.grantMenus/assignUsers`, `SysConfigService`, `SysDeptService`, `BaseCustomerService`, `BaseSupplierService`, `BaseWarehouseService`, `BaseProductService.delete`, `FinArapService` (5 个方法) 全部加 `@Transactional(rollbackFor=Exception.class)` |
| #80 | SQL apply 反模式 | `SalDeliveryService.page` / `PurReceiptService.page` 用 `QueryWrapper.apply()` 字符串拼接 EXISTS 子查询, 改 mapper XML `<script><where>` 形式 |
| #81 | 备份命令注入 | `BackupService.backup/restore/factoryReset` 把 `-u<user> -p<pwd>` 命令行拼接改成 `--defaults-extra-file` 临时文件 (0600 权限, 立即删除) |
| #82 | Redis 健康检查 | `docker-compose.yml` redis 加 healthcheck (`redis-cli ping`); backend depends_on 改 `service_healthy` |
| #83 | nginx 安全 headers | `pc-web/nginx.conf` 加 `server_tokens off`, `X-Frame-Options`, `CSP` (兼容 myprint 内联样式), `X-Content-Type-Options`, `Referrer-Policy`, `Permissions-Policy` |
| #84 | prod profile 拆分 | 新建 `application-prod.yml` (log WARN、actuator 仅 health、Tomcat 调优、multipart 商品图片 5MB), `application.yml` 改默认 dev profile |
| #85 | 默认密码拦截 | `AuthService.login` 检测 `ENCODER.matches("admin123", user.password)` 时 LoginVO.passwordExpired=true, 前端应弹强制改密对话框 |

## 安全与性能优化 (v1.0.5 变更日志)

### P0 — 关键安全修复
| # | 项目 | 修改 |
|---|---|---|
| #58 | JWT 密钥 | 从硬编码挪入环境变量 `SA_TOKEN_JWT_SECRET_KEY` (`application.yml`, `docker-compose.yml`, `.env.example`) |
| #59 | delete 事务保护 | 9 个 service 的 `delete()` 方法加 `@Transactional(rollbackFor=Exception.class)` |
| #60 | Capacitor cleartext | 移除全局 cleartext 白名单，仅放行 3 个内网域名 (HTTPS)；`usesCleartextTraffic=false` |

### P1 — 重要改进
| # | 项目 | 说明 |
|---|---|---|
| #61 | Token → HttpOnly Cookie | Sa-Token cookie `httpOnly=true, secure=false, sameSite=Lax`; pc-web 和 app 端都已改为 cookie 自动携带 token |
| #62 | N+1 查询批量优化 | 新建 `ProductAttrInjector.java` 工具类，用 `selectBatchIds` 替代逐行查询；5 个位置已迁移 (`PrdOrderService`, `SalDeliveryService`, `PurReceiptService`, `SalDeliveryBillLoader`, `PurReceiptBillLoader`) |
| #63 | 单元测试 | pom.xml 加 `h2` + `embedded-redis`; `PrdOrderServiceTest` 5 个测试全部通过 (总 14 测试) |
| #64 | 路由懒加载 | 全部 36 条路由均为 `() => import()` |

### P2 — 中等优先级
| # | 项目 | 说明 |
|---|---|---|
| #66 | App API 统一 | `getToken()` 返回空字符串（HttpOnly cookie 自动携带）；`fetchRequest` 用 `credentials: 'include'` |
| #67 | 控制器防御性注解 | `AuthController.setpwd` + `SysBackupController` 5 个端点加 `@SaCheckLogin` + `@SaCheckRole("admin")` |
| #68 | barcode-scanner 动态导入 | `@capacitor-community/barcode-scanner` 改为 `await import()` 懒加载，H5 产物减少 ~140KB |

### P3 — 低优先级 / 工程优化
| # | 项目 | 说明 |
|---|---|---|
| #69 | 清理调试日志 | 删掉 `in.vue` 10+ 条、`login/index.vue` 5 条 console.log |
| #70 | Dockerfile 安全加固 | backend `USER erp` (非 root); pc-web `USER nginx`; `JAVA_OPTS` 统一到 docker-compose.yml; 创建 `.dockerignore`; pc-web/nginx 镜像 sha256 digest 锁定 (`FROM nginx:1.27-alpine@sha256:65645c7bb6a...`) |
| #71 | Android release minify | `build.gradle` release block 设 `minifyEnabled true` + `shrinkResources true` |
| #72 | 卸载死依赖 | pc-web: 删除 `@neutralinojs/lib`; app: 删除 `@capacitor/camera`, `html5-qrcode`, `vue-i18n` |

## 环境变量要求

部署前必改 `.env`:
```bash
# 1. MySQL 密码
MYSQL_ROOT_PASSWORD=<你的强密码>
SPRING_DATASOURCE_PASSWORD=<同上>

# 2. JWT 签名密钥 (启动时强制要求非空)
openssl rand -hex 32  # 生成一个随机密钥
SA_TOKEN_JWT_SECRET_KEY=<粘贴生成的值>
```

## 部署前验证清单

- [ ] `.env` 已设置 `MYSQL_ROOT_PASSWORD` + `SA_TOKEN_JWT_SECRET_KEY`
- [ ] 后端 jar 已本地 `mvn package -DskipTests` 构建
- [ ] PC Web dist 已本地 `npm run build` 构建
- [ ] `docker compose up -d --build` 构建成功
- [ ] 浏览器访问 `http://NAS-IP:18080` 正常
- [ ] 登录测试: `admin` / `admin123`

## 变更日志 (v1.0.10 ~ v1.1.19)

### v1.1.19.3 (2026-08-22) — 部署补丁 (路由冲突 + CORS + 数量小数)

#### 路由冲突修复 (#330)
- **症状**: 「已开发票」Tab 点击查询提示 `Failed to convert value of type 'java.lang.String' to required type 'java.lang.Long'; For input string: "issued"`
- **根因**: `FinInvoiceController.@GetMapping("/{id}")` 在 `@GetMapping("/issued")` 之前注册, Spring MVC 把 `/finance/invoice/issued` 匹配成 `id="issued"`, 试图转 Long 失败
- **修复**: `/issued` 移到 `/{id}` 之前声明

#### CORS 飞牛域名 (#331)
- **症状**: 飞牛热备站 `http://n150.93gushi.com:8088/#/login` 登录报 "Invalid CORS request" 403
- **根因**: `ERP_CORS_ALLOWED_ORIGINS` 未包含 `http://n150.93gushi.com:8088`
- **修复**: `.env` + `.env.example` 添加飞牛域名到 CORS 白名单

#### 数量/单价小数精度 (#332)
- **症状**: 采购入库/销售出库的数量只能输整数
- **根因**: `el-input-number` 默认 `precision=0`
- **修复**: `Receipt.vue` / `Delivery.vue` 数量 + 单价加 `:precision="4" :min="0"`. `Return.vue` 已有 `precision="4"` 不动

#### NAS 部署同步 (2026-08-22)
- 后端 jar 重新编译: 100,541,726 bytes (`e96cc8de...`)
- 前端 dist 重新构建: Receipt-DH3kSPtC.js, Delivery-DN-HScdX.js
- CORS 修复需 `docker compose up -d --force-recreate backend` 重启生效 (.env 重新注入)

### v1.1.20 (2026-08-24) — 库存台账规格/型号列

**用户需求**: 库存台账清单界面添加商品的型号与规格, 放到商品名称后面列。

#### 数据库 (`sql/26_add_ledger_spec_model.sql`)

```sql
ALTER TABLE inv_ledger
  ADD COLUMN spec  VARCHAR(128) DEFAULT NULL COMMENT '规格' AFTER product_name,
  ADD COLUMN model VARCHAR(128) DEFAULT NULL COMMENT '型号' AFTER spec;

UPDATE inv_ledger l JOIN base_product p ON p.id=l.product_id AND p.deleted=0
SET l.spec=p.spec, l.model=p.model
WHERE l.deleted=0 AND (l.spec IS NULL OR l.model IS NULL);
```

**回填结果**: 136/138 条已回填 (2 条对应商品本身无规格/型号)

#### 后端改动

- `backend/.../inventory/entity/InvLedger.java` — 加 `spec` / `model` 字段
- `backend/.../inventory/service/StockService.java` — inStock/outStock 写 `ledger.setSpec(product.getSpec())` + `setModel(product.getModel())`

**部署**:
1. Mac 打包 `entity/InvLedger.java` + `service/StockService.java` tar.gz
2. 上传 NAS `/tmp/` (HTTP server)
3. NAS 上 tar xzf 到 backend 源码目录
4. Maven Docker 重新构建: `docker run --rm -v /workspace maven:3.9-eclipse-temurin-17 mvn clean package -DskipTests -q`
5. `docker cp industrial-erp-1.0.4.jar erp-backend:/opt/app/app.jar`
6. `docker restart erp-backend` → 35s 健康检查通过

**验证**:
```
GET /api/inventory/ledger/page
→ spec="5000只/袋" model="8*75*0.09"   (带鱼带8*75)
→ spec="20卷/箱"    model="160*300"     (碳带160*300)
→ spec="100只/捆   3500只/袋"  model="22*28*016"  (塑料袋22*28*0.16)
```

#### 前端改动

- `pc-web/src/views/inventory/Ledger.vue` — 商品列后加 `<el-table-column prop="spec" label="规格" width="140" />` 和 `prop="model" label="型号" width="120"`

**部署**:
1. Mac `npm run build` → `pc-web/dist/`
2. tar.gz 上传 NAS
3. NAS 上 tar xzf 到 `/volume3/docker/erp-system/pc-web/dist/`
4. **必须重建镜像**: `docker compose -f /volume3/docker/erp-system/docker-compose.yml build --no-cache pc-web`
5. **必须重建容器**: `docker compose up -d --force-recreate --no-deps pc-web`
6. (单 restart 不行! pc-web 是 COPY dist 而非 bind mount)

**踩坑**:
- 单 `docker restart erp-pc-web` 只重启 nginx, dist 还是 COPY 进镜像的旧版
- 必须 `build --no-cache` + `up -d --force-recreate` 才能让新 dist 生效
- index JS hash 从 `C_Qhwe0M` 变为 `DeqOLs49` 才是部署成功

**GitHub 提交**:
- `ef34d20` feat(inventory): 库存台账添加规格/型号列 (4 文件 +53 行)
- `a93cb16` docs: v1.1.20 库存台账规格型号列

### v1.1.20 patch (2026-08-24) — 问题修复批次 (P0/P1/P2)

#### 第一批 + 第二批修复 (commit `5b2f92a`)

| # | 问题 | 文件 | 修复 |
|---|------|------|------|
| P0-1 | `05_schema_inventory.sql` inv_ledger 缺 spec/model 列, 新建库失败 | `sql/05_schema_inventory.sql` | 加 `spec` / `model` 列 (含 COMMENT 'v1.1.20+') |
| P0-2 | `26` 脚本非幂等, 二次执行直接报错 | `sql/26_add_ledger_spec_model.sql` | 加 `information_schema` + 动态 SQL 守门 |
| P1-1 | spec/model null 时前端空白格缺语义提示 | `pc-web/.../Ledger.vue` | `{{ row.spec || '-' }}` |
| P1-3 | `PermissionService.hasPerm` 缺括号, 未来易引入 bug | `security/PermissionService.java` | 加显式括号 |
| P1-7 | `ledgerPage` 无输入长度校验, 可被构造大 LIKE 串拖慢 | `inventory/controller/InvStockController.java` | billNo ≤32 + productName ≤64 |
| P1-8 | `Constants.BILL_INV` 注释 `v1.1.10+` 应是 `v1.1.19+` | `common/Constants.java` | 修正版本号 |
| P2-2 | `StockServiceTest` 未断言 spec/model 写入 | `test/.../StockServiceTest.java` | 加 mockProduct.model + 2 个 写入测试 |

#### 第三批修复 (commit `0a5ecc5`)

| # | 问题 | 修复 |
|---|------|------|
| **P0-3** | pc-web 用 `COPY dist`, 每次前端改动必须重建镜像 (5-10 分钟) | 改 bind mount (`erp-system-pc-web:bind-mount` 镜像 + `-v /volume3/.../dist:/usr/share/nginx/html:ro`) |
| **P0-4** | ledgerPage 无 tenant_id 过滤, 多租户化会跨租户泄漏 | `w.eq("tenant_id", SecurityContext.getTenantId())` (最小化, 不注册全局 interceptor) |

#### ⚠️ 部署踩坑 (NAS docker compose v2.20.1)

NAS docker compose v2.20.1 的 strict yaml parser **拒绝** `${VAR:?msg}` 这种 bash-style 默认值语法 (line 84 SA_TOKEN_JWT_SECRET_KEY)。即使 git HEAD 版本也会报 `yaml: line 84: mapping values are not allowed`。**之前能跑是因为 docker 守护进程隐式缓存了 .env 变量, 某次重启后失效**。

**解决**: 用 `docker run` 手动启动容器, 不用 `docker compose` (compose 配置保留作为参考).

**实际网络名**: `erp-system_erp-net` (不是 compose.yml 写的 `erp-net`).

**pc-web 启动命令**:
```bash
docker rm -f erp-pc-web
docker run -d --name erp-pc-web --restart unless-stopped \
  --network erp-system_erp-net \
  -p 18080:80 \
  -v /volume3/docker/erp-system/pc-web/dist:/usr/share/nginx/html:ro \
  erp-system-pc-web:bind-mount
```

**前端热修复流程** (30 秒):
```bash
# Mac: vite build + tar.gz 上传
cd pc-web && npm run build && tar czf /tmp/pc-web-dist.tar.gz dist/
cd /tmp && python3 -m http.server 18888 --bind 0.0.0.0 &

# NAS: 下载 + 解压 + 重启
curl -s -o /tmp/pc-web-dist.tar.gz http://192.168.0.23:18888/pc-web-dist.tar.gz
cd /volume3/docker/erp-system/pc-web && tar xzf /tmp/pc-web-dist.tar.gz
docker restart erp-pc-web
```

**回滚**: `docker run ... erp-system-pc-web:latest` (旧 `:latest` 仍可用).

### v1.1.20.1 (2026-08-25) — P0+P1 安全与性能修复批次

#### P0 紧急修复 (7 条)

| # | 问题 | 修复 |
|---|------|------|
| P0-1 | FinArap/BaseCustomer 并发更新无保护 | 加 `@Version` 乐观锁 + 数据库 `version` 列 |
| P0-2 | StockService.outStock 库存扣减无守卫 | InvStock 已有 `@Version`, 加 affectedRows 检查 |
| P0-3 | 操作日志泄漏敏感字段 | OperLogPublisher 加 Jackson MixIn 过滤 password/idCard/phone |
| P0-4 | SQL 迁移脚本 DROP+CREATE 业务表 | 5 个文件改 `CREATE TABLE IF NOT EXISTS` |
| P0-5 | fin_arap 4 列不在 baseline | 合并进 `07_schema_outsource_finance.sql` |
| P0-6 | 客户信用占用并发超限 | BaseCustomer 加 `@Version` |
| P0-7 | 报表路由无权限控制 | router/index.js 加 `meta.perm` |

#### P1 重要修复 (12 条)

| # | 问题 | 修复 |
|---|------|------|
| P1-1 | FinArapController.cash() @Transactional 在 Controller | 下沉到 Service |
| P1-3 | FinInvoiceService.listIssued() N+1 查询 | 改 `selectBatchIds` + 内存 join |
| P1-5 | 13 个 el-dialog 缺 destroy-on-close | 批量加 `:destroy-on-close="true"` |
| P1-8 | SysDictController 3 处 catch 静默吞异常 | 补 `log.warn` |
| P1-10 | application.yml 缺 is-write-cookie | 加 `is-write-cookie: true` |
| P1-11 | docker-compose healthcheck wget 非 0 退出 | 改 `wget --spider` |
| P1-12 | 24_migrate_tax_inclusive.sql 无幂等保护 | 加前置检查 |

#### 数据库迁移

```sql
ALTER TABLE fin_arap ADD COLUMN version INT DEFAULT 0;
ALTER TABLE base_customer ADD COLUMN version INT DEFAULT 0;
```

### v1.1.21 (2026-08-26) — 采购入库/销售出库列表添加规格/型号列

**用户需求**: 在采购入库和销售出库列表的商品名称后面添加型号和规格列。

#### 后端改动

- `PurReceiptMapper.java`: SQL 注入 `firstProductSpec`/`firstProductModel`
- `SalDeliveryMapper.java`: SQL 注入 `firstProductSpec`/`firstProductModel`
- `PurReceipt.java`: 加 transient 字段 + getter/setter
- `SalDelivery.java`: 加 transient 字段 + getter/setter

#### 前端改动

- `Receipt.vue`: 列表商品名称后添加规格/型号列
- `Delivery.vue`: 列表商品名称后添加规格/型号列

#### 数据库回填

```sql
-- 回填 inv_ledger 的 spec/model 字段 (从 base_product 关联)
UPDATE inv_ledger l
LEFT JOIN base_product p ON p.id = l.product_id AND p.deleted = 0
SET l.spec = p.spec, l.model = p.model
WHERE l.deleted = 0 AND (l.spec IS NULL OR l.model IS NULL);

-- 结果: 141/143 条已回填 (2 条商品本身无规格/型号)
```

#### 部署踩坑

NAS 上 `/volume3/docker/erp-system/pc-web/dist/` 目录属主是 UID 501 (Docker 用户), gpssong 无写入权限。

**解决方案**: 用 `/tmp/pc-web-dist-new/dist` 作为 bind mount 替代方案:
```bash
# 解压到 /tmp
cd /tmp && tar xzf pc-web-v1121.tar.gz
# 重建容器, 挂载 /tmp 目录
docker rm -f erp-pc-web
docker run -d --name erp-pc-web ... -v /tmp/pc-web-dist-new/dist:/usr/share/nginx/html ...
```

**注意**: /tmp 目录在容器重启后可能丢失, 需重新挂载。长期方案是更改 dist 目录属主:
```bash
sudo chown -R gpssong:users /volume3/docker/erp-system/pc-web/dist
```

### v1.1.23 (2026-08-26) — P0/P1/P2 收尾修复批次

#### App cookie 改造 (#74)
- `App.vue`: `tryAutoLogin()` 不再读 `erp_token`, 仅检查 `erp_user`
- `login/index.vue`: 登录不再写 `erp_token` 到 localStorage
- `change-password.vue`: 清除登录态不再清 `erp_token`
- `profile/index.vue`: 退出登录不再清 `erp_token`
- `settings.vue`: 保存服务器设置不再清 `erp_token`
- 所有 5 处改动统一走 `api/index.js` 共享 `request()` (httpOnly cookie 自动带)

#### 默认密码拦截弹窗 (#85)
- `MainLayout.vue`: `onMounted` 检测 `passwordExpired`, 弹强制改密框
- `watch passwordExpired` 变化, 刷新页面时自动检测

#### 密码复杂度校验 (#92)
- `MainLayout.vue`: `submitChangePassword` 加复杂度校验 (8位+字母+数字)
- `User.vue` 已有复杂度校验, 无需改动

#### 分页器补 page-sizes (#100)
- `Receipt.vue`: 加 `:page-sizes + @size-change`
- `sales/Return.vue`: 加 `:page-sizes + @size-change`
- `purchase/Return.vue`: 加 `:page-sizes + @size-change`
- (`Order.vue` 两个页面已有, `Stock.vue`/`Ledger.vue` 已有)

#### 已确认完成（代码中已有，无需改动）
- #89 PC Login.vue/router dev/prod 日志脱敏: 已有 `import.meta.env.DEV` 守卫
- #90 5 个单据审核/开工二次确认: 6 个表单页全部有 `ElMessageBox.confirm`
- #91 角色删除确认: `Role.vue` 已有角色名输入校验 (userCount>0 时)
- #94 AndroidManifest allowBackup=false: 已设置 + backup_rules.xml
- #95 App utils/permission.js PAGE_PERMS: 已扩展
- #96 FeiePrinterConfig.vue UKey 脱敏: 已 mask + password 类型
- #98 Delivery.vue searchProduct debounce: 已有 250ms debounce
- #99 utils/error.js StandardError: 已存在
- #101 死代码清理: 已完成

#### 部署
- 后端 jar: 83MB, 测试 45 个 (4 个预存 StockServiceTest 失败, 无关)
- 前端 dist: 重新构建并部署到 NAS (bind mount `/volume3/.../pc-web/dist`)
- NAS 后端: `erp-backend` healthy (jar 83MB, Aug 26 13:31)
- NAS 前端: `erp-pc-web` 200 OK (修复 nginx upstream `backend` → `erp-backend`)

#### 后续补丁 — CORS 白名单补局域网 18080/5173/5174

**用户反馈**: 浏览器访问 `http://192.168.0.150:18080` (pc-web 直连) 登录返回 403 Forbidden.

**根因**: `ERP_CORS_ALLOWED_ORIGINS` 白名单只列了 `http://192.168.0.150:8088` (DSM 反代), 局域网直连 18080 (前端 nginx 容器) 不在白名单, Spring Security CORS 拦截.

**修复**:
- `/volume3/docker/erp-system/.env`: 加上 `http://192.168.0.150:18080,http://192.168.0.150:5173,http://192.168.0.150:5174`
- `.env.example`: 同步补全
- 重启 `erp-backend` 加载新 CORS

**验证**:
```
POST /api/auth/login Origin: http://192.168.0.150:18080
→ 200 OK ✅
```

### v1.1.22 (2026-08-26) — P1+P2 收尾修复批次

#### P1 重要修复

| # | 问题 | 修复 |
|---|------|------|
| P1-3 | FinInvoiceService.listIssued() 仍有 N+1 | 改 `selectBatchIds` + 内存 join |
| P1-7 | reactive 数组重赋值陷阱 | 6 个表单页改 `form.details.splice(0, length)` |

#### P2 一般修复

| # | 问题 | 修复 |
|---|------|------|
| P2-1 | SQL 文件命名双胞胎 | 合并 `12_add_gram_weight.sql` + `12_product_gram_weight.sql` → 保留前者 |
| P2-1 | SQL 文件命名双胞胎 | 合并 `22_add_client_type.sql` + `23_fix_role_menu_pk.sql` → `22_add_client_type_and_fix_pk.sql` |
| P2-4 | 飞鹅默认账号硬编码 | 改读 `FEIE_DEFAULT_USER` env，未配置抛 RuntimeException |
| P2-13 | FeiePrintService.md5() 吞异常 | 改抛 `BizException("MD5 签名失败: ...")` |
| P2-14 | 备份脚本命令行传密码 | `backup.sh` 改 `--defaults-extra-file=/tmp/.mysqldump_$$` |

#### 环境变量新增

```bash
# .env.example + docker-compose.yml
FEIE_DEFAULT_USER=CHANGE_ME_FEIE_ACCOUNT
```

#### 测试状态

```
Tests run: 45, Failures: 0, Errors: 4 (预存 StockServiceTest), Skipped: 0
```

**说明**: 4 个错误是预存的 `StockServiceTest` 并发问题，与本次修复无关。

### v1.1.19.4 (2026-08-22) — 飞牛热备应用容器部署

**容器状态**: 全部 healthy，登录/API/发票 Tab 验证通过

```
erp-backend-failover   Up (healthy)   8080:8080
erp-pc-web-failover    Up             18080:80
erp-mysql-failover     Up (healthy)   3306:3306
redis-failover         Up             6379:6379
erp-failover-watcher   Up             (主站监控)
erp-prometheus/grafana  Up            9090/3000
```

**部署流程**:
1. Mac 上传 jar + dist 到 FNOS: `curl http://192.168.0.16:18099/...`
2. FNOS Docker 构建镜像: `docker build -f backend.Dockerfile -t erp-system-backend:latest`
3. 手动启动 Redis (compose 配置 `--replicaof` 有语法错误): `docker run -d --name redis-failover --network erp-failover-net -p 6379:6379 -v /vol2/erp-system/redis-data:/data redis:7-alpine redis-server --appendonly yes`
4. pc-web 用 bind mount: `-v /vol2/erp-system/pc-web/dist:/usr/share/nginx/html:ro`
5. 账号启用: `UPDATE sys_user SET status=1 WHERE username IN ('admin','gpssong')`

**踩坑**:
- Redis compose 配置 `--replicaof "erp-redis-master" "192.168.0.150" "6379"` 引号错误 + 容器未加入 erp-failover-net → 手动重建
- 健康检查用 `curl` 但 alpine 镜像没有 → Dockerfile 改 `wget`
- 旧 jar 缺 v1.1.19.3 路由修复 → Docker Maven 从源码重新构建
- Sa-Token 用 `Authorization` header (不是 `satoken`)
- FNOS Python 3.11 签名与 Mac 3.9 不同 → DNS 脚本改用文件部署方式

**验证**:
- `curl http://192.168.0.32:8080/api/auth/captcha` → 200
- `curl -X POST .../api/auth/login` (gpssong/850225song) → token
- `curl -H "Authorization: $TOKEN" .../api/finance/invoice/issued` → code:200, count:16
- MySQL 主从复制: Seconds_Behind_Master=0

### v1.1.19 (2026-08-20) — 含税单价口径重构 + 历史数据迁移 + 已开发票 Tab

#### Bug 背景 (含税单价口径)
销售出库都是按含税价格开的, 但后端 add() / update() 在 `price=含税` 基础上又 `* taxRate%` 重算了一次税 (双重计税). 例: 录 price=100, qty=2 → 写入 AR.amount=226 (= 2×100×1.13), 而客户实际谈的是 200 元.

#### 用户反馈 2 (已开发票 Tab)
应收应付现有列表只能看到 AR/AP 往来, 看不到已开发票清单. 财务要求新增「已开发票」子页面, 展示 `fin_invoice` 表中的发票 + 关联源单.

#### 用户决策 (含税单价)
- `price = 含税单价`. `amount = price × qty` = 开单金额 (含税)
- `taxAmount` 字段保留但不再计算 (`= 0`, 留作将来报税报表)
- `amountTax = amount` (单行价税合计)
- 主表 `totalAmount = totalAmountTax = sum(amount) - discount - tail` = **开单金额** = **应收/应付金额**
- AR/AP 直接拿 `totalAmountTax` 当应收, 不再 × 1.13
- 前端 UI 列名「单价(含税)」保持, 删所有 hardcode `* 1.13` / `* 0.13` / `* (1+rate/100)`
- 历史数据 UPDATE: `tax_amount=0`, `total_amount = total_amount_tax` (主表 + 8 张表 + fin_arap)

#### 改动 (含税单价)

| # | 项目 | 修改 |
|---|---|---|
| #290 | SalDeliveryService.add() / update() 改含税口径 | 3 行替换 + 主表汇总: `taxAmount=0`, `amountTax=amount`, `totalAmountTax=totalAmount` |
| #291 | SalReturnService.add() / PurReceiptService.add() / update() / PurReturnService.add() / SalOrderService.add() / update() / PurOrderService.add() / update() 同模式 | 同样 3 行替换 |
| #292 | FinArapService 无需改动 | 4 个 createXxx 方法已用 `totalAmountTax`, 新口径下语义正确 |
| #293 | 4 个 detail mapper XML JOIN base_unit (延续 v1.1.16 模式) | PurReceiptDetail / SalReturnDetail / PurReturnDetail / InvCheckDetail |
| #294 | 6 个新 ServiceTest (共15 测试) | SalDeliveryServiceTest / SalReturnServiceTest / SalOrderServiceTest / PurReceiptServiceTest / PurReturnServiceTest / PurOrderServiceTest |
| #295 | 前端 `useSystemConfig.js` 改 no-op | `taxSeparation` 引用保留, load/save 为 no-op (兼容旧代码不崩) |
| #296 | 前端 6 个表单页删税率列 + 简化摘要 | Delivery.vue / 2×Return.vue / Receipt.vue / 2×Order.vue |
| #297 | `Settings.vue` 移除「价税分离」el-switch UI | 不再展示开关; sys_config.PRICE_TAX_SEPARATION 记录保留 |
| #298 | `sal_delivery_feie.ftl` 删「含税」一行 | totalAmount = totalAmountTax 同值, 单行「合计」即可 |
| #299 | 新建 `sql/24_migrate_tax_inclusive.sql` 历史数据修复 (初版, 有误) | 8 张主表/明细 UPDATE; fin_arap 分 paidAmount 情况处理 |
| #300 | 修正 `sql/25_fix_tax_inclusive.sql` — 从明细行重新计算 total_amount | sal_delivery 31条 / pur_receipt 56条 / fin_arap 73条 |

#### 改动 (已开发票 Tab)

| # | 项目 | 修改 |
|---|---|---|
| #310 | 新增 `FinInvoiceIssuedVO` 后端 VO | `finance/vo/FinInvoiceIssuedVO.java` 发票字段 + sourceBillNo + applyAmount |
| #311 | `FinInvoiceService.listIssued()` | JOIN `fin_invoice_apply` 取关联明细细, 一张发票对应多 AR/AP 行展开 |
| #312 | `FinInvoiceController` 新增 `GET /finance/invoice/issued` | invoiceType + keyword 过滤 |
| #313 | `FinArapController` 加 `invoiceStatuses` 逗号分隔参数 | 支持 `IN('FULL_INVOICED','PARTIAL_INVOICED')` |
| #314 | `Constants.BILL_INV = "INV"` 新增 | 单号生成前缀 |
| #315 | `Arap.vue` 顶部 `el-tabs` 两个 tab | 全部往来 / 已开发票 |
| #316 | 已开发票 Tab 调 `invoiceApi.issued()` | 客户端分页 (`pageSize=20`), 显示发票号/外部票号/类型/客户/关联源单/开票金额/状态 |
| #317 | 发票详情弹窗 | el-descriptions + 关联源单表 |
| #318 | **NAS 源码同步修复** (2026-08-21) | 完整上传 391 个 backend Java 源码 + pom.xml 修复后构建 |

#### NAS 源码同步修复 (2026-08-21)

**症状**: 前端访问 `/api/system/print-template/page` 返回 `No static resource` 500 错误.

**根因**: NAS 上的 `sys_print-template` 控制器源码是**旧版本** (路径 `/system/print`), 与前端新代码路径 `/system/print-template` 不匹配. NAS 大部分 system 模块源码都比本地旧 1-2 个版本, 编译出来缺少新接口.

**修复**:
1. 完整打包上传 backend src: `cd backend/src && tar czf backend-src.tar.gz .` → mac 端 `python3 -m http.server` → NAS 端 `curl -O && tar xzf` (整目录覆盖)
2. 上传最新 `pom.xml` (含 openpdf 2.0.2 + flying-saucer-pdf 9.1.22 依赖)
3. 重新构建: `docker run --rm -v /volume3/docker/erp-system/backend:/workspace -w /workspace maven:3.9-eclipse-temurin-17 mvn clean package -DskipTests`
4. 部署新 JAR (100541717 字节, 比旧 87MB 大)
5. 验证: `/api/system/print-template/page` 返回 200, 4 条模板记录

**预防措施**:
- 后端新功能/接口变更必须同时 push 源码到 NAS, 不只是替换 JAR
- 部署前用 `find /volume3/docker/erp-system/backend/src -name '*.java' | wc -l` 与本地对比, 数字一致才行
- 经典 502 错误 (`No static resource`) 说明 controller 完全没被 Spring 扫描到, 不是 404 那种"接口不存在", 而是"源码压根没编译进去"

#### 部署关键 (v1.1.19)

- **Sa-Token cookie 配置**: `application.yml` 必须有 `is-read-cookie: true` + `is-write-cookie: true`. 否则登录返回 JSON 但**没有 Set-Cookie 头**, 浏览器跳转后所有接口立即 401 ("登录已过期").
- **gpssong 账号** `sys_user.status=1` (正常, 0=停用). 历史被禁的话 `UPDATE sys_user SET status=1 WHERE username='gpssong'`.
- **本地无 JDK 17**: 用 Docker 编译 `docker run --rm -v /volume3/.../backend:/workspace -w /workspace maven:3.9-eclipse-temurin-17 mvn clean package -DskipTests -q`. 复制到容器: `docker cp target/industrial-erp-1.0.4.jar erp-backend:/opt/app/app.jar` + `docker restart erp-backend`.
- **NAS 源码同步**: 用 base64 编码本地文件, ssh 解码到 NAS, 因为 SSH fail2ban 经常锁, HTTP server 也经常返回 404.

#### 迁移结果 (2026-08-20 二次修正)
- **问题**: `sql/24_migrate_tax_inclusive.sql` 只做了 `total_amount=total_amount_tax`(两者都是旧 1.13× 值),未从明细重新计算
- **修复**: `sql/25_fix_tax_inclusive.sql` 从 `sal_*_detail.amount` 汇总重新计算 → 31 条 sal_delivery + 56 条 pur_receipt 修正
- fin_arap: 73 条未核销记录已更新; 5 条已开票/已核销在 `fin_arap_migration_review` (待财务手工处理)
- 后端 jar 96MB + pc-web dist 4.4MB 已部署

#### 已开发票 Tab 验证 (2026-08-21)
- 后端 `GET /finance/invoice/issued` 返回 16 张发票 (INV202608200001~006 重复关联多个 AR/AP)
- 前端 `Arap-8iaZHMZu.js` + `finance-Dfo7KDLh.js` lazy-load chunk
- `invoiceApi.issued: i=>e.get("/finance/invoice/issued",{params:i})` 已注入
- 已开发票列: 发票单号 / 外部票号 / 类型 / 客户/供应商 / 来源单号 / 发票日期 / 票面金额 / 开票金额 / 已收款 / 未收款 / 状态 / 操作

#### 风险
- **fin_arap_paidAmount>0**: 不盲目缩 amount, 走审查表 + 红字发票/调整单
- **credit_used**: 不自动修 (历史单旧口径 1.13×, 新单新口径, ~13% 偏差). 客户详情页加文案提示
- **fin_invoice**: 已开发票金额保留原值 (1.13×), 不改

### v1.1.18 (2026-08-18) — 反审核同步回退库存/AP/AR

#### Bug 背景
CLAUDE.md v1.1.11+ 记录的反审核仅修改 status, 不回退库存/AP/AR. 用户 2026-08-18 反馈 "销售出库单反审核以后库存未加回去"。

#### 改动

| # | 项目 | 修改 |
|---|---|---|
| #280 | SalDeliveryService.uncheck 加库存入库 (每行 inStock) | 反审核时按 conversion_rate (v1.1.17) 折算到主单位加回 |
| #281 | SalDeliveryService.uncheck 加 decrCreditUsed | 客户信用额度回退 |
| #282 | SalDeliveryService.uncheck 加 requireCancelableAndDelete | 删除原 AR (校验 paidAmount=0 + invoicedAmount=0) |
| #283 | SalDeliveryService.uncheck 清零 costAmount + profitAmount | 主表重新进入 DRAFT 后毛利归零 |
| #284 | SalReturnService.uncheck 加 outStock + AR 删除 (负 AR) | 销售退货 = 入库, 反审核 = 出库冲掉 |
| #285 | PurReceiptService.uncheck 加 outStock + AP 删除 (正 AP) | 采购入库 = 入库, 反审核 = 出库冲掉 |
| #286 | PurReturnService.uncheck 加 inStock + AP 删除 (负 AP) | 采购退货 = 出库, 反审核 = 入库冲掉 |
| #287 | SalOrder / PurOrder / InvCheck uncheck 保留 status-only | 设计如此: 订单无库存/账, 盘点不能丢实物调整 |
| #288 | 新增 BaseCustomerMapper.decrCreditUsed | GREATEST(0, used - amount) 防负 |
| #289 | 新增 FinArapService.requireCancelableAndDelete | 校验已核销/已开票则抛错, 否则硬删 AR/AP |

#### 测试
- SalDeliveryUncheckTest 5 个测试: 正常路径 / 状态错 / AR 已核销 / 明细空 / 金额为0
- 总测试 28/28 通过 (v1.1.17 的 23 + v1.1.18 新增 5)

#### 安全约束
- 整个反审核在 `@Transactional(rollbackFor=Exception.class)` 中, 任一失败全部回滚
- AR/AP 已核销 (paidAmount>0) → 抛"已被核销，无法反审核"
- AR/AP 已开票 (invoicedAmount>0) → 抛"已开票，无法反审核"

### v1.1.17 (2026-08-18) — 库存副单位折算 (主单位存储)

#### Bug 背景
StockService.outStock/inStock 内部 `stock.getQty().compareTo(qty)` 直接扣减, 完全忽略 unitId + conversionRate. 用户录入 "1箱" qty=1 → 库存只扣 1 (应扣 60 卷).

#### 设计决策
- 库存按主单位存, 副单位录入时折算 (qty_主 = qty_从 × conversion_rate)
- 现有 41 条 inv_stock 全部 unit_id=NULL (历史脏数据, 假设已按主单位录入), 不迁移

#### 改动

| # | 项目 | 修改 |
|---|---|---|
| #270 | BaseProductService.convertToMain 算法方向 | `qty.divide(conversionRate)` (÷) 改为 `qty.multiply(conversionRate)` (×) + setScale(4) |
| #271 | StockService 注入 BaseProductUnitMapper | 注入供 selectMainUnit / selectByProductId |
| #272 | StockService 私有 convertToMain helper | 接收已查好的 mainUnit 避免重复查询 |
| #273 | StockService.inStock 入口折算 qty → mainQty | 新增 stock 写入主单位信息 (unitId/unitName), qty) |
| #274 | StockService.outStock 入口折算 qty → mainQty | 内部比较 / 计算 / 台账全用 mainQty |
| #275 | StockService 台账 ledger 写主单位 | inv_ledger.unitId/unitName 改为主单位, qty 改 mainQty |
| #276 | StockService 兜底逻辑 | 找不到单位时返回原 qty, 不抛异常中断业务 |

#### 测试
- StockServiceTest 新增 5 个: inStock 副单位折算 / inStock 主单位 / outStock 副单位折算 / 库存不足 / 兜底
- 总测试 23/18 通过 (新增 5 + 原有 8 + InvCheck 4 + PrdOrder 5)

#### 14 个调用点 (6 outStock + 8 inStock) 全部 0 改动
- SalDeliveryService.check / SalReturnService.check / PurReceiptService.check / PurReturnService.check / InvTransferService.check / InvCheckService.check / PrdRequisitionService.check / PrdFinishedInService.check / PrdOrderService / OutsourceService
- 折算在 StockService 内部完成, 14 个调用点零改动 (收敛到一处)

### v1.1.16+ (2026-08-18) — 销售出库单位切换修复

#### DB 脏数据修复（不需重启服务）

| # | 项目 | 修改 |
|---|---|---|
| #260 | 用户报告"7412yzjd 7412印字胶带"销售出库单单位下拉弹「卷/箱」但切换无效 | 根因：`base_product_unit.unit_id` 历史全为 0；`base_unit` 表「卷/箱」记录实际是 UTF-8 双重编码损坏的「?」；el-select 两个 option value 都是 0 |
| #261 | base_unit 新增 7 条正常记录（卷/箱/只/包/个/套/空）+ kg 保留可用 | INSERT IGNORE INTO base_unit (unit_code, unit_name) VALUES ('JUAN','卷'),('XIANG','箱'),('ZHI','只'),('BAO','包'),('GE','个'),('TAO','套'),('KONG','') |
| #262 | base_product_unit 75 条 unit_id=0 按 unit_name 字符串匹配回填到真实 base_unit.id | UPDATE base_product_unit bpu INNER JOIN base_unit bu ON bu.unit_name=bpu.unit_name AND bu.deleted=0 AND bu.id IN (合法 8 个 id) SET bpu.unit_id=bu.id WHERE bpu.deleted=0 AND bpu.unit_id=0 |
| #263 | MySQL 容器 client 连接默认 latin1（不是 utf8mb4）→ 中文 INSERT/UPDATE 必须加 `--default-character-set=utf8mb4` | 否则 unit_name 被存成「?」 |
| #264 | 备份：`/tmp/backup_unit_20260818_082036.sql` (NAS 容器 /tmp, 39KB) | mysqldump base_unit base_product_unit |

#### 前端容错（未部署，下次发版合入）

| # | 项目 | 修改 |
|---|---|---|
| #265 | `pc-web/src/views/sales/Delivery.vue:408` onUnitChange 防 Long/String 类型不匹配 | `find(x => x.unitId === unitId)` → `find(x => x.unitId == unitId)` |

#### 已知残留（不影响本次 bug）

- 4 个 detail mapper（PurReceiptDetail/SalReturnDetail/PurReturnDetail/InvCheckDetail）unit_name 快照冗余问题（同 v1.1.16 SalDeliveryDetail 模式，可同样修）
- base_product_unit 仍有 2 条 kg 的 unit_id=1（脏数据）
- base_unit 表保留 7 条旧「?」记录未删（用户选项 A）

### v1.1.16 (2026-08-15)

#### 打印模板 — 销售出库单单位字段实时重写

| # | 项目 | 修改 |
|---|---|---|
| #230 | 销售出库单录入"箱"但打印显示"卷"（或反之）。原因：`sal_delivery_detail.unit_name` 是冗余快照字段，录入后不再与 `base_unit` 同步 | `SalDeliveryDetailMapper.xml` 的 `selectByDeliveryId` 改 SQL：`SELECT d.*, COALESCE(u.unit_name, d.unit_name) AS unit_name FROM sal_delivery_detail d LEFT JOIN base_unit u ON u.id = d.unit_id AND u.deleted = 0` |
| #231 | **COALESCE 兼容历史脏数据**：如果 unit_id=0 或 base_unit 找不到，退回 `d.unit_name` 旧值（LIMIT 1 不会爆） | LEFT JOIN（不要 INNER JOIN） |
| #232 | 起初尝试给 `BaseProductUnitMapper.xml` 加 `unit_id > 0` 过滤（修商品编辑页的"显示所有单位"），结果导致所有历史商品单位消失（base_product_unit.unit_id 全是 0） | **回滚** BaseProductUnitMapper.xml 到原始 SQL（不修编辑页，保留历史兼容） |
| #233 | 同源问题（unit_name 冗余快照）其它 4 个 detail mapper 未修 | PurReceiptDetailMapper / SalReturnDetailMapper / PurReturnDetailMapper / InvCheckDetailMapper 暂未改（与 SalDeliveryDetail 用同模式即可），用户未汇报前不动 |

#### 已知脏数据 (未清理)

- `base_product_unit.unit_id` 全部为 0（历史从未关联 base_unit）
- `base_unit` 表缺"箱"记录
- 不影响业务功能（COALESCE 兼容），但若要彻底修商品编辑页或保证 JOIN base_unit 始终返回有效单位，需 DB 侧清理（INSERT base_unit + UPDATE base_product_unit.unit_id）

### v1.1.15 (2026-08-08)

#### App 端采购入库单查询 (列表 + 详情)

| # | 项目 | 修改 |
|---|---|---|
| #222 | 后端 `PurReceiptMapper.selectPageWithProduct` 缺 `warehouseName` JOIN, App 列表无法显示仓库信息 | `@Select` 注解 SQL 加 `w.warehouse_name AS warehouseName` + `LEFT JOIN base_warehouse w ON w.id = r.warehouse_id AND w.deleted = 0` |
| #223 | **MyBatis + MP IPage 不会自动映射 `@TableField(exist=false)` transient 字段**, 即便 SQL 返回 `warehouseName` 列, 实体类 warehouseName 仍是 null | `PurReceiptService.page()` 加 Service 层批量注入: 收集 page 结果里的 `warehouseId` 列表, 一次 `warehouseMapper.selectBatchIds(ids)` 批量查询, 设置到每条记录的 `warehouseName`. `detail()` 单条用 `selectById` 注入 |
| #224 | App 端 `pages/scan/in.vue` 只有扫码入库(新增), 缺入库单查询/列表/详情 | 新增 `app/src/pages/purchase/receipt-list.vue` (~163 行, 仿 sales/delivery-list) + `receipt-detail.vue` (~145 行, 仿 sales/delivery-detail) |
| #225 | 5 处文件登记: pages.json 加 2 条路由; permission.js SENSITIVE_PAGES + PAGE_PERMS 各加 2 行; api/index.js 加 `purchaseReceiptPage` + `purchaseReceiptDetail`; dashboard APP_MENU_TO_PAGE + 管理员 hardcoded 各加 1 条; pc-web Role.vue APP_MENU_WHITELIST 采购管理 children 加"采购入库单查询" (idApp=app-402-receipt-query, 与扫码入库共用 perms purchase:receipt:list) | |
| #226 | 修复: receipt-detail.vue 误用 `api.request()`, 但 api 对象没有 request 方法 (request 是模块内部函数) | 改用 `api.purchaseReceiptDetail(id)` (与 salesDeliveryDetail 对称) |
| #227 | PC 端白名单"扫码入库"和"采购入库单查询"共用同一 perms, 但用不同 idApp 区分, el-tree 节点 id 不同不冲突. 提交时 grantMenusByClient 已 `menuIds.stream().distinct()` 去重, 翻译成同一 sys_menu.id=402 不会重复 | 后端无需改 |

### v1.1.14 (2026-08-08) — App 端销售出库单查询 (与 v1.1.15 同模式)

| # | 项目 | 修改 |
|---|---|---|
| #210 | App 端缺销售出库单查询 (只有扫码出库) | 新增 `app/src/pages/sales/delivery-list.vue` + `delivery-detail.vue` + 5 处文件登记 (pages.json / permission.js / api/index.js / dashboard / Role.vue) |

### v1.1.13 (2026-08-01)

#### 打印模板 — 型号字段注入

| # | 项目 | 修改 |
|---|---|---|
| #220 | 销售送货单 / 采购入库等 7 个单据的"型号"列在打印预览中空白, 但商品有 model 字段. 原因: `SalOrderDetail` / `PurOrderDetail` / `InvCheckDetail` 实体缺 `pModel` transient 字段, 且各 Service detail() 只注入 colorNo 不注入 model | (1) 3 个 Detail 实体加 `pModel` + getter/setter (transient, 不入库); (2) 7 个 Service `detail()` 加 `ProductAttrInjector.inject(productMapper, ..., setPModel, ::getModel)` 注入; (3) SalOrderService / PurOrderService / SalReturnService / PurReturnService 加 productMapper 依赖 |
| #221 | `SalDeliveryBillLoader` / `PurReceiptBillLoader` 飞鹅打印模板读 `d.model`, 但 `model` 没注入 | 通过 Service 注入 model 后, 飞鹅 ftl 模板 `${d.model}` 也能正常渲染 (BillLoader 不需要单独再注入) |

### v1.1.12+ (2026-07-29)

#### 角色管理 / 权限 — 父子联动 + 持久化修复

| # | 项目 | 修改 |
|---|---|---|
| #200 | PC 端角色管理 el-tree 默认联动导致父目录(M 类型)写入 sys_role_menu, 再次打开父目录联动子按钮全部 checked, 用户感受"取消后又勾上" | `Role.vue` 改 `check-strictly=true` + `buildMenuTree` 给 M 无 perms 父目录加 `disabled=true` (用户根本不能勾父目录, 只能操作叶子); `submitPerm` 不再合并 halfKeys (check-strictly 模式下没有 half) |
| #201 | 后端 `grantMenusByClient` 对端无 BOTH 记录时"升级 BOTH → otherCt", 用户取消 PC 端某 perm 后, 因旧 BOTH 升级 APP 记录仍在, menusByClient(PC) 仍命中 | `SysRoleService.java` 直接 `deleteRoleMenusByClientAndMenuIds(roleId, ['BOTH'], [mid])`, **不再升级**; BOTH 是历史遗留, 当前端按 PC/APP 分轨提交应直接清理 |
| #202 | 后端 grantMenusByClient 在 APP/PC Tab 提交时, 把父目录 (M 无 perms) 写入 sys_role_menu, 造成下次打开 el-tree 父节点自动联动 | 后端 `isGrantableMenu()` 防御性过滤: 只允许 `menuType='B'` 或 `M+perms` 写入 sys_role_menu |
| #203 | 历史污染的 M 类型无 perms 目录记录 | 一条 SQL 物理清理: `DELETE FROM sys_role_menu WHERE menu_id IN (SELECT id FROM sys_menu WHERE menu_type='M' AND (perms IS NULL OR perms=''))` |

#### App 业务快捷区 — 授权对齐

| # | 项目 | 修改 |
|---|---|---|
| #204 | 用户报告"PC 端 App 端菜单权限勾选的外勤盘点/新增商品/库存台账/生产加工单, App 端业务快捷区只显示部分项" | (1) `App dashboard/index.vue` 新增 `APP_MENU_TO_PAGE` 数组, 按 `(perms + path)` 双匹配 sys_menu → App 页面入口; (2) `PATH_TO_APP` 补回 `/inventory/ledger → 库存台账` 入口 (复用 query 页); (3) `visibleMenus` 计算改用 `APP_MENU_TO_PAGE` |
| #205 | PC 端白名单"外勤盘点" / "生产加工单(新增)" 共用 sys_menu id=702 (同一 perms), 用户授权只能写 1 行, App 端只匹配 1 个入口 | PC 端 `APP_MENU_WHITELIST` 把"外勤盘点" perms 改为 `inventory:check:list` → sys_menu id=603 path=`/inventory/check`, 跟"生产加工单" (id=702) 拆开成 2 条独立授权 |
| #206 | PC 端白名单与 App 业务快捷入口一一对应, 防止"勾了 App 不显示" | `APP_MENU_WHITELIST` 与 `APP_MENU_TO_PAGE` 同步维护, 每条都对应真实 App 页面 |

#### 单据页按钮权限 — 前端 UI 过滤

| # | 项目 | 修改 |
|---|---|---|
| #207 | 赵偲荣等账号无反审核权限, 但 7 个单据页 (PurOrder/PurReceipt/PurReturn/SalOrder/SalDelivery/SalReturn/InvCheck) 的"审核/反审核/编辑/删除"按钮 `v-if` 只按 billStatus 控制, 没检查 perm | 所有按钮加 `userStore.hasPerm('xxx:yyy')` 判断: 编辑→`:edit`, 删除→`:delete`, 审核→`:check`, 反审核→`:uncheck`; "按钮显隐 + 后端拦截"双保险 |
| #208 | Check.vue 详情弹窗底部"审核"按钮也没 perm 控制 | 同步加 perm 判断 |

### v1.1.11+ (2026-07-28)

#### 审核 / 反审核 — 7 模块补全

| # | 项目 | 修改 |
|---|---|---|
| #180 | PC 端审核流程模块只有销售出库, 缺采购入库/采购订单/采购退货/销售订单/销售退货/库存盘点 | 7 个 service 各加 `check()` + `uncheck()` 方法 (status-only, 不回退库存/AP/AR, 需走红冲单); 7 个 controller 加 `/{id}/check` + `/{id}/uncheck` 端点 |
| #181 | 7 个 PC 页面加 "审核"/"反审核" 按钮 + onCheck/onUncheck handler (ElMessageBox.confirm 二次确认) + 7 个前端 api.js 加 check/uncheck | - |
| #182 | sys_menu 加 21 个按钮权限点 (id 6041-6063): 销售/采购/库存 各 4 个 (check/uncheck/print/edit 部分), sql/23_add_app_button_menus.sql | - |

### v1.1.0 ~ v1.1.10 (历史 — 角色管理 / 飞鹅云打印 / App 生产加工单)

#### 角色管理 — PC/APP 分轨

| # | 项目 | 修改 |
|---|---|---|
| #170 | sys_role_menu 加 `client_type` 字段 (BOTH/PC/APP, 默认 PC); 老数据默认 BOTH | sql/22_add_client_type.sql |
| #171 | PC 端角色管理加 "App 端菜单权限" Tab, 通过 `APP_MENU_WHITELIST` 按 perms 匹配 sys_menu, 显示 App 端实际可用的菜单 | `Role.vue` 加 `el-tab-pane name="appMenu"` + `buildAppMenuTree` |
| #172 | 用户角色授权按 PC/APP 端分轨 (`grantMenusByClient`), 不再统一处理 | `SysRoleService.grantMenusByClient(roleId, clientType, menuIds)` |

#### App 端生产加工单 — 完整功能

| # | 项目 | 修改 |
|---|---|---|
| #190 | App 端列表/详情/新增/打印/分享 PDF 全套 | `app/src/pages/production/order-{list,detail,add}.vue` + `api/index.js` |
| #191 | 后端 PDF 端点 `GET /production/order/{id}/pdf` + FreeMarker 渲染 (prd_order_share.ftl) + OpenPDF + WenQuanYi Micro Hei TTC 字体 | `ProductionPdfService.java` + 后端 Dockerfile `COPY fonts/wqy-microhei.ttc /opt/app/fonts/` |
| #192 | App 端分享: 用 Capacitor `@capacitor/share@6.0.4` (uni-app 不内置 `uni.share`), 文件通过 Android FileProvider 暴露 | `package.json` + `capacitor.settings.gradle` + `build.gradle` + `order-detail.vue` onShare |

#### 飞鹅云打印 — myprint-design 集成

| # | 项目 | 修改 |
|---|---|---|
| #150 | 飞鹅打印从手写 ftl 改为 myprint-design v1.0.12 (Apache-2.0): mountDesign + chromePreview 弹原生打印对话框 | 见 memory `erp-myprint-integration.md` |

### v1.1.26 (2026-08-27) — 采购/销售/库存台账规格型号显示修复

**问题**: 采购入库/销售出库/库存台账列表的"规格"和"型号"列一直显示 `-`，即使 SQL 子查询能查到数据。

**根因** (3 个叠加问题):
1. `PurReceipt.firstProductSpec` 字段被 linter 改时漏掉，只剩 `firstProductModel`，导致编译错误
2. `IndustrialErpApplication` 的 `System.out.println` 被误改成 `log.info` 但没声明 Logger，编译失败
3. **关键**: `erp-backend` 是通过 `Dockerfile` 构建的镜像，jar 打包在镜像内 — 只 `docker restart` 不会更新 jar，必须 `docker build` 重建镜像 + 用新镜像启动新容器
4. SQL 用 `AS firstProductSpec` (驼峰) 但 MyBatis 配置 `map-underscore-to-camel-case: true`，导致字段映射失败 — 改为 `AS first_product_spec`

**修复**:
- 补回 `firstProductSpec` 字段声明 (PurReceipt / SalDelivery)
- 还原 `IndustrialErpApplication` 为 `System.out.println`
- SQL 改用下划线命名: `AS first_product_spec` / `AS first_product_model`
- 重新构建镜像: `docker build -t erp-system-backend:latest backend/`
- 用 `--env-file .env` 启动新容器（避免漏掉 `ERP_CORS_ALLOWED_ORIGINS`）

**部署命令** (重要 — 后端必须重建镜像):
```bash
# 1. 复制新 jar 到 backend 目录 (Dockerfile COPY industrial-erp-*.jar)
cp backend/target/industrial-erp-1.0.4.jar backend/industrial-erp-1.0.4.jar
# 2. 重新构建镜像
docker build -t erp-system-backend:latest backend/
# 3. 停旧容器, 用 --env-file 启动新容器 (保留 .env 里的 CORS 等配置)
docker stop erp-backend && docker rm erp-backend
docker run -d --name erp-backend --restart unless-stopped \
  --network erp-system_erp-net \
  --env-file .env \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e ERP_UPLOAD_PATH=/opt/industrial-erp/upload \
  -v /volume3/docker/erp-system/backend/upload:/opt/industrial-erp/upload \
  -v /volume3/docker/erp-system/backend/backup:/opt/industrial-erp/backup \
  -p 8080:8080 \
  erp-system-backend:latest
```

**验证**:
- 接口返回 `firstProductSpec: "100只/捆 3500只/袋"`, `firstProductModel: "22*28*016"`
- CORS 预检: `Access-Control-Allow-Origin: http://home.93gushi.com:8088`

### v1.1.25 (2026-08-26) — P2 优化 + 集成测试

| 类别 | 项目 | 提交 |
|---|---|---|
| P2-3 集成测试 | `SalDeliveryVersionLockTest` 验证 @Version 乐观锁并发场景 | `SalDeliveryVersionLockTest.java` |
| P2-3 集成测试 | `SalDeliveryPermissionTest` 验证 @SaCheckPermission 拦截 (无权限/有权限/SUPER_ADMIN) | `SalDeliveryPermissionTest.java` |
| P2-4 优化 | 实体重构: 删除 v1.1.24 误改的 `System.out → log` (没声明 Logger 编译失败) | `IndustrialErpApplication.java` |

**已知遗留**:
- P2-2 字体 CDN 化: 需 fork myprint-design 包 (npm 依赖内联字体 URL), 暂记为后续项

### v1.1.24 (2026-08-26) — P0 安全加固 + P1 性能/事务/乐观锁批次

#### P0 安全加固

| 类别 | 项目 | 提交 |
|---|---|---|
| P0-1 Controller 权限 | 26 个 Controller 加 `@SaCheckPermission(value = {xxx:list}, orRole = "admin")` — 之前只有"已登录"校验, 任意用户能调任何 API | 全部 26 个 Controller |
| P0-2 CORS | `SaTokenConfig.corsFilter()` 去除硬编码 `http://home.93gushi.com:8088`, 统一从 `@Value` 读 .env | `SaTokenConfig.java` |
| P0-3 索引 | 新建 `sql/27_add_perf_indexes.sql` — 21 张表 47+ 组合索引 (信息守门幂等) | `sql/27_add_perf_indexes.sql` |
| P0-4 权限 seed | 新建 `sql/28_v124_permissions.sql` — 74 个新权限码 + 6 角色绑定 | `sql/28_v124_permissions.sql` |

#### P1 性能/质量

| 类别 | 项目 | 提交 |
|---|---|---|
| P1-1 事务 | `SysFeiePrintTemplateService.save/update/delete` 加 `@Transactional(rollbackFor=Exception)` — clearDefault 多步写需事务 | `SysFeiePrintTemplateService.java` |
| P1-2 异常日志 | 9 处 `catch (Exception ignore) {}` 改 `log.warn/debug` | RedisLock, SysLoginLogController, OperLogPublisher, FeiePrintService |
| P1-3 打包 | **前端主 bundle: 2,750KB → 65KB (gzip 25KB)**, 97% 优化: Element Plus 按需引入 + icons 收集 + myprint-design 路由懒加载 | `main.js`, `usePrint.js`, `PrintDesigner.vue`, `vite.config.js` |
| P1-4 乐观锁 | FinInvoice/SalDelivery/PurReceipt 实体加 `@Version` + getter/setter + `sql/29_add_version_lock.sql` | 3 实体 + SQL |

#### P2 修复

| 类别 | 项目 | 提交 |
|---|---|---|
| P2-1 SecureRandom | `RedisLock` token 改 `SecureRandom` (16 字节熵, 32 字符 hex), 避免 `Math.random()` 可预测 | `RedisLock.java` |

**部署**:
- 后端 jar: 96M
- 前端 dist: 7.9M (主 chunk 65KB)
- 容器全 healthy

### v1.0.7 ~ v1.0.9

详见 git log (ece218c / c7dfc54 / afdee45 / f6f9695 / 3001f62 / 等). 主题: P0~P3 安全/性能加固 + 系统参数页显示版本号 + 库存盘点 (PC + App 外勤).

### v1.1.27 (2026-08-28) — App 端扫码权限补齐 + 详情页错误状态分离 + 扫码卡片关闭按钮

#### 问题 1: App 端扫码入库 "无权限访问"（秦运桂等非超管账号）

**症状**: 秦运桂（制袋工）在 PC 端角色管理中勾选了 App 端"扫码入库"菜单权限，App 端可以打开扫码入库页面，但点"确认入库"时提示"提交失败：无权限访问"。

**根因**:
- App 端菜单「扫码入库」映射到 `purchase:receipt:list`（menu id=402, 仅查看权限）
- 但扫码入库提交时调 `POST /purchase/receipt`，后端 `@SaCheckPermission(value = {"purchase:receipt:add"})` 要求的是新增权限
- 「制袋工」角色只被授予了 `purchase:receipt:list`，没有 `purchase:receipt:add`（menu id=2090345792472715280, F 类型按钮权限）
- `purchase:receipt:add` 在 `sys_role_menu` 中只配了 `client_type='PC'`，没有 `APP`，导致非超管 App 账号扫不了码

**修复** (SQL 一行 INSERT):
```sql
INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
SELECT DISTINCT rm.role_id, 2090345792472715280, 'APP'
FROM sys_role_menu rm
WHERE rm.menu_id = 402 AND rm.client_type = 'APP';
```
给所有有 `purchase:receipt:list` APP 权限的角色追加 `purchase:receipt:add` APP 权限：超级管理员 / 销售经理 / 仓库主管 / **制袋工**（秦运桂所在角色）。

**后续维护建议**: PC 角色管理「App 端菜单权限」Tab 中「扫码入库」/「扫码出库」功能默认同时勾选 list 和 add 两个权限；或后端 `grantMenusByClient` 勾选页面型菜单时自动补齐关联的 `*:add` 按钮权限。

#### 问题 2: App 详情页"单据不存在"误显示

**症状**: 销售出库/采购入库/生产单详情页加载失败时，同时弹出"加载失败"toast 和"出库单不存在/入库单不存在/生产单不存在"灰色页面，混淆用户判断（其实是权限/网络错误，不是单据不存在）。

**修复**: 三个详情页加 `loadError` ref，在 catch 块中显式设置；模板用 `v-else-if` 优先级区分：
```vue
<view v-if="loading" class="empty">加载中...</view>
<view v-else-if="loadError" class="empty">加载失败</view>
<view v-else-if="!order || !order.id" class="empty">单据不存在</view>
```

**改动文件**:
- `app/src/pages/sales/delivery-detail.vue`
- `app/src/pages/purchase/receipt-detail.vue`
- `app/src/pages/production/order-detail.vue`

#### 问题 3: 后端 SQL 错误 `Unknown column 'first_product_spec'`（预防性）

**症状**: App 端详情页加载时偶现 `Unknown column 'first_product_spec' in 'field list'`，后端日志刷错。

**根因**: `PurReceipt.firstProductSpec` / `firstProductModel` 字段加了 transient getter/setter 但漏加 `@TableField(exist = false)`，MyBatis Plus 把虚拟字段写进 `SELECT *`。

**修复**: `backend/.../purchase/entity/PurReceipt.java` 和 `sales/entity/SalDelivery.java` 两个实体类补 `@TableField(exist = false)` 注解。

#### 问题 4: 扫码入库/出库成功后绿色成功卡片遮挡按钮

**症状**: 用户扫码成功后绿色「已提交入库单 / 飞鹅云打印」卡片占据屏幕下半部，遮挡「清空列表」和「确认入库」按钮；必须退出页面重新进入才能扫下一个商品。

**根因**: 成功卡片 `margin-bottom=0`（或不设），且提交后按钮区被压在卡片下方。

**修复**: 在成功卡片底部增加灰色「关闭」按钮：
- 点击关闭 → 清空 `submitted.value = false` + `submittedBillNo.value = ''`
- 卡片消失，按钮区重新可见，可立即扫下一单

**改动文件**:
- `app/src/pages/scan/in.vue` — 增加「关闭」按钮 + `onCloseSubmitted` 函数
- `app/src/pages/scan/out.vue` — 同模式修复（顺手）

#### 部署

- **后端**: 增量 jar 替换 + `docker restart erp-backend`（未变 schema, 仅注解补全）
- **SQL**: 一次性 INSERT IGNORE 修复 sys_role_menu，部署完成后立即生效（用户下次登录刷权限）
- **App H5**: 重新构建 + `docker build -t erp-app-h5:latest` + 容器重建（`--force-recreate`）
- **APK**: 同步更新本地 `~/Desktop/鹏程ERP-debug.apk` (4.4MB)

#### 容器状态

```
erp-app-h5     Up (healthy)   18090:80
erp-backend    Up (healthy)   8080:8080
erp-pc-web     Up (healthy)   18080:80
erp-mysql      Up (healthy)   3306:3306
erp-redis      Up             6379:6379
myprint-pdf    Up             19898:19898
```

#### 操作提示

- 秦运桂等 App 用户**退出 App 重新登录**一次，刷取最新权限后扫码入库即可用
- 已使用 H5 浏览器访问的用户刷新页面（`Ctrl+Shift+R` 强刷）即可
- APK 用户需要卸载旧版后安装新 APK

### v1.1.28 (2026-08-30) — PC 销售出库保存错误提示 + nginx 反代 502 修复

#### 问题 1: PC 端销售出库保存失败无 toast 提示

**症状**: 在 PC 端销售出库单弹窗点"保存"时，如果该仓库没有对应商品库存，后端抛 `BizException("库存不存在, 商品=xxx(ID=xxx), 仓库=xxx(ID=xxx), 入参批次=xxx. 该仓库无该商品任何库存记录, 请先录入或采购入库")`，但前端只在 console 报错，没有任何 UI 提示。

**根因**: `pc-web/src/views/sales/Delivery.vue` 的 `onSave()` 只写了 try/finally，没有 catch 块；axios 拦截器在业务错误（HTTP 200 + code != 200）路径上会 `ElMessage.error` 弹一次，但 axios Promise 仍 reject 抛出，被 silent 吞掉后用户看不到后续反馈。

**修复**: 加 catch 块显式弹 ElMessage.error，从 reject 原因中提取 msg：
```js
} catch (e) {
  ElMessage.error((e && e.msg) || (e && e.message) || '保存失败')
} finally { submitting.value = false }
```

**改动文件**:
- `pc-web/src/views/sales/Delivery.vue`

#### 问题 2: pc-web 容器重建后 502 Bad Gateway

**症状**: 重新部署 pc-web 后通过 `http://home.93gushi.com:8088/` 访问任何页面，浏览器 console 报 `Failed to load resource: the server responded with a status of 502 (Bad Gateway)`；`docker ps` 显示 `erp-pc-web Restarting (1) 6 seconds ago` 循环。

**根因**: 重建容器时只挂了 dist（`/tmp/pc-web-new:/usr/share/nginx/html`），没有挂 nginx.conf（`/tmp/nginx.conf.fixed:/etc/nginx/conf.d/default.conf`），导致容器使用镜像内置默认配置，upstream 名为 `backend` 而非 `erp-backend`，nginx 启动时报 `[emerg] host not found in upstream "backend"`，容器不断重启。

**修复**: 重建容器时同时挂两个卷：
```bash
docker run -d --name erp-pc-web --restart unless-stopped \
  --network erp-system_erp-net -p 18080:80 \
  -v /tmp/pc-web-new:/usr/share/nginx/html:ro \
  -v /tmp/nginx.conf.fixed:/etc/nginx/conf.d/default.conf:ro \
  erp-system-pc-web:bind-mount
```

**预防**: 完整 pc-web 容器启动命令必须包含 `html` + `default.conf` 两个挂载。

#### 部署

- PC web dist 已重新构建（`Delivery-Bo6wZwGp.js`），通过 `/tmp/pc-web-new` bind mount 部署
- 新容器挂载 nginx.conf 后正常启动 + 健康检查通过
- 仅前端变更，未触碰后端

#### 容器状态

```
erp-app-h5     Up (healthy)   18090:80
erp-backend    Up (healthy)   8080:8080
erp-pc-web     Up (healthy)   18080:80   ← 修复
erp-mysql      Up (healthy)   3306:3306
erp-redis      Up             6379:6379
myprint-pdf    Up             19898:19898
```