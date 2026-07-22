# 工业 ERP 系统 (industrial-erp)

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