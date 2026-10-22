# 工业 ERP 系统 (industrial-erp)

**当前版本**: v1.1.112 (2026-10-22 — R20 翻页 bug 全仓根治 + App H5 成品入库扫码关联工单。(1) **翻页 26 处**: 用户报「库存查询页无法翻页」, 全仓扫描发现同一 R20 bug (`@current-change="loadData"` handler 不接参 → `query.pageNum` 永不更新) 遍布 25 个文件 26 处, 含客户/商品/供应商/采购订单/采购入库/采购退货/销售订单/销售出库/销售退货/生产 BOM/半成品入库/财务发票/质量检验/设备维保计划/系统用户/角色/菜单/参数/打印模板/飞鹅日志/操作日志(双表)/工作流 4 页/XK 原料 — 全改显式接参 handler (同值短路 + 切页大小重置 pageNum=1); `SemiFinishedIn.vue` 是 ref 型需 `.value`, 脚本批量改时误写成 pageSize 已手工修正。(2) **App H5 成品入库扫码关联**: `app/src/pages/scan/finished-in.vue` 加「📷 扫码关联工单」按钮, 扫工单条码/单号二维码 → 先本地列表精确匹配 billNo, 未命中再调 `prdOrderPage({billNo})` LIKE 查后端 → 自动带入工单并带出成品/规格/计划量。(3) **v1.1.110**: `order-detail.vue` `onRelease()` 调 `loadOrder()` 漏传 id → 开工成功后清空 order 显示「生产单不存在」, 改 `loadOrder(orderId.value)`。**飞牛 canary + home 主站均已上线**。)
**前版本 v1.1.109**: PC finance Arap 应收应付页面翻页修复 (Arap.vue 首现 R20, 本次扩到全仓 26 处)。
**前版本 v1.1.108**: App APK 隐私/卫生第三批: (1) 关 uni-stat 遥测 — `src/manifest.json` 加 `h5.uniStatistics.enable=false`, H5/APK 产物不再向 DCloud 公网 `tongji.dcloud.io`/`tongji-collector.dcloud.net.cn` 上报设备/页面数据; (2) `index.html` SW 注册加 `!window.Capacitor` 守卫, 消掉 APK WebView 里常驻的 `Failed to register /static/sw.js` 报错 (PWA 离线缓存只给浏览器 H5); (3) `api/index.js` 抽 `logDebug()` 走 `import.meta.env.DEV`, 生产 H5/APK 静默 DEBUG 级日志, 保留网络层/非JSON 的 warn (R13 诊断价值). 纯 App 端, 不动后端/PC/SQL.

## 速查

- **完整变更日志**: [docs/CHANGELOG.md](docs/CHANGELOG.md) (v1.0.10 ~ v1.1.103 hotfix-1, 倒序)
- **部署速查**: [docs/17_群晖NAS部署指南.md](docs/17_群晖NAS部署指南.md) (home) + [docs/19_飞牛OS后端部署指南.md](docs/19_飞牛OS后端部署指南.md) (飞牛 canary)
- **必读 memory**: `~/.claude/projects/-Users-tongban/memory/MEMORY.md` — 50+ 条踩坑 + 设计原则 R1~R18

## 架构速览

Spring Boot 3.2.5 + JDK 17 + MyBatis-Plus 3.5.9 后端 (19 module, 346 Java 类) / Vue 3 + Element Plus + Vite PC Web (66 视图) / uni-app (Capacitor 6) App 端 (38 视图) / MySQL 8.0 + Redis 7.0 + Sa-Token 鉴权 / XK16-204 食品接触塑料合规中心 / MES 车间闭环 (工单+扫码报工+批次追溯+排程+OEE) / 工作流(维保/安检/应急/租客) / AI 助手 (Agnes+MiniMax failover) / MCP (Hermes 接入).

## 模块入口映射

| 模块 | 后端 module | SQL seed | PC 视图 | App 页面 |
|---|---|---|---|---|
| 生产(MES 一二+XK 联动) | `production` `mes` | sql/53 / sql/113 / sql/119 | pc-web/src/views/production/ | app/src/pages/production/ |
| 采购(XK 三步门禁) | `purchase` | sql/100_v195_pur_receipt_xk / sql/115~117_v1101 | pc-web/src/views/purchase/Receipt.vue | app/src/pages/scan/in.vue |
| 销售 | `sales` | (基础) | pc-web/src/views/sales/ | app/src/pages/sales/ |
| 库存 | `inventory` | (基础) | pc-web/src/views/inventory/ | app/src/pages/inventory/query.vue |
| 质量(QMS 5 类检验) | `qms` `qa` | sql/54_v190 / sql/104_v197 / sql/105_v197 | pc-web/src/views/quality/ + xk/IqcLedger.vue | - |
| 设备维保 | `em` | sql/102_v196_em_maintain | pc-web/src/views/equipment/ | app/src/pages/equipment/ |
| 工作流(维保/安检/应急/租客) | `workflow` | sql/50 / sql/50b_v179_workflow_uploads | pc-web/src/views/workflow/ | - |
| XK 合规中心 | `xk` | sql/55_xk_compliance | pc-web/src/views/xk/ | app/src/pages/xk/ |
| AI / MCP 对接 | `ai` `mcp` | sql/48_v176_ai_perm / sql/51_hermes_mcp_bot | pc-web/src/views/report/Ai.vue + system/Integration.vue | app/src/pages/ai/ |
| 系统管理 | `system` | (基础 + 增量 perm seed) | pc-web/src/views/system/ (Role / Menu / AppModules / Backup / OperationLog / Integration) | app/src/pages/system/ |

## 设计原则 (R9~R19 — 完整版见 memory)

- **R9 后端按端分流 perm**: `perms_by_user_client(uid, X-Client-Type)` → 不能给 PC/APP 混合 perm 数组 (App storage 残留会导致按钮误显, 见 v1.1.55 hotfix-3)
- **R10 sys_menu.perms 必须 UNIQUE 索引**: 否则 `INSERT IGNORE` 漏检导致 B+F 行并存弹窗双勾 (v1.1.56 hotfix-1 教训)
- **R11 Vue 3 v-if 不要引用 setup 函数引用**: `v-if="(c && fn) || (d && fn2)"` 中 fn/fn2 是 closure 永远 truthy, 必须 `fn()` 显式调用 (v1.1.59 教训)
- **R12 物理 back = 回退 ≠ 退出**: 工作台吞掉事件不退出 App, 栈深 ≥3 走 navigateBack, 栈深 =2 走 switchTab 工作台 (v1.1.60)
- **R13 拦截器已弹 toast, 组件 catch 不重复弹**: Android `uni.showToast` 后调用中断前调用, 用户只看后 catch 弹的无信息量那条
- **R14 APK 产物校验必须分两层**: 源码 grep 三处版本一致 + APK 内嵌 chunk grep 版本字面量 = manifest.versionName (否则 dist 陈旧)
- **R15 版本字面量分散 3 源 + 1 env, 极易漂移**: 4 处必须同步 (`pc-web/package.json` + `application.yml` + `SystemVersionInitializer` 默认 + 容器 `ERP_VERSION`), 前端必重打 dist
- **R16 showLoading/hideLoading 配对**: try/catch/finally 三处至少一处 hideLoading; 不 showLoading 的函数 catch 里也不 hideLoading (完整模板见下方)
- **R17 后端 jar 镜像内置 → 三连部署**: home `backend` 是 baked-in jar (`COPY industrial-erp-*.jar app.jar`), `docker restart` 不拉新 jar, 必须 `build --no-cache` + `docker rm -f` + `docker run`; 飞牛 baked-in 同样坑; **`docker cp` 只对挂载的 host jar 生效**
- **R18 sql seed perm 行必须同时给 PC + APP 双授权**: 凡 seed perm 节点都给 6 内置角色 × 双 client_type, 漏一个客户端就 dashboard 少图标 (v1.1.103 #3 教训), 巡检: `SELECT client_type, COUNT(*) FROM sys_role_menu GROUP BY client_type` 期望 5-10 倍 PC>APP 差距
- **R19 App 运行时探测只认 `window.Capacitor`, 不认 `plus`**: 本项目 APK 是 **Capacitor 6** 不是 HBuilder, WebView 里**没有 `plus`** → `typeof plus === 'undefined'` 会把 APK 误判成 H5, `getBase()` 变 `/api` → 请求打回 WebView 自己的 localhost 拿到打包进去的 `index.html` (v1.1.105 APK 完全无法登录)。**判定一律 import `app/src/utils/runtime.js`** (`isNativeApp()` / `isH5Runtime()`), 全仓禁止再写内联 `typeof plus`; 配套 **CORS 必须放行无端口 `http://localhost`** (`androidScheme=http` 的 Capacitor origin 不带端口, 只配 `localhost:*` → 实测 403), 见 ADR-0007。排查 APK 问题先看 logcat `[api] DEBUG base:` 一行

## R16 完整模板

```js
async function onXxx() {
  if (typeof uni !== "undefined" && uni.showLoading) uni.showLoading({ title: "Xxx中...", mask: true })
  try {
    await api.xxx()
    if (typeof uni !== "undefined" && uni.hideLoading) uni.hideLoading()
    // 业务 toast
  } catch (e) {
    if (typeof uni !== "undefined" && uni.hideLoading) uni.hideLoading()
    // 业务弹窗 / 静默 (R13)
  } finally {
    if (typeof uni !== "undefined" && uni.hideLoading) uni.hideLoading()
  }
}
// 例外: 不 showLoading 的函数 catch 里也不 hideLoading
```

## 容器/进程速查

```
erp-mysql       Up (healthy)  3306:3306 (内部)
erp-redis       Up            6379:6379 (内部)
erp-backend     Up (healthy)  8080:8080  (后端 baked-in jar)
erp-pc-web      Up (healthy)  18080:80   (PC dist bind-mount: /volume3/docker/erp-system/pc-web-new/dist)
erp-app-h5      Up (healthy)  18090:80   (H5 dist bind-mount: /volume3/docker/erp-system/app/dist/build/h5)
```

外部域名: `http://home.93gushi.com:8088` (PC) / `http://home.93gushi.com:18090` (App H5).

## 双站分工

- **home 主站 (192.168.0.150, Synology DS918+)** — 生产数据, baked-in jar 镜像
- **飞牛 canary (192.168.0.32)** — 新版本预发, `cat|ssh` 流 jar, `docker cp` 覆盖 `erp-backend-failover:/opt/app/app.jar` + `docker restart`

**部署顺序**: 飞牛 canary 先行 24h+, 验证无问题再同步 home (canary-first 原则).

---

## 历史变更日志

CLAUDE.md 3186 行拆分后, 完整正文 → [docs/CHANGELOG.md](docs/CHANGELOG.md).
下方仅保留 v1.1.79 hotfix-1 之前的最早期正文 (仍以原貌存档, 维持 git blame 可追溯性).

---



## 历史变更日志

CLAUDE.md 第二轮拆分后 (2026-10-20), 行 91~3093 完整历史正文已下沉到 [docs/CHANGELOG.md](docs/CHANGELOG.md).

为保持 git blame 可追溯性, 完整内容已在新章节 `### [HISTORICAL - PRE-1.1.60] (2026-10-20 — CLAUDE.md 第二轮拆分, v1.1.79 hotfix-1 之前所有版本正文归档)` 中.

---

