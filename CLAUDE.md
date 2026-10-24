# 工业 ERP 系统 (industrial-erp)

**当前版本**: v1.1.124 (2026-10-24 — 采购入库「非食品采购」入口补齐 + R26 mapper XML 坑)。**(1) 非食品采购入口**: 用户反馈「采购入库只有 XK 原料入库, 非食品包装商品没有对应入库入口」—— **根因不是后端门禁, 是前端没地方选商品**: v1.1.95 把整个采购入库页重构成「XK 三步检验表单」, `form.details` 只能由「关联采购订单」带入, **没有任何商品明细选择器** → 线上 130 个 `is_food_grade=0` 商品根本进不来 (后端 v1.1.122 已按行豁免, 完全兼容)。修法: `Receipt.vue` 的「新增采购入库」Tab 内嵌两个子 Tab —— `[原料入库 · XK受控]` (现有 XK 三步表单, 不动) + `[非食品采购 · 免检直通]` (新增标准入库表单: 供应商/仓库/入库日期 + 入库明细表(商品/规格/数量/单价/批次/库位) + 备注 + 供应商历史采购 + 「保存入库单(草稿)」→ 跳单据列表审核)。**路由判定不给手选开关** —— 按明细行商品 `is_food_grade` 实时算, 含食品级行则拒绝提交并引导切原料 tab (前端按开关判/后端按明细判会打架)。顶部按钮按子 tab 分流 (XK 的「保存检验记录/生成验放单并入库」只在原料 tab 显示)。**后端零改动 / SQL 零改动**。**(2) R26 mapper XML**: `PrdBomMapper.xml` 手写 `<insert>` 只写 13 列, **漏 8 列** (`product_id`/`product_code`/`product_name`/`spec`/`unit_id`/`unit_name`/`base_qty`/`output_qty`) → `product_id` 已被 sql/19 放宽 nullable 故**不报错静默丢数据** (线上 8 行 3 行 NULL), `base_qty` 有 DB DEFAULT 1.0 **被默认值掩盖**。补齐到 21 列。**⚠️ 实测纠正了一个常见误解**: 手写 XML 覆盖 MP 内置方法**会**绕过全局 `update-strategy: not_null` (稀疏对象被写成 NULL → 500, v1.1.123 修), 但**不会**绕过 `@TableField(fill=)` 自动填充 (MP `MybatisParameterHandler` 按 `SqlCommandType.INSERT` 触发, 与手写无关 —— 飞牛建 BOM 实测 `create_by`/`create_time` 正常)。**实测**: 双站非食品采购入库全链路 (建单 → 审核 → 库存 +100, `xkExempt=1`, 无 IQC 关联), BOM 建单 `product_id`/`base_qty`/`output_qty` 正确写入; 测试数据已清理。**双站 jar md5 均 `18ab6532621eee892302ebc452314b91`, 版本均 1.1.124**.)
**前版本 v1.1.122**: PC 生产工单详情「生产记录号」二维码。需求: PC 生产工单页 `/production/order` 右侧详情卡把「生产记录号」(工单单号 `PD2026…`) 做成二维码, 现场扫码快速定位/追溯。纯前端 1 文件 `pc-web/src/views/production/Order.vue`: 引 `qrcode` 包 (浏览器版零原生依赖, vite 自动 resolve browser 入口; 补进 package.json dependencies 防 npm ci 缺失), `QRCode.toDataURL(no,{width:132,margin:1})` 生成 dataURL + `qrDataUrl` ref + `watch(detail.no)` 换单号即重画 (无单号隐藏), 详情卡头部状态 tag 旁加二维码框 (img + 单号文本, `image-rendering:pixelated`)。**零后端改动/不重打 jar**。踩坑: watch 必须放 `detail` computed 定义之后 (script 顶部 immediate 会 TDZ 抛错); 飞牛 pc-web 容器 bind-mount 真源是 `/vol2/erp-system/pc-web/dist-new` (非 compose 写的 dist), 只读 → 改 host dist-new 再 restart, 不能 docker cp。)
**前版本 v1.1.117**: App 成品入库单位显示真实单位名 (原兜底字面量「主单位」) + 销售出库批次复选 (| 分隔复用 batch_no 零 DDL) + R21 延伸 hotfix-2 (`unitId=0` 被真值过滤误删单位行, 3 处) + hotfix-3 (sql/127 `unit_id=0` 数据回填, 公斤→并入 kg / 片→新建字典行)。**坑**: 空串是**合法**批号「无批次」不能过滤(会让「A+无批次」全扣 A 报库存不足); uncheck 必须读台账镜像回补不能重算分配(否则造 `batch_no='A|B'` 幽灵库存行)。
**前版本 v1.1.117**: App 成品入库单位显示真实单位名 (原兜底字面量「主单位」) + 销售出库批次复选 (| 分隔复用 batch_no 零 DDL) + R21 延伸 hotfix-2 (`unitId=0` 被真值过滤误删单位行, 3 处) + hotfix-3 (sql/127 `unit_id=0` 数据回填, 公斤→并入 kg / 片→新建字典行)。**坑**: 空串是**合法**批号「无批次」不能过滤(会让「A+无批次」全扣 A 报库存不足); uncheck 必须读台账镜像回补不能重算分配(否则造 `batch_no='A|B'` 幽灵库存行)。
**前版本 v1.1.116**: R22 Jackson 大写 P 前缀 transient 字段 key 漂移根治 + 生产工单详情默认不拉修复。(1) **根因**: 实体 `pThickness` 等 transient 字段 getter `getPThickness()`, Jackson legacy 命名规则把首段 P 小写化 → 序列化输出 `pthickness` (非 `pThickness`), 前端按 `pThickness` 读**恒 undefined 且不报错** → 工单详情「成品规格属性」7 字段 + 食品合规 5 字段全显示 `—`。同一 JSON 里无前缀 `getThickness()` 正常输出 `thickness` (打印模板在用, 保留)。(2) **影响面**: 全仓 8 实体 29 字段中招 — `PrdOrder` 11 / `SalDeliveryDetail` 7 / `SalReturnDetail` 6 / `PurReceiptDetail` 7 / `PurReturnDetail` 6 / `SalOrderDetail` 2 / `PurOrderDetail` 1 / `InvCheckDetail` 1; 工业品工单没露出来只因食品合规字段对它们没意义。(3) **修复**: 字段加 `@JsonProperty("pXxx")` 钉死 key, 不依赖 getter 推导; 零 schema 变更。(4) **另一处**: `loadPlans()` 默认选第一个未完工工单时直接给 `activeWo` 赋值绕过 `onPickPlan` → `woDetail` 一直 null, 右栏详情卡 7 字段 + 生产时间 + 当班人员 + BOM 领料全 `—`, 还拼出「领料单 PD…0002-01 已生成」假陈述; 拉详情收敛成 `watch(activeWo)`, 删掉 `w.no.replace('WO-','LL-')+'-01'` 兜底。(5) **磁盘**: home `/volume1` 是 2.3G 系统卷, 放 jar 会撑爆 (gunzip 静默产出截断 jar); 备份迁 `/volume3/erp-backup`, 删 9 月 9 日旧部署残留 122M (无容器挂载, .env 已留档)。**验证**: 拉线上 jar 解 `PrdOrder.class`, 用线上 jackson 2.15.4 跑 `writeValueAsString`, 修复前 JSON 只有小写 p 系列, 修复后同时输出 11 个大写 P key。**)
**前版本 v1.1.115**: R21 雪花 id 精度丢失根治 + 幽灵仓库库存修复 (sql/126 + App 端 7 行 `Number(id)` → 直传字符串)。
**前版本 v1.1.114**: App H5 销售出库单「新建」功能 + R18 APP 授权补灌 + home 主站全量同步。
**前版本 v1.1.112**: R20 翻页 bug 全仓根治 26 处 (25 文件) + App H5 成品入库扫码关联工单 + v1.1.110 详情页开工修复。
**前版本 v1.1.109**: PC finance Arap 应收应付页面翻页修复 (Arap.vue 首现 R20, 本次扩到全仓 26 处)。
**前版本 v1.1.108**: App APK 隐私/卫生第三批: (1) 关 uni-stat 遥测 — `src/manifest.json` 加 `h5.uniStatistics.enable=false`, H5/APK 产物不再向 DCloud 公网 `tongji.dcloud.io`/`tongji-collector.dcloud.net.cn` 上报设备/页面数据; (2) `index.html` SW 注册加 `!window.Capacitor` 守卫, 消掉 APK WebView 里常驻的 `Failed to register /static/sw.js` 报错 (PWA 离线缓存只给浏览器 H5); (3) `api/index.js` 抽 `logDebug()` 走 `import.meta.env.DEV`, 生产 H5/APK 静默 DEBUG 级日志, 保留网络层/非JSON 的 warn (R13 诊断价值). 纯 App 端, 不动后端/PC/SQL.

## 速查

- **完整变更日志**: [docs/CHANGELOG.md](docs/CHANGELOG.md) (v1.0.10 ~ v1.1.122, 倒序)
- **部署速查**: [docs/17_群晖NAS部署指南.md](docs/17_群晖NAS部署指南.md) (home) + [docs/19_飞牛OS后端部署指南.md](docs/19_飞牛OS后端部署指南.md) (飞牛 canary)
- **必读 memory**: `~/.claude/projects/-Users-tongban/memory/MEMORY.md` — 50+ 条踩坑 + 设计原则 R1~R24

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

## 设计原则 (R9~R24 — 完整版见 memory)

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
- **R22 transient 实体字段名带大写 `P` 前缀 → JSON key 漂移, 必须显式 `@JsonProperty`**: 实体为打印/详情 JOIN 注入的 transient 字段若取 `pXxx` 形式 (如 `pThickness`), 其 getter `getPThickness()` 在 Jackson 序列化时**不会**输出 `pThickness`。Jackson legacy 命名规则: 去 `get` 后剩 `PThickness`, 因首段 `P` 后紧跟大写 `T`, 被判定为「P + Thickness」驼峰切分 → 首段整体小写 → 输出 **`pthickness`**; 而无前缀的 `getThickness()` 正常输出 `thickness`。同一份 JSON 里两套 key 并存, 前端按 `pThickness` 读**恒为 `undefined`**, 且**不报错**。v1.1.116 全仓 8 实体 29 字段中招: 生产工单「成品规格属性」7 字段 + 食品合规 `pFoodExecStd`/`pIsFoodGrade` 等 (工业品工单只是没露出来)。**规范**: 凡 `p` 前缀 transient 字段, 字段上必加 `@JsonProperty("pXxx")` 钉死 key, 别靠 getter 推导; 打印模板 (ftl/`<#if p.productName>`) 用无前缀 key, 与 `@JsonProperty` 不冲突。**验证口诀**: 怀疑 key 漂移就拉线上 jar 解目标 `.class`, 用线上同款 jackson 跑一次 `writeValueAsString` 看真实 key 名。巡检 `grep -rn "getP[A-Z]" backend/src/main/java --include="*.java"`
- **R23 App H5 拦截器已拆封, 页面禁止再读 `.data`**: `app/src/api/index.js` **两处**拦截器 (uni.request 路径 + fetch/H5 路径) 都是 `if (d.code === 200) resolve(d.data)` —— **`r` 就是业务数据本身, 没有 `{code,msg,data}` 外壳**。页面写 `const pd = (r && r.data) || {}` 会让 `r.data` 恒 `undefined` → 恒 `{}` → 下游字段**静默恒空且不报错** (v1.1.118 成品入库单位恒「该商品未配置单位」+ 工艺参数恒空 2 处中招)。**规范**: 统一双形态防御 `(r && (r.data || r)) || {}` / `(r && (r.data || r)) || []`; 同文件其它处 (`r && r.records || r && r.data && r.data.records`) 是正确范式。**PC 端无此病** (`pc-web` 另一套 request 层), 排查别去 PC 找。**排查口诀 (本 bug 绕了 4 轮弯路)**: 遇到「字段恒空/恒兜底文案」, 若 **① 请求 200 ② 后端确实返回该字段 ③ 部署产物与本地最新构建字节级一致 ④ 无 SW 缓存 ⑤ 硬刷新+重选仍复现** —— 这 5 条全中时**立刻去读 `api/index.js` 的 `resolve(...)` 到底给了什么**, 别再查缓存/部署/鉴权/数据。**「请求成功」≠「响应被正确读取」**。巡检 `grep -rn "&& r\.data) ||\|&& r\.data ||" app/src --include="*.vue"`
- **R24 幂等 SQL 脚本必须连跑 N 次验证, 不只跑 1 次**: 「幂等守门」(NOT EXISTS / information_schema 探测) 只看**第 1 次**是否跳过, 但**第 2 次的状态是第 1 次写下的** —— 一条"只看当前值就决定改不改"的 UPDATE 在第 1 次跑完会把自己的前提条件也改没, 第 2 次跑就把上一次自己建的行降级/删掉。v1.1.119 实例: `sql/128` 把 3 个膜料主单位切到 kg, 第 1 步「把所有非 kg 行 is_main 归零」若**不带 `unit_name <> 'kg'`**, 第 2 次执行会把第 1 轮插入的 kg 行也归零, 而「插 kg 行」的 NOT EXISTS 又因 kg 已存在不补插 → **三个产品一条主单位都不剩** → 前端显示「该商品未配置单位」(恰好是 R23 那个 symptom)。**规范**: (1) 任何改 `is_*` / 状态位 / 软删标记的 UPDATE, 必须把「自己上轮建的行」排除在 UPDATE 之外; (2) 写脚本时就**建个 scratch 库从生产数据灌子集, 连跑 3 次比对**, 别拿生产库赌; (3) **验证 SELECT 的期望值写进注释**(v1.1.118 的 `md5` 校验是同样思路 —— 第 1 次跑不出错不等于对)。巡检 `grep -rn "SET is_main = 0\|SET .* = 0 WHERE" sql/*.sql`
- **R25 Java 字符串拼 SQL 加列必须检查尾部连接符**: `@Select("<script>" + "SELECT a, " + "b " + "FROM t")` 这类拼接 SQL, 加新 SELECT 列时**被替换那行的尾部逗号/空格要一并检查**, 不是只加新片段就完事。v1.1.122b 实例: 给 `PurReceiptMapper.selectPageWithProduct` 加 `xk_exempt` 时保留了 `first_product_model ` 的尾空格又加了逗号 → 拼出 `AS xk_exempt, FROM pur_receipt` **悬空逗号** → `SQLSyntaxErrorException` 列表接口全 500。**规范**: (1) 新加列若是最后一个 → **不带尾逗号**; 中间列 → 替换行原尾逗号移到新列后; (2) **编译不验 SQL 语法, 改完必须真打一次接口**; (3) canary-first 在这救过一次 (飞牛先炸拦住了, home 没跟着炸)。排查口诀: 「500 + SQLSyntaxError + 报 `near 'FROM/LEFT JOIN'`」→ 99% 是 SELECT 列表尾部多了逗号, 别查表结构/索引/权限。巡检 `grep -rn '@Select("<script>"' backend/src/main/java --include="*.java" -l` 找出所有拼接 SQL 再逐个核连接符
- **R26 手写 mapper XML 覆盖 MP 内置方法: 两条后果要分清**: ① **绕过全局 `update-strategy: not_null`** → `check()`/`uncheck()` 传的稀疏对象 (只 id+status) 其余字段被写成 NULL → 撞 NOT NULL 约束 → 500 (`Column 'bill_no' cannot be null`, v1.1.123 实例, `PurOrderMapper.xml` + `SalOrderMapper.xml` 2 处手写 `updateById` 全字段无条件 SET)。修法: 改 `<set>` + 每字段 `<if test="et.xxx != null">`。② **不绕过 `@TableField(fill=)` 自动填充** —— MP 的 `MybatisParameterHandler` 按 `SqlCommandType.INSERT` 触发 `insertFill`, **与语句是否手写无关** (v1.1.124 实测: 飞牛走手写 XML insert 建 BOM, `create_by`/`create_time` 全部正常写入)。**别把这两件事混为一谈** —— 「实体加了 fill 但审计列 NULL」要先看**数据创建时间是否早于加注解时间** (home `prd_requisition` 分界线恰好在 v1.1.52 补注解那天, 不是在引入 XML 那天)。**另一类真问题 = 手写 `<insert>` 漏列**: `PrdBomMapper.xml` 漏 8 列 (`product_id`/`base_qty`/`output_qty` 等), `product_id` 已被 sql/19 放宽 nullable → **不报错静默丢数据**, `base_qty` 有 DB DEFAULT → **被默认值掩盖看不出来**。**规范**: (1) 手写 MP 内置方法前, 逐字段核对实体字段数 vs XML 列数; (2) 全字段 SET 必带 `<if>`; (3) 巡检 `grep -rn 'id="insert"\|id="updateById"\|id="deleteById"\|id="selectById"' backend/src/main/resources/mapper/` 找出全部手写覆盖逐个核。排查口诀: 「审核/反审核 500 + Column 'xxx' cannot be null」→ 先看该表 mapper 有没有手写 `updateById`

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

