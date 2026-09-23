> **文档说明**: v1.1.49 起 changelog 拆分为 `docs/CHANGELOG.md` (完整历史) + 本文件顶部 (当前版本摘要)。
> 本文件保留"当前版本"标题段 + 关键架构决策 + 部署速查。

## changelog (倒序)
### v1.1.59 (2026-09-23) — R11 终极根因修复: v-if 函数引用永远 truthy

**症状**: v1.1.58 hotfix APK 装到 gpssong1 (WAREHOUSE_MGR + FINANCE) 手机, 进 RKP202609220004 详情, 底部**仍显示【反审核】黄色按钮**。所有数据层面 (后端 `/auth/me` 返回 12 perm 不含 uncheck + DB 干净 + storage 全清干净 + APK chunk MD5 匹配) 都已修复, 但按钮**仍然显示**。

**根因 (R11)**: Vue 3 编译器对 v-if 表达式里的 setup 局部函数引用不会自动包 `()` 调用, 因为 setup 里 `function canCheck(){}` 是局部声明, render 通过闭包访问而非 reactive proxy, 编译器按字面表达式编译成 closure 引用。

```js
// 源码 (R11 bug):
v-if="(order.billStatus==='DRAFT' && canCheck) || (order.billStatus==='CHECKED' && canUncheck)"

// 编译产物 (Vite ES module minified):
"DRAFT"===billStatus && D || "CHECKED"===billStatus && A
// D/A 是函数引用 (closure), JS 里永远 truthy, v-if 永远渲染
```

**修复**: 显式调用 `canCheck()` / `canUncheck()`:

```vue
<!-- 改后 (R11 fix): -->
<view v-if="(order.billStatus==='DRAFT' && canCheck()) || (order.billStatus==='CHECKED' && canUncheck())">
```

编译产物变成 `"DRAFT"===billStatus && (b()...) || "CHECKED"===billStatus && (h()...)`, 真正调用函数, 函数返回 false 时 v-if 正确不渲染。

**部署**:
- 改 2 文件: `app/src/pages/purchase/receipt-detail.vue` L88 + `app/src/pages/sales/delivery-detail.vue` L88
- `bash scripts/build-app.sh` → APK MD5 `c02bbc8f30b0f8050c35df6b8c760682`, 4369473 字节
- 输出: `/Users/tongban/Library/CloudStorage/飞牛同步-Mac/erp/erp-app-20260923.apk`
- 不动: 后端 jar / pc-web dist / DB / SQL (纯前端编译产物修复)

**端到端验证 (2026-09-23, CDP)**: WebView devtools socket (绕过 Origin header raw websocket) 实测 — 新 APK 装上后 `uncheckBtn=0`, action-card 不渲染 ✅

**R11 设计原则 (写入 CLAUDE.md)**:
- Vue 3 v-if 直接引用 setup 函数 → 永远 truthy
- 永远用 `fn()` 显式调用, 或改用 `computed(() => ...)`
- 这个 BUG 与 storage / 后端 / DB / chunk 全部无关, 是 Vue 3 编译器边界 case
- 排查按钮误显示问题: 1) CDP 读 DOM; 2) grep 编译产物 v-if; 3) 看是否函数引用被当成 truthy

### v1.1.55 hotfix-3 (2026-09-22) — R9 后端按 X-Client-Type 分流 permissions + sql/39 撤 WAREHOUSE_MGR PC uncheck

**症状**: hotfix-2 (前端 getAppPermissions 派生) 部署后, gpssong1 (WAREHOUSE_MGR) 仍能通过老 APK storage 残留绕过, App 端入库单/出库单详情显示【反审核】按钮; 同时用户报告 PC 端反审核按钮也显示, 即使 popup 里"采购入库反审核/销售出库反审核" UNCHECKED。

**根因** (Phase 2 调研 + live API 验证):
1. 后端 `selectPermsByUserId` 不过滤 client_type, 返回 PC+APP 混合 111 个 perm; WAREHOUSE_MGR 因 sql/35 1.3 cross-join 绑了 PC `:uncheck` 授权
2. App 端 `getAppPermissions()` fallback 链脆弱 (老 APK storage 残留 → fallback 混合 perm → 按钮误显)
3. PC 端 `userStore.hasPerm` 查混合数组 → PC uncheck 命中 → 按钮也误显; popup 取消勾选不同步 sys_role_menu 行

**修复** (R9 后端彻底按端分流 + sql/39 物理撤 PC uncheck):
1. **后端 `SysMenuMapper.xml` + `SysMenuMapper.java`** 新增 `selectPermsByUserIdAndClient(userId, clientType)` — 复用 `rm.client_type IN ('BOTH', #{clientType})` 过滤
2. **后端 `AuthService.java`** 抽 `resolveClientType` + `permsByClientType` 工具方法, login + currentUser 都按 header 选 SQL
3. **后端 `AuthController.java`** `/me` 接 HttpServletRequest 透传 header
4. **App 端 `src/api/index.js`** 2 处加 `header['X-Client-Type'] = 'APP'` (uni.request + fetch)
5. **`sql/39_v155_hotfix3_revoke_warehouse_uncheck_pc.sql`** 物理 DELETE WAREHOUSE_MGR PC `:uncheck` 两行, 保留 5 其他内置角色

**live 验证** (home 192.168.0.150:8080, gpssong1 登录):
| 调用 | perms 数 | 含 `:uncheck` |
|---|---|---|
| `/me` 不带 header (混合) | 109 | 无 ✓ |
| `/me` 带 `X-Client-Type: APP` | **12** | 无 ✓ |
| `/me` 带 `X-Client-Type: PC` | 108 | 无 ✓ |

**R9 设计原则**: 后端返回的 `permissions` 必须按请求方端别分流 (`APP`/`PC`), 不能 PC+APP 混合给; 前端任何 storage 派生只能作 fallback, 不能作唯一来源 (受 APK 升级/重装/老版残留影响); 后端分流触发点 = 请求 header (X-Client-Type), 不依赖用户 clientScope。

### v1.1.55 hotfix-2 (2026-09-21 晚) — App 端 canCheck/canUncheck 走 APP 端 perm (非 PC+APP 混合)

**症状**: sql/38 撤销 WAREHOUSE_MGR uncheck APP 后, gpssong1 退出 App 重登, **App 端销售出库详情仍显示【反审核】按钮**。

**根因 — 后端 selectPermsByUserId 不分端**:
```sql
-- SysUserMapper.xml selectPermsByUserId
SELECT DISTINCT m.perms
FROM sys_user_role ur
INNER JOIN sys_role_menu rm ON rm.role_id = ur.role_id
INNER JOIN sys_menu m ON m.id = rm.menu_id AND m.deleted = 0
WHERE ur.user_id = #{userId} AND m.perms IS NOT NULL AND m.perms != ''
```
**没有 `WHERE rm.client_type='APP'` 过滤**, 返回 PC+APP 混合 perm. sql/38 撤销了 APP 端 uncheck, 但 PC 端 uncheck 仍绑 WAREHOUSE_MGR → gpssong1 的 perm 数组里**仍然有** `sales:delivery:uncheck`.

App 端 `canUncheck() = getPermissions().includes('sales:delivery:uncheck')` 命中 → 按钮误显示。

**修复 — 前端从 erp_menus (按 APP 严格过滤) 派生纯 APP 端 perm 数组**:
1. **`app/src/utils/permission.js`** 新增 `getAppPermissions()` 函数, 从 `erp_menus` (后端 `selectMenusByUserIdAndClient(uid, "APP")` 返回) 的每条菜单 `perms` 字段(逗号分隔)展开。fallback 链: `erp_app_permissions` storage → `erp_menus` 实时派生 → `erp_permissions` (兼容老 APK)。
2. **`app/src/pages/dashboard/index.vue` onMounted** 多写一份 `erp_app_permissions` storage: `/me` 响应拿到 `meResult.appMenus`, 遍历 `perms` 字段(逗号分隔)→ 去重 → 写入 `localStorage.setItem('erp_app_permissions', ...)`。
3. **`app/src/pages/sales/delivery-detail.vue` + `purchase/receipt-detail.vue`** `canCheck()` + `canUncheck()` 改用 `getAppPermissions()` 而非 `getPermissions()`。

**部署**: APK 重打 MD5 `8ad88f01d723a8264b23dfae130b3241` (4369487 字节, home + 飞牛双站部署)。

**不动的部分**:
- PC 端 `userStore.hasPerm` 仍走 `erp_permissions` 混合数组 — 业务上 PC 端 perm 检查不区分端(只要有权限就放行), 这正是 PC 端 uncheck 业务保留的目的
- 后端 SQL 不动: 改 `selectPermsByUserId` 加 `client_type` 过滤会破坏 PC 端 `hasPerm` 业务逻辑(PC 端要查全部 perm 包括 PC 专用)

**经验 (R8)**:
- 后端 `selectPermsByUserId` 不分端是**历史设计** (返回全部 perm 给前端, 前端按需自取)
- 优点: PC + APP 一套 API, PC 端 hasPerm 业务不受影响
- 缺点: App 端**必须**用 `erp_menus` (按 client_type 严格过滤) 派生 perm 数组, 不能直接用 `erp_permissions`
- 后续任何 App 端按钮显隐 / 页面 perm 检查都应走 `getAppPermissions()` (App 端专用), 不要直接调 `getPermissions()` (混合端)

**文件清单**:
- `app/src/utils/permission.js` (新增 `getAppPermissions()` 函数, 110 行内)
- `app/src/pages/dashboard/index.vue` (onMounted 多写 `erp_app_permissions` storage)
- `app/src/pages/sales/delivery-detail.vue` + `app/src/pages/purchase/receipt-detail.vue` (canCheck/canUncheck 改 `getAppPermissions()`)
- APK 重打 MD5 `8ad88f01d723a8264b23dfae130b3241` 双站部署
- 后端 / pc-web / DB **不动**

### v1.1.55 hotfix (2026-09-21 晚) — 撤销 WAREHOUSE_MGR 自动 uncheck APP

**症状**: gpssong1 (WAREHOUSE_MGR) 在 PC 端分配权限弹窗里**没勾**「采购入库反审核」「销售出库反审核」, 但 App 端销售出库详情 (CKP202609210003 已审核) **显示了黄色【反审核】按钮**。用户判断: 这是 BUG, 没勾就不该出现按钮。

**根因**:
- sql/35 第 2-3 段 "有 check APP 补 uncheck APP" 自动授权策略, 对 WAREHOUSE_MGR (仓库主管) **业务不合理** — 仓管员"能审核 ≠ 敢反审核" (反审核回退库存/AP/AR, 高风险)
- 这条策略对 6 个内置业务角色 (SUPER_ADMIN/PURCHASE_MGR/SALES_MGR/FINANCE/PRODUCTION_MGR) 业务合理, 但 WAREHOUSE_MGR 不该自动给
- PC 端 Role.vue 显示的勾选状态反映 sys_role_menu 实际绑定, 用户从未真点过保存, 系统维持 sql/35 自动授权结果

**修复**:
1. **sql/37_v155_hotfix_uncheck_app_full.sql** — 全量触发器给所有"有 check APP 没 uncheck APP"角色补 uncheck APP (兼容历史漏配, 比如 sql/35 第一次跑时 uncheck F 类型 sys_menu 行没 seed 出来的情况)
2. **sql/38_v155_hotfix_revoke_warehouse_uncheck.sql** — 物理 DELETE WAREHOUSE_MGR 的 `purchase:receipt:uncheck APP` + `sales:delivery:uncheck APP` 两行 (F 类型 2090345792472715355 + B 类型 6050, 飞牛上两行都有), PC 端 uncheck 保留
3. 部署: home + 飞牛 双站完成, 校验 WAREHOUSE_MGR 现在 `pur_uncheck_app=0, sal_uncheck_app=0, pur_uncheck_pc=1, sal_uncheck_pc=1`
4. 用户操作: gpssong1 **退出 App 重登一次** → App 端反审核按钮消失 (Sa-Token session 缓存的 perm 数组从 DB 重新加载)
5. **PC 端不变** — uncheck 仍绑, 业务上仓库主管可通过 PC 反审核 (PC 业务流需要)

**经验 (R7)**:
- **自动补授权策略只适用于 6 个内置业务角色**, 这些角色业务上"能审核就能反审核"
- WAREHOUSE_MGR 这类"可正向不能反向"角色应**手动**走 PC 端 Role.vue 勾选 uncheck APP, **不要依赖 sql 触发器自动补**
- 后续新增反向操作 perm (红冲/补差/撤销), 拆分原则: **业务上"能正向 ≠ 敢反向"的角色** 必须**手动**勾选 uncheck, 任何自动补授权都会变成 BUG
- 不要写 `INSERT IGNORE` 让它自动扩散授权范围 — 业务权限应显式授予, 不应隐式扩散

**文件清单**:
- `sql/37_v155_hotfix_uncheck_app_full.sql` (新增, 全量触发器补 uncheck APP)
- `sql/38_v155_hotfix_revoke_warehouse_uncheck.sql` (新增, DELETE WAREHOUSE_MGR uncheck APP)
- `sql/35_v155_uncheck_perm.sql` (顶部加 hotfix 警告 + R7 原则)
- `docs/CHANGELOG.md` + `CLAUDE.md` + 记忆 `erp-app-audit-perm.md` (更新)
- 后端 / App / pc-web **不动** — 纯 SQL 修复

### v1.1.56 (2026-09-21) — 采购入库单查询独立 perm (`purchase:receipt:query`) 修复 App 端入口缺失

**症状**: gpssong1 (WAREHOUSE_MGR) 在 PC 端勾选了「采购入库单查询」App 菜单权限, 但 App 端工作台没有「采购入库单」入口。销售侧对称的「销售出库单查询」入口正常。

**根因** (Phase 1 调研):
- App 工作台 `app/src/pages/dashboard/index.vue` 的 `APP_MENU_TO_PAGE` 按 **(perms, path) 双匹配** 生成快捷入口, `visibleMenus` 用 `find()` 取第一个匹配。
- sys_menu 里「扫码入库」和「采购入库单查询」**复用同一条 sys_menu 402** (perms=`purchase:receipt:list`, path=`/purchase/receipt`)。
- `APP_MENU_TO_PAGE` 同时有两条命中 402: L135 扫码入库 (`/pages/scan/in`) + L146 采购入库单 (`/pages/purchase/receipt-list`), `find()` 永远返回 L135 → **L146 采购入库单被永久遮蔽**, 任何非超管账号都看不到「采购入库单」入口。
- 销售没出事: 销售出库单 (sys_menu 502, `sales:delivery:list`) vs 扫码出库 (sys_menu 503, `sales:return:list`) perms 不同, 不撞车, 所以销售两个 App 入口都能显示。

**方案** (对齐销售 502/503 模型, 给采购入库单查询独立 perm):
1. **新建 `sql/36_v156_receipt_query_perm.sql`**:
   - sys_menu 新增 `purchase:receipt:query` (F 类型, parent_id=0, is_visible=0, sort_no=2016 紧邻 uncheck 2015)
   - 给所有 `purchase:receipt:list` APP 授权角色自动补 `purchase:receipt:query` APP ("有 list 才有 query", 沿用 sql/33 模式, 保证仓管/制袋工入库单查询入口出现, 扫码入库不受影响)
   - 校验查询确认 WAREHOUSE_MGR/zdg 等 list APP 角色同时拿到 query APP
2. **App 端**:
   - `app/src/pages/dashboard/index.vue` `APP_MENU_TO_PAGE` L146: `purchase:receipt:list` → `purchase:receipt:query`, 与 L135 扫码入库 (仍 `purchase:receipt:list`) 分离
   - `app/src/utils/permission.js` `PAGE_PERMS` receipt-list/receipt-detail: `purchase:receipt:list` → `purchase:receipt:query`
3. **PC 端**: `pc-web/src/views/system/Role.vue` `APP_MENU_WHITELIST`「采购入库单查询」`perms` 改 `purchase:receipt:query`
4. **后端不动**: 查询端点 (`PurReceiptController /page + /{id}`) 仍走 `purchase:receipt:list` (扫码入库也调它); `query` perm 纯是**入口显隐**级 perm 载体, 与销售的 502/503 双 sys_menu 模型对称

**部署**:
- home + 飞牛 各跑 `sql/36_v156_receipt_query_perm.sql` (飞牛走 `bash --noprofile --norc` + `LANG=C` sudo 模式, 避免 stdin mangling)
- 校验: home WAREHOUSE_MGR `pur_list_app=1, pur_query_app=1`; 飞牛同 (注: 飞牛与 home 的 `pur_list_app` 各角色授权有既有差异, 属预期, sql/36 的 query APP 授权镜像 list APP, 不影响 gpssong1)
- App APK 重打 (`bash scripts/build-app.sh`), 校验 `purchase:receipt:query` 在 dashboard + permission chunk, `purchase:receipt:list` 仍在 (扫码入库/库存 tab 不变), 部署 home + 飞牛
- pc-web dist 重打 (`npm run build`), 校验 `purchase:receipt:query` 在 Role chunk, 部署 home (`/tmp/pc-web-new`) + 飞牛 (`/vol2/erp-system/pc-web/dist-new` sudo swap), `docker restart` 两站
- **App 用户退出重新登录一次**, Sa-Token session 缓存的 perm 数组从 DB 重新加载, 采购入库单查询入口才会出现

**用户操作**:
- **gpssong1 等 App 端仓管/制袋工账号退出 App 重新登录一次** — 采购入库单入口才可见
- 重新安装 `/Users/tongban/Desktop/erp-app-20260921.apk` (MD5 `66e7048304c64ba0234be9459ca229ef`) 才能拿到新 dashboard 匹配逻辑

**v1.1.56 hotfix (2026-09-21 晚)** — F 类型 perm 载体 `path=''` 不进 (perms,path) 双匹配, 改 perm-only 入口:

**症状**: v1.1.56 上线后 gpssong1 重装 APK + 重登 App, 「采购入库单」入口**仍未出现**。超管账号正常。

**根因** (Phase 2 排查):
- `purchase:receipt:query` 是 F 类型 perm 载体, sys_menu 行 `path=''` (因为纯 perm, 没有路由)。
- 但 `APP_MENU_TO_PAGE` L146 / v1.1.56 改后的 L148 `entry.path='/purchase/receipt'` 不等于 `m.path=''`。
- 后端 `selectMenusByUserIdAndClient` 返回的 menu 行带 `path=''`, L184-194 loop `entry.path === m.path` 永远 false → query 入口**无法命中双匹配**。
- 对比 `report:view` (sys_menu id=951, path='', menuType=F) — `report:view` 走 L196-204 的 perm-only 兜底分支 (因 sys_menu 951 同样 path=''), 所以经营简报入口能出来。`purchase:receipt:query` 之前没这个 perm-only 兜底, 所以漏了。

**修复**:
- `app/src/pages/dashboard/index.vue` L196-204 之后追加 perm-only 兜底 (镜像 `report:view` 模式):
  ```js
  // v1.1.56+: purchase:receipt:query 与 report:view 同理 — F 类型 perm 载体 sys_menu 行 path 为空
  try {
    const perms = JSON.parse(localStorage.getItem('erp_permissions') || '[]')
    if (Array.isArray(perms) && perms.includes('purchase:receipt:query') && !seen.has('/pages/purchase/receipt-list')) {
      seen.add('/pages/purchase/receipt-list')
      result.push({ path: '/pages/purchase/receipt-list', title: '采购入库单', icon: '🧾' })
    }
  } catch (e) {}
  ```
- 移除 `APP_MENU_TO_PAGE` L148 的 `purchase:receipt:query` 条目 (dead code — 因 path 不匹配, 永远不命中), 保留注释说明。

**部署**:
- App APK 重打 (MD5 `9fa92d720ad5a1e279b769c597197a8a`), 部署 home + 飞牛
- 后端 + pc-web **不动** (PC 端 Role.vue 上次已对齐, 后端 query 入口路由不依赖 perm, 仅前端 perm-only 分支变化)
- 校验: APK `pages-dashboard-index` chunk 含 `purchase:receipt:query`

**用户操作**:
- gpssong1 等受影响 App 用户**重新安装新版 APK** (`/Users/tongban/Desktop/erp-app-20260921.apk`, MD5 `9fa92d72`) — 旧 APK dashboard chunk 没这个 perm-only 兜底, 必须更新
- 然后**退出 App 重新登录一次** — Sa-Token perm 数组从 DB 重载

**经验 (R6)**: App 端"perm 载体"型 sys_menu (F 类型, path='', 仅做授权) 不要走 `APP_MENU_TO_PAGE` 双匹配 (永远 path 不匹配), 必须走 perm-only 兜底分支 (同 `report:view`)。新增"perm 载体型" App 入口时, 把这条规则写进模板 — 不要再依赖 (perms,path) 双匹配。

### v1.1.55 (2026-09-21) — 反审核独立 perm (`xxx:uncheck`) + PC 端 4 年 BUG 修复

**症状**: v1.1.54 上线后,用户反馈 App 端"无反审核权限的账号不显示反审核按钮" 应当实现 (但 v1.1.54 审核/反审核共用 `xxx:check` 一个 perm, 无法精细控制"能审核" vs "敢反审核")。业务上反审核是回退库存/AP/AR 的高风险操作,通常只给老板/主管/超管。

**调研关键发现** (Phase 1):
1. **后端 Service 层早就在用 `:uncheck` perm**: `PurReceiptService.java:322` + `SalDeliveryService.java:386` 都 `permService.requirePerm("xxx:uncheck")`,但 controller 层 `@SaCheckPermission("xxx:check")` 用 AOP 先拦截,service 层永远到不了 → 现状 controller 注解才是真生效 perm
2. **PC 端前端早就在用 `:uncheck` perm 检查**: `pc-web/src/views/purchase/Receipt.vue:45` + `pc-web/src/views/sales/Delivery.vue:60` 都 `userStore.hasPerm('xxx:uncheck')`,但 sys_menu 从未 seed 过该 perm → **PC 端反审核按钮 4 年来对所有非超管账号永久隐藏** (隐性 BUG)
3. **PermissionService.hasPerm 有 fallback**: `:add`/`:edit`/`:delete` 自动 fallback 到 `:list`,但 `:uncheck` **不** fallback 到 `:check`,必须真有 perm 才放行 → 新 perm 必须 seed + 绑角色

**方案**:
1. **新建 `sql/35_v155_uncheck_perm.sql`**:
   - sys_menu 新增 `purchase:receipt:uncheck` (F 类型, parent_id=0, is_visible=0, sort_no=2015)
   - sys_menu 新增 `sales:delivery:uncheck` (F 类型, parent_id=0, is_visible=0, sort_no=1015)
   - 给所有 check APP 角色补 uncheck APP ("有 check 才有 uncheck", 沿用 sql/34 模式)
   - 给 6 个内置角色补 uncheck PC (CROSS JOIN, 修复 4 年 BUG)
   - 校验查询 (6 内置: pc=1; zdg/cmg: app=1)
2. **后端 controller 注解对齐** (`backend/.../purchase/controller/PurReceiptController.java:74` + `.../sales/controller/SalDeliveryController.java:86`):
   - `@SaCheckPermission("xxx:check")` → `@SaCheckPermission("xxx:uncheck")` for `POST /{id}/uncheck`
   - service 层 `requirePerm("xxx:uncheck")` 不变 (早已是 :uncheck),形成"双保险"语义一致
3. **App 详情页拆分 perm 显示** (`app/src/pages/purchase/receipt-detail.vue` + `sales/delivery-detail.vue`):
   - 拆 `canAudit()` → `canCheck()` (看 `:check`) + `canUncheck()` (看 `:uncheck`)
   - DRAFT 状态 → 审核按钮 (canCheck), 文案"无审核权限"
   - CHECKED 状态 → 反审核按钮 (canUncheck), 文案"无反审核权限"
   - 二次确认 `uni.showModal` 文案与 PC 端 `onCheck`/`onUncheck` handler 一致 (库存/AP/AR 影响)
4. **PC 端 Role.vue 白名单加 2 行** (`pc-web/src/views/system/Role.vue` APP_MENU_WHITELIST):
   - 采购管理 → 采购入库反审核 (`purchase:receipt:uncheck`)
   - 销售管理 → 销售出库反审核 (`sales:delivery:uncheck`)
   - PC 端 Receipt.vue:45 + Delivery.vue:60 的 `userStore.hasPerm('xxx:uncheck')` 检查无需改,这次部署自动生效

**部署**: home + 飞牛 MySQL 都跑了 sql/35 (home 验证: 6 内置角色 `pur_*_pc=1` AND `sal_*_pc=1`; 制袋工 zdg `pur_uncheck_app=1`);后端 jar 重打 (docker build --no-cache + run --env-file .env);App APK 重打装机;pc-web dist 重打部署。

**用户操作**:
- App 用户**退出 App 重新登录**一次刷权限 (`isAdmin()` 短路确保超管立即可见)
- PC 端内置非超管账号 (PURCHASE_MGR/SALES_MGR 等) 现在 PC 端入库单/出库单列表会看到反审核按钮 (4 年 BUG 修复, 通知里说清楚)

**设计原则** (R5):
- **何时拆 perm**: 操作风险不对称 (反审核回退库存 vs 审核) / 频率不对称 (99% 用户只正向) / 可观察性不对称 (反审核需更高级别审计)
- **何时不拆**: add/edit 对称低风险; print/export 派生只读; 已有 perm 不批量重塑
- **后续模块** (红冲/补差等) 一律拆 `:uncheck` 独立 perm; 删除类暂不拆; 不批量改造历史 perm

**关键踩坑** (这次没踩):
- `INSERT IGNORE + JOIN sys_menu ON perms='xxx:uncheck'` 不 hardcode menu_id (兼容 sql/28 后续调整)
- 飞牛 MySQL 是 binlog replica, 单独跑 sql/35 防 lag (历史 v1.1.49 踩过)
- 后端 controller 注解改了, 但 service 层 `requirePerm` 早已是 :uncheck, 无需改 service
- PC 端 userStore.hasPerm 直接 `permissions.includes(p)`, 任何 perms 字符串都能 query, 无需改 userStore

### v1.1.54 (2026-09-21) — App 端采购入库/销售出库审核 + PC/App 端菜单权限同步

**症状**: PC 端采购入库/销售出库审核功能 end-to-end 完整(后端 `/{id}/check` + `/{id}/uncheck` + PC `onCheck/onUncheck`),但 App 端**完全没有审核入口**。App 扫单/开单后单据 DRAFT,必须回 PC 审核,老板/经理出差或外勤时无法闭环。

**方案**:
1. **App 详情页加审核/反审核按钮** (`app/src/pages/purchase/receipt-detail.vue` + `app/src/pages/sales/delivery-detail.vue`):
   - DRAFT 显示绿色【审核】,CHECKED 显示黄色【反审核】
   - 二次确认用 `uni.showModal`(等同 PC `ElMessageBox.confirm`),审核文案与 PC 端 `onCheck`/`onUncheck` handler 严格对齐(库存+成本+AP/AR 影响)
   - perm 检查 `purchase:receipt:check` / `sales:delivery:check`,无 perm 显示"无审核权限, 请联系管理员"
   - 复用 xxx:check perm,反审核无独立 perm(项目惯例)
2. **App API 加 4 个方法** (`app/src/api/index.js`): `purchaseReceiptCheck/Uncheck` + `salesDeliveryCheck/Uncheck`,调 `POST /{id}/check` 等
3. **PC 端 App 端菜单权限 Tab 加 2 个白名单条目** (`pc-web/src/views/system/Role.vue` `APP_MENU_WHITELIST`):
   - 采购管理 → 采购入库审核 (`purchase:receipt:check`)
   - 销售管理 → 销售出库审核 (`sales:delivery:check`)
4. **sql/34 自动授权** (`sql/34_v154_app_audit_perm.sql`):
   - 凡是有 `purchase:receipt:list` APP 授权的角色,补 `purchase:receipt:check` APP 授权
   - 凡是有 `sales:delivery:list` APP 授权的角色,补 `sales:delivery:check` APP 授权
   - `INSERT IGNORE + JOIN sys_menu` 同 sql/33 模式,涵盖 v1.1.27 后所有新建角色

**部署**: home + 飞牛 MySQL 都跑了 sql/34 (home 验证: 6 内置角色 + 制袋工 `has_pur_check_app=1`);App 重打装机;pc-web dist 不需要重打;后端 jar 不需要重打 (0 改动)。受影响的 App 用户**退出 App 重新登录**一次刷权限。

**后端 0 改动** — `/{id}/check` + `/{id}/uncheck` + perm `xxx:check` (含反审核) 已存在 (PurReceiptController.java:67-79, SalDeliveryController.java:79-91, sql/28_v124_permissions.sql:33/46)。

**关键踩坑 (这次没踩)**:
- `purchase:receipt:check` 在 sys_menu 已存在 (sql/28 seed),不需要新建 perm 行
- `markDisabled` v1.1.53 已认 F 类型,白名单里直接出现这两个 perm 就能勾
- 不需要重打后端 jar,纯前端 + SQL 即可
- 飞牛 MySQL 是 binlog replica,sal/fin/permissions 可能有 binlog 滞后;idempotent 跑两遍安全

### v1.1.53-scan-in-perm (2026-09-21) — App 端扫码入库 403 hotfix (制袋工等非标角色 add perm 缺失)

**症状**: 秦运桂(角色=`制袋工 zdg`, role_id=`2079395004410298370`)在 App 端「扫码入库」打开 OK,但点「确认入库」弹「提交失败: 无权限访问」。

**根因** (3 层确认):
1. **DB 验证** — `sys_role_menu` 里秦运桂/制袋工角色只有 `purchase:receipt:list` (menu_id=402, APP),**没有** `purchase:receipt:add` (menu_id=`2090345792472715280`, F 类型按钮权限) 的 APP 授权
2. **后端 controller** — `PurReceiptController:45 @SaCheckPermission("purchase:receipt:add", orRole="admin")`,调 `POST /api/purchase/receipt` 必走 add perm 校验。`orRole="admin"` 只短路 sys_role.role_code='admin' 那一行,秦运桂 role_code='zdg' 不命中
3. **sql/28 历史 hotfix 范围不足** — v1.1.27 修过同款 bug,但当时 INSERT 只补 6 个内置角色 (SUPER_ADMIN/PURCHASE_MGR/SALES_MGR/WAREHOUSE_MGR/PRODUCTION_MGR/FINANCE),**漏掉了 v1.1.27 之后新建的非标角色**(制袋工 zdg / 吹膜工 cmg)。本次新建制袋工时就中招

**修复** (`sql/33_v153_scan_in_perm.sql` hotfix,已部署到 home 主站 MySQL):
```sql
INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
SELECT DISTINCT rm.role_id, 2090345792472715280, 'APP'
FROM sys_role_menu rm
WHERE rm.menu_id = 402           -- purchase:receipt:list 的 menu_id
  AND rm.client_type = 'APP';     -- 只看 APP 端授权, 避免给纯 PC 角色加 APP
```

**部署后验证** (2026-09-21):
- DB 验证: `SELECT u.username, GROUP_CONCAT(m.perms) ... WHERE u.username='秦运桂'` → `permissions` 包含 `purchase:receipt:add` ✅
- 7 个角色同时被修复: 6 个内置 + 制袋工(zdg)
- 吹膜工(cmg) 因为没人给它授 list APP 权限, has_list=0 has_add=0(预期,不补 — 没人扫码入库就不该有)
- 后端 / 前端 0 改动(纯数据修复)
- **用户操作**: 秦运桂等 App 用户**退出 App 重新登录**一次刷权限

**预防** (后续考虑):
- PC 角色管理「App 端菜单权限」Tab 中「扫码入库」/「扫码出库」功能, 勾选时自动补齐关联的 `*:add` 按钮权限(后端 `grantMenusByClient` 加联动逻辑)
- 同模式也要应用到 `sales:delivery:add` / `sales:order:add` / `production:order:add` / `inventory:check:add` 等其他"页面菜单 + 按钮权限"组合 — 后续 v1.1.54+ 按需扩展

**踩坑**:
- `orRole="admin"` 在 Sa-Token 注解里**只短路 sys_role.role_code='admin'**, 不是 `id=1`(id=1 是超级管理员, role_code='SUPER_ADMIN'),命名误导。**纯靠 orRole 短路**的话需要建一个 role_code='admin' 的角色,或者老老实实授权 add perm
- 飞牛 MySQL 是 home binlog replica,只同步原 7 用户 — 飞牛登录要 admin/gpssong (秦运桂根本不存在于飞牛),所以本次修复只在 home 部署,飞牛无需动
- 用户级别验证只能靠"退出 App 重新登录" — Sa-Token session 缓存的 permission 列表,DB 改了用户必须重新登录才生效

**回滚**: `DELETE FROM sys_role_menu WHERE menu_id = 2090345792472715280 AND client_type = 'APP' AND role_id NOT IN (1,2,3,4,5,6)` (保留 6 个内置角色 add 授权,只删给非标角色追加的)

### v1.1.53-app-inv-warning (2026-09-21) — App 端库存预警不显示修复 (`/me` 提前到 `recompute*` 之前)

**症状**: 用户 gpssong1 (id=2101579680441774082, role=4=仓库主管) 在 PC 端「角色管理 → 分配权限 → App 端菜单权限」勾选了「库存预警」(perms=`inventory:warning:list`, sys_menu id=2090345792472715300, client_type=APP),但手机 App 工作台不显示「库存预警」卡片。

**根因** (3 层确认):
1. **数据库验证** — `sys_role_menu` 已正确写入: `(role_id=4, menu_id=2090345792472715300, client_type='APP')` ✅
2. **后端验证** — `gpssong1` token 调 `/api/auth/me` 返回的 `permissions` 数组包含 `'inventory:warning:list'` ✅, 调 `/api/inventory/warning/list` 返回 200 + 2 条预警数据 ✅
3. **前端代码层** — `app/src/pages/dashboard/index.vue` 的 `onMounted` 执行顺序错了:
   ```
   loadUser()
   recomputeKpiVisible()       // ← 读 erp_permissions storage
   recomputeWarningVisible()   // ← 读 erp_permissions storage, 用陈旧值
   recomputeReportEntryVisible()
   if (kpiVisible) await api.dashboard()
   if (warningVisible) await api.warningList()
   applyTabBar()
   try {
     const r = await api.me()  // ← 后面才刷新 storage, 太晚
     localStorage.setItem('erp_permissions', ...)
   }
   ```
   **老 APK 缓存的 `erp_permissions` 不含 `inventory:warning:list`**, `recomputeWarningVisible()` 读到陈旧值 → `warningVisible=false` → 整块 `<view v-if="warningVisible && warningItems.length">` 不渲染 → 即使后面 `/me` 返回了正确 perm,也不会重算 `warningVisible` ref

**修复** (`app/src/pages/dashboard/index.vue`):
- **提取** `loadWarningItems()` 函数,把 KPI 数据加载和预警数据加载都拉成命名函数
- **关键顺序调整**: `onMounted` 开头**先** `await api.me()`,把最新 `permissions` / `appMenus` / `user` 写进 storage, **再** 跑 `recomputeKpiVisible` / `recomputeWarningVisible` / `recomputeReportEntryVisible`
- 然后跑 `applyTabBar` + 后续 `reLaunch` 守卫(只比较 `appMenus` 变化触发)
- **容错**: `/me` 失败时 `meResult=null`, `recompute*` 仍跑老路径(向后兼容,不阻塞 UI)

**改动**: 仅 1 个文件 `app/src/pages/dashboard/index.vue`,`-25 行 +50 行`(核心: 重排 onMounted 顺序, 提取 loadWarningItems, 简化末尾 re-launch 守卫)

**部署**:
- APK: `~/Desktop/erp-app-20260921.apk` md5 `0372902863090862e8114ace2c5e59b1` (4.4MB, 含 hotfix)
- NAS H5 容器 `erp-app-h5` rebuild + recreate: 容器内 `pages-dashboard-index.BFh9nwHZ.js` md5 `21febe59a6553900f1d991dee197fc86` ✅
- 后端 / DB 无改动(数据本来就对)

**验证**:
- APK 内嵌 chunk vs dist md5 一致: `21febe59a6553900f1d991dee197fc86` ✅
- H5 chunk 字符串位置验证: `erp_permissions`(写 storage, pos=3444) < `inventory:warning:list`(perm check, pos=4469) < `warningList()`(加载, pos=4806) ✅ 顺序正确
- `erp-app-h5` 容器 `Up 4 seconds (health: starting)` ✅
- `/api/inventory/warning/list` gpssong1 token 调通: 200 + 2 条数据 ✅

**用户操作**:
- **APK 用户**: 卸载旧 ERP → 安装 `~/Desktop/erp-app-20260921.apk` → 打开 → 登录 gpssong1 → 工作台应见「⚠️ 库存预警 (2)」卡片
- **H5 浏览器用户**: 强制刷新 (`Cmd+Shift+R`) → 登录 → 工作台应见预警卡片
- PC web 端: 不受影响(只改 App 端)

**踩坑**:
- macOS tar 打包带 `LIBARCHIVE.xattr.com.apple.provenance` 扩展头,Linux `tar` 不识别但能解压 (warning 不影响)
- Docker `COPY dist/build/h5` 不会自动 bind mount, 容器内 dist 还是旧版 → 必须 `docker build --no-cache` + `docker rm -f` + `docker run` recreate
- `python3 -m http.server` 的 `--bind 0.0.0.0` 必须显式,否则默认只监听 127.0.0.1,NAS 跨网段连不上
- mac en0 IP 不是固定的 192.168.0.5 (我之前误以为是 .23),`ipconfig getifaddr en0` 才是真相

**回滚**: git 回退 `app/src/pages/dashboard/index.vue` 即可,不用动 APK(用户装回上一个版本 APK)

### v1.1.53-sysinfo-version (2026-09-21) — 系统信息版本号同步实际部署版本

**症状**: 用户截图反馈 — 「系统设置 → 系统信息」显示 前端版本 `1.0.0`、后端版本 `1.0.9`,与实际部署的 `v1.1.53-hotfix.1` 不符,运维排错时不知道线上是哪一版。

**根因**:
1. **前端** `pc-web/package.json` 的 `"version": "1.0.0"` 是 v1.0.0 项目脚手架初始值,从未同步过。`pc-web/vite.config.js:61` 的 `__APP_VERSION__: JSON.stringify(require('./package.json').version)` 把这个常量编译时注入,Settings.vue 第 21 行 `{{ frontendVersion }}` 直接渲染 — 所以页面显示的"前端版本"实质就是 `package.json.version`, 与 Git tag/CHANGELOG/部署包命名完全脱节
2. **后端** `backend/.../SystemVersionInitializer.java:45` 的 `@Value("${erp.version:1.0.9}")` 默认值是项目脚手架初始值,从未同步过。该值在启动时被 upsert 到 `sys_config` 表 `SYSTEM_VERSION_INFO` 行的 `version` 字段,前端 Settings.vue 第 22 行 `{{ backendInfo.version }}` 读的就是它
3. **App** `app/package.json` 的 `"version": "1.0.10"` 和 `app/src/manifest.json` 的 `"versionName": "1.0.4"` 也从未与 PC 端同步过

**修复** (5 处文件):
- `pc-web/package.json`: `1.0.0` → `1.1.53-hotfix.1`
- `backend/src/main/java/com/industrial/erp/config/SystemVersionInitializer.java:45`: 默认值 `1.0.9` → `1.1.53-hotfix.1`
- `backend/src/main/resources/application.yml:100`: 新增 `erp.version: ${ERP_VERSION:1.1.53-hotfix.1}` (允许部署时用环境变量覆盖)
- `app/package.json`: `1.0.10` → `1.1.53-hotfix.1`
- `app/src/manifest.json`: `versionName` `1.0.4` → `1.1.53-hotfix.1`, `versionCode` `104` → `11531` (1.1.53-hotfix.1 数字拼接,uni-app android 要求单调递增)

**部署**:
- **PC-web** (一键脚本): `./deploy-version-bump.sh` — npm build → tar over ssh 上传 → grep 验证 → docker restart → curl md5 对照
- **后端**: 重新 `mvn package -DskipTests` → docker build → recreate backend 容器 (启动时 SystemVersionInitializer 自动 upsert 新版本到 sys_config)
- **App**: `./scripts/build-app.sh` 重打 APK

**踩坑 (实操踩到, 写脚本前先看这段)**:
1. **pc-web 容器 bind mount 源不是 docker-compose.yml 写的路径**: `docker-compose.yml` 写的是 `/volume3/docker/erp-system/pc-web/dist:/usr/share/nginx/html:ro`, 但线上实际跑的是历史 docker run 命令 `-v /tmp/pc-web-new:/usr/share/nginx/html:ro` (Sep 6 初次部署命令)。`docker inspect erp-pc-web | grep Mounts` 才能拿到真实 bind 源。**部署脚本必须用 `docker inspect` 取出的实际路径,不能相信 docker-compose.yml**
2. **rsync/scp 在 Synology DSM 上不友好**:
   - `rsync` 通过 sshpass 传密码时 PAM "Permission denied" 偶发 (sshpass 不直接支持 rsync 的 SSH 协议嵌套)
   - `scp` 在 DSM 上报 `subsystem request failed` (没装 OpenSSH 的 scp subsystem)
   - **最终方案**: `tar -cf -` + `sshpass ssh ... 'tar xf -'` (管道流, SSH 原生支持 stdout, 最稳)
3. **dist 目录 owner 是 `tongban:20`, gpssong 没写权限**: 上一次部署用了 tongban 账户, 留下的文件 owner 不是 gpssong. 替换必须 `sudo find -delete && sudo cp -a`
4. **gpssong 用户的 `/tmp` 是只读** (NAS DSM 默认 `/tmp` 是 root:wheel 755), staging 也要 sudo 创建 + chmod 777
5. **macOS tar 默认带 LIBARCHIVE.xattr 扩展头**, 上传到 Linux 会刷 80+ 行 `Ignoring unknown extended header keyword`, 用 `--no-xattrs` 静默掉
6. **部署后必须 md5 对照本地 vs 线上 vs nginx 服务**: 三者都一致才算成功 (否则可能是 nginx open file cache 没失效, 或 bind mount 路径错了)

**验证**:
- 上传后 grep `1.1.53-hotfix.1` 在 dist/assets/*.js 应有命中 → `Settings.vue` 渲染即生效
- 后端 jar 重启后,`SELECT config_value FROM sys_config WHERE config_key='SYSTEM_VERSION_INFO'` 的 JSON `version` 字段应为 `"1.1.53-hotfix.1"`

**踩坑**:
- 单改代码**不会自动生效** — 前端需 npm build、后端需 mvn package、App 需 cap sync + gradle assembleDebug
- `versionCode` 必须单调递增(uni-app android 限制),从 `104` 直接跳 `11531` 是合法的 (数字编码 1.1.53-hotfix.1)
- 后端默认值改了,但 `application.yml` 用 `${ERP_VERSION:1.1.53-hotfix.1}` 给了双重保护 — 部署时可通过环境变量覆盖(例如临时打成 hotfix.2 时不动 jar)

### v1.1.53 (2026-09-20) — 工作台权限细粒度拆分 (KPI / 趋势 / 排行 / 库存预警)

**症状**: PC + App 端工作台原本一刀切鉴权,整个 dashboard 由一个 `report:view` perm 控制。
业务痛点: 仓库主管被迫也能看到销售金额等敏感数据;销售经理/老板/主管共享同一视图,无法按角色单独授权 dashboard 子模块。

**方案**: 把工作台拆成 4 个独立可选子模块,角色授权页 (PC 端 Tab + App 端 Tab) 都能分别勾选:

| 子模块 | perm 码 | 后端接口 |
|---|---|---|
| 销售指标 (4 个 KPI 卡片) | `dashboard:kpi` (新) | `GET /report/dashboard` |
| 销售趋势 (近30天折线图) | `dashboard:sales-trend` (新) | `GET /report/sales/summary` |
| 销售排行 TOP10 | `dashboard:sales-ranking` (新) | `GET /report/sales/ranking` |
| 库存预警 | `inventory:warning:list` (复用 sql/28) | `GET /inventory/warning/list` |

**保留** `report:view` 作为报表中心菜单(`/report/sales`、`/report/inventory` 子页面)的总闸 — 与工作台正交。

**改动**:
- SQL 新增 `sql/30_v153_dashboard_subperms.sql`: 3 行 sys_menu (F 类型,parent_id=0,is_visible=0) + CROSS JOIN 给内置 6 角色各绑 3 个新 perm (PC client_type)
- `backend/.../report/controller/ReportController.java`: **删类级 `@SaCheckPermission("report:view")`**;`/dashboard` 加 `dashboard:kpi` + **清掉幽灵 `requirePerm("dashboard:view")`**(v1.0.10 遗留, sql 里无对应 sys_menu 行);`/sales/summary` 加 `dashboard:sales-trend`;`/sales/ranking` 加 `dashboard:sales-ranking`;其余 (inventorySummary/aging/arap/profit) 继续 `report:view`
- `pc-web/src/views/dashboard/Index.vue`: 重写 onMounted,4 个 section 各自独立 `userStore.hasPerm(...)` 门禁,模板 `v-if` 包裹,失败静默
- `pc-web/src/views/system/Role.vue` `markDisabled`: 补 F 类型 perm 行识别 (对齐后端 `isGrantableMenu` v1.1.52.6),否则 4 行 F 类型 perm 节点仍 disabled 不可勾
- `app/src/pages/dashboard/index.vue`: `kpiVisible` 改判 `dashboard:kpi`(原本 `report:view`);新增 `warningVisible` 走 `inventory:warning:list`;库存预警区块 `v-if` 从 `kpi.warningCount` 改为 `warningVisible && warningItems.length`,onMounted 中库存预警加载从 KPI try 内拆出独立 try

**部署**:
- SQL 跑 home + 飞牛 各一遍;飞牛通过 `docker cp` + `SOURCE /tmp/v153.sql` 注入
- 后端 jar md5 `e2098890b861` 双站 rebuild + recreate container
- home backend recreate 时**必须传 `SA_TOKEN_JWT_SECRET_KEY`**(从 `.env` 读,64 字符 hex)— 否则 SecurityPreflightValidator (v1.1.49) 拒绝弱密钥启动
- home pc-web 启动需 `--hostname backend`
- 飞牛 pc-web 用自定义 `nginx-failover.conf` + bind mount 新 dist
- App H5 重打 APK: `~/Desktop/erp-app-20260920.apk` md5 `ac2b3f35bffe`,4.4MB

**回滚**:
- 后端: `ReportController` 类级加回 `@SaCheckPermission("report:view")`,3 个方法级注解删掉
- SQL: `DELETE FROM sys_menu WHERE perms LIKE 'dashboard:%' AND deleted = 0` (CASCADE 删 sys_role_menu)
- 前端: `Index.vue` 恢复 `canView` 整体门禁

### v1.1.53-mojibake-hotfix (2026-09-21) — dashboard 子模块 menu_name latin1 乱码修复

**症状**: 上线后用户进系统管理 → 角色管理 → 分配权限,看到 3 个新增 perm 节点菜单名是乱码 (é”€å”®æŒ‡æ ‡ / é”€å”®è¶‹åŠ¿ / é”€å”®æŽ’è¡Œ) 而不是"销售指标/趋势/排行"。`HEX(menu_name)` 返回 `C3A9E2809DE282AC...` 而不是 utf8mb4 字节 `E99480E594AE...`。

**根因**: MySQL 8 容器默认字符集 latin1, `docker exec -i erp-mysql mysql ...` 没指定 `--default-character-set=utf8mb4` 时, 客户端以 latin1 解释 utf8 字节并**写回 latin1 → utf8 双层编码** (Mojibake)。
- `inventory:warning:list` 行 `menu_name='库存预警'` 正确 (HEX `E5BA93E5AD98...`), 因为 sql/28 跑的人用了 `--default-character-set=utf8mb4`
- v1.1.53 新增的 3 行没用,所以乱码
- **UPDATE WHERE perms='...'** 不受字符集影响,可以正确定位行

**修复** (按 perms 定位,直接 UPDATE):
```sql
-- 必须指定 --default-character-set=utf8mb4 (UPDATE 自身不需要,但 mysql client 默认连接要)
UPDATE sys_menu SET menu_name='销售指标' WHERE perms='dashboard:kpi';
UPDATE sys_menu SET menu_name='销售趋势' WHERE perms='dashboard:sales-trend';
UPDATE sys_menu SET menu_name='销售排行' WHERE perms='dashboard:sales-ranking';
```

**修复后 HEX 校验**: `E99480E594AEE68C87E6A087` = 销售指标 ✅

**预防**:
- `sql/30_v153_dashboard_subperms.sql` 文件顶部加注释,提醒必须 `--default-character-set=utf8mb4`
- 跑 SQL 前一律用 `docker exec -i erp-mysql mysql industrial_erp -uroot -p$PW --default-character-set=utf8mb4 < $SQL_FILE`
- 跑完 SELECT HEX(menu_name) 校验,不是 utf8mb4 字节 (E5BA93 = 库) 就要修复

**负向业务验证** (2026-09-21): 临时把 WAREHOUSE_MGR 的 3 个 dashboard:* perm 移除, 用 gpssong1 账号重新登录 → 4 endpoint 状态:
| endpoint | 移除前 | 移除后 (预期 403) | 恢复后 |
|---|---|---|---|
| `/report/dashboard` (KPI) | 200 | 403 ✅ | 200 |
| `/report/sales/summary` (trend) | 200 | 403 ✅ | 200 |
| `/report/sales/ranking` (ranking) | 200 | 403 ✅ | 200 |
| `/inventory/warning/list` (warning) | 200 | 200 ✅ (保留) | 200 |

**业务闭环确认**: 仓库主管未来如果只勾 inventory:warning:list 不勾 dashboard:*,登录 PC 端工作台会**只看到库存预警卡片**,销售 KPI/趋势/排行 section 静默消失。

### v1.1.53-parent-fix (2026-09-21) — dashboard 4 行 F-type perm 从 root 移到「工作台」下

**症状**: 用户截图反馈 — 系统管理 → 角色管理 → 分配权限,工作台分类下没看到新增的销售指标/趋势/排行选项。

**根因**:
- `sql/30` 第一版把 4 行 F-type perm (dashboard:kpi/销售趋势/销售排行/inventory:warning:list) `parent_id=0`,作为 root 级菜单存在
- `Role.vue` 的 `buildMenuTree` 按 `parentId` 把它们和其他根菜单 (工作台/系统管理/...) 一起堆在 el-tree 顶层
- el-tree 视口只显示「工作台 → 飞鹅打印机 → ...」+ 「系统管理 → ...」,4 行 dashboard perm 排在飞鹅打印机下面但**和飞鹅打印机同一层级**,截图里看到飞鹅打印机展开 7 个子节点 (查询/新增/编辑/删除/测试/日志/模板),但仪表盘的 4 行被滚动条遮住看不见

**修复** (改 parent_id):
```sql
UPDATE sys_menu SET parent_id = 1
  WHERE perms IN ('dashboard:kpi','dashboard:sales-trend','dashboard:sales-ranking','inventory:warning:list')
    AND parent_id = 0;
```

**预期效果**: 4 行移到工作台分类下, Role.vue 的 el-tree 展开工作台后能看到:
```
▼ 工作台 (id=1)
   ☑ 飞鹅打印机 (id=952 menuType=M)
   ☐ 库存预警 (id=... menuType=F perms=inventory:warning:list)
   ☐ 销售指标 (id=... menuType=F perms=dashboard:kpi)
   ☐ 销售趋势 (id=... menuType=F perms=dashboard:sales-trend)
   ☐ 销售排行 (id=... menuType=F perms=dashboard:sales-ranking)
```

**前端不需要 rebuild**: `Role.vue` 的 `buildMenuTree` 每次打开分配权限对话框都会 `await menuApi.list()` 重新拉,**前端代码无任何变化**,改 DB 即可。

**SQL 文件同步修复**: `sql/30_v153_dashboard_subperms.sql` 第 35-45 行 INSERT 的 `parent_id` 改为 `1`,新增 1b 段 UPDATE parent_id 兼容已部署环境。

**PUT /system/role/{id}/menus/client 保存验证**: zdg 角色全 4 个 perm 缺失时 PUT 4 个 snowflake id → 后端 200 → DB sys_role_menu 4 行均恢复 ✅

### v1.1.53-feiniu-deploy (2026-09-21) — 飞牛备份栈升级 (jar/dist/SQL/端口修正)

**症状**: 飞牛备份栈自 16 小时前 backend 启动后,内容仍是 v1.1.51:
- backend jar md5 `a690b817ec6d…` 而非 home 的 `e2098890b861fd9cfdd6ff59f44c5950`
- pc-web dist md5 `6f8412890933…` 而非 home 的 `d670e756783dc69f8077af5eb5b6f4c9`
- SQL 4 个 dashboard perm 行 latin1 mojibake + parent_id=0

**升级流程** (4 步全跑通):
1. **SQL**: scp `sql/30_v153_dashboard_subperms.sql` 到飞牛 /tmp/ → `docker cp` 进 MySQL 容器 → 容器内 `mysql --default-character-set=utf8mb4 < /tmp/v153.sql`
   - 跑完后有 4 行 utf8mb4 新行 + 4 行旧 mojibake 行,**按 HEX 前缀 `C3A9` 识别删除 mojibake,按 perm dedup 保留一行**
2. **backend**: docker cp 飞牛容器 `erp-backend` 的 `/opt/app/app.jar` → mac `/tmp/` → tar-over-ssh 传到飞牛 `/tmp/jar-stage/` → `cp` 到 `/vol2/erp-system/backend/erp-backend.jar` → `docker build -f backend.Dockerfile` 重建镜像 → 删旧容器 → 用原 env + **新增 `-e SA_TOKEN_JWT_SECRET_KEY=ecdd…`** 重建容器 → 健康检查 30s 通过
   - `docker logs` 看 `【安全预检】JWT secret 通过校验 (长度 64 字符, 不在弱密钥黑名单)` + `Industrial ERP Started Success`
3. **pc-web**: 本地 `pc-web/dist` 107 文件 tar-over-ssh 流到飞牛 `/vol2/erp-system/pc-web/dist-new-staging` → sudo 替换线上 `/vol2/erp-system/pc-web/dist-new` → 重启容器
4. **业务验证**: 飞牛 backend 8080 + 飞牛 pc-web 18080 双端口,罗飞/123456 账号 4 endpoint 全 200

**踩坑 (5 条新踩)**:
1. **scp 在飞牛系统也缺 subsystem** (`subsystem request failed on channel 0`) — 跟 Synology 同款问题,必须 `tar over ssh` 流式传
2. **MySQL mojibake 第二次踩** — SQL 30 INSERT 跑一次产生 4 个 utf8mb4 行,但旧 mojibake 行还在 (HEX 前缀 `C3A9E2…`),需要按 HEX 前缀识别删除 + 按 perm dedup
3. **飞牛 compose 没传 SA_TOKEN_JWT_SECRET_KEY** — 重建容器时必须手动加 `-e SA_TOKEN_JWT_SECRET_KEY=ecdd48955b16c58239b0b9326ac384f4b59babace53fda067424dd80dcba7c5f`,否则 SecurityPreflightValidator 拒绝启动 (v1.1.49 强校验)
4. **飞牛 pc-web 容器端口 18080 不是 8080** — 飞牛 `docker-compose.failover.yml` 把 pc-web 映射到 host 18080 (跟 home 同),但 backend 还是 8080,我之前测 8080 拿到的 404 是 backend fallback 到 Spring Whitelabel 错误页,跟 nginx 没关系
5. **gpssong1 不在飞牛 MySQL** — 飞牛 MySQL 是 home 的 binlog replica,用户列表只有原 7 个 (admin/gpssong/罗飞/秦运桂/赵偲荣/师雨晨/侯丽君),home 后加的 gpssong1 不会同步;**飞牛业务测试用「罗飞/123456」或「admin/admin123」**

**最终校验**:
| 项 | home | 飞牛 | 一致 |
|---|---|---|---|
| backend jar md5 | `e2098890b861fd9cfdd6ff59f44c5950` | 同 | ✅ |
| pc-web dist md5 | `d670e756783dc69f8077af5eb5b6f4c9` | 同 | ✅ |
| Settings.vue chunk 命中 `1.1.53-hotfix.1` | 1 次 | 1 次 | ✅ |
| 4 dashboard endpoint (登录态) | 200 | 200 | ✅ |

**长期记忆**:
- `erp-feiniu-pc-web-port.md` — 飞牛端口映射 + 容器路径 + 用户列表 + JWT secret 取法
- `erp-mysql-utf8mb4-mojibake.md` 增加 "mojibake 删除模式" (按 HEX `C3A9` 前缀识别)

### v1.1.53-hotfix.dist-reroll (2026-09-20 18:58) — home pc-web dist 未真正覆盖 (CHANGELOG 漏写)

**症状**: v1.1.53-hotfix.1 修完后用户报"是不是用了旧的版本,更新好的一些功能又丢失了"。
实际是 home 主站 pc-web 容器虽然重启了,但 bind-mount 的 `/tmp/pc-web-new/` 内容仍是 **Sep 6 16:02 的旧版本**(只有目录 mtime 是 17:06,文件 mtime 全是 Sep 6)。工作台看不到 dashboard 子模块拆分的 perm 门禁。

**根因**:
- v1.1.53 部署时 `tar xzf` 命令可能半成功(目录被 `mkdir -p` 触发了 mtime 更新,但文件没覆盖)
- CHANGELOG v1.1.53 段写"pc-web 部署" + "容器已重启",但**没有当场 md5 验证文件本身是否真的换了**

**诊断步骤**:
```bash
# 1. 对比本地 vs 线上 dist 文件 md5 (不是目录 mtime)
md5sum /Users/tongban/.../pc-web/dist/index.html        # 本地 17:00 build
ssh NAS md5sum /tmp/pc-web-new/index.html               # 线上
# 不一致 = 没覆盖

# 2. grep 关键 v1.1.53 字符串,看线上 chunk 是否含新代码
ssh NAS 'grep -l dashboard:kpi /tmp/pc-web-new/assets/*.js'
# 空 = 没新代码
```

**修复**:
- 重新打包本地 dist → tar pipe 上传到 `/volume3/docker/erp-system/upload/pc-web-v1153.tar.gz`(gpssong 可写)
- SSH 到 NAS,sudo 备份 `/tmp/pc-web-new` → `/tmp/pc-web-new.bak`,解压新版到 `/tmp/pc-web-new`,`chown -R 1000:1000`(容器跑在 uid=1000)
- `docker restart erp-pc-web` (bind-mount 自动生效,无需 rebuild)
- 验证: `curl http://home.93gushi.com:8088/assets/Index-DadcDsRj.js | grep dashboard:kpi` 命中 3 个 perm ✅

**教训 (CHANGELOG 写"部署完成"前必做)**:
1. **md5 对比文件**: 本地 dist/index.html md5 == 线上 /tmp/pc-web-new/index.html md5
2. **grep 验证 chunk**: `grep -l "dashboard:kpi" /tmp/pc-web-new/assets/*.js` 必须有命中
3. **curl 验证服务**: `curl http://host:port/assets/Index-xxx.js | grep 关键字符串` 在 dist 上命中
4. **看容器状态**: `docker ps` 显示 Up + healthy

**待办 (飞牛)**:
- 飞牛 erp-backend-failover 当前是 v1.1.52.6 (md5 `a690b817ec`),需要升 v1.1.53 (md5 `e2098890b861`)
- 飞牛 pc-web dist 状态未知,需核 (fail2ban 锁暂未核对)

### v1.1.53-hotfix.1 (2026-09-20 17:48) — 登录 403: 后端 CORS env 漏传

**症状**: v1.1.53 部署后 5 分钟,所有用户从浏览器登录 `http://home.93gushi.com:8088` 全部 403 Forbidden。
DevTools Network 看 `POST /api/auth/login` → 403;Response body 是 `{"code":500,"msg":"账号已停用"}` (误导)。

**根因**:
- `application.yml:103-104` 的 `cors.allowed-origins` 默认值仅含 `localhost` / `127.0.0.1` 端口
- 完整域名白名单 (`home.93gushi.com:8088`, `n150.93gushi.com:8088` 等) 通过 `${ERP_CORS_ALLOWED_ORIGINS:默认值}` env var 注入
- `/volume3/docker/erp-system/.env` 里此 env **存在**(完整 18 个 origin)
- **v1.1.53 部署 `docker run` 命令漏传 `ERP_CORS_ALLOWED_ORIGINS`**(只传了 `SA_TOKEN_JWT_SECRET_KEY` / MySQL / Redis)
- 容器启动后 Spring Security 用默认 allowlist → 浏览器带 `Origin: http://home.93gushi.com:8088` 直接 403
- Spring 在抛 403 前已写入 LoginService user-lookup 结果到 body → 前端误以为是「账号停用」

**修复**:
- `docker rm -f erp-backend` 后用 `--env-file /volume3/docker/erp-system/.env` 重新启动(一次性灌入所有 env,避开逗号转义陷阱)
- backend 容器跑 32 分钟后改成此启动命令 → `:: Industrial ERP Started Success ::` 启动成功

**验证**:
- `curl -H "Origin: http://home.93gushi.com:8088" http://192.168.0.150:8080/api/auth/captcha` → 200 ✅
- `curl -H "Origin: http://n150.93gushi.com:8088" /api/auth/captcha` → 200 ✅

**教训**:
- 任何用 `${VAR:default}` 占位符的配置项,**部署时一律用 `--env-file`** 而不是逐个 `-e` (env 值经常含逗号/IP/密码特殊字符)
- SA_TOKEN_JWT_SECRET_KEY (v1.1.49) + ERP_CORS_ALLOWED_ORIGINS (v1.1.53) 都是启动关键 env
- 排错看到 403 + 业务错误信息时,**先 curl `Origin` 验证 CORS** 再去查 user table,两类问题 403 表现一样但修法差 10 倍

### v1.1.52.6 (2026-09-20) — 库存预警 App 授权「开启了但重进还是未开启」

**症状**: v1.1.52.5 把库存预警加进 App 端权限树后,用户勾选 → 确定 → 退出重进,又变回未勾选。

**根因**: `SysRoleService.isGrantableMenu()` 只认 `menu_type='B'` / 带 perms 的 `'M'`。库存预警等 `sql/28_v124_permissions.sql` 生成的功能菜单是 **`menu_type='F'`** → `grantMenusByClient` 提交前被 `isGrantableMenu` filter 丢弃 → 没写进 `sys_role_menu` → 回填未勾选。

**修复**(代码级,`SysRoleService.isGrantableMenu`):`'F'` 与 `'M'` 同级纳入可授权(带 perms 放行):
```java
if ("F".equals(m.getMenuType()) || "M".equals(m.getMenuType())) {
    return m.getPerms() != null && !m.getPerms().trim().isEmpty();
}
```

**部署**: 新 jar `a690b817ec` 双站 backend 重建,均 healthy。

**回滚**: 删掉 `|| "F".equals(...)` 半句。

### v1.1.52.5 (2026-09-20) — App 端菜单权限补『库存预警』选项

**症状**: App 工作台有「库存预警」卡片(v1.1.44+ `api.warningList()` → `/inventory/warning/list`),但 PC 端「角色管理 → 分配权限 → App端菜单权限」Tab 里**没有**库存预警的勾选框。

**根因**: App 端权限树是 `pc-web/src/views/system/Role.vue` **硬编码**的 `APP_MENU_WHITELIST`(维护规则:任何 App 端新增功能都要在此登记才显示)。库存预警漏登记。

**修复**(纯前端):`Role.vue` 的「库存管理」children 追加:
```js
{ name: '库存预警', perms: 'inventory:warning:list', idApp: 'app-4011-warning' }
```
`buildAppMenuTree` 按 `perms` 反查真实 `sys_menu.id`(双库雪花 ID `2090345792472715300`, status=1),勾选后 `grantMenusByClient` 持久化到 `sys_role_menu`。

**踩坑**:
- `sql/28_v124_permissions.sql` 里 `4011` 是 **sort_no 不是菜单 id**,实际 `sys_menu.id` 是雪花 ID
- home/飞牛 `~` 与 `/tmp` 不可写,传 tar 落到项目目录
- 飞牛 backend 镜像内置 jar,`docker restart` 不换 jar;本次容器曾丢失,须 `docker build`+`docker run`(compose env + `--network erp-failover-net`)重建,backend 起 nginx 才不再 restart loop

**回滚**: 删 Role.vue 那行白名单,重新 build dist。

### v1.1.52.4 (2026-09-15) — 生产单删除误报「数据已存在,请检查编码/名称是否重复」

**症状**: 赵偲荣在 home.93gushi.com:8088 删生产单 PD202609150005/0006,提示 `数据已存在, 请检查编码/名称是否重复`,删不掉。

**根因**: `prd_order` 唯一索引 `uniq_prd_order_bill_no (bill_no, deleted)` 本意「同单号最多一条活行」,但逻辑删除 `deleted` 固定取 `1`:
1. 删过 PD...0005 (id A, `deleted=1`)
2. 同单号又新建一条 (id B, `deleted=0`)
3. 再删 B → 逻辑删把 B 改成 `deleted=1` → 撞 `(bill_no,1)` 唯一键(因 A 已是 1)→ MySQL `DuplicateKeyException` → `GlobalExceptionHandler.handleDuplicateKeyException` 统一包成「数据已存在」(误报)。

**修复** (`PrdOrderService.delete()`):软删本行**之前**先物理删掉同 `bill_no` 下其它已软删历史行(tombstone,物理删安全),腾出 `(bill_no,1)` 槽位:
```java
orderMapper.delete(new LambdaQueryWrapper<PrdOrder>()
        .eq(PrdOrder::getBillNo, order.getBillNo())
        .ne(PrdOrder::getId, orderId)
        .eq(PrdOrder::getDeleted, 1));
orderMapper.update(null, new LambdaUpdateWrapper<PrdOrder>()
        .eq(PrdOrder::getId, orderId).set(PrdOrder::getDeleted, 1));
```

**数据清理** (home + 飞牛 双库,手工执行一次):
```sql
DELETE FROM prd_order WHERE bill_no IN ('PD202609150005','PD202609150006') AND deleted=1;
```
清掉卡住的重复 tombstone,每 bill_no 只剩一条。

**踩坑**:
- 报错文案来自 `GlobalExceptionHandler` `DuplicateKeyException` 处理器 → 把所有唯一键冲突统一包成「数据已存在,请检查编码/名称是否重复」,本例是**删除**撞码不是新增,文案具误导性
- 飞牛 / home backend 均为**镜像内置 jar**(`COPY ...jar app.jar`),`docker restart` 不换 jar,必须 `docker build` + `docker rm -f` + `docker run` 重建容器
- 飞牛 `docker compose up -d backend-failover` 报 `no configuration file provided`(service 挂 `profiles:[failover]`),只能按 compose env 手动 `docker run`

**回滚**: `delete()` 恢复为单行 `orderMapper.update(...)`。

### v1.1.52.3 (2026-09-15) — 操作员姓名注入回退: `real_name` 空时回退 `username`

**症状**: 赵偲荣生产单列表「操作员」列仍 `-`(v1.1.52 修了 `create_by` NULL,姓名仍空)。

**根因**: `sys_user` 里赵偲荣/罗飞/秦运桂/师雨晨/侯丽君 5 个早期账号 `real_name` 为 NULL(仅 `username` 有中文姓名)。`CreateByNameInjector` 只取 `u.getRealName()` → 这些人 `create_by` 注入成 null → 前端 `row.createByName || '-'` → `-`。

**修复** (代码级, `CreateByNameInjector.resolveName(SysUser)`):优先 `realName`,为空回退 `username`:
```java
static String resolveName(SysUser u) {
    String name = u.getRealName();
    if (name == null || name.trim().isEmpty()) name = u.getUsername();
    return (name == null || name.trim().isEmpty()) ? null : name;
}
```
代码级根治,无需回填 `sys_user` 数据;历史 + 未来单据都生效。双站 jar 已换(101085440 字节)。

**回滚**: `resolveName` 只用 `getRealName()`。

### v1.1.52.2 (2026-09-15) — 飞鹅打印 403: 赵偲荣(仓库主管/财务)缺 RBAC 权限

**症状**: 赵偲荣账号 (user_id=2073693353985228802, 角色 4=仓库主管 / 6=财务) 在 home.93gushi.com:8088 打开「飞鹅打印预览」弹窗(生产单 `PD202609150006`), 点「确认打印」后 toast 提示 `打印失败: Request failed with status code 403`。前端 axios 错误被 `ElNotification.error` 吞了具体 reason。

**根因**: 不是 CORS, 是 RBAC 权限缺失。
- `FeiePrintController.printBill` 注解 `@SaCheckPermission(value = {"production:order:feie-print"}, orRole = "admin")`
- 菜单 959 (`飞鹅打印`, `perms=production:order:feie-print`) **之前只授予给角色 1(超级管理员) 和角色 2(采购经理)**
- 仓库主管(4) / 财务(6) 都没 menu 959 → Sa-Token 校验 `@SaCheckPermission` 失败 → 403

**修复** (纯数据修复, 无代码改动, 已应用到 home NAS `erp-mysql`):
```sql
INSERT INTO sys_role_menu (role_id, menu_id, client_type)
SELECT 4, 959, 'BOTH' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id=4 AND menu_id=959);

INSERT INTO sys_role_menu (role_id, menu_id, client_type)
SELECT 6, 959, 'BOTH' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id=6 AND menu_id=959);
```
**副作用**: 赵偲荣需要**退出再重新登录** (Sa-Token 在 session 启动时读权限点, 已登录的旧 session 不生效)。

**踩坑**:
- `sys_role_menu` 实际 schema 比源码 entity 多一个 `client_type VARCHAR(8)` 字段 (v1.1.11 双端授权改造, 取值 `PC/APP/BOTH`, 默认 `PC`)
- 同 role+menu 可能存在多行 (PC + APP 各一行), 插入 BOTH 前必须 `WHERE NOT EXISTS` 防重复
- `create_time` 字段在 `sys_role_menu` 不存在 (v1.1.11 改造后去掉, entity 也没这个字段), SQL 不能写
- 备份: 部署前已 `CREATE TABLE sys_role_menu_bak_20260915 AS SELECT * FROM sys_role_menu;` (141 行)

**回滚**:
```sql
DELETE FROM sys_role_menu WHERE menu_id=959 AND role_id IN (4, 6) AND client_type='BOTH';
```

**前端配套**(可选, 不在本版本):
- `pc-web/src/api/index.js` 或打印相关 Vue: 403 时 toast 显示「无权限, 请联系管理员授予『飞鹅打印』权限」而不是 `Request failed with status code 403`
- 角色管理页 (sys/Role.vue) 勾选「飞鹅打印」时默认勾 BOTH (跟 v1.1.11 改造一致)

### v1.1.52.1 (2026-09-15) — 飞牛热备站 n150.93gushi.com 同步部署 (后端 jar + 前端 dist)

**症状**: 用户反馈 "n150.93gushi.com:8088 工作台库存预警卡片为空 + 生产单列表操作员列全 -"。

**根因**: 飞牛热备站 (192.168.0.32, `/vol2/erp-system/`) 部署落后主站 ~3 周:
- 后端 jar: 8/22 v1.1.19.4 时代 (100.5MB), 缺 v1.1.44 dashboard 实时预警 + v1.1.50/51/52 修复
- 前端 dist: 8/22 时代 (`Index-DBC3Xl1C.js` 4618 字节), 缺 v1.1.44 实时 `warningList` 调用 + v1.1.51 操作员列

**修复** (纯部署同步, 无代码改动):

1. **后端 jar** (101MB, v1.1.52 commit `fade2af`):
   ```bash
   # 本地编译好
   # 上传:
   sshpass -O scp backend/target/industrial-erp-1.0.4.jar gpssong@192.168.0.32:/vol2/erp-system/erp-backend.jar
   # 重建镜像 (旧 image hash 不会被 restart 拉到, 必须 --no-cache + rm -f + run):
   sshpass -p '850225sonG' ssh gpssong@192.168.0.32 "echo '850225sonG' | sudo -S -p '' /usr/bin/docker build --no-cache -t erp-system-backend:latest -f /vol2/erp-system/backend.Dockerfile /vol2/erp-system/"
   sshpass -p '850225sonG' ssh gpssong@192.168.0.32 "echo '850225sonG' | sudo -S -p '' /usr/bin/docker rm -f erp-backend-failover"
   sshpass -p '850225sonG' ssh gpssong@192.168.0.32 "echo '850225sonG' | sudo -S -p '' /usr/bin/docker run -d --name erp-backend-failover --restart unless-stopped --network f92636759441ef70c8b2f2d58ee910bc304194cfe59be81f0714735525722ee1 -p 8080:8080 -e SPRING_PROFILES_ACTIVE=prod ... erp-system-backend:latest"
   ```
2. **前端 dist** (7.9MB, v1.1.51 `Index-BGP5wSoh.js`):
   ```bash
   # 本地 build
   cd pc-web && npm run build
   tar -czf /tmp/pc-web-dist-v1152.tar.gz -C dist .
   sshpass -O scp /tmp/pc-web-dist-v1152.tar.gz gpssong@192.168.0.32:/tmp/
   # 上传 + ACL 兜底 (在 home 解压再 cp, 避免 Synology ACL 拦 assets/):
   sshpass -p '850225sonG' ssh gpssong@192.168.0.32 "rm -rf ~/pc-extract && mkdir ~/pc-extract && tar -xzf /tmp/pc-web-dist-v1152.tar.gz -C ~/pc-extract && sudo -S -p '' cp -r ~/pc-extract/. /vol2/erp-system/pc-web/dist/ && sudo -S -p '' docker restart erp-pc-web-failover"
   ```

**验证**:
- `/api/inventory/warning/list` → 1 条预警 (塑料袋22*28*0.16, 缺 42000)
- `/api/report/dashboard` → `warningCount=1` (实时统计)
- 新建生产单 → `createBy=2 createByName=gpssong` ✅ (v1.1.52 FieldFill 修复生效)
- 历史旧单 `createBy=None` → 前端兜底显示 `-`

**踩坑**:
- 飞牛 docker 在 `/usr/bin/docker` (非 `/usr/local/bin/docker`, 跟群晖不同), `sudo` 必须 `echo 密码 | sudo -S -p ''` 方式 stdin 输密码
- mac 端 `python3 -m http.server` 在此环境 listen 后 socket 立即 CLOSED (sandbox 限制?), 不可靠; **改用 scp** (101MB jar + 4.5MB tarball 都成功)
- `docker restart` 用旧 image hash 不拉新, 必须 `docker rm -f` + `docker run` 用 `:latest` 重新拉
- `/api/dashboard/kpi` 是 404 (旧路径), v1.1.44+ 正确路径是 `/api/report/dashboard` (report 模块)
- 飞牛 dist 备份: `/vol2/erp-system/pc-web/dist.bak.20260915_v1151`

**回滚**: `sudo docker run -d ... erp-system-backend:<old-tag>` (旧 tag 保留) + `sudo cp -r /vol2/erp-system/pc-web/dist.bak.20260915_v1151 /vol2/erp-system/pc-web/dist` + `sudo docker restart erp-pc-web-failover`

### v1.1.52 (2026-09-15) — 全 entity 补 `@TableField(fill=...)`, 修复 `create_by` 全表 NULL

**症状**: v1.1.51 上线当天用户实测反馈 "新增了生产单, 操作员没有显示出来"。查 API `GET /api/production/order/page` 返回 `createBy=null createByName=null`; 直查 DB `SELECT create_by FROM prd_order ORDER BY id DESC LIMIT 5` 全是 NULL。进一步查 `sal_order` / `pur_receipt` / `inv_check` / `fin_arap` 等所有单据表, **create_by 全部 NULL**。

**根因**: `MybatisPlusConfig.java:44` 注册了 `MetaObjectHandler`, `insertFill` 调用 `strictInsertFill(metaObject, "createBy", Long.class, userId)`。但**所有 entity 都缺 `@TableField(fill = FieldFill.INSERT)` 注解** — MyBatis-Plus 的 `TableInfo` 在启动期扫字段时, 看不到 fill 配置, 不会调用 handler。表现:
- `create_by BIGINT`: 无 DB 默认值 + handler 不生效 → 永远 NULL
- `create_time DATETIME`: DB 默认值 `CURRENT_TIMESTAMP` 兜底 → 有值
- `update_by`: 同 create_by, NULL
- `update_time`: 同 create_time, 但有 `on update CURRENT_TIMESTAMP` 自动维护

**方案**:
- 写一个一次性脚本 (`scripts/add_fieldfill.py`) 批量给 44 个 entity 补 `@TableField(fill = FieldFill.INSERT)` (createBy/createTime) + `INSERT_UPDATE` (updateBy/updateTime), 同时补 `import FieldFill`
- 21 个 entity 原本只用 `TableId/TableLogic/TableName`, 没 `TableField` import, 手动加
- 6 个日志类 entity (SysLoginLog/SysOperLog/SysFeiePrintLog/SysBackupRecord/FinInvoiceApply/InvLedger) 字段顺序或类型特殊, 脚本 no-match 跳过(不影响功能, 后续 v1.1.53+ 再补)

**改动** (44 个文件 + 1 个新脚本):
- 8 个模块全覆盖: base / finance / inventory / outsource / production / purchase / sales / system
- 详见 git diff: `git show --stat v1.1.52 | grep 'A entity\|M entity'`

**部署** (2026-09-15 已发到 NAS `home.93gushi.com:8088`):
- 后端 jar (101MB) → scp 到 NAS → `docker compose up -d --force-recreate --build backend` 重建镜像 + 启动
- 健康检查: 30 秒后 `healthy`, `SecurityPreflightValidator` v1.1.49+ 通过
- API 实测: `POST /api/production/order` → 立即查 `GET /api/production/order/page` → `createBy=1 createByName=系统管理员` ✅
- DB 实测: `SELECT bill_no, create_by FROM prd_order ORDER BY id DESC LIMIT 2` → `PD202609150004 create_by=1` ✅, 老单 `PD202609150003 create_by=NULL`(符合预期, 不回填)

**为什么 v1.1.51 没发现**:
v1.1.51 的 CHANGELOG "实测" 段写 "DB 手动插一条 `base_customer (customer_code='WITHOPERX', create_by=2)` → API 返回 `createBy=2 createByName=gpssong` ✅" — 这是**手工 insert 模拟已有数据**, 验证了 injector 逻辑, 但**绕过了 MetaObjectHandler**, 所以没发现 create_by 在新增时根本没填值。教训: 测试应该走完整 Service 路径 (`BaseCustomerService.add`), 不要只 mock INSERT。

**新增**:
- `scripts/add_fieldfill.py` (一次性脚本, 留作日志类 entity 后续补 fill 时复用)

**已知遗留**:
- **历史数据 create_by 全部 NULL** — 不回填。如需回填, SQL 模板:
  ```sql
  UPDATE prd_order po
  LEFT JOIN sys_user u ON u.id = (SELECT id FROM sys_user WHERE username = 'gpssong' LIMIT 1)
  SET po.create_by = u.id
  WHERE po.create_by IS NULL AND po.create_time >= '2026-09-13';
  ```
  (按业务需要决定是否执行, 本版本不动)

**风险**:
- 44 个 entity 文件改动, 但都是模板化微改(4 行加注解 + 1 行 import), 风险可控
- 历史脏数据仍显示 `-`, 不影响新增数据
- 没有改动 Service 层逻辑(`order.setCreateBy(SecurityContext.getUserId())` 等), 完全依赖 MetaObjectHandler, 符合 v1.1.49 起的架构方向

### v1.1.51 (2026-09-15) — 全列表注入「操作员姓名」列

**症状**: 用户 (2026-09-15) 要求"在库存台账中增加操作的账号,让我知道操作的是哪个员工"。现状是 14 个单据列表页 (库存台账/销售订单/销售出库/销售退货/采购订单/采购入库/采购退货/盘点/生产加工单/应收应付/发票/客户/供应商/商品) 的 `create_by` 字段虽然存了 user_id, 但前端 el-table 从未展示成中文姓名 — 用户需要查 sys_user 表才能反查谁开的单/谁审的核,体验差。

**方案**:
- 新增 `com.industrial.erp.common.CreateByNameInjector<T>` 泛型工具类 — 封装 `collect createBy ID → userMapper.selectBatchIds(...) → 注入 setCreateByName(realName)` 模板 (零 N+1, 一页只查一次 user 表)
- 13 个 Entity 加 `@TableField(exist = false) private transient String createByName;` + getter/setter (不入库)
- 13 个 Controller `page()` 方法注入 `SysUserMapper` 调用 injector;**fin_invoice/fin_arap** 因 Service/Controller 自己拼分页, 同样改造;**fin_invoice** 注入放在拿到 IPage 后
- 14 个 Vue 文件 el-table 在「状态」列之前新增 `<el-table-column prop="createByName" label="操作员" width="100">`, `null`/`-` 兜底
- `Order.vue` 的「关联出库单」弹窗表格也加 (它走 `pageByOrder` 端点, 后端也已注入)

**改动**:
- 后端新增 `common/CreateByNameInjector.java` (~80 行, 静态 + 实例两套 API)
- Entity 加字段: SalOrder / SalDelivery / SalReturn / PurOrder / PurReceipt / PurReturn / PrdOrder / InvCheck / InvLedger / FinArap / FinInvoice / BaseCustomer / BaseSupplier / BaseProduct
- Controller 改造: SalOrderController / SalDeliveryController (page + pageByOrder) / SalReturnController / PurOrderController / PurReceiptController / PurReturnController / PrdOrderController / InvCheckController / InvStockController (ledgerPage) / FinArapController / FinInvoiceController / BaseCustomerController / BaseSupplierController / BaseProductController (page + appSearch)
- 前端 14 个 Vue: sales/Order.vue (主表 + 关联出库单弹窗) + sales/Delivery.vue + sales/Return.vue + purchase/Order.vue + purchase/Receipt.vue + purchase/Return.vue + inventory/Check.vue + inventory/Ledger.vue + finance/Arap.vue + finance/Invoice.vue + production/Order.vue + base/Customer.vue + base/Supplier.vue + base/Product.vue
- 4 个 Entity (FinInvoice / BaseCustomer / BaseSupplier / BaseProduct) 加了 `@TableField` import

**部署** (2026-09-15 已发到 NAS `home.93gushi.com:8088`):
- 后端 jar (96MB) → HTTP server 上传到 NAS → `docker build --no-cache` → 容器重启
- 前端 dist (4.4MB) → NAS wget → `cp -r` 到 `/volume3/docker/erp-system/pc-web/dist` → 容器重启 (chunk `index-DXe12Cbw.js`)
- JWT secret 强制更新 (`SecurityPreflightValidator` v1.1.49 起拒绝弱密钥), 所有用户**重新登录**

**实测**:
- DB 手动插一条 `base_customer (customer_code='WITHOPERX', create_by=2)` → API 返回 `createBy=2 createByName=gpssong` ✅
- 历史脏数据 (2026-09-13 之前所有 create_by NULL) → 前端显示 `-` (符合方案 A 预期, 不补历史)
- 新录入数据目前还是 NULL — `BaseCustomerService.add` / `BaseSupplierService.add` / `BaseProductService.add` 等 Service 没设 `setCreateBy(SecurityContext.getUserId())`, 这是 pre-existing 问题 (v1.1.49 之前就这样), 不在本任务范围 — 想让"新数据也带操作员"是独立的 Service 层增强

**风险**:
- 一页 1 次 user 表查询, N+1 不会发生 (BatchIds 一次查所有不同 ID)
- NULL / 用户被删 → 字段保持 null → 前端 `-` 兜底
- 改动涉及 32 个文件 (1 新建 + 14 Entity + 14 Controller + 14 Vue), 但每个文件都是模板化微改 (~20 行), 回滚只需 `git revert`

**v1.1.51 部署踩坑 (填到 `erp-nas-deploy-v149-quirks.md`)**:
- `gpssong` 不能写 NAS `/tmp` (drwxr-xr-x owned 501:20), 用 sudo + bind mount path `/volume3/docker/erp-system/backup/` 当中转
- `mkdir -p` backup 后 docker run 才能起 (之前 backup 不存在)
- `SPRING_DATASOURCE_URL` 里的 `&` 不能转义成 `&\`, 否则 MyBatis `BooleanPropertyDefinition` 抛 `No enum constant FALSE\` 启动失败
- SecurityPreflightValidator 起作用, 弱密钥直接 IllegalStateException; 本次用 `openssl rand -hex 32` 生成新值
- 容器 bind mount 路径是 `/volume3/docker/erp-system/pc-web/dist` 不是 `/tmp/pc-web-new`, 后者是 v1.1.36 临时路径, **要 `cp -r` 到正确路径才能生效**
- NAS docker 不在默认 PATH, 必须 `export PATH=/usr/local/bin:$PATH`

### v1.1.50 (2026-09-13) — 销售订单关联出库单跳转修复

**症状**: PC 端销售订单 → 「关联出库单」弹窗 → 点「查看」/单号, 跳转到 `/sales/delivery?id=xxx` 后, 详情弹窗显示空的「新增销售出库单」(No Data), 拿不到对应出库单。

**根因**: 出库单 `id` 是雪花 ID (`Long`, 如 `2097666445908426754`), 超过 `Number.MAX_SAFE_INTEGER` (9007199254740991)。`Delivery.vue` onMounted 里 `onView({ id: Number(_detailId) })` 把字符串强转成 `Number`, 精度丢失 → 后端收到错的 id → 404/null → `Object.assign(form, null)` 未填 form.id → 弹窗空。

**修复** (pc-web):
- `Delivery.vue` onMounted: `Number(_detailId)` → `String(_detailId)`, axios 原样拼 URL, 不丢精度
- `Order.vue` `jumpToDelivery`: 先关弹窗 + `nextTick` 再 `router.push`, 避免 dialog overlay 拦截导航

**部署**: 重新构建 PC-web bundle 并同步到 NAS `/volume3/docker/erp-system/pc-web/dist` (bind mount, 立即生效)。

### v1.1.49 (2026-09-13) — P0 安全/部署修复

**症状**: 项目审计 (2026-09-13) 发现 4 项生产风险: 弱 JWT secret / MySQL 默认密码兜底 / H2 测试漏 MySQL 严格模式 / App APK 升级流程没固化.

**方案**:
- **SecurityPreflightValidator** 启动期拒绝弱 JWT secret (中等强度校验: 长度 ≥32 字符 + 黑名单; `@Profile("!test")` 测试不受影响)
- docker-compose.yml healthcheck 强制从 .env 读 MYSQL_ROOT_PASSWORD (去掉 erp_root_pwd 兜底)
- Testcontainers MySQL 8.0 加 @Tag("integration"), mvn verify 才跑, 详情 schema-test.sql + data-test.sql
- scripts/build-app.sh 把 cap sync + assembleDebug 全流程固化, 输出版本号命名的 APK 到桌面 + 项目根
- .github/workflows/ci-build-check.yml 首次引入 CI (后端 compile + PC H5 build + App H5 build)
- docs/security.md 文档化 .env 必填项 + 生成命令 + 密钥泄漏后果
- .env.example 顶部加 preflight 安全提示

**改动**:
- 后端: SecurityPreflightValidator (新增) + pom.xml 加 testcontainers mysql/junit-jupiter + failsafe-plugin
- 后端测试: application-mysql-test.yml + sql/schema-test.sql + sql/data-test.sql + InvLedgerQueryMapperMysqlIT
- docker-compose.yml: healthcheck 强制从 .env 读 MYSQL_ROOT_PASSWORD
- .env.example: 顶部加安全预检说明 + JWT secret 生成命令提示
- scripts/build-app.sh (新增): npm build → cap sync → 自动补 capacitor-share → assembleDebug → 输出到桌面
- .github/workflows/ci-build-check.yml (新增): PR 自动跑后端 compile + PC H5 build + App H5 build
- docs/security.md (新增): .env 必填项 + 生成命令清单 + 升级流程

**部署副作用**:
- 本次部署时 NAS .env 的 SA_TOKEN_JWT_SECRET_KEY 自动替换为强随机值 → 所有用户**必须重新登录**(cookie 失效)
- MYSQL_ROOT_PASSWORD 不动 (避免破坏现有数据)

**已有失败测试 (pre-existing, 非本次引入)**:
- SalOrderServiceTest / SalDeliveryVersionLockTest 等 13 个测试 NPE (Mockito + @InjectMocks 注入 mapper 失败)
- 根因是这些测试类用了父类构造注入 mapper, 不是 @Autowired, Mockito 无法自动注入
- 不在本次 P0 范围内, 留 v1.1.50+ 处理

**风险**:
- SecurityPreflightValidator 启动失败 = backend 起不来, 必须 .env 改对; 测试 profile 不受影响
- Testcontainers 首次 mvn verify 要拉 mysql:8.0 镜像 (~500MB), 本地 mvn test 不受影响
- build-app.sh 在 macOS 上跑 (JAVA_HOME 路径写死 homebrew), Linux 需改


### v1.1.48 (2026-09-13) — App 端库存预警产品列表上线 + 重新打包 APK

**症状**: v1.1.45 (9月9日) App 端 dashboard 已写好 `warningItems` 列表渲染 + `await api.warningList()` 调用,
**但用户手机看到的还是只显示数字 "(1)"**。v1.1.47 后端 SQL 修好后, 数字恢复成 1, 但产品列表仍未显示。

**根因**:
1. **APK 内置资源陈旧** — Capacitor APK 打包时把 `dist/build/h5` 内嵌到 `android/app/src/main/assets/public/`。
   用户手机上跑的 `app-debug.apk` 是 8月31日打的, 内嵌的 `pages-dashboard-index.CpVb6CJr.js` 是 v1.1.45 之前的版本,
   **setup 函数里只有 `await api.dashboard()`, 根本没有 `await api.warningList()`, `warningItems` 永远空数组**。
2. **H5 容器和 NAS dist 是新版** — 9月9日 v1.1.45 时已经写过代码, 但 APK 没重新 cap sync + 重新打包,
   浏览器访问 `192.168.0.150:18090` 和 `dist/build/h5/assets` 都是新版 chunk `CWb9SSks` (含 `await warningList`)。

**修复**:
1. `npm run build:h5` → `npx cap sync android` → 把新版 H5 同步进 APK assets (MD5 校验一致)
2. **手动补 `:capacitor-share` 到 `capacitor.settings.gradle`** — Capacitor CLI 6.x 的 cap sync 不会自动注入 `@capacitor/share`,
   `app/build.gradle` 引用 `implementation project(':capacitor-share')` 会报 `Project with path ':capacitor-share' could not be found`。
   解决方案: 在 `capacitor.settings.gradle` 末尾追加:
   ```gradle
   include ':capacitor-share'
   project(':capacitor-share').projectDir = new File('../node_modules/@capacitor/share/android')
   ```
3. **删 `node_modules/@capacitor/share/android/build/` 缓存** — 否则 `checkDebugAarMetadata` 报 NPE `Cannot invoke "java.util.List.get(int)" because "path" is null`
4. `./gradlew assembleDebug` (不带 `--offline`, 重新走 `capacitor-share:writeDebugAarMetadata`) 成功

**产物**:
- APK: `~/Desktop/erp-app-v1.1.48-20260913.apk`, 4,369,313 字节, MD5 `9423265417daff4dab5508861a7310eb` (vs 旧 `84eccd601880020346bd6c9a0bdab2fa`)
- 内嵌 dashboard chunk MD5 `a21fe68e1a35b7c68212ec4e740a8b84` = `dist/build/h5` 同名 chunk MD5 (sync 一致)
- 含 `await v.warningList()` 调用 + `warning-item` CSS class + 完整 v-for 渲染逻辑

**改动**:
- `app/android/capacitor.settings.gradle` — 追加 `:capacitor-share` include 路径 (注释里说明 CLI 6.x 不会自动扫 @capacitor/share)

**部署**:
- 用户手机: 安装新 APK 后, "工作台 → 库存预警" 卡片下能看到 1 条预警 (4-06-003-0018 塑料袋22*28*0.16, 库存 0 / 安全 70000)
- H5 浏览器 (`http://home.93gushi.com:18090` 或 `http://192.168.0.150:18090`): 已正常显示, 无需重新部署容器
- APK 产物位置 (不入 git, 直接给用户安装):
  - `/Users/tongban/Documents/根据前端开发erp 2/erp-app-v1.1.48-20260913.apk`
  - 备用: `~/Desktop/erp-app-v1.1.48-20260913.apk`
  - 大小 4,369,313 字节, MD5 `9423265417daff4dab5508861a7310eb`

**踩坑**:
- `cap sync` 后第一次 `assembleDebug` 要去掉 `--offline`, 之后可加
- cap sync 不会触发 `npm install`, plugin 缺包时 build 直接失败
- 不要在 `capacitor.settings.gradle` 里改 `include ':capacitor-android'` 那行 — cap sync 会重置, 但**追加**的 include 会被保留

### v1.1.45 (2026-09-09) — App 端库存预警显示具体产品

**症状**: v1.1.44 修复后, PC 端 dashboard「库存预警」能正常列出低于安全库存的商品, 但 App 端「工作台」只显示数字「⚠️ 库存预警 (1) 有 1 个商品库存低于安全线」, 用户看不到具体是哪些商品。

**根因**:
1. **App 端从未调预警产品接口** — `app/src/pages/dashboard/index.vue` 模板只渲染 `kpi.warningCount` 数字, 模板内没有产品列表区。
2. **App 端 API 缺 `warningList()`** — `app/src/api/index.js` 没暴露 `/inventory/warning/list` 端点。
3. **字段命名歧义** — 后端 `/inventory/warning/list` 返回的 `safety_stock` 来自 `inv_stock` 表 (从未维护, 全是 0), 而**真正的安全库存**在 JOIN 别名 `p_safety_stock` (`base_product` 表)。App 端代码若简单用 `it.safety_stock` 读, 会拿到 0。
4. **后端 `dashboardKpi` 查死表** — `ReportMapper.xml` 用 `(SELECT COUNT(*) FROM inv_warning WHERE status = 0)` 统计, 但**后端没有维护 `inv_warning` 表**, 永远返回 0 → App 端即使显示数字也永远是 0。
5. **后端 `selectStockAll` 缺过滤** — `InvLedgerQueryMapper.xml.selectStockAll` 之前 PC 端有过滤, App 端这次复测发现 61 条 (全表) 而非 1 条 (过滤后), 怀疑之前 jar 内版本是旧 SQL 未生效 (后端 jar 是 v1.1.40 时期的, 此次重新打包才生效)。

**方案**:
- **后端**:
  - `ReportMapper.xml.dashboardKpi` — 用 `inv_stock JOIN base_product` 实时统计 `qty < safety_stock` 的商品数 (替代死表 `inv_warning`)
  - `InvLedgerQueryMapper.xml.selectStockAll` — 保持现有过滤 `s.qty > 0 AND p.safety_stock > 0 AND s.qty < p.safety_stock` (重新打包 jar 让 SQL 生效)
- **App 端**:
  - `api/index.js` — 新增 `warningList() → GET /inventory/warning/list`
  - `pages/dashboard/index.vue` — 模板增加 `v-for="item in warningItems"` 卡片列表, 显示编码/名称/仓库/当前库存(红)/安全库存; onMounted 在 `kpi.warningCount > 0` 时调 `warningList()`, 取前 10 条, 优先读 `p_safety_stock` (兼容 `safety_stock` fallback)
  - 点击预警项 → 跳 `/pages/inventory/query` (存 `erp_stock_filter` storage, 预留给后续 detail 页筛选)
  - `formatNum` 工具函数 — 整数直接显示, 小数最多 2 位 (避免 `63000.0000`)

**改动**:
- 后端: `backend/src/main/resources/mapper/report/ReportMapper.xml` — `dashboardKpi` 改用实时统计
- 后端: `backend/src/main/resources/mapper/inventory/InvLedgerQueryMapper.xml` — `selectStockAll` 已有过滤 (此次重新打包 jar)
- App: `app/src/api/index.js` — 新增 `warningList()` (1 行)
- App: `app/src/pages/dashboard/index.vue` — 模板加产品列表 (≈25 行) + script 加 `warningItems` / `formatNum` / `openWarningDetail` (≈30 行) + style 加 `.warning-item` (≈40 行)
- 数据库: 0 改动
- 后端 jar: 重新打包 (`zipfile` 替换 2 个 XML), 上传 NAS `backend/industrial-erp-1.0.4.jar`, `docker build --no-cache`, restart `erp-backend`
- App H5: `npm run build:h5` → tar → 通过 mac HTTP server 18888 分发, NAS `curl` 拉取 → 解压到 `dist/build/h5` → `docker build --no-cache` → restart `erp-app-h5`

**部署踩坑** (重点):
1. **后端 jar 路径**: NAS 上有两份 jar — 根目录 `industrial-erp-1.0.4.jar` 和 `backend/industrial-erp-1.0.4.jar`。**Dockerfile 是从 `./backend/` build, 必须改 `backend/` 那份**, 改根目录没用。
2. **BuildKit COPY 缓存**: 改文件后 `docker build` 即使 file mtime 变了, COPY 层仍可能命中缓存。**必须 `--no-cache`** 才能让 jar 真正进新镜像。
3. **管道传大文件**: `cat local.jar | ssh user@nas "cat > remote.jar"` 偶尔会因 SSH 会话重置产生 0 bytes 文件, **必须 `ls -la` 验证 size**, 不对就重传。
4. **NAS `dist/build/h5` 目录被 ACL 锁**: tar 直接 `xzf -C dist/build/h5` 会丢失 `assets/` 等子目录 (root-owned 文件 OK, gpssong 写的子目录被 ACL 拦)。**正确做法**: 先在 user home (如 `~/test-extract`) 解压, 再 `cp -r` 到目标位置。
5. **Dockerfile 锁 sha256**: 默认 `FROM nginx:1.27-alpine@sha256:...` 在 NAS 上拉镜像超时。改用 `FROM nginx:1.27-alpine` (不锁) 才能 build。
6. **SSH fail2ban**: 多次密码错误后被锁, 需等 30-60s 重试。
7. **Sa-Token header**: 用 `Authorization: <token>`, **不是** `satoken: <token>` (response 里 `tokenName: "Authorization"` 字段提示)。

**验证**:
- DB: `SELECT COUNT(*) ... WHERE s.qty < p.safety_stock` → 1 条: `4-06-003-0018 塑料袋22*28*0.16` qty=63000 < safety=70000 ✅
- API: `GET /api/report/dashboard` → `warningCount: "1"` ✅
- API: `GET /api/inventory/warning/list` → 1 条, `p_safety_stock: 70000` ✅
- App H5: `pages-dashboard-index.CWb9SSks.js` (新 build chunk) 通过 `http://192.168.0.150:18090/assets/...` 200 OK 可访问 ✅
- 容器: 后端 jar + app-h5 全部用 `--no-cache` rebuild + restart ✅

### v1.1.44 (2026-09-09) — 库存预警不显示

**症状**: 工作台「库存预警」卡片始终为空，即使有商品低于安全库存也不显示。

**根因**:
1. **前端从未调用预警接口** — `Dashboard/Index.vue` 的 `onMounted` 只调用了 KPI 和趋势接口，没有加载 `warningList`（初始值 `ref([])` 永远不变）
2. **后端 SQL 没有过滤低库存条件** — `InvLedgerQueryMapper.xml` 的 `selectStockAll` 只返回全部库存，没有 `qty < safety_stock` 过滤
3. **`inv_stock.safety_stock` 字段从未写入** — 入库时未从 `base_product.safety_stock` 同步，导致所有库存的 `safety_stock = 0`，即使 SQL 加了过滤也查不到任何预警

**方案**:
- 后端: `InvLedgerQueryMapper.xml` — `selectStockAll` 加 `JOIN base_product` 取 `p.safety_stock`，WHERE 改为 `s.qty < p.safety_stock`（直接用商品表的安全库存值，避免依赖 inv_stock.safety_stock）
- 前端: `inventory.js` — 新增 `stockApi.warningList()` → `GET /inventory/warning/list`
- 前端: `Dashboard/Index.vue` — `onMounted` 末尾调用 `stockApi.warningList()`，取 `product_name` / `qty` / `safety_stock` / `wh_name`，客户端过滤 `qty < safetyStock`
- 前端: 预警表格新增「编码」和「仓库」列，当前库存红色加粗

**改动**:
- 后端: `backend/src/main/resources/mapper/inventory/InvLedgerQueryMapper.xml` — `selectStockAll` 加 `LEFT JOIN base_product p` + WHERE 过滤 (-6 行 +8 行)
- 前端: `pc-web/src/api/inventory.js` — 新增 `stockApi.warningList()` (-1 行 +2 行)
- 前端: `pc-web/src/views/dashboard/Index.vue` — 导入 `stockApi` + `onMounted` 加预警加载 + 表格列扩展 (≈15 行)
- 数据库: 0 改动

**验证**:
- DB: `SELECT COUNT(*) FROM base_product WHERE safety_stock > 0` → 3 个商品设置了安全库存 ✅
- DB: `SELECT p.product_name, s.qty, p.safety_stock FROM inv_stock s JOIN base_product p ON p.id=s.product_id WHERE s.qty < p.safety_stock` → 1 条: `BOPP薄膜22*28*0.16` qty=63000, safety_stock=70000 ✅
- API: `GET /inventory/warning/list` → 返回预警数据（需登录 token）
- 前端: `Index-DLgjbuhr.js` 含 `stockApi.warningList` 调用 ✅
- 容器: 后端 jar 热替换（jar 内 XML 修改）+ 重启 ✅

### v1.1.43 (2026-09-08) — 修复订单关联出库单「查看」白屏 + useRoute HMR bug

**症状**: 
1. 从销售订单列表点「关联出库单」弹窗，再点击出库单号/「查看」按钮，浏览器打开 `/sales/delivery/<id>` 新标签页显示空白。
2. 修复后出现 `TypeError: Cannot read properties of undefined (reading 'query')` — `useRoute()` 在 Vite HMR `hot.accept` 回调内被调用时返回 undefined。

**根因**:
1. `Order.vue:jumpToDelivery()` 用 `window.open('/sales/delivery/${id}')` 跳转，但 router 没有 `sales/delivery/:id` 动态路由，新标签打开的是列表组件（无数据）→ 白屏。
2. 首次修复时将 `useRoute()` 移入 `onMounted` 回调内，但 Vite 的 HMR `hot.accept` 会重新执行 setup() 并将 onMounted 回调立即调用（而非等待挂载），导致 `useRoute()` 在路由未初始化时调用返回 undefined。

**方案**:
- 前端: `Order.vue` — `jumpToDelivery()` 改用 `router.push({path:'/sales/delivery',query:{id}})` 替换整页导航（不再开新标签）
- 前端: `Delivery.vue` — `useRoute()` 在**模块级别**调用并预捕获 `query.id`（`const _route = useRoute(); const _detailId = _route.query?.id`），onMounted 回调内直接用 `_detailId`
- SQL: 0 改动

**改动**:
- 前端: `Order.vue` — 新增 `useRouter` 导入, `jumpToDelivery` 改为 `router.push(...)` (-1 行 +2 行)
- 前端: `Delivery.vue` — 模块级别加 `_route` / `_detailId` 两行（非 onMounted 内调用 useRoute）; onMounted 改为用 `_detailId` (-3 行 +2 行)
- 后端: 0 改动

### v1.1.42 (2026-09-08) — 销售出库单列表交货方式中文标签

**症状**: 销售出库单列表「交货方式」列显示英文枚举值 (DELIVERY/PICKUP/DIRECT)，用户看到的是英文而非中文标签。详情弹窗和打印模板已通过 BillLoader/Service.detail() 注入中文标签，但列表 API (page) 缺少注入逻辑。

**方案**:
- 后端: `SalDeliveryService.page()` — 分页结果返回后，遍历每行调用 `setDeliveryMethodLabel(mapDeliveryMethod(...))`，与 `detail()` 保持一致
- 前端: `Delivery.vue` — 列表表格新增「交货方式」列，宽度 100px，显示 `row.deliveryMethodLabel || '-'`

**改动**:
- 后端: `SalDeliveryService.java` — page() 方法加批量注入循环 (-1 行 +6 行)
- 前端: `Delivery.vue` — 列表新增交货方式列 (+3 行)
- SQL: 0 改动 (delivery_method 列已存在)

**未覆盖范围**:
- 「关联出库单」弹窗 (`pageByOrderId`) 暂无中文标签注入，如需可后续补充
- 详情弹窗内已有 `deliveryMethodLabel` (detail() 方法已注入)

### v1.1.41 (2026-09-08) — 销售订单发货联动

**需求**: 从销售订单生成出库单时自动带入交货方式和采购订单号；出库单审核后回写订单明细 out_qty；订单列表显示已发货/未发货数量。

**改动**:
- 后端: `SalOrderDetailMapper.java` 新增 `selectShippedQtyByOrderDetailId()` (累计 sal_delivery_detail.qty WHERE bill_status='CHECKED')
- 后端: `SalDeliveryService.add()` — 从源订单带入 deliveryMethod；从源订单带入 poNo 到明细行
- 后端: `SalDeliveryService.check()` — 审核后按 order_detail_id 累计已审核出库 qty 回写到 sal_order_detail.out_qty
- 后端: `SalDeliveryService.uncheck()` — 反审核后重置关联明细 outQty 为 0
- 后端: `SalOrderService.page()` — 每页订单批量注入 shippedQty (detail.outQty 之和)
- 后端: `SalOrderService.getDeliverySummary()` — 新增 API GET /sales/order/{id}/delivery-summary
- 后端: `SalOrder.java` 新增 transient shippedQty 字段
- 前端: `Order.vue` — 列表新增「已发/未发」列 (CHECKED 状态显示数字)
- 前端: `Order.vue` — 操作列新增「发货详情」按钮，弹窗展示明细级已发/未发数量
- 前端: `Order.vue` — 生成出库单弹窗新增「交货方式」「采购订单号」只读字段
- 前端: `sales.js` — 新增 `getDeliverySummary` API

**SQL**: 无需新建列 (out_qty/delivery_method/po_no 均已存在)

### v1.1.40 (2026-09-08) — 销售出库单交货方式 + 商品明细采购订单号

**需求**: 销售出库单主表需要记录交货方式 (送货/自提等)，商品明细表需要记录客户采购订单号 (PO 号)。

**改动**:
- SQL: `sql/32_add_delivery_fields.sql` — idempotent migration, ALTER TABLE 加 `delivery_method VARCHAR(32)` + `po_no VARCHAR(64)`
- 后端: `SalDelivery.java` 加 `deliveryMethod` 字段 + `deliveryMethodLabel` transient 字段 (打印模板用)
- 后端: `SalDeliveryDetail.java` 加 `poNo` 字段
- 前端: `Delivery.vue` 表单新增「交货方式」输入框, 商品明细表新增「采购订单号」列, 打印 field map 补充两个字段
- 后端: 0 行 service/mapper 逻辑改动 (Jackson 自动序列化, BaseMapper insert/update 自动写库)

**验证**:
- DB: `sal_delivery.delivery_method` + `sal_delivery_detail.po_no` 列已创建 ✅
- 后端 jar: 已注入容器并重启 ✅
- 前端: `index-ChI1740u.js` (v1.1.40 构建) 正常加载 ✅

### v1.1.39 (2026-09-08) — 按客户切换浏览器打印模板

**症状**: 不同客户可能需要不同的打印格式 (Logo/抬头/备注栏), 但现有模板系统只支持全局单一模板。

**方案**: `sys_print_template` 加 `customer_id` 字段, 打印时优先匹配客户专属模板, 无则 fallback 到全局默认模板。

**改动**:
- SQL: `31_add_print_template_customer.sql` — ALTER TABLE 加 `customer_id BIGINT NULL` + INDEX
- 后端: `SysPrintTemplate.java` 加 `customerId` 字段
- 后端: `SysPrintTemplateMapper.java/.xml` 新增 `selectByBizTypeAndCustomer(bizType, customerId)`
- 后端: `SysPrintTemplateService.java` 新增 `getActiveByBizType(bizType, customerId)` — 客户专属优先, NULL 回退全局默认
- 后端: `SysPrintTemplateController.java` `/biz-type/{bizType}` 加 `@RequestParam Long customerId`
- 前端: `pc-web/src/api/system.js` `getByBizType(bizType, customerId?)`
- 前端: `pc-web/src/composables/usePrint.js` `getTemplate/doPrint/clearTemplateCache` 加 `customerId` 参数
- 前端: `pc-web/src/views/sales/Order.vue` `doPrint({..., customerId: row.customerId})`
- 前端: `pc-web/src/views/sales/Delivery.vue` 同上
- 前端: `pc-web/src/views/sales/Return.vue` 同上
- 前端: `pc-web/src/views/purchase/Receipt.vue` `customerId: row.supplierId` (采购用供应商 ID)
- 前端: `pc-web/src/views/purchase/Return.vue` 同上
- 前端: `pc-web/src/views/system/PrintTemplate.vue` 弹窗加"绑定客户"字段 + 表格列 + 加载客户列表

**模板匹配优先级**:
1. `(bizType, customerId)` → 客户专属模板
2. `(bizType, NULL)` → 全局默认模板 (向后兼容)

**验证**:
- 后端 jar MD5: `ebf8ed55...` ✅
- 前端 PrintTemplate 新 chunk: `PrintTemplate-BI1NVdy1.js` ✅
- API `GET /api/system/print-template/biz-type/SAL_ORDER?customerId=1` → 401 (路由正确, 需登录)

### v1.1.38 (2026-09-08) — 销售订单「关联出库单」追溯入口

**症状**: v1.1.35 已实现订单 → 出库单一键生成，联动字段 `sal_delivery.order_id` / `order_no` / `sal_delivery_detail.order_detail_id` 已写入 DB，但订单页面没有任何 UI 查看该订单已生成的出库单。

**方案**: 销售订单列表操作列新增「关联出库单」按钮 (所有状态) → 弹窗表格展示关联出库单 (单号/日期/仓库/状态/金额) → 点击单号跳转销售出库详情页。

**改动**:
- 后端: `SalDeliveryMapper.java` 新增 `selectPageByOrderId()` (按 orderId 分页，JOIN warehouse)
- 后端: `SalDeliveryService.java` 新增 `pageByOrderId(pageNum, pageSize, orderId)`
- 后端: `SalDeliveryController.java` 新增 `GET /sales/delivery/page-by-order?orderId=&pageNum=&pageSize=`
- 前端: `pc-web/src/api/sales.js` 新增 `salDeliveryApi.pageByOrderId(orderId, params)`
- 前端: `pc-web/src/views/sales/Order.vue` 操作列 width 370→460，新增「关联出库单」按钮 + dialog + 3 个方法

**验证**:
- 后端 jar MD5: `9b4fcd6a...` ✅ healthy
- 前端 Order 新 chunk: `Order-BGxQ7mn9.js` (含 pageByOrderId) ✅
- 接口 `GET /sales/delivery/page-by-order?orderId=1` → 200 (401 为未登录, 路由正确)

**踩坑**: Spring 路由顺序 — `@GetMapping("/page-by-order")` 必须放在 `@GetMapping("/{id}")` **之前**, 否则 `/page-by-order` 被当成 `{id}` 路径变量 → `String → Long` 转换失败

### v1.1.37 (2026-09-06) — 销售订单新增采购订单号、交货方式字段

**症状**: 销售订单编辑弹窗缺「采购订单号」和「交货方式」输入框, 业务上需要记录客户 PO 号和送货/自提等交货方式.

**根因**:
- `sal_order` 表无 `po_no` / `delivery_method` 列
- `SalOrder.java` 实体无对应字段
- `Order.vue` 编辑弹窗只有客户 / 仓库 / 交货日期 / 付款方式 / 备注, 无 PO 号 / 交货方式

**方案**:
- 后端: `sal_order` 加 `po_no VARCHAR(64)` + `delivery_method VARCHAR(32)` 列, 索引 `idx_po_no`
- 实体: `SalOrder.java` 加 `poNo` / `deliveryMethod` + getter/setter (MyBatis Plus BaseMapper.insert/update 自动写入)
- 前端: `Order.vue` 编辑弹窗增加采购订单号输入框 + 交货方式下拉 (送货/自提/专车直送)
- 打印模板: 修正 `deliveryMethod` 字段绑定 (原来误绑 deliveryDate), 明细表「采购订单号」列绑定改为 `poNo` (订单头字段)

**改动**:
- `sql/30_add_sales_order_po_delivery.sql`: 新增迁移脚本 (幂等, INSERT IF NOT EXISTS)
- `backend/.../sales/entity/SalOrder.java`: 加 `poNo` / `deliveryMethod` 字段 + getter/setter (+8 行)
- `pc-web/src/views/sales/Order.vue`: 编辑弹窗加采购订单号 + 交货方式输入框 (+12 行), `SAL_ORDER_HEADER_MAP` 加 poNo/deliveryMethod
- `sys_print_template.content`: 更新模板, field `deliveryMethod` 修正, 明细表采购订单号列从 `batchNo` 改 `poNo`

**验证**:
- DB: `SHOW COLUMNS FROM sal_order` 看到 `po_no VARCHAR(64)` / `delivery_method VARCHAR(32)` ✅
- 旧数据 `po_no=NULL` / `delivery_method=NULL` (兼容)
- 前端构建: `Order-CNroBjW5.js` (新 chunk, 9997 字节) ✅
- 打印模板字段映射: `deliveryMethod` / `poNo` 正确绑定 ✅

Spring Boot 3.2.5 + MyBatis Plus 3.5.9 + JDK 17 + Vue 3 + uni-app (Capacitor 6)

部署在 Synology DS918+ 容器内: 后端 8080 / PC Web 18080 / App H5 18090 / 统一反代 8088


### v1.1.47 (2026-09-13) — 库存预警: 库存=0 但低于安全库存的产品现在能进预警

**症状**: 商品 `4-06-003-0018` (塑料袋 22*28*0.16) 安全库存 70000, 当前库存 0, 应该是最严重的预警信号, 但 PC / App 端库存预警列表都不显示.

**根因**:
- `InvLedgerQueryMapper.xml` 的 `selectStockAll` 和 `ReportMapper.xml` 的 `dashboardKpi.warningCount` 都从 `inv_stock` 表出发 INNER JOIN, 同时 WHERE 加了 `AND s.qty > 0` 过滤
- 库存=0 但仍有 inv_stock 行的产品 (如出库后保留 0 行) 全部被排除
- 库存=0 且根本没 inv_stock 行的产品更永远不会出现
- v1.1.44 修复软删除 JOIN 排除时, "把 INNER JOIN 改为 LEFT JOIN" 是正确的, 但**没去掉 `qty > 0` 过滤**, 留下了这个死角
- MySQL `sql_mode=only_full_group_by` 进一步限制: HAVING/ORDER BY 不能引用 SELECT 别名

**方案**: 以 `base_product` 为驱动表 LEFT JOIN inv_stock, 包一层子查询避开 `only_full_group_by`:
- 内层: GROUP BY p.id + SUM(s.qty) + MIN(w.warehouse_name)
- 外层: WHERE qty < p_safety_stock + ORDER BY shortage DESC
- 同时把 `p.deleted=0 AND p.status=1` 显式写在 WHERE (确保停用商品不出现)

**改动**:
- `backend/src/main/resources/mapper/inventory/InvLedgerQueryMapper.xml` — `selectStockAll` 改为"以 product 驱动 + 子查询包外层" (line 11-30)
- `backend/src/main/resources/mapper/report/ReportMapper.xml` — `dashboardKpi.warningCount` 子查询改为同样逻辑 (line 80-89)
- 前端: 0 改动 (PC 端 `Index.vue` 已做 `p_safety_stock` fallback, App 端 `dashboard/index.vue` 也兼容, SQL 输出字段一致)

**验证**:
- 端到端 `GET /api/inventory/warning/list` → 200, 返回 `4-06-003-0018` qty=0 safety_stock=70000 shortage=70000
- `GET /api/report/dashboard` → `warningCount: 1`

### v1.1.46 (2026-09-13) — 销售订单列表"已发/未发"列实时 SUM 修复 (hotfix)

**症状**: 销售订单列表"已发"列显示 `0 / 592800`, 但弹窗"发货详情"正确显示 `119140`. 列表和弹窗数据不一致.

**根因**:
- `SalOrderService.page()` 用 `sal_order_detail.out_qty` 字段求和注入 `shippedQty`
- 但 `out_qty` 列**只有 v1.1.41 之后**新审核的出库单才会回写, 历史 9/9、9/11 审核的出库单从未回写
- 结果: 弹窗走 `selectShippedQtyByOrderDetailId` 实时 SUM (正确), 列表走 `out_qty` 字段求和 (全 0)

**方案**: 列表也改成实时 SUM, **不依赖 `out_qty` 列**

**改动**:
- `backend/.../SalOrderDetailMapper.java`: 加 `selectShippedQtyGroupByOrderId` default 包装方法 (≈15 行)
- `backend/.../SalOrderDetailMapper.xml`: 加批量 SQL, 列别名**必须用下划线** (MyBatis 不自动转驼峰, 这里踩了坑)
- `backend/.../SalOrderService.java`: `page()` 改用批量 SQL, 一次查询所有订单的已发数量

**部署教训 (2026-09-13 一日三坑)**:
1. **scp 静默失败** — `Connection closed` 但无报错, 文件没替换. 改用 `ssh 'cat > /path' < localfile` 稳
2. **后端服务 jar 目录没挂载** — `volumes` 只挂 `upload/backup`, 容器内 jar 是 `docker build` 时 COPY 进去的. host 替换 jar 没用, 必须 `docker build` + 重启容器
3. **手动 docker run 漏 `ERP_CORS_ALLOWED_ORIGINS`** — curl 测试 200, 浏览器返回 403 (CORS 拒绝). 完整环境变量清单见 [[erp-nas-deploy-jar-quirk]]

### v1.1.45 (2026-09-13) — App 端库存预警显示具体产品

**症状**: App 端"库存预警"模块只显示汇总数, 看不到哪些产品低于预警值.

**方案**: 复用 pc-web 端 `/inventory/stock-warning` 接口, App 端加列表展示产品名/编码/当前库存/预警值/差额

**改动**:
- `app/src/api/index.js`: 新增 `getStockWarning` API 封装
- `app/src/pages/dashboard/index.vue`: 库存预警模块改为产品列表

### v1.1.44 (2026-09-13) — 库存预警不显示

**症状**: pc-web 库存预警模块一直空白, 后端 `/inventory/stock-warning` 返回空数组

**根因**: MyBatis Plus 默认 `deleted=0` 过滤掉软删除的产品, 但预警阈值 (sys_config 配置) 引用了已删除产品的 ID, 导致 join 后无结果

**方案**: 预警查询不加 `deleted=0` 过滤, 兼容软删除

**改动**:
- `backend/.../InvLedgerQueryMapper.xml`: 移除 `deleted=0` 条件

### v1.1.43 (2026-09-13) — 修复订单关联出库单"查看"白屏

**症状**: 销售订单列表点"关联出库单"弹窗里的"查看"按钮, 跳转销售出库详情页白屏

**根因**: `SalDeliveryController` 的 `@GetMapping("/{id}")` 路由声明在 `@GetMapping("/page-by-order")` 之前, Spring 把 `page-by-order` 当 id 解析为 Long 失败 → 500 错误

**方案**: 调整声明顺序, `page-by-order` 必须在 `/{id}` 之前

**改动**:
- `backend/.../SalDeliveryController.java`: 调整方法声明顺序 + 加注释提醒后续

### v1.1.42 (2026-09-13) — 销售出库单列表交货方式中文标签

**症状**: 销售出库单列表"交货方式"列显示英文枚举值 `DELIVERY/PICKUP/DIRECT`, 应显示中文 "送货/自提/专车直送"

**方案**: 后端 service `page()` 注入 `deliveryMethodLabel`, 与销售订单一致

**改动**:
- `backend/.../SalDeliveryService.java`: `page()` 加 `deliveryMethodLabel` 注入

### v1.1.41 (2026-09-13) — 销售订单发货联动 (delivery-summary + 已发/未发)

**症状**: 销售订单列表没"已发/未发"汇总, 用户需要逐行对比订单明细 vs 出库单

**方案**: 
- 弹窗"发货详情" — 新 endpoint `/sales/order/{id}/delivery-summary` 返回每行订单明细的已发/未发
- 列表"已发/未发"列 — 后端注入 `shippedQty` 字段 (基于 `out_qty` 求和, v1.1.46 改为实时 SUM)
- 销售出库审核时回写 `sal_order_detail.out_qty` (v1.1.46 才发现这个回写只对新数据有效)

**改动**:
- `backend/.../SalOrderController.java`: 加 `deliverySummary` endpoint
- `backend/.../SalOrderService.java`: 加 `getDeliverySummary` 方法, `page()` 加 `shippedQty` 注入
- `backend/.../SalOrderDetailMapper.java`: 加 `selectShippedQtyByOrderDetailId` (SQL 注解)
- `backend/.../SalDeliveryService.java`: 审核时回写 `out_qty` 列
- `pc-web/src/views/sales/Order.vue`: 加"发货详情"按钮和弹窗
- `pc-web/src/api/sales.js`: 加 `getDeliverySummary` API

### v1.1.40 (2026-09-13) — 销售出库单交货方式 + 商品明细采购订单号

**症状**: 销售出库单保存时缺交货方式字段, 商品明细无法关联采购订单号

**方案**: 表单加交货方式下拉 (送货/自提/专车直送), 明细行加采购订单号字段 (从源订单带入)

**改动**:
- `backend/.../SalDelivery.java` / `SalDeliveryDetail.java`: 加字段 + getter/setter
- `pc-web/src/views/sales/Delivery.vue`: 表单加交货方式, 明细加采购订单号
- `backend/src/main/resources/templates/print/sal_order_feie.ftl`: 模板加交货方式显示

### v1.1.39 (2026-09-12) — 按客户切换浏览器打印模板

**症状**: 不同客户订单格式不同, 但浏览器打印只用单一模板

**方案**: `sys_print_template` 表加 `customer_id` 字段, 浏览器打印时按当前订单客户 ID 优先匹配, fallback 到默认模板

**改动**:
- `backend/.../SysPrintTemplate.java` / `SysPrintTemplateMapper.java` / `SysPrintTemplateService.java`: 加 `customerId` 字段
- `backend/.../SysPrintTemplateController.java`: 模板查询加客户过滤
- `backend/.../SysPrintTemplateMapper.xml`: SQL 加 customer_id 条件
- `pc-web/src/api/system.js`: 模板查询 API 加 customerId 参数

### v1.1.38 (2026-09-11) — 销售订单关联出库单 (追溯入口)

**症状**: 销售订单出库后, 没法在订单页面回看出库单, 必须切到出库列表手动搜

**方案**: 销售订单列表加"关联出库单"按钮, 弹窗显示该订单的所有出库单, 支持"查看"跳转

**改动**:
- `backend/.../SalDeliveryController.java`: 加 `/page-by-order` endpoint
- `backend/.../SalDeliveryMapper.java` / `SalDeliveryService.java`: 加 `pageByOrderId` 方法
- `pc-web/src/views/sales/Order.vue`: 加按钮和弹窗
- `pc-web/src/api/sales.js`: 加 `pageByOrderId` API

### v1.1.36 (2026-09-06) — 销售订单补打印按钮

**症状**: 销售出库单 `Delivery.vue` 有浏览器打印 + 飞鹅云打印 dropdown, 但销售订单 `Order.vue` 完全没有打印能力.

**根因**: 
- `SysPrintTemplateService.BIZ_TYPES` 白名单没有 `SAL_ORDER` → 即使手动调 API 也会返回 null
- 没有 `BillLoader` 实现 → 飞鹅预览 `POST /feie/print/SAL_ORDER/{id}/preview` 会抛 "不支持的单据类型"
- 没有 ftl 模板 → 渲染失败
- 前端 `usePrint.js` 没有 `SAL_ORDER` 枚举 → `doPrint` 的 bizType 无效

**方案**: 
- 后端: 白名单 + BillLoader + ftl 模板三件套全补上
- 前端: `usePrint.js` 加枚举, `Order.vue` 操作列加打印 dropdown (browser/feie-preview/feie-print)

**改动**:
- `backend/.../SysPrintTemplateService.java`: BIZ_TYPES 加 `"SAL_ORDER"` (1行)
- `backend/.../bill/SalOrderBillLoader.java`: 新增 (≈55行), 加载 SalOrder + 明细, 注入 pModel
- `backend/.../templates/print/sal_order_feie.ftl`: 新增 (≈28行)
- `pc-web/src/composables/usePrint.js`: BIZ_TYPES + BIZ_TYPE_LABEL 各加 SAL_ORDER (6行)
- `pc-web/src/views/sales/Order.vue`: 操作列 width 290→370, 加打印 dropdown + 飞鹅预览弹窗 + onPrint/onPrintCommand 等 (~110行)

**验证**:
- `GET /api/system/print-template/biz-type/SAL_ORDER` → 200, data=null (未创建模板时正常)
- `GET /api/feie/print/SAL_ORDER/{id}/preview` → 200, 返回渲染后的飞鹅标签文本
- 测试数据: 订单 `SO202609060001` → 预览返回 `<CB>销售订单</CB><BR>单号: SO202609060001...合计: ¥7,910` ✅

### v1.1.35-1 (2026-09-06) — 销售订单审核 bill_no NULL 报错 hotfix

**症状**: 销售订单审核 (`POST /api/sales/order/{id}/check`) 抛 `java.sql.SQLIntegrityConstraintViolationException: Column 'bill_no' cannot be null`. 错误堆栈明确指向 `SalOrderMapper.updateById-Inline` —— 即走的是 XML 里自定义的 `<update id="updateById">`.

**根因**: `SalOrderMapper.xml` 自 v1.0 初始化 (93d4932) 起就有自定义 `<update id="updateById">` (line 23-36), **全字段 SET** (`bill_no = #{et.billNo}, bill_date = #{et.billDate}, ...`). MyBatis Plus 找到自定义 XML 后优先于默认行为, 即使实体字段为 NULL 也会写入 SQL 参数.

- `SalOrderService.check()` (line 185-188 原版) 只 set 了 `id` + `billStatus`, 其他字段都是 NULL → UPDATE 时 `bill_no` 被覆盖成 NULL → 触发 NOT NULL 约束
- 同理 `uncheck()` (line 200-203 原版) 也有同样 BUG
- `delete()` 用的是 `LambdaUpdateWrapper.set(SalOrder::getDeleted, 1)`, 所以没受影响
- `SalDeliveryMapper.xml` 没自定义 updateById, 所以出库单没这问题

**为什么 v1.1.11 引入审核功能时没发现**: 历史 UAT 没人实际点过「审核」按钮, 或者后续 BUG 被噪音盖过.

**方案**: `check()` / `uncheck()` 改用 `LambdaUpdateWrapper.eq(SalOrder::getId, id).set(SalOrder::getBillStatus, ...)`, 只 SET 一个字段, 其他列保持不变. 跟 `delete()` 同样模式.

**改动**:
- `backend/.../SalOrderService.java`: check + uncheck 改 LambdaUpdateWrapper (共 -10 行 +12 行)
- `backend/.../SalOrderServiceTest.java`: +3 测试 (check_usesLambdaUpdateWrapper / uncheck_usesLambdaUpdateWrapper / check_wrongStatus_throws), 用 `@BeforeAll initTableInfo` + `MapperBuilderAssistant` 初始化 lambda cache
- 其他: 0 改动

**验证 (NAS 部署后)**:
- `POST /api/sales/order/2096482979270463489/check` → `{"code":200,"msg":"操作成功"}` ✅
- DB 字段保留: `bill_no=SO202609060001, bill_status=CHECKED, customer_id=..., customer_name=7412, total_amount=7910` ✅
- 反审核 + 再审核: 均 200 ✅
- 单测: `SalOrderServiceTest` 5/5 + `SalDeliveryServiceTest` 6/6 全过 ✅

**未修复** (留 v1.1.36+): `SalOrderMapper.xml` 全字段 updateById 是历史债, 修风险大 (影响 `update()` 编辑订单路径), 本次只针对 check/uncheck. 后续可考虑删 XML 自定义 updateById 让所有路径走 MyBatis Plus 默认.

### v1.1.35 (2026-09-06) — 销售订单「生成出库单」一键联动入口

**症状**: 销售订单审核后, 仓库需要重新录入一份 `sal_delivery` (重复录客户/商品/价格), schema 里预留的 `order_id` / `order_no` / `order_detail_id` / `out_qty` 联动字段一直是死代码 (grep 验证 0 个 `fromOrder` / `setOutQty` 调用).

**方案**: 销售订单列表 (仅 `CHECKED` 状态) 加「生成出库单」按钮 → 轻量级弹窗 → 自动带入订单客户 / 仓库 / 商品明细 → 用户改仓库 / 库位 / 批次 → 保存为草稿 → 走现有 `salDeliveryApi.add()` 流程 (审核 / 反审核 / AR / 库存账完全不动).

**改动**:
- 后端: **0 行代码** (SalDeliveryMapper.xml 只有 `<select>`, BaseMapper.insert 自动写 entity 字段)
- 前端: `pc-web/src/views/sales/Order.vue` 加按钮 + 弹窗 + `onGenerateDelivery` / `onConfirmGenerate` + 复用 v1.1.33 `normNum` (≈180 行)
- SQL: **0 改动** (`sal_delivery.order_id` / `order_no` / `sal_delivery_detail.order_detail_id` 列已存在; `sales:delivery:add` perm 已 seed)
- 测试: `SalDeliveryServiceTest#add_fromOrder_writesLinkageFields` 通过

**关键发现**: SalDelivery 实体 `orderId` / `orderNo` 字段不是 `@TableField(exist=false)`, 所以 Jackson 反序列化 → BaseMapper.insert 自动写入 DB. **前端传联动字段 → 后端零改动**.

**用户决策** (2026-09-06):
- 默认数量 = 订单明细 qty (全量). 多笔出库 / 部分发货靠用户后续手动减
- 状态守卫: 仅 CHECKED 订单显示按钮
- 弹窗设计: 轻量级内联弹窗 (不抽取公共组件)

**未启用** (留待后续版本):
- `sal_order_detail.out_qty` 累计回写 (需 SQL + SalOrderService.check 后置调用)
- 订单状态机 PICKING/FINISHED 自动推进
- 订单审核即扣库存 (见 v1.1.34 风险分析, 不建议)

**验证**: T1 单测通过 (`mvn test -Dtest='SalDeliveryServiceTest#add_fromOrder_writesLinkageFields'` → BUILD SUCCESS). T2 pc-web build 通过 (`Order-DqF1mThT.js` 新 chunk).

---

### v1.1.34 (2026-09-05) — 侧边栏 4 个菜单图标缺失 (补 Sell/Grid/DataAnalysis/Odometer)

**症状**: 用户截图显示侧边栏 **销售管理 / 库存管理 / 报表中心** 三个主菜单左侧没有图标 (空白方块位置), **工作台** 也没图标. 其余菜单 (系统管理/基础资料/采购管理/生产管理/应收应付) 图标正常.

**根因**: `pc-web/src/layouts/MainLayout.vue` 用 `<component :is="m.icon" />` **字符串式** 渲染图标, 要求图标必须在 `app.component(name, Component)` 注册. `main.js` 注册列表漏了 4 个:
- `Sell` → 销售管理
- `Grid` → 库存管理
- `DataAnalysis` → 报表中心
- `Odometer` → 工作台

**修复**: `pc-web/src/main.js` import 与注册循环各补 4 个:
```js
// import 末尾:
import { ..., Sell, Grid, Odometer } from '@element-plus/icons-vue'
// (DataAnalysis 已在列表中, 不重复)
// 注册循环末尾:
;[..., Sell, Grid, Odometer].forEach(c => app.component(c.name, c))
```

**验证**: 4 个图标文件确实存在 (`@element-plus/icons-vue/dist/types/components/{sell,grid,odometer,data-analysis}.vue.d.ts`), 不是拼写错误.

**GitHub**: `8cdc861` (已推送)

---

### v1.1.33 (2026-09-05) — 改用 el-input 彻底修复小数点无法输入

**问题**: v1.1.32 修了 `stripZeroParse` 中间态返回字符串, 但 el-input-number 在表格内仍被 EP `precision + min + step-strictly` 组合的内部 `displayValue/watch` 机制干扰, 用户截图显示数量/单价列完全无法输入小数点.

**根因**: el-input-number 在 `handleInput(value)` 后会:
1. 把 `value` 给 `parser`, 拿到的结果如果是字符串 '1.' 就把 currentValue 也设为字符串
2. `watch(currentValue)` 触发 `displayValue = formatter(currentValue)`, 但 formatter 的输入字符串 `'1.'` 不会被正确格式化
3. 表格 cell 在渲染时会强制调用 `el-input-number.setCurrentValue(parseFloat(value))`, 触发 clamp

**修复**: **完全不用 `el-input-number`, 改用 `<el-input inputmode="decimal">`** + 三个事件处理器:
- `@focus` — 焦点移入时清掉占位 `0` (否则点不动), 同时把 `_priceFromUnit` 置 false (允许用户编辑)
- `@input` — 实时清洗: 去掉非数字字符, 限制只有一个 `.` 和一个 `-`
- `@blur` — 失焦时把字符串归一化成 number (保证 `row.qty * row.price` 计算正确)

```vue
<el-input v-model="row.price" size="small" type="text" inputmode="decimal"
  @focus="onPriceFocus(row, $event)"
  @input="onPriceInput(row, $event)"
  @blur="row.price = normNum(row.price)" placeholder="0" />
```

```js
const normNum = (v) => {
  if (v == null || v === '') return 0
  const n = Number(String(v).replace(/,/g, ''))
  return isFinite(n) ? n : 0
}
function onPriceInput(row, ev) {
  let raw = ev.target.value
  let clean = raw.replace(/[^\d.-]/g, '')
  // 只允许第一个 -, 第一个 .
  const dotIdx = clean.indexOf('.')
  if (dotIdx >= 0) clean = clean.slice(0, dotIdx + 1) + clean.slice(dotIdx + 1).replace(/\./g, '')
  if (clean !== raw) ev.target.value = clean
  row.price = clean  // 中间态 "1." 保持 string, 让金额列也能容错
}
```

**涉及文件** (1 个):
- `pc-web/src/views/sales/Delivery.vue` — 销售出库表格数量/单价列改用 el-input

**未来调整**: 若修复需要扩散到采购入库/退货/生产加工单等其他表单, 把 `normNum/onPriceFocus/onPriceInput` 抽到 `useStripZero.js` 暴露即可.

### v1.1.32 (2026-09-05) — stripZeroParse 修复单价无法输入小数点

**问题**: 用户在销售出库/采购入库的"单价(含税)" `el-input-number` 框无法输入小数点, 例如想打 `1.5` 只能输入 `15`.

**根因**: `useStripZero.js` 中的 `stripZeroParse` 函数对任何 `Number()` 转换失败的结果都返回 `null`:
```js
const stripZeroParse = (v) => {
  if (v == null || v === '') return null
  const n = Number(String(v).replace(/,/g, ''))
  return isFinite(n) ? n : null  // ← BUG: "1." 转 NaN → isFinite false → 返回 null
}
```
当用户输入 `1.5` 时, Element Plus 在中间态传 `"1."` 给 parser. `Number("1.")` 返回 `NaN`, 不被识别为 finite, parser 返回 `null`, EP 立即把整个输入框清空 → 用户没法继续输入.

**修复**: 中间态 (`1.` / `.5` / `-` 等不完整输入) 直接返回字符串透传, 不强制转 Number:
```js
if (/^-?\d*\.$|^-$/.test(cleaned)) return cleaned  // 中间态原样返回
const n = Number(cleaned)
return isFinite(n) ? n : cleaned  // 完全无效才返回原始字符串 (不再 null)
```

**涉及文件** (3 个):
- `pc-web/src/composables/useStripZero.js` — 共用 hook, 修复源
- `pc-web/src/views/sales/Delivery.vue` — 销售出库 (本地副本, 同步修复)
- `pc-web/src/views/production/Order.vue` — 生产加工单 (本地副本, 同步修复)

**部署提示**:
- 容器实际挂载路径是 `/tmp/pc-web-new -> /usr/share/nginx/html` (不是 `docker-compose.yml` 写的 `/volume3/docker/erp-system/pc-web/dist`)
- 部署时不能直接 `tar -xzf`, 因 macOS 打包的 tarball 携带 LIBARCHIVE.xattr 扩展头导致部分文件被跳过 → 必须先 tar 到本地, 然后在 NAS 端 `rm -rf /tmp/pc-web-new && mkdir -p /tmp/pc-web-new/assets && cp dist/index.html /tmp/pc-web-new/ && cp dist/assets/* /tmp/pc-web-new/assets/`
- 然后 `docker restart erp-pc-web`, 通过 `curl http://127.0.0.1:18080/index.html` 验证引用了新 bundle

### v1.1.31 (2026-09-05) — 销售出库审核库存不足无提示

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

## 变更日志 (v1.0.10 ~ v1.1.34)

### v1.1.31 (2026-09-02) — 销售出库审核库存不足无提示 (ElMessageBox 样式 + alert 强制可见)

**症状**: 销售出库 → 审核 → 库存不足时**没有任何弹窗提示**(用户看到 JS 错误堆栈,但页面无反应)。原本 v1.1.11 已加 try-catch + ElMessage.error,看似应该能弹,实际不行。

**根因链** (5 层):
1. **ElMessage (toast) 容易被路由切换/遮罩层销毁**: 用户操作太快时 toast 一闪而过,看不到
2. **后端抛 BizException("库存不存在, ...")** → R.fail(500, msg) → 响应 200 (HTTP 层)
3. **request.js 拦截器检测到 `data.code !== 200`**: 立刻 `ElMessage.error(data.msg)` + 同时 `reject(new Error(data.msg))`
4. **onCheck catch 又触发** `ElMessage.error((e && e.msg) || (e && e.message) || '审核失败')` — **双弹覆盖**,用户看到一闪而过的 toast
5. **`unplugin-vue-components` 按需组件不识别 API 调用的 CSS**: `ElMessageBox.confirm/alert` 是 API 调用, 不会触发自动 CSS 导入, 弹窗虽然显示但**没有背景色/边框/阴影**

**修复 — 4 个 commit**:

| # | commit | 内容 |
|---|---|---|
| 1 | `3a4dcff` | 全局 `el-message-box` 居中 CSS (但 position: fixed 破坏内部布局) |
| 2 | `3b87823` | request.js 拦截器去掉重复 ElMessage, 让组件 catch 统一弹 (用 bizErr.msg = data.msg 传业务 msg) |
| 3 | `6a3661c` | onCheck/onUncheck 改用 ElMessageBox.alert (modal 风格, 强制可见, 必须点确认才能关) |
| 4 | `2a34069` | main.js 手动 import `element-plus/theme-chalk/el-message-box.css` + `el-message.css` + `el-notification.css` (解决样式缺失) + 修正居中 CSS 用 flex 布局 |

**关键教训**:
- Element Plus 的 `ElMessageBox.confirm/alert` 是 API 调用, **unplugin-vue-components 不会自动导入其 CSS**, 必须在 main.js 手动 `import 'element-plus/theme-chalk/el-message-box.css'`
- `ElMessage` (toast) 不适合提示业务异常, **重要错误必须用 `ElMessageBox.alert` (modal)**, 强制用户确认
- **axios 拦截器不要重复弹 ElMessage**, 让组件 catch 统一处理 (避免双弹覆盖)
- 全局居中 CSS 用 flex (`.el-message-box__wrapper { display: flex; align-items: center }`), **不要 position: fixed + transform** (破坏 Element Plus 内部 box 布局)

**部署注意事项**:
- 部署前清理 NAS 旧 tar 包: `rm -f /tmp/dist*.tar.gz` (避免 /tmp 4GB 满了)
- 上传用 `cat local.tar.gz | ssh 'cat > /tmp/remote.tar.gz'` 比 scp 更稳
- tar 用 `--strip-components=1` 去掉 `dist/` 前缀

### v1.1.30 (2026-08-31) — 飞鹅模板保存后再打开内容丢失 (insertTag 同步 + UpdateWrapper)

**症状**: 用户在 PC 端 `系统 → 飞鹅打印模板` 编辑生产加工单模板, 内容含 `规格: ${order.spec!''}` 等字段占位符, 保存后再次打开, 该段占位符整段不见 (具体表现为 `${order.spec!''}<BR>` 被吃掉, 末尾 `<BR>` 计数 +1).

**根因诊断** (Explore agent 全代码搜索 + 后端日志分析):

| 怀疑点 | 排查结果 |
|---|---|
| 后端 sanitize / XSS / 占位符转义 | **无任何相关代码** (WebMvcConfig / Jackson / Interceptor / Filter / 注解) |
| MySQL `content` 字段类型 | `LONGTEXT` (4GB 上限), 不可能长度截断 |
| MyBatis-Plus `updateById` 字段策略 | 全局 `update-strategy: not_null`, 对 String content 不影响 (不为 null) |
| Freemarker 渲染路径 | `renderCustomContent` 只读不写, 不影响 DB |
| **前端 `insertTag()` DOM 操作 vs Vue v-model** | **真正的 bug 嫌疑**: 直接 `textarea.value = ...` 改 DOM, 没 dispatch 'input' 事件, **Element Plus 的 v-model 同步可能丢失**, 导致保存时拿到的 `form.content` 与 DOM 不同步 |

**修复 — 4 个方向**:

| # | 文件 | 改动 |
|---|---|---|
| 1 | `pc-web/.../FeiePrintTemplate.vue` `insertTag()` | 同时写 `form.content` + dispatch 'input' 事件, 保证 Vue v-model 与 DOM 同步 |
| 2 | `pc-web/.../FeiePrintTemplate.vue` PRD_ORDER 字段显示 | 取消 `v-if="!== 'PRD_ORDER'"` 排除, 字段显示统一带 `!''` (UX 修正) |
| 3 | `backend/.../SysFeiePrintTemplateService.java` `update()` | 改用 `LambdaUpdateWrapper` 显式 SET (排除 MyBatis-Plus 任何隐藏副作用) + 回查验证日志 |
| 4 | `backend/.../SysFeiePrintTemplateService.java` `save()` | 入参 + 写库回查日志 (`[FeieTpl#save]` / `[FeieTpl#update]` 含 contentLen/head/tail/match) |
| 5 | `pc-web/.../FeiePrintTemplate.vue` `loadData` + `onSubmit` | 加 `console.log` 诊断, 输出 contentLen / head / tail |

**关键教训**:
1. **Element Plus `el-input` / `el-textarea` 不要直接改 DOM `.value`**, 必须同步触发 'input' 事件让 Vue 响应式系统知道 (与 Vue 2 不同, Vue 3 + Element Plus 内部对 `el-textarea` 的 v-model 是基于 input 事件)
2. **MyBatis-Plus `updateById` 的字段策略 + 全局 `not_null` 是潜在隐患**, 对涉及核心字段的 service 可改用 `LambdaUpdateWrapper` 显式 SET, 增加确定性
3. **保存后再打开数据丢失**类 bug 排查路径: 网络请求体 → 后端日志 (请求参数) → DB 实际值 → 前端 v-model 绑定. 任何一环都可能丢.

**验证方法**:
- 用户重新部署前端 dist + 后端 jar 后, 再保存一次模板
- 后端日志看 `[FeieTpl#save/update] contentLen=... match=true/false`
- 前端 console 看 `[FeieTpl#onSubmit] content.length=... head=... tail=...`
- 若 `match=true` 但前端仍丢, 问题在 list/getTemplate 返回路径
- 若 `match=false`, 问题在保存路径 (MyBatis-Plus 字段策略/Hook)

**DB 直查验证 + 直接修复** (2026-08-31 14:30 SSH 到 NAS 后):

通过 SSH + sudo docker exec 进 `erp-mysql` 容器,以 `-h mysql -uroot -perp_root_pwd` (实际密码是 `erp_root_pwd`, 不是记忆中的 `850225sonG`) 查 DB:

```sql
SELECT id, name, biz_type, LENGTH(content) AS len,
       content LIKE '%${order.spec}%' AS has_spec_var
FROM sys_feie_print_template WHERE deleted=0;
```

→ **DB 中 `规格：` 后是空字符串 (换行后直接 `备注：`),`update_time` = `2026-08-31 14:00:57` (用户截图当天)**, **完全对应图17 的现象**。
→ 即: **用户编辑时点"插入字段"按钮插入 `${order.spec!''}`, DOM 文本框显示了, 但 Vue 的 `form.content` reactive 没同步; 点击保存时 payload.content 不含 `${order.spec!''}`, DB 写入时就丢了**。

**直接修复**: 用 Python 生成正确的完整 content (17 行), 在 NAS 上 `/tmp/fix.sql` 跑:

```sql
ALTER TABLE sys_feie_print_template ADD COLUMN content_backup_20260831 LONGTEXT;
UPDATE sys_feie_print_template SET content_backup_20260831 = content WHERE id = 2078644534343434242;
UPDATE sys_feie_print_template SET content = '<CB>生产单</CB>\n...规格：${order.spec!''''}<BR>\n...' WHERE id = 2078644534343434242;
```

→ 修复后 `LENGTH(content) = 437` (含规格行),`INSTR(content, 0x247B6F726465722E737065632127277D) = 298` (规格占位符存在).
→ **同时备份了原 content 到 `content_backup_20260831` 列**, 需要时可手动回滚: `UPDATE ... SET content = content_backup_20260831 WHERE ...`.

**SSH 隧道细节** (后续运维参考):
- `gpssong` 用户没 docker 组权限, 必须 `echo 19850225aB | sudo -S -p "" /usr/local/bin/docker exec ...`
- docker exec 走 `mysql -h mysql` (用容器 hostname), 直接 `-h 127.0.0.1` / `-h localhost` 会报 `Access denied` (root@'localhost' 走 socket, 密码不对)
- mysql root 实际密码是 `erp_root_pwd` (与 `erp-backend` 容器 env 一致), 不是记忆中的 `850225sonG` (compose 文件里写的, 但部署时换过)

**v1.1.30 部署踩坑** (2026-08-31 15:00~15:15 部署期间遇到):

**坑 1: NAS docker compose v2.20 解析 `${VAR:?MSG}` 语法报错**
- 报错: `yaml: line 84: mapping values are not allowed in this context`
- line 84 是 `SA_TOKEN_JWT_SECRET_KEY: ${SA_TOKEN_JWT_SECRET_KEY:?SA_TOKEN_JWT_SECRET_KEY must be set in .env. Generate with: openssl rand -hex 32}` (用 `?` 强制校验 + 中文逗号在错误信息里)
- 这版 docker compose (v2.20.1-6047-g6817716, NAS DSM 7.4) 不支持该语法 (官方 v2.x 应该支持, 但此 build 不行)
- 解决: **绕过 compose**, 直接 `docker stop && docker rm && docker run -d` 用显式命令行参数重建容器
- **后续 SOP**: 把所有 deploy 步骤写到一个 `deploy-to-nas.sh` 脚本里 (避免遗漏 env), 或修复 compose 文件 escape

**坑 2: 手动 docker run 漏传 `ERP_CORS_ALLOWED_ORIGINS` env, 部署后所有前端登录 403**
- 现象: 部署完用户 PC 浏览器 `POST /api/auth/login 403 Forbidden`
- 后端日志: 无 `POST /api/auth/login` 记录 — 因为 Spring Security CORS 校验先于 controller
- 根因: 我手动重建容器时, 只复制了 SPRING_DATASOURCE_*/ / JAVA_OPTS / SA_TOKEN_JWT_SECRET_KEY / SPRING_DATA_REDIS_HOST 等"必须的"env, **漏了 `ERP_CORS_ALLOWED_ORIGINS`**
- 后端 application.yml 默认值只有 `http://localhost:5173/5174/8080, http://127.0.0.1:5173/5174/8080`, **不含 `home.93gushi.com:8088` / `n150.93gushi.com:8088` / `192.168.0.150:8088`** 等外部域名
- 浏览器发 OPTIONS 预检 → Spring Security 返回 403 "Invalid CORS request" → POST 根本没发
- 修复: `docker rm -f erp-backend && docker run -d ... --env ERP_CORS_ALLOWED_ORIGINS=http://home.93gushi.com:8088,https://home.93gushi.com:8089,...` (完整 list 16 个 origin)
- 验证: `curl -X OPTIONS ... -H "Origin: http://home.93gushi.com:8088"` → 200 + `Access-Control-Allow-Origin` ✓
- **关键 env list 必须完整** (任何删减都会让某 origin 走不通):
  ```
  ERP_CORS_ALLOWED_ORIGINS=http://home.93gushi.com:8088,https://home.93gushi.com:8089,
    https://home.93gushi.com,http://home.93gushi.com,
    http://n150.93gushi.com:8088,https://n150.93gushi.com:8088,
    http://192.168.0.150:8088,http://192.168.0.150,
    http://localhost:5173,http://localhost:5174,http://localhost:8080,
    http://127.0.0.1:5173,http://127.0.0.1:5174,http://127.0.0.1:8080,
    http://localhost/*,http://127.0.0.1/*,
    http://192.168.0.150:18080,http://192.168.0.150:5173,http://192.168.0.150:5174
  ```

**坑 3: pc-web 容器 bind mount 的不是 `/volume3/docker/erp-system/pc-web/dist`, 而是 `/tmp/pc-web-new`**
- Dockerfile 注释说 "前端 dist 通过 bind mount 进容器 /usr/share/nginx/html", 但实际 mount source 是 `/tmp/pc-web-new`
- 部署时我把新 dist `tar 流式传到 /volume3/docker/erp-system/pc-web/dist` 后, **必须** 再 `cp` 一份到 `/tmp/pc-web-new` 才能生效
- `docker restart erp-pc-web` 才能加载新内容 (容器本身不需重建, 因为 dist 通过 mount 进)
- **NAS 上 `/tmp/pc-web-new` 是 uid=501 拥有, gpssong 没权限, 需要 `echo 19850225aB | sudo -S cp` 或 `rm -rf && cp -r`** (新建文件可写)

**坑 4: SSH "Permission denied, please try again" 间歇性锁**
- 多次 `sshpass` 连续发命令时, NAS 端 sshd 偶尔会触发密码失败计数, 临时 lock gpssong 用户 30 秒
- 解决: `sleep 8~15` 重试; 若是连续多次错误, 可能需要更长时间 (等 NAS 自动解锁)
- `ssh -o StrictHostKeyChecking=no` 即可, 不要用错字 `StrictHostKeyKeyChecking=no` (会引发其他错误)

**部署命令汇总** (后续 v1.1.30+ 重启时复用):
```bash
# 1. pc-web dist 上传 (SSH)
sshpass -p '19850225aB' ssh gpssong@192.168.0.150 'echo 19850225aB | sudo -S -p "" sh -c "
  cd /volume3/docker/erp-system/pc-web && tar cf - --exclude=node_modules dist/ \
    | tar xf - -C /tmp/pc-web-new && \
    docker restart erp-pc-web
"'

# 2. backend jar 上传 (SSH, cat 流式)
cat backend/target/industrial-erp-1.0.4.jar | sshpass -p '19850225aB' ssh gpssong@192.168.0.150 \
  'cat > /volume3/docker/erp-system/backend/industrial-erp-1.0.4.jar'

# 3. backend 镜像 build + container recreate (完整 env list 见上方)
sshpass -p '19850225aB' ssh gpssong@192.168.0.150 'echo 19850225aB | sudo -S -p "" sh -c "
  cd /volume3/docker/erp-system && docker build -t erp-system-backend:latest -f backend/Dockerfile ./backend && \
  docker rm -f erp-backend && \
  docker run -d --name erp-backend --network erp-system_erp-net \
    --publish 8080:8080 \
    --mount type=bind,source=/volume3/docker/erp-system/data/upload,target=/opt/industrial-erp/upload \
    --mount type=bind,source=/volume3/docker/erp-system/data/backup,target=/opt/industrial-erp/backup \
    --env SPRING_PROFILES_ACTIVE=prod \
    --env SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/industrial_erp?... \
    --env SPRING_DATASOURCE_USERNAME=root \
    --env SPRING_DATASOURCE_PASSWORD=erp_root_pwd \
    --env SPRING_DATA_REDIS_HOST=redis \
    --env SPRING_DATA_REDIS_PORT=6379 \
    --env SA_TOKEN_JWT_SECRET_KEY=71db660065bfdd3b78f277b1e90b715e6da12567420b6a1733f97bdaba4b6562 \
    --env JAVA_OPTS=\"-Xms256m -Xmx768m -XX:MaxMetaspaceSize=192m -XX:+UseG1GC -Dfile.encoding=UTF-8 -Duser.timezone=GMT+8\" \
    --env ERP_UPLOAD_PATH=/opt/industrial-erp/upload \
    --env ERP_BACKUP_PATH=/opt/industrial-erp/backup \
    --env ERP_BACKUP_SQL_PATH=/opt/industrial-erp/sql \
    --env ERP_CORS_ALLOWED_ORIGINS=http://home.93gushi.com:8088,https://home.93gushi.com:8089,... \
    --env TZ=Asia/Shanghai \
    --health-cmd=\"wget -qO- http://127.0.0.1:8080/api/auth/captcha || exit 1\" \
    --health-interval=30s --health-timeout=3s --health-retries=3 \
    erp-system-backend:latest
"'

# 4. 验证
curl -s http://127.0.0.1:8080/api/auth/captcha | python3 -m json.tool  # 后端
curl -sI http://127.0.0.1:18080/ | head -5                                # pc-web
curl -sv -X OPTIONS http://127.0.0.1:8088/api/auth/login -H "Origin: http://home.93gushi.com:8088" -H "Access-Control-Request-Method: POST" | grep "Access-Control-Allow"  # CORS
```

### v1.1.29 (2026-08-31) — App 生产单分享 PDF 完整修复 (3 层穿透 + 自愈机制)

**问题**: App 端生产单详情 → "📤 分享生产单" → 生成 PDF → 连续 3 个错:
1. `"Share" plugin is not implemented on android` — Capacitor CLI 6 把 `capacitor.plugins.json` 重置为 `[]`, Share plugin 从未注册
2. `Unsupported url` — uni.downloadFile 返回 `_doc/uniapp_temp/xxx.pdf` 没 `file://` 前缀, Capacitor Share 的 `url` 校验失败
3. `Failed to find configured root that contains /localhost/UUID` — `plus.io.convertLocalFileSystemURL` 返回 webview 风格路径, FileProvider 找不到 root
4. `file not found: /data/.../files/blob:http://localhost/UUID` — `uni.saveFile` 返回 webview blob 虚拟路径, 根本没写文件

**修复 — 4 层穿透**:

| # | 层 | 文件 | 作用 |
|---|---|---|---|
| 1 | Capacitor 插件注册 | `android/app/src/main/assets/capacitor.plugins.json` | 手动写 4 个插件 (NativeScanner/Share/BarcodeScanner) 的 classpath, 防 CLI sync 重置为 `[]` |
| 2 | Capacitor 项目引用 | `android/capacitor.settings.gradle` | 显式 include `:capacitor-share` + `:capacitor-community-barcode-scanner` |
| 3 | FileProvider root | `android/app/src/main/res/xml/file_paths.xml` | 加 `<files-path>` + `<external-cache-path>` (原只有 external/cache) |
| 4 | **新增 NativeSharePlugin** | `android/app/src/main/java/com/pengcheng/erp/NativeSharePlugin.java` | **彻底绕开 JS 端文件路径问题** — 原生 plugin 接收 `url` 自己 `HttpURLConnection` 下载到 `getFilesDir()/share-<ts>.pdf` (真实磁盘路径), `FileProvider.getUriForFile` 转 content://, `ACTION_SEND + EXTRA_STREAM` 启 share sheet |
| 5 | Gradle 自愈 | `android/app/build.gradle` 的 `mergeDebugAssets` | 每次 build 前校验 plugins.json, 缺失 SharePlugin/NativeSharePlugin 就重写 |

**JS 端** (`app/src/pages/production/order-detail.vue`):
- `import { registerPlugin } from '@capacitor/core'; const NativeShare = registerPlugin('NativeShare')`
- `onShare()` 直接传 `url: api.prdOrderPdfUrl(...)`, 不再 `uni.downloadFile` + `uni.saveFile`
- 删除 `saveDownloadedPdf` helper (已不需要)
- 不再依赖 `@capacitor/share` (但仍保留注册, 避免破坏 plugins.json 兼容性)

**新增原生 plugin**: `NativeSharePlugin.java` (77 行):
```java
@CapacitorPlugin(name = "NativeShare")
public class NativeSharePlugin extends Plugin {
    @PluginMethod
    public void sharePdf(PluginCall call) {
        String url = call.getString("url");
        // 后台 ExecutorService 下载到 getFilesDir()/share-<ts>.pdf
        // 自动带 CookieManager cookie 让后端 Sa-Token 鉴权通过
        // FileProvider.getUriForFile → content:// URI
        // Intent.ACTION_SEND + EXTRA_STREAM 启 share sheet
    }
}
```

**MainActivity 注册**: `registerPlugin(NativeSharePlugin.class)` 在 `super.onCreate` 之前.

**为什么不用 Capacitor Filesystem 写文件**: 该插件未安装, 安装会触发 peer dep 冲突 (`barcode-scanner` 锁 capacitor 5, filesystem 要 capacitor 6). 自写 plugin 更短更可控.

**新 APK**: `~/Desktop/鹏程ERP-debug.apk` (4.37MB). 部署后预期: 生产单详情 → 分享生产单 → 后台下载 PDF → 弹原生分享菜单 → 微信/QQ 收到真实 PDF 附件.

**踩坑经验 (下次再写 Capacitor plugin 参考)**:
- Capacitor 6 不再自动扫描 `@CapacitorPlugin` 注解生成 `capacitor.plugins.json`, 必须 `npx cap sync` 后**手动**列 classpath, 或写 gradle 任务自愈
- `npx cap sync` 会把 `capacitor.plugins.json` 重置为 `[]` 如果 `includePlugins` 没匹配 (因为本地 native plugin 不是 npm 包), 强烈建议 gradle 任务自愈
- Capacitor Share 6 的 `files` 数组对路径非常挑剔, uni-app 沙箱 `plus.io` 输出又不稳定 — 自写原生 plugin + `getContext().getFilesDir()` 是最干净的方案

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