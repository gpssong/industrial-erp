# 工业 ERP 系统 (industrial-erp)

**当前版本**: v1.1.115 (2026-10-22 — R21 雪花 id 精度丢失根治 + 幽灵仓库库存修复。(1) **根因**: App 端 `warehouseId: Number(this.warehouseId)` 把 19 位雪花 id 转回 JS Number, 双精度在 2^60 量级 ulp=256 → 真实成品仓 `2073701024629047297` 被低位抹成 `...7300`, 该 id 在 `base_warehouse` 里查无此仓 → 库存查询仓库列空白 + `GROUP BY s.warehouse_id` 把同一产品**裂成两行**。**静默错数据, 后端不报错**。(2) **同一 bug 让 10 张 App 成品入库单 `prd_order_id` 全部挂空** (存 ...9400 / 真实 ...9426), MES 报工↔成品入库↔批次溯源整条链断掉。(3) **数据修复** `sql/126_v1115_fix_ghost_warehouse_stock.sql`: 4 行幽灵 qty 累加进成品仓同产品行 + 幽灵行软删 + `prd_finished_in` 10 张单与 `inv_ledger` 18 条仓库回正 + `prd_order_id` 靠 `prd_order_no` 文本列救回 (已验证 4 个工单号全库唯一)。(4) **代码** App 端 7 行 `Number(id)` → 直传字符串 (零后端改动): `finished-in.vue`(warehouseId+prdOrderId) / `semi-in.vue`(同上) / `pat.vue`(sourceBillId) / `delivery-add.vue`(customerId+warehouseId)。(5) **灌 SQL 踩坑**: `inv_ledger` **只有 create_time 没有 update_time**, 写 update_time 会 Unknown column 整个事务回滚 — 同名表列名不一致必须先查 `information_schema`; home `gpssong` 无任何可写目录 (连 /tmp 都不行), 备份只能 `docker cp` 到 `/volume1/erp-backup/`。**home 已验证: 合并后 108500/32500/5600/86300 总量守恒 232900, 一产品一行, 工单关联 10/10 挂回。**)
**前版本 v1.1.114**: App H5 销售出库单「新建」功能 + R18 APP 授权补灌 + home 主站全量同步。
**前版本 v1.1.112**: R20 翻页 bug 全仓根治 26 处 (25 文件) + App H5 成品入库扫码关联工单 + v1.1.110 详情页开工修复。
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

## 设计原则 (R9~R21 — 完整版见 memory)

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
- **R20 el-pagination handler 必须显式接参**: `@current-change="fn"` 收到的是页码, handler 必须写成 `(p) => { if (p === pageNum.value) return; pageNum.value = p; load(); }` — 漏接参 = 翻页静默失效 (v1.1.109 全仓 26 处, 翻页自项目诞生起就没工作过)
- **R21 前端禁止 `Number(雪花id)`**: 19 位雪花 id 超 `MAX_SAFE_INTEGER`, 双精度在 2^60 量级 ulp=**256**, 低位被静默抹平 (`...297` → `...300`)。后端 `WebMvcConfig` 的 `ToStringSerializer` 只防了**输出**, **输入方向没人管** —— 前端随手一个 `Number()` 就绕过去, 后端 `Long` 字段照单全收写进一个**字典表里不存在的 id**。**故障是静默错数据不报错**: 坏 `warehouseId` → 库存查询仓库列空白 + 同产品裂成两行; 坏 `prdOrderId` → 工单关联挂空, MES/批次溯源整链断。**前端一律直传字符串** (后端 `Long` 接 JSON 字符串本来就没问题)。排查口诀: 看到「字段莫名空白 + 同一主体裂成多行」先查该 id 在字典表里是否**真的不存在**。全仓巡检 `grep -rn "Number(.*[iI]d)" app/src pc-web/src`

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
erp-app-h5      Up (healthy)  18090:80   (H5 dist bind-mount: /volume3/docker/erp-system/app-h5/dist/build/h5)
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

