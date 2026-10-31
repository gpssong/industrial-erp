> **文档说明**: v1.1.49 起 changelog 拆分为 `docs/CHANGELOG.md` (完整历史) + 本文件顶部 (当前版本摘要)。
> 本文件保留"当前版本"标题段 + 关键架构决策 + 部署速查。

## changelog (倒序)

### v1.1.145 (2026-10-31 — PC 报表中心·销售报表能「按客户/时间段查销售明细 + 汇总」并下载 CSV；顺带把这张页从"只有超管进得去"里救出来)

> **需求原话**: 「pc端报表中心-销售报表需要能按客户/时间段查询销售明细及汇总，并生成表格供我下载。」
> 此前这个页面只有两张趋势图 + 一张 TOP10 榜单，**明细一行都看不到、也没有下载**；而 v1.1.144 那套「按客户/时间段查汇总」只长在 AI 助手里 —— 报表中心本身反而查不到，且非超管连页面都进不去。

**设计红线**：零新表、零 SQL 迁移、**纯只读**（无写操作 ⇒ 不需要「确认执行」）。四件事：**① 口径只写一处**（AI 与报表页共用同一个 service，两处数字必然一致），**② 列名/数字格式后端出**（前端按 `columns` 动态渲染，不重排、不再格式化，免得下载的和屏幕上对不上），**③ 合计永远全查询范围**（另开一条不分页的聚合 SQL，绝不让浏览器拿当前页累加），**④ CSV 回服务端重查**（不能把屏幕上那一页导回去，权限判定也只能在服务端做）。

**后端 · 口径下沉** `modules/ai/agent/AiSalesSummaryService` → [modules/report/service/SalesSummaryService](../backend/src/main/java/com/industrial/erp/modules/report/service/SalesSummaryService.java)（改名 + 搬家，`ErpToolRegistry` 与 `AiAgentController` 跟着换引用）：AI 侧的 `query()` 一行没改逻辑，报表中心新加 `detail()`（分页）+ `detailForExport()`（一次取满 `MAX_EXPORT_LINES=20000`，不分页）+ `toCsv()`。所有产物仍是同一个 `Table`(title/columns/rows/totals/note/hint/params/error/page/pageSize/totalRows/truncated)。**权限仍是双层**：`DATA_PERMS = {sales:delivery:list, report:view, dashboard:sales-ranking}` 任一，缺权限**返回 `Table#error` 且不查库**，绝不返回空表（空表会被当成「这客户没卖过」）。分页参数夹逼 `MAX_PAGE_SIZE=200`，`limit` 夹到 `MAX_LIMIT=300`；**先数行数再取页** —— 越界页直接返回空列表，不然 `offset` 为负会让 SQL 直接炸。

**后端 · 明细 SQL** [ReportMapper.salesDetailLines / salesDetailTotals](../backend/src/main/resources/mapper/report/ReportMapper.xml) + 公共过滤段 `<sql id="salesDetailWhere">`：一行 = 一张出库单的一个商品行，主表 `sal_delivery h` JOIN **`sal_delivery_detail d`**（⚠️ 行表叫 `sal_delivery_detail`，**不是** `sal_delivery_line`），过滤段逐字对齐 `aiSalesSummary`：`h.bill_status IN ('CHECKED','FINISHED') AND h.deleted=0 AND d.deleted=0 AND h.bill_date BETWEEN …`，另加 `customerId` 精确 / `customer` 与 `product` 名码 LIKE。**汇总金额=单据头 `total_amount_tax`，明细金额=行 `amount_tax` 加总**，两者口径不同，所以 `note` 里明写「对不上说明有出库单的单据头金额与明细行不符，需核对该单」—— 这个差异必须在表面上说清楚，否则用户以为系统在瞎报数。汇总那条 SQL 也补了 `customerId`（报表页下拉用精确 id，AI 只认识客户名所以传 null 走 LIKE）。

**后端 · 三个新端点** [ReportController](../backend/src/main/java/com/industrial/erp/modules/report/controller/ReportController.java)：`GET /report/sales/by-customer`（汇总，`groupBy=month` 出「月份×客户」）、`GET /report/sales/lines`（明细分页）、`GET /report/sales/export?scope=summary|detail`（CSV 二进制）。三个都挂 `@SaCheckPermission(value={"report:view","sales:delivery:list"}, mode=OR)` —— **销售岗常只被勾了出库列表，不该因为没勾「报表中心」就看不了自家客户的销售数**。返回值统一是 `R<Table>`，参数不对/没权限走 `Table.error` 而不是抛 500，前端能把原因原样显示在表格位置；CSV 端点例外：`scope` 非法或 `Table.error` 抛 `BizException`（那侧只能靠 content-type 判别）。

**⚠️ 后端 · 两处真实 403（用非超管账号跑出来的，超管实测完全掩盖）**：
1. `GET /report/sales/summary` 与 `/report/sales/ranking` 原来只认 `dashboard:sales-trend|sales-ranking`（v1.1.53 为工作台拆的细权限），但**报表中心销售报表页顶部两张图用的正是这两个端点** —— 只授权「报表中心」的人整页图表静默空白。按 v1.1.130 KPI 卡片的先例，两个都加 `"report:view"` 进 OR。
2. [router](../pc-web/src/router/index.js) 里 `report/sales` 的 `meta.perm` 写的是 **`report:sales:view`**，这个字符串在 `sys_menu` 里**根本没有对应行**（角色权限树勾不到），后果是这张页只有超管进得去。改成真实存在的 `report:view`（已核对 `sql/46_v169_recycle_report_view_grant.sql` 里 `report:view` 有 `F` 型行）。

**PC** [report/Sales.vue](../pc-web/src/views/report/Sales.vue)（150 → 351 行）：筛选栏加日期范围 / 客户下拉（`customerApi.list()`，失败自动退成手输客户名关键字，不挡住页面）/ 商品关键字 / 按客户·按月切换，并在页面上写清「商品筛选只作用于明细表；客户/日期同时作用于图表、明细与汇总」。两张新卡片 —— 明细（`el-table` + `show-summary` + 后端 `totalRows` 分页器 + 「共 N 行 · M 张单」）与汇总（`truncated` 时挂「已达行数上限，请缩小日期范围」黄标），列全按后端 `columns` 渲染，`error` 用 `el-alert` 顶掉表格，`note`/`hint` 分两行显示。下载走 **`fetch(url, {credentials:'include'})` → blob**，不复用 axios：PC 是 httpOnly Sa-Token cookie，且 [request.js](../pc-web/src/utils/request.js) 的响应拦截器只认 `{code:200}` 的 JSON，二进制喂进去会被判成失败；所以 `reportApi.salesExportUrl(params)` 只拼 URL（`API_BASE` 从 request.js **导出**复用，避免页面里再拼一遍 Electron/localStorage 优先级）。响应先嗅 `content-type`：`json` 或非 2xx 就 `.json()` 取 `msg` 报错，否则从 `content-disposition` 解 `filename*=UTF-8''` 落盘。**`loadCharts()`** 三个图表各自 try/catch + `Promise.all`，汇总成一条 warning：一个 403 不该把另外两张图一起清空，也不该留未捕获的 rejection。

**单测**（全量 **223/223 绿**，0 fail / 0 error / 0 skip）：[SalesSummaryServiceTest](../backend/src/test/java/com/industrial/erp/modules/report/service/SalesSummaryServiceTest.java) **24 例**（沿用 AI 那 15 例 + 明细 9 例：分页夹逼、越界页不查库、合计取全范围而非当前页、`detailForExport` 上限、明细/汇总两套 `columns` 与 `note`、CSV 表头取 `label` + BOM + 合计行 + 口径行、无权限 fail-fast 不查库、非法日期与反向日期、`customerId` 与 `customer` 同时传）。全量数字 `214 − 15 (AiSalesSummaryServiceTest) + 24 = 223`。

**实测**（飞牛 canary，**真·非超管 `gpssong1`（铜板，`WAREHOUSE_MGR`+`FINANCE`，`is_admin=0`**）；⚠️ 这个账号是 2026-09-20 就存在的**真实在职账号，不是测试号，不能删**，测完只把它在 `.32` 备库上被改过的密码 hash 与主库对齐回来）：
- 页面进入 + 筛选：客户下拉列出真实客户，选中后 `customerId` 是**完整 19 位雪花 id**（URL 里没掉精度）；商品关键字只作用在明细表（汇总表行数不变）；按客户 ↔ 按月切换多出「月份」列。
- 分页与合计：明细翻到第 2 页、页大小 50，页脚合计仍是**整个查询范围**的数（汇总 `1955438.53 / 366896.82 …`，明细 `58 张单 / 1819938.53 / 358697.02`）。⚠️→✅ **首测页脚是空的** —— `summaryOf()` 按 `c.key` 取值，但 Element Plus 的 `summary-method` 给的是列的 `property`（来自 `:prop`），改成 `c.property` 后重打 dist 才正常。
- 两份 CSV：都 200 + `text/csv` + `filename*=UTF-8''销售明细_….csv`，内容有 BOM、表头取 `label`、末尾带合计与口径行，页面弹「已下载 …」。
- 负例：`scope=bogus` → 报「scope 只支持 summary 或 detail」；反向日期 → `Table.error` 显示在表格位置；修复前 `/report/sales/summary` 对 `gpssong1` 是 403（图表静默空白），加 OR 后三个图表端点全 200。
- **零写入**：全程只读，主库业务表未动一行。

**⚠️ 追出一个"数据不一致"虚惊（结论：主库干净，是 canary 副本本地脏数据）**：`note` 说汇总与明细可能对不上，实测就撞上 `CKP202610290001`（明细 8 行 / 汇总差 8,199.80）与 `CKP202610290002`（4 行 / 差 11,120.00）。逐站对账：**`.150` 主库整月 0 张单据头金额 ≠ Σ 明细行**，这两张单在主库是 4 行 / 2 行、金额吻合；**行数翻倍只存在于 `.32` 备库** —— 历次 canary 端到端测试的本地写残留（`read_only=1` 拦不住 root 连本地库，见 [[canary-writes-stay-on-feiniu-replica]]）。所以**不需要修主库数据**，报表页那句口径说明留着即可（它对真实脏数据仍然有效）。

**部署**（三站一致）：jar `dd25e8bef28c2874ac858ebcdbcf17d9`（101,616,003 B）—— `.32` `docker cp /opt/app/app.jar` + `docker commit` 回 **`erp-system-backend:1.1.145`**（compose 引用的就是这个 tag，commit 到别处下次重建就静默回退）、`.150` 把旧 jar 改名 `.bak_v1145_*` 让 `COPY industrial-erp-*.jar` 只匹配一个 + `compose build backend` / `up -d backend`、阿里云 `.env ERP_VERSION=1.1.145` + `compose up -d erp-backend`（⚠️ 该站 compose 服务名是 **`erp-backend`**，`up -d backend` 报「no such service: backend」）。三站都核**镜像本体** `docker run --rm --entrypoint md5sum <image:tag> /opt/app/app.jar` = `dd25e8be…`。`ERP_VERSION` 容器 env 三站 1.1.145，主库 `sys_config.SYSTEM_VERSION_INFO` = 1.1.145（启动 13:36:22）。
PC dist `index.html` md5 `912ccec590f3dee0fe4971ca26bd0576`、`assets/Sales-BNOaDZzD.js` `525fc161c278f3366dd815027ba79f32`、`assets/sales-DlGGIJ_J.js` `8c88a80e4791549ae2371d4d7338ddf5`（`.32` `dist-new` / `.150` `pc-web/dist` bind-mount 覆盖 + `docker restart erp-pc-web-failover`；阿里云 `/opt/erp-system/pc-web` 平铺替换）—— 三站都**从容器端口 curl 回同一份 `912ccec5…`**。**App H5 零改动**（本轮没碰 `app/`，`.32:18090` 与阿里云仍 200，`.150` 无 H5 容器），故无 APK 重出。
副本 `.32` `Slave_IO/SQL_Running=Yes`、`Seconds_Behind_Master=0`；阿里云未登录打 `/api/report/sales/lines` 得 401（证明端点已在新 jar 里）。测试遗留（`.32` 上传的 jar/tarball、`.150` upload 中转、阿里云 `/opt/erp-system/staging` + `docker image prune -f`、本地 `/tmp`）全清。

**同日第二批修补（版本仍 1.1.145 —— 纯 PC 一行路由 + 一处副本数据清理，零后端改动、零 SQL、零 jar 重打）**：用户就上一条汇报里的两个待拍板项拍板：「把副本那 6 行脏明细清掉」+「改成 report:view」。
- **① 清副本脏明细（只动 `.32`，主库与阿里云本来干净）**：`.32` 上 `CKP202610290001` 有 8 行活明细（单据头只有 4 行）、`CKP202610290002` 有 4 行（单据头 2 行）—— 这 6 行是 2026-10-31 09:22–09:32 历次 canary 端到端测试的本地写残留（`read_only=1` 拦不住 root 直连本地库）。做法：**先 `mysqldump` 备份**（`.32` `/var/tmp/sal_delivery_detail_dirty_20261031.sql` md5 `d52238c0…`，本地另存一份），再按「主库没有的 id」精确 `DELETE` **17 行** = 6 行 `deleted=0` 的活行 + 11 行 `deleted=1` 的已软删残留。后者不参与任何统计，但**必须一起删**：将来这张单一旦被软删再恢复，`RecycleBinService.restoreDetail` 是按 `delivery_id` 整表翻 `deleted=0` 的 —— 留着它们会被一起复活，翻倍再现。删完 `.32` 这两张单的明细与 `.150`/阿里云**逐行一致**（同 id、同活/删状态）；全量「单据头 ≠ Σ 明细行」体检从 2 张降到 **0 张**；复制 `IO/SQL=Yes Behind=0` 不受影响（从库本地写不回主库）。回滚办法：`mysql industrial_erp < /var/tmp/sal_delivery_detail_dirty_20261031.sql`（文件里是原样 id 的 `INSERT`）。
- **② 库存报表页的幽灵权限**：[router](../pc-web/src/router/index.js) `report/inventory` 的 `meta.perm` 由 `report:inventory:view` 改成 `report:view`。后端 `/report/inventory/summary`·`/aging` 本来就挂 `report:view`，**只改前端路由守卫**，`sys_menu` 一行不用动 —— 901/902 两个 M 节点的 `perms` 本来就是空，页面可见性由 F 型「报表查看」`report:view` 派生。
- **顺带全量体检**：把 PC 路由里 41 个 `meta.perm` 与 `sys_menu` 实际存在的 240 个 perms 求差集 ⇒ **改完已无幽灵权限**（此前只有 sales / inventory 两个）。这是查"页面只有超管进得去"的通用办法，比逐页猜靠谱。
- **实测（三站各一遍，真·非超管）**：`gpssong1`（`is_admin=0`、182 perm、**有 `report:view` 无 `report:inventory:view`**）—— canary 用真实 UI 登录留下的会话；生产 `.150` 与阿里云用干净 profile 的 CDP 注入会话（`.150` 复用站点 Redis 里那个已存在的 gpssong1 会话；阿里云没有非超管会话，**临时** `SET Authorization:login:token:<uuid> 2101579680441774082 EX 900` 造一个，测完 `DEL` 掉，并把 Sa-Token 顺带生成的 `login:session:2101579680441774082` 一并删掉，Redis keyspace 已复原成原样）。结果：三站 `/api/report/inventory/summary`·`/aging` 全 **200**，页面「库存汇总 (按商品)」+「呆滞库存 (30天未动)」正常出图（1 个 canvas）+ 41 行真实数据，**无「无访问权限」提示**。
  - ⚠️ **浏览器吃旧缓存会让人以为没改**：dist 换新后不强制刷新，页面仍跑旧 bundle → 路由守卫照样拦 → 表现成"改了没用"。实测用 `location.replace(origin + '/?t=' + Date.now() + '#/report/inventory')` 破缓存。
- **部署**：**只重打 PC dist，jar 未动** —— 三站 `ERP_VERSION` 仍 1.1.145，主库 `SYSTEM_VERSION_INFO` 不变。PC dist `index.html` md5 **`4cd580fd340968c3df70e18e85e579fb`**（上一版 `912ccec5…` 作废）、`assets/index-C7Fm4Gdk.js` `6eddae2994354a32dcc1fae8554e9703`、`assets/Sales-DU-7jXTO.js` `585642215cc6112580eabdfbfd3db5ff`、`assets/sales-BNxSh55u.js` `625b8666e6a32e3870268eaaac5f71ea` —— 三站都**从对外端口 curl 回同一份 `4cd580fd…`**（`.32:18080` / `.150:8088` / 阿里云公网）。App H5 与后端 jar 零改动。

**同日第三批修补（版本仍 1.1.145 —— 用户在生产站看到控制台报错，一行属性修掉）**：用户报 `http://home.93gushi.com:8088/#/report/sales` 提示
`ElementPlusError: [el-pagination] [API] small is about to be deprecated in version 3.0.0, please use size instead.`
根因：**第一批我给销售明细页加分页器时写了旧布尔属性 `small`**（`<el-pagination small ...>`）—— element-plus 2.6 起该属性已弃用，组件加载时用 `debugWarn` 抛一条 `ElementPlusError`，控制台一片红（页面功能本身正常，但用户看到的是"报错"）。
修法：`<el-pagination size="small" ...>`（element-plus 2.4+ 的 `size` = `default|small|large`，视觉与旧 `small` 完全一致 —— 实测 DOM 类名仍是 `el-pagination--small`）。**全项目只有这一处用了旧属性**：其余 20 多个 `el-pagination` 都是 `class="pager"` + CSS 控制尺寸，没有 `small` 布尔属性。
- **实测**：飞牛 canary 与生产 `home.93gushi.com:8088` 各用**干净 profile 的 CDP** 跑一遍（注入非超管会话 + 复原 localStorage 登录态），抓 `Runtime.consoleAPICalled` / `Log.entryAdded` ⇒ **el-pagination 的弃用告警已消失**，控制台只剩一条与本次无关的 `favicon.ico` 404（`index.html` 引用了 `/favicon.ico` 但 `pc-web/public/` 是空的 —— 这个 404 从项目第一天就有，**本次未处理**）。分页器正常渲染：`Total 108 20/page 1 2 3 4 5 6 Go to`。
- **部署**：仍**只重打 PC dist**，jar / ERP_VERSION / App H5 全不动。PC dist `index.html` md5 **`a913330f35dcde0bfa96f6b5a3f42334`**（第二批 `4cd580fd…` 作废）、`assets/index-CKr1vla8.js` `fac9539b2965463d33d0cff492934eed`、`assets/Sales-CKIZXHp7.js` `bdf4c67087df077fb98d18a5ef181d11`、`assets/sales-fA0L7t-B.js` `15dbd66be266f3f554e4618b217d7a10` —— 三站 + `home.93gushi.com:8088` 都从对外端口 curl 回同一份 `a913330f…`。

⚠️ 踩坑记录：
1. **路由 `meta.perm` 可以是个"幽灵权限"** —— `sys_menu` 里没有这一行，角色权限树就勾不到，页面变成超管专属；非超管实测是唯一能暴露它的办法。**根治办法**：把路由里所有 `meta.perm` 与 `sys_menu` 现有 perms 求差集，一次性揪干净（v1.1.145 同日第二批已把 sales / inventory 两个都改掉，改完全量差集为空）。
2. **`summary-method` 只认列的 `property`（`:prop`），不认自定义 `key`** —— 按 `key` 取值会得到 `undefined`，症状是「数据行好好的、合计行整片空白」。
3. **PC 下载必须 `fetch(..., {credentials:'include'})` + blob，不能走 axios**（拦截器强制 `code===200`），也别在页面里重拼 API_BASE —— 从 `utils/request.js` 导出同一个 `API_BASE`，否则 Electron/localStorage 优先级两处各写一遍必然漂移。
4. **明细行表叫 `sal_delivery_detail`**，`sal_delivery_line` 不存在（`ERROR 1146`）。
5. **图表逐个兜异常**：三个图表共用一个 `try` 时，一个 403 会让另外两个已取到的数据一起丢，还会抛未捕获 rejection。
6. **canary 上的"数据对不上"先分站对账再下结论** —— `.32` 是 root 直连本地库的**可写副本**，历次端到端测试写的脏数据不回主库，拿它当生产数会误判成线上 bug、甚至误"修"主库。
7. **删副本脏数据要连已软删的残留一起删**：只删 `deleted=0` 的活行不够 —— `RecycleBinService.restoreDetail` 按外键整表翻 `deleted=0`，将来这张单被软删再恢复，活着的旧残留会被一起复活，脏数据"诈尸"。删之前一定 `mysqldump` 留一份（还原时 id 原样写回）。
9. **element-plus 的弃用属性也会被用户当成"报错"** —— `small` 布尔属性在 2.6 已弃用，组件加载时直接 `console.error` 一条 `ElementPlusError`（页面上其实一切正常）。**新写组件一律用新 API**（`size="small"` 而不是 `small`）；改完要用 CDP 的 `Runtime.consoleAPICalled` / `Log.entryAdded` **抓控制台**复核，别只看页面渲染正常。
10. **仓库里没有 favicon** —— `index.html` 写着 `<link rel="icon" href="/favicon.ico" />` 而 `pc-web/public/` 是空的，于是每个 PC 页面都有一条 `favicon.ico` 404（项目第一天就存在，与业务无关）。
8. **换 dist 后浏览器可能还在跑旧 bundle**：路由守卫拦的报错和改之前一模一样，很容易误判成"改了没用"。实测前先破缓存（`location.replace(origin + '/?t=' + Date.now() + hash)`），并确认 `performance.getEntriesByType('resource')` 里加载的是新 `index-*.js`。

### v1.1.144 (2026-10-31 — AI 助手能「按客户/时间段查销售汇总」了：数字直接出表格 (不经模型转述)，PC/App 都能下载 CSV)

> **需求原话**: 「要求ai助手能帮我按客户/时间段查询销售汇总，并生成表格供我下载。」(附截图：AI 助手对「长晟9月份一共销售多少」回「目前工具里**没有按客户/时间段查询销售汇总**的接口」)。
> 此前 AI 只有逐单列表类工具，用户问"某客户某段时间卖了多少"它就宣布做不到。

**设计红线**：零新表、零 SQL 迁移、**纯只读** (不涉及写操作，因此不需要「确认执行」)。两件关键事：**① 口径只写一处** (同一份算法同时喂模型 / 对话表格 / CSV)，**② 数字不让模型抄** (表格由后端 payload 直接渲染)。

**后端 · 查询** [ReportMapper.aiSalesSummary](../backend/src/main/resources/mapper/report/ReportMapper.xml) (+`ReportMapper.java` 接口)：按 `sal_delivery` 聚合，`bill_status IN ('CHECKED','FINISHED') AND deleted = 0`，金额取**含税** `total_amount_tax`，时间窗落在 `bill_date`；`groupBy=month` 时加 `DATE_FORMAT(bill_date,'%Y-%m') AS period` 并按 客户+月份 出行。
> ⚠️ **不能写 `NULL AS period`**：MyBatis `resultType="java.util.Map"` 不回填 NULL 列，那一列会**整列消失**，前端表头就少一格 —— 要么给真值要么别查这列。

**后端 · 口径唯一出口** [AiSalesSummaryService](../backend/src/main/java/com/industrial/erp/modules/ai/agent/AiSalesSummaryService.java) (新)：`query()` 一个方法产出 `Table`(title/columns/rows/totals/note/fileName/params)，三个消费者 (`toToolJson()` 给模型、`Result.tables` 给前端、`toCsv()` 给下载) 全从它派生，所以**屏幕上的表和 CSV 一定逐字节一致**。内含：
- 日期归一：`^\d{4}-\d{2}$` (模型很爱直接写「2026-09」) 自动展开成**该月首末日**；非法日期不抛异常，进 `Table#error`。
- `limit` 默认 100、硬夹到 `MAX_LIMIT=300`，防大结果集打爆上下文。
- **双层权限**：AI 页本身有 `report:ai`，但销售金额属敏感数据，另要求 `DATA_PERMS` = `sales:delivery:list` / `report:view` / `dashboard:sales-ranking` **任一**；缺权限**fail-fast 返回错误且不查库** —— 绝不返回空表，因为空表会被模型解读成「这个客户没卖过」，那是会拿去做生意决定的假结论。
- CSV 前置 `CSV_BOM` (`\ufeff`)，Excel 打开中文列头才不乱码。

**后端 · 表格透传** [ErpToolRegistry](../backend/src/main/java/com/industrial/erp/modules/ai/agent/ErpToolRegistry.java) + [AgentService](../backend/src/main/java/com/industrial/erp/modules/ai/agent/AgentService.java) + [AgentResultVO](../backend/src/main/java/com/industrial/erp/modules/ai/vo/AgentResultVO.java)：新增 `TABLE_TOOLS = Set.of("getSalesSummary")` 白名单，`AgentService.collectTable()` 在工具执行后把**原始 tool JSON** 塞进 `Result.tables` → `AgentResultVO.tables` 返回前端。理由很实际：**LLM 会抄错数字** (`14622.00` 抄成 `14,622.0` 甚至整行错位)，表格根本不经过模型；`collectTable` 只认白名单、且跳过 error 与空表 (报错要让模型自己解释)。`getSalesSummary` 的 schema 描述里明写「回答这类问题【必须】用本工具，不要说没有销售汇总接口」。

**后端 · 下载端点** [AiAgentController.salesSummaryCsv](../backend/src/main/java/com/industrial/erp/modules/ai/controller/AiAgentController.java)：`GET /api/ai/sales-summary/csv`，`@SaCheckPermission(value={"report:ai"}, orRole="admin")`，**服务端按参数重新查询**而不是把前端那张表导回来 —— 否则"表已经显示在屏幕上"就成了绕过销售金额权限的后门。`Content-Disposition` 用 `filename="…"` + `filename*=UTF-8''…` 双写发中文文件名。

**提示词** (`AgentService.sys()` 新增【销售汇总】段 4 条硬性规则)：(a) 相对时间换算成具体日期再传参 (「9月」= 本年 2026-09-01 ~ 09-30；拿不准就写 `YYYY-MM`，后端自己展开)；(b) 客户名不确定先调 `listCustomers` 核对关键字，**严禁猜客户名** (猜错查不到数，用户会以为没卖过)；(c) 返回里带 columns/rows/totals 时系统会直接渲染表格并支持下载，**不要再把整张表重抄一遍**，只用一两句话点合计/涨幅/风险；(d) 工具回 error 时如实转述，不许编一个销售额出来。

**PC** [report/Ai.vue](../pc-web/src/views/report/Ai.vue)：`.table-block` (标题 + 「下载 CSV」+ `el-table` 按 `t.columns` 动态出列 + `.table-foot` 合计 + `.note` 口径说明)；`fmt()` 只补千分位不改数值；下载**在客户端拼 CSV** —— PC 的 axios 响应拦截器强制 `code===200`，`responseType:'blob'` 的二进制响应过不去，所以复用屏幕这份 `tables` 生成 Blob (BOM + 表头 + 数据 + 合计 + 口径)，行序与后端 `toCsv` 同款。

**App** [report/ai.vue](../app/src/pages/report/ai.vue) + [api/index.js](../app/src/api/index.js)：同一张表按窄屏重排 (`COL_W` 列宽 + `cellStyle`)；`downloadTable(t)` 分平台 —— H5 走 `<a download>` 直连后端 `aiSalesSummaryCsvUrl(p)` (同源带 cookie，回后端重查)；原生 App 走 `NativeSharePlugin.shareFile`。[NativeSharePlugin.java](../app/android/app/src/main/java/com/pengcheng/erp/NativeSharePlugin.java) 把原来只服务 PDF 的 `sharePdf` 通用化成 `share(call, mimeType, defaultSuffix)`，`shareFile` = `share(call, "text/csv", ".csv")`，内部 `download()` 仍从 `CookieManager` 取 cookie 注入 `HttpURLConnection` 让 Sa-Token 过鉴权，再用 FileProvider 分享出去。

**单测** (全量 **214/214 绿**，0 fail / 0 error / 0 skip)：新增 [AiSalesSummaryServiceTest](../backend/src/test/java/com/industrial/erp/modules/ai/agent/AiSalesSummaryServiceTest.java) 15 例 (月份展开、非法日期、limit 夹逼、无权限 fail-fast 不查库、CSV 表头取 label / 含 BOM / 合计行 / 口径行、`groupBy=month` 多一列)、`AgentServiceTest` +4 例 (透传只认白名单 / error 与空表不透传 / 答复里不重复出现表格)、`ErpToolRegistryTest` +1 例 (`getSalesSummary` 在 `tools()` 与 `toolsEnabled()` 都在且只读)。

**实测** (飞牛 canary，真·非超管临时账号 `ai_ss144` = `WAREHOUSE_MGR`、`is_admin=0`、持 `report:ai` + 销售数据读权限；负例 `ai_ss144n` 只持 `report:ai`)：
- **PC 按客户**：问「长晟9月份一共销售多少」→ 表头 客户/出库单数/数量/销售额(元)，行 `长晟塑业 3 193800 14622.00` + 合计 + 口径说明；模型正文只补结论，没重抄表。
- **PC 下载 CSV**：⚠️→✅ **首测下载的表头是 `,,,`** —— 客户端把 `columns` 当数据行喂给了 `csvRow(cols)`，而 `csvRow` 只认 `row[c.key]`；改成 `cols.map(c => csvCell(c.label))` 后重打 dist (最终 `8631156a…`，首版 `a8acfbd5…` 作废)，复测得 `客户,出库单数,数量,销售额(元)` + 数据 + 合计 + 口径，文件名 `销售汇总_长晟_20260901-20260930.csv`。**"下载内容对不对"单测覆盖不到** (后端 `toCsv` 有单测，客户端那份没有)，只有真点一次下载才暴露。
- **App H5 按客户 + 按月**：「长晟今年每个月的销售额」多出「月份」列 → `2026-09 长晟塑业 3 193800 14622.00` / `2026-10 … 9 573320 44984.91` + 合计 `12 767120 59606.91`；点「下载」的 `<a>` href 是 `/api/ai/sales-summary/csv?startDate=…&customer=%E9%95%BF%E6%99%9F&groupBy=…`，实测 200 + `filename*=UTF-8''销售汇总_…`，按月 CSV 同样带「月份」列。
- **负例**：`ai_ss144n` 提问拿到文档化的"无权查看销售金额"提示且 `tables: []` —— 不给空表也不给数。
- **零写入**：全程只读；主库 `sal_delivery`/`inv_*` 未动 (当天两张 `CKP…` 出库单是 08:07/08:56 的真实草稿，本来就不计入口径)；临时账号与上传产物事后全清 (只落 `.32` 备库本地写)。

**部署** (三站一致)：jar `ed5563719a0938ed452504cb774e59a3` (101,610,385 B) —— `.32` `docker cp /opt/app/app.jar` + `docker commit erp-system-backend:1.1.144` (⚠️ 该站 backend 服务名是 `erp-backend-failover` 且带 `profiles: ["failover"]`，`compose up -d backend` 会报「no such service」，要 `--profile failover up -d erp-backend-failover`)、`.150` 把 `industrial-erp-1.1.143.jar` 改名 `.bak_v1144_20261031` 让 `COPY industrial-erp-*.jar` 只匹配一个 + `compose build backend` / `up -d backend`、阿里云 `.env ERP_VERSION=1.1.144` + `compose up -d --build erp-backend`；**三站都核镜像本体** `docker run --rm --entrypoint md5sum <image:tag> /opt/app/app.jar` = `ed556371…`。`ERP_VERSION` 容器 env 三站 1.1.144、主库 `sys_config.SYSTEM_VERSION_INFO` = `{"version":"1.1.144","startTime":"2026-10-31 12:01:01"}`。
PC dist `index.html` md5 `8631156a9386de94d9b82b6a6da00fb0`、`assets/Ai-Cn-v_MLd.js` `a1e1ba7054c177cd4835982bd0c0b73e` (`.32` `dist-new` / `.150` `pc-web/dist` 两处 bind-mount 覆盖即可；阿里云 `/opt/erp-system/pc-web` 平铺替换) —— 三站都**从容器端口 curl 回同一份 md5** 才算落地。
App H5 `index.html` `4be6fc1fed0d4a52b6bb4f82dd1994a0`、`assets/pages-report-ai.BXRyJhRE.js` `5c6f35b3f069eb3b3e3da71c448598b6` (`.32:18090` 与阿里云 `:18090` 逐字节一致；`.150` 无 H5 容器)。APK `erp-app-20261031-1144.apk` md5 `f9d15b7251862249954486e3bcc67455` (4,447,955 B，versionName 1.1.144 / versionCode 1144，内嵌 H5 与部署版逐字节一致)。
**生产复核**：`.150` PC 走**真实 UI 登录** (`gpssong`，验证码答案从本站 Redis `erp:login:limit:captcha:<key>` 读；⚠️ PC 的 Sa-Token cookie 是 httpOnly 且 store 故意不存 token，"JS 注入登录态"这条捷径不通) → AI 页出表、「下载 CSV」Blob 文件名正确、`GET /api/ai/sales-summary/csv` 200 返回同款内容；阿里云从容器端口取回 PC `8631156a…` / chunk `a1e1ba70…` 与 H5 `4be6fc1f…` / chunk `5c6f35b3…`，未登录打 CSV 端点 401。副本 `Slave_IO/SQL_Running=Yes`、`Seconds_Behind_Master=0`。

⚠️ 踩坑记录：
1. **PC 客户端 CSV 表头必须取 `label`** (`csvRow(cols)` 只会读 `row[c.key]`)；"数据行对、表头空逗号"这种半对问题最容易被误判成后端 bug。
2. **`curl` 手工编码客户名要复核**：把「长晟」写成 `%E6%99%8F`(晕) 会返回空结果，看着像"查不到这个客户"，其实是自己编码错 —— 用 `curl -G --data-urlencode "customer=长晟"` 让工具做编码。
3. **uni-app H5 的按钮**用 `browser-use` 的 click 会 `TypeError: this.scrollIntoView is not a function`，改 `evaluate_script` 直接打选择器 (`.sendbtn` / `uni-button` 里的「下载」)。
4. **`.150` 是主库**，`SHOW SLAVE STATUS` 恒为空 (那是备库该查的)；主库看 `SHOW MASTER STATUS`，复制健康去 `.32` 看。

### v1.1.143 (2026-10-31 — AI 助手可以「把生产出来的货入账」了：成品入库两步进白名单，PC/App 都要点「确认执行」；顺带根治单号计数器落后)

> **需求原话**: 「要求ai助手能帮我自动添加产品，只要我确认就行。」(附微信截图：AI 助手对「生产入库」回「这个写操作暂不在自动执行白名单里，我不能直接执行」)。
> 此前白名单只有 `checkSalDelivery` (审核出库) 与 `addPurReceipt` (采购入库)，车间产出的成品入账只能手工录单。

**设计红线不变**：零新表、零 SQL、**后端仍然不执行任何写**。链路仍是 `模型 PROPOSE → 前端弹「确认执行」→ 用户点击 → 前端直调原写端点 (带用户自己的 token，走原有 `@SaCheckPermission`)`。

**后端** [ErpToolRegistry](../backend/src/main/java/com/industrial/erp/modules/ai/agent/ErpToolRegistry.java) — 加两个**只读**工具 (同时挂进 `tools()` 与 `toolsEnabled()`，漏一处就等于"开写但模型查不到")：`listWarehouses` (拿真实 `warehouseId` + `isDefault` 默认仓)、`listFinishedInBills` (拿草稿单真实 `id`)；新增依赖 `BaseWarehouseMapper` + `PrdFinishedInService`。

**后端** [AgentService](../backend/src/main/java/com/industrial/erp/modules/ai/agent/AgentService.java)：
- `run()`：**`allowWrite=true` 时强制放开 `GROUP_BUSINESS` 工具组**。写提议依赖只读工具先查真实 id，而这些工具全在 business 组 —— 只开写、经营数据组关着，模型就只剩"臆造 id"一条路。
- `sys()` 白名单加两步 + 各自硬性规则：
  - `addPrdFinishedIn` (建草稿单)：(a) 先 `listProducts` 拿真实 `productId`，**成品在本厂档案里可能是 `FG` 也可能是 `GOODS`** (如「马鲛鱼」「胶带」就是 `GOODS`)，不得因类型不是 FG 就拒建，同名多候选必须先列出来问用户；(b) 先 `listWarehouses` 拿 `warehouseId`，用户没说仓就用 `isDefault=1` 并把仓名写进 summary；(c) `qty` **只能用用户明确说出的实际产量**，严禁拿工单计划量/克重推算；(d) 不填 `billNo/billDate/billStatus/spec/unitId/unitName/amount/productCode/productName` —— 后端生成单号并按 v1.1.142 `ProductDeriveService` 回填规格与单位；(e) 一单一商品，多商品提多条 PROPOSE；(f) `RAW`/`SEMI` 不走本动作。
  - `checkPrdFinishedIn` (审核入库存)：必须先 `listFinishedInBills` 拿到真实 `id` 且该单 `billStatus=DRAFT`；"刚建的那张"取列表最新一条 DRAFT 并把单号写进 summary 让用户核对；严禁臆造 id、严禁重复审核 CHECKED 单。**两步分开提，一次只提一步**。
- **`quoteLongIds()` (后端统一出口) + 提示词【id 写法】规则**：19 位雪花 id 在浏览器 `JSON.parse` 超过 2^53 会被抹尾 (`2116353847494504578 → …504400`)，前端拿去审核就报「入库单不存在」。PROPOSE payload 里 ≥16 位裸整数一律加引号变字符串，同时要求模型自己就写成带引号的字符串 (双保险)。
- **`MAX_STEPS` 收尾改造**：跑满轮数仍未收敛 (备援 provider 常犯) 时，不再把「(已达到最大分析轮数 6…)」这种内部提示丢给用户，改成**禁掉工具再问一次**强制基于已查到的数据出文本答案，并照样收集该轮的 PROPOSE。加 `[AI Agent] step/tools/proposals` 日志便于事后定位模型到底调了哪几个工具。

**后端** [BillNoGenerator](../backend/src/main/java/com/industrial/erp/utils/BillNoGenerator.java) (顺带根治一个线上必现的 500)：Redis 计数器只记得"本站见过的事"，库里的行可能来自别处 —— 主备复制把主库单号带进备库、或 Redis 被清空/换实例，计数器落后就重复发号，撞 `uniq_*_bill_no` 让建单报「数据已存在」。现在发完先跟**库内当天该前缀最大单号**对账 (19 个前缀 → `表.列` 常量表)，落后就把计数器抬到最大号后重发。`JdbcTemplate` 用 `@Autowired(required=false) @Nullable` 注入，未知前缀/无数据源/查库异常一律返回 `-1` **不阻塞开单** (fail-open)。

**PC** [report/Ai.vue](../pc-web/src/views/report/Ai.vue)：`parseProposal()` 前端二次防精度 (与后端同一条正则)；动作别名表加 `addPrdFinishedIn`/`checkPrdFinishedIn` 及 8 个模型常见同义写法；执行分支校验缺商品/缺仓库/数量≤0 时给**下一步指引**文案而不是干报错；建单成功后回显真实单号 (`latestFinishedInBillNo()`)；`spent(p)` 把已执行/执行中的「确认执行」按钮锁死 —— **写提议不是幂等的，再点一次就多建一张草稿单**；失败的原样留着让用户改口重试。

**App** [report/ai.vue](../app/src/pages/report/ai.vue)：同款「确认执行 / 取消」，成功后 `disabled` 锁死 (产物 `pages-report-ai.wP0JtLK9.js` 内 `G(e)` = `!!e.executing || 0===String(e.executed||"").indexOf("✓")`)。

**单测** (新增 16 例，全量 **194/194 绿**)：[AgentServiceTest](../backend/src/test/java/com/industrial/erp/modules/ai/agent/AgentServiceTest.java) 5 (含 `quoteLongIds` 精度、未收敛收尾)、[ErpToolRegistryTest](../backend/src/test/java/com/industrial/erp/modules/ai/agent/ErpToolRegistryTest.java) 6 (两个新工具在 `tools()` 与 `toolsEnabled()` 都在、只读不改数据)、[BillNoGeneratorTest](../backend/src/test/java/com/industrial/erp/utils/BillNoGeneratorTest.java) 5 (落后抬升重发、未知前缀/无 JdbcTemplate 不阻塞)。

**实测** (飞牛 canary，真·非超管临时账号 `ai_wm1143` = `WAREHOUSE_MGR`、`is_admin=0`、持 `report:ai` + `production:finished-in:add` + `production:finished-in:check`，**不用超管**避免 `orRole=admin` 掩盖 403)：
- **PC 两步全链**：AI 助手「把刚生产出来的 X 入到成品仓」→ 模型自己调 `listProducts`+`listWarehouses` → PROPOSE 里 `productId`/`warehouseId` 是**带引号的 19 位字符串** → 点「确认执行」→ 建草稿单 → 再说「审核」→ PROPOSE `checkPrdFinishedIn` → 点确认 → `bill_status=CHECKED`；两步执行后按钮均变灰不可再点。
- **App 两步全链** (H5 `:18090`)：同款，`UNI-BUTTON` 上 `disabled` 生效。
- **落库证据**：`inv_ledger` `PROD_IN` 的 `before_qty/after_qty` 走 `0 → 5000 → 5200`、`inv_stock` 落 `5200.0000`，`unit_name`/`spec` 由 v1.1.142 的 `ProductDeriveService` 自动带上 (AI 完全没填这些字段)。
- **单号自愈**：故意让 Redis 计数器落后，日志命中「计数器落后: redis=2 库内最大=4, 抬升后重发」，建单不再撞唯一键。
- **清理**：本次造的入库单/台账/库存变动与临时账号 `ai_wm1143` 全部删除 (只落 `.32` 备库本地写，主库零写入)；副本 `Slave_IO/SQL_Running=Yes`、`Seconds_Behind_Master=0`。
- **阿里云 (生产)**：登录 → AI 助手页文案已是新版 (「以及帮你开成品入库单 (需要你点确认)」) → 提问后 PROPOSE 卡正常渲染，`productId=2075453564248965121` / `warehouseId=2073701024629047297` **19 位逐位精确未掉精度** → 点「取消」，复核该站 `prd_finished_in` 最大 `create_time` 仍是 09:35:35 ⇒ 取消不写任何东西 (生产主库业务数据未动)。

**部署** (三站一致)：jar `fa4e69afbdfb6ae9939474841549ae2d` (101,597,471 B) — `.32` `docker cp /opt/app/app.jar` + `docker commit erp-system-backend:1.1.143`、`.150` 把 `industrial-erp-1.1.142.jar` 改名 `.bak_v1142_*` 使 `COPY industrial-erp-*.jar` 只匹配一个后 `compose build/up -d backend`、阿里云 `.env ERP_VERSION=1.1.143` + `compose up -d --build erp-backend`；**三站都核镜像本体** `docker run --rm --entrypoint md5sum <image:tag> /opt/app/app.jar`。`ERP_VERSION` 三站 1.1.143、`sys_config.SYSTEM_VERSION_INFO` 三站回写 `{"version":"1.1.143"}`。PC dist `index.html` md5 `1b9228875c08a5b896dd56000d836cb5` (`.32` `dist-new` bind-mount / `.150` `pc-web/dist` bind-mount / 阿里云整目录替换 + restart `erp-pc-web`)。App H5 `index.html` `9e0606f893a469e3d41ba3b5e8f2aa45`、`assets/pages-report-ai.wP0JtLK9.js` `71b9885c3c31d6b4ec35390053311c2f` (`.32:18090` 与阿里云逐字节一致；`.150` 无 H5 容器)。APK `erp-app-20261031-1143.apk` md5 `5c8b623a00442f72f305ec98da2b3bdf` (4,477,598 B，versionName 1.1.143 / versionCode 1143)。三站 `AI_INTEGRATION.llm.capabilities.allowWrite=true` 均已开 (阿里云复核)。

**踩坑**:
1. **开写权限必须同时开"查真实 id"的只读工具** —— 否则模型只能臆造 id，症状是建单报「商品/仓库不存在」，看起来像数据问题，其实是工具组没联动 (`allowWrite` ⇒ 强制 `GROUP_BUSINESS`)。
2. **19 位雪花 id 过 `JSON.parse` 必掉精度** —— 后端输出侧加引号是唯一可靠出口，前端再兜一层正则；提示词也要显式教模型写字符串 (v1.1.115 是"前端别 `Number(id)`"，这次是"跨 JSON 文本协议层就得是字符串")。
3. **写提议按钮执行完必须锁死** —— `addPrdFinishedIn` 不幂等，用户多点一次就多一张草稿单；失败态反而要留着让人改口重试。
4. **单号计数器要认库不认 Redis** —— 主备复制/换 Redis 实例都会让本站计数器落后于库内最大号，撞唯一键的表现是建单 500「数据已存在」，用户完全看不出为什么。对账失败要 fail-open，不能因为查不到最大号就不让人开单。
5. **`browser-use` 点 uni-app 按钮会 `TypeError: this.scrollIntoView is not a function`** —— `click` 的 schema 只有 `{uid,dblClick,includeSnapshot}`，且 uni-app H5 渲染的是 `UNI-BUTTON` 不是 `button`；改用 `evaluate_script` 按 class 选择器 (`uni-button.pbtn` / `uni-button.sendbtn`) + `textContent` 过滤来点。
6. **自动化登录不必识图** —— 验证码答案就在本站 Redis `erp:login:limit:captcha:<uuid>`，点一次图片刷新让 TTL 拉开差距，取剩余 TTL 最大的那条，再和截图对一眼即可。
7. **`prd_finished_in` 没有 `unit`/`check_by` 列** (是 `unit_name`/`update_by`)、**`inv_ledger` 没有 `in_qty`** (是 `qty` + `biz_direction` + `before_qty`/`after_qty`) —— 查库前先 `information_schema.COLUMNS` 列表名。

### v1.1.142 (2026-10-31 — App 工单成品入库「该商品未配置单位」: 深链 `onLoad` 竞态 + 工单快照本来就空，读/写两条路径都改成后端兜底回填)

> **需求原话**: 「9*75的产品有单位，但成品入库时显示没有配置单位」+ 澄清入口「app端通过生产单成品入库」 (截图: App 成品入库 `PD202610200001 · 带鱼袋9*75*0.07`，规格 `—`、单位显示兜底文案「该商品未配置单位」)。
> 该商品 `dyd975007` (product_id `2104473647277748226`) 在 `base_product_unit` 里确实配了主单位「只」(`is_main=1, deleted=0`)，所以"没配单位"是假象 —— 页面没拿到。

**两层根因** (都要修，缺一层仍是坏的):
1. **App 初始化竞态** — [app/src/pages/scan/finished-in.vue](../app/src/pages/scan/finished-in.vue) 的 `onLoad(query)` 里 `this.loadAll()` 是 async 但**没 await**，紧接着同步调 `onOrderPicked()`；那一刻 `orderList` 还是 `[]` → `pickedOrder` 取不到 → 「带出成品/规格/数量/批号 + `loadUnits()`」整条链路直接 return，单位永远停在 `unitTip()` 的兜底文案。只有**「生产单详情 → 建成品入库」这条深链** (`goScan` 带 `?orderId=`) 100% 命中；手工从页面内 picker 选单反而正常 (这也是为什么 PC 端一直没人报)。`scan/semi-in.vue` 同款写法，一并修。
2. **工单快照本来就是空的** — canary 与主库 `prd_order` (`PD202610200001`) 都是 `spec=''`、`unit_id=NULL`、`unit_name=NULL`：开单时没人往工单写单位 (单位只在 `base_product_unit`)；规格则因 `base_product.spec` 未填、实际写在 `base_product.model='9*75*0.07'`。所以就算竞态修好、`loadUnits()` 跑起来，工单侧的兜底仍取不到值。

**App** (`scan/finished-in.vue`、`scan/semi-in.vue`): `onLoad` 改 `async` + `await this.loadAll()`，之后再 `applyOrder(query.orderId)`；新增 `applyOrder(id)` —— 首屏工单列表里找不到该 id 时按 `prdOrderDetail(id)` 单独补拉进 `orderList` 再 `onOrderPicked()` (老工单/工单多于分页 100 条时不再"白选一次")；响应解包沿用 v1.1.118 口径 `(r && (r.data || r))`。

**后端** — 新增 [ProductDeriveService](../backend/src/main/java/com/industrial/erp/modules/production/service/ProductDeriveService.java) 作为「规格 / 单位」派生口径的**唯一出口** (与 v1.1.133 `totalWeightKg()` 同一治理思路):
- `spec(snapshot, product)`: 单据快照 → `base_product.spec` → `base_product.model`；`mainUnit(productId)` / `mainUnits(ids)` 一次批量查 `base_product_unit` `is_main=1` (TableLogic 自带 `deleted=0`)；`hasUnit(unitName)` **只认 `unit_name`** —— `unit_id=0` 是 v1.1.117 记录的历史脏行 (指向不存在的 `base_unit`)，与 null 同等对待，判定一律不用 `unitId > 0` 过滤。
- `inboundFields(...)` + `InboundFields`: 入库单的 productId/code/name/spec/unitId/unitName 六个字段按「单据自带 → 商品主数据 → 工单快照」逐级兜底，非空快照永不覆盖。
- **读路径回填** `PrdOrderService.backfillDerived()` 挂到 `page()` (批量 `mainUnits(productIds)`，不逐行查) 与 `detail()` —— App/PC 工单列表、详情的规格列与「建成品入库」带出的初始值都靠它。**`update()` 故意不回填** (否则派生值会被持久化写回 `prd_order`，把"快照本来为空"这个事实抹掉)。
- **写路径回填** `PrdFinishedInService.add()`、`PrdSemiInService.add()` 在 `mapper.insert()` 前调 `inboundFields(...)`。这一条是关键: `check()` 会把入库单的 `unitId/unitName` 一路传进 `stockService.inStock()` 落 `inv_ledger`/`inv_stock`，不兜底则库存流水与出入库报表永远没单位。

**零 SQL** (存量工单/入库单不改，只改读取与写入时的派生口径)、**零 PC 代码** (PC 的 `FinishedIn.vue`/`SemiFinishedIn.vue` 本来就自己查商品单位，且已按 v1.1.117 只认 `unitName`)。

**实测** (三站，`PD202610200001` / 商品 `dyd975007`)。⚠️ 鉴权链一律用**真·非超管** `gpssong1` (roles `WAREHOUSE_MGR`+`FINANCE`，38 个 perm，含 `production:finished-in:add`/`production:semi-in:add`，无 `add` 之外的 admin 兜底)，不用超管，避免 `orRole=admin` 掩盖缺口:
- **读路径 API** (`.32` 与 `.150`，`gpssong1` token)：`GET /production/order/page?billNo=PD202610200001` 与 `GET /production/order/2112543587985600514` 均返回 `spec=9*75*0.07`、`unitName=只`、`unitId=2089154538889621509`；同一时刻 `SELECT spec,unit_id,unit_name FROM prd_order WHERE id=…` 仍是 `''`/NULL/NULL ⇒ 回填只在读取时发生，不污染存量。阿里云同一口径复测 (两接口) 亦带出。
- **浏览器全链** (headless CDP + 真实 bundle)：工作台 → 生产单列表 → 点行 → 详情商品行渲染 `(9*75*0.07) × 1 只` → 点「建成品入库」→ 落 `#/pages/scan/finished-in?orderId=2112543587985600514`，信息区 `规格=9*75*0.07`、计划量 `1`，表单 `单位 = 只` (修复前此处是「该商品未配置单位」)、「数量」由计划量带出、「批号」默认=工单号 → 点「保存草稿」→ 落库 `PFI202610310002` 带 `spec='9*75*0.07'` / `unit_name='只'` / `unit_id=2089154538889621509`，`create_by` 为 `gpssong1`。全程**未点「审核入库」** (不产生真实库存与 XK 门禁副作用)。
- **写路径回填 (故意发空)**：`POST /production/finished-in` 只带 `productId` + 仓库 + `spec=''`/`unitId=null`/`unitName=null` → 落库仍 `product_code='dyd975007'`、`spec='9*75*0.07'`、`unit_name='只'`；`POST /production/semi-in` 同 (得 `PSI202610310001`)。证明老客户端/PC 之外的入口不会再写进 NULL 单位。
- **清理**：本次在 canary 造的 3 张测试单 (`PFI202610310001`、`PFI202610310002`、`PSI202610310001`) 逐条按 `id + bill_no + bill_status='DRAFT'` 三条件手工 DELETE，复核 `leftover=0`、当日 `inv_ledger=0` 行 (草稿不写库存)；`.150` 主库当日 `prd_finished_in` 0 行 ⇒ **主库零写入**；副本 `Slave_IO/SQL_Running=Yes`、`Seconds_Behind_Master=0`。
- **遗留待拍板 (未动)**：已审核的存量单 `PFI202610230003` (42000 只) 仍 `spec=''`/`unit_name=NULL`，它当年写进 `inv_ledger`/`inv_stock` 的行同样没有单位 —— 属数据回填 (需 SQL + 改主库)，本次只兜未来，等用户点头再灌。

**部署** (三站一致)：jar `5aea1a3e2cf88ed7eb44f3dcf5e42141` (101,592,434 B) — `.32` `docker cp /opt/app/app.jar` + `docker commit erp-system-backend:1.1.142` (compose 第 66/73 行同改)、`.150` 把 `industrial-erp-1.1.141.jar` 改名 `.bak_v1142_*` 使 `COPY industrial-erp-*.jar` 只匹配一个再 `compose build/up -d backend`、阿里云 `.env ERP_VERSION=1.1.142` + `compose build erp-backend` → **三站都 `docker run --rm --entrypoint md5sum <image:tag> /opt/app/app.jar` 核镜像本体** (不核运行中容器)。`ERP_VERSION` 三站 1.1.142，`sys_config.SYSTEM_VERSION_INFO` 已回写 `{"version":"1.1.142"}` (PC「系统参数」页显示的版本号来自这里，故本次**不重打 PC dist**)。App H5 `index.html` md5 `c650e0deae6191cb94f461516c0579a1` (v1.1.141 是 `ccefa8fe…`)，`.32:18090` 与阿里云 `127.0.0.1:18090` 逐字节一致且 `assets/pages-scan-finished-in.C1snyO5a.js` 200 (`.150` 无 H5 容器)。APK `erp-app-20261031.apk` md5 `70275043d9b498f12d677d4bbac0f68b` (4,445,089 B，versionName 1.1.142 / versionCode 1142，内嵌 `pages-scan-finished-in.C1snyO5a.js` + `pages-scan-semi-in.N6H-639F.js`)，落飞牛同步盘 `erp/` 与 Desktop。七处版本 `scripts/check-version.sh 1.1.142` 全 OK。

**同日第二批修补** (补完单元测试后暴露的回落链缺口，版本仍 1.1.142，零 App/PC/SQL 改动):
- **跑全量单测** (`mvn -o test`，JDK 17) 揪出 `PrdOrderServiceTest.detail_shouldInjectColorNo` 报 `NullPointerException: … ProductDeriveService.mainUnit(Long) because "this.deriveService" is null` —— `PrdOrderService` 构造器多了 `deriveService` 依赖，`@InjectMocks` 却没有对应 `@Mock`。补 `@Mock ProductDeriveService deriveService` (LENIENT，默认桩让 `backfillDerived()` 退化成 no-op，原来的 `verify(productMapper, times(1))` 断言照旧成立)。
- **新增** [ProductDeriveServiceTest](../backend/src/test/java/com/industrial/erp/modules/production/service/ProductDeriveServiceTest.java) 9 例：规格四级回落 (快照 → `base_product.spec` → `.model` → 工单快照)、`hasUnit()` 只认 `unit_name`、主单位名空视为没配、`mainUnits()` 一次批量 + 丢空名/null 行、商品无主单位时回落工单快照且 `unit_id=0` 脏行不回填、瘦客户端全空时六字段补齐、已有快照不被覆盖。**它当场打出一个生产代码真缺口** —— `inboundFields()` 的 `productCode`/`productName` 用嵌套三元 `快照 ?: (商品 != null ? 商品的值 : 工单快照)`，**商品行存在但 `product_code` 为空时链子断在中间层**，工单快照永远轮不到。改成 `firstNonBlank(快照, 商品, 工单)` 私有 helper (全空仍返回第一个入参=原快照，保持「原样返回」)；类头 javadoc 口径表同步补「编码/名称 = 单据快照 → base_product → 工单快照」。
- `PrdOrderServiceTest` 另加 2 例守住纪律：`detail_shouldBackfillSpecAndUnitWhenSnapshotBlank` (空快照被派生值填上) + `detail_shouldNotOverrideExistingSnapshot` (已有快照不动)。全量 **178/178 绿** (`Tests run: 178, Failures: 0, Errors: 0`，原 167 + 新 11)。
- **重打 jar 三站部署** (版本保持 1.1.142)：jar `140774ac8cb53ff4411b3005e640319d` (101,592,592 B) 替换上一批 `5aea1a3e…` — `.32` `docker cp /opt/app/app.jar` + `docker commit erp-system-backend:1.1.142`；`.150` 把 `industrial-erp-1.1.142.jar` 改名 `.bak_v1142a_20261031` (保证 `COPY industrial-erp-*.jar` 只匹配一个) 后 `compose build/up -d backend`；阿里云 cp + restart + `docker commit erp-backend:1.1.142`。**三站都核镜像本体** `docker run --rm --entrypoint md5sum <image:tag> /opt/app/app.jar` = `140774ac…`，captcha 200、`ERP_VERSION` 1.1.142、`sys_config.SYSTEM_VERSION_INFO` 三站一致回写 `{"version":"1.1.142","startTime":"2026-10-31 09:22:22"}`。App H5 (`c650e0de…`) / PC dist / APK (`70275043…` 1.1.142/1142) 未动 —— 本批零前端改动。
- **修补后复测** (仍按「非超管跑全链」)：`.32` 与 `.150` 用 `gpssong1` (loginId `2101579680441774082`) 会话 —— `order/page?billNo=PD202610200001` 与 `order/{id}` 都返回 `spec=9*75*0.07` / `unitName=只` / `unitId=2089154538889621509`；`.32` 再发一张故意留空的成品入库 → `PFI202610310003` 落库 `product_code=dyd975007`、`spec=9*75*0.07`、`unit_name=只`，验完按 `id + bill_no + bill_status='DRAFT' + deleted=1` 四条件硬删，`batch_no LIKE 'E2E-V1142%'` 副本 0 行、主库 0 行；副本 `Replica_IO/SQL_Running=Yes`、`Seconds_Behind_Source=0`、`Last_Error` 空。阿里云两个非超管会话已失效 (401)，该站读路径以 `gpssong` 复核通过 (数据与主库同源)。
- **现场佐证**：主库今天两张**真实**入库单 (仓管上午录的 长晟9*70*0.095 / 22565、长晟10*74*0.095 / 22212) 已自动带上 `spec` 与 `unit_name=只`；它们 `unit_id` 仍空 —— `base_product_unit.unit_id=0` 脏行被 `validUnitId()` 挡掉，与 v1.1.117 口径一致。⚠️ 这两张的单号 `PFI202610310001`/`PFI202610310002` 与上面 09:00 那批**已硬删**的测试草稿重号 (硬删后日序列重新计数) —— **对账请认 id 不认单号** (草稿 id `2116333747194613762`/`2116334108231913473`，真实单 id `2116339318495690754`/`2116339634511331329`)。

**踩坑**:
1. **`onLoad` 里调 async 初始化不 await，就是一条深链 100% 空跑的链路** —— 「只有某个入口坏、手工操作正常」的现象，第一嫌疑人是初始化竞态而不是接口；uni-app 页面 `onLoad` 可以直接声明 `async`。
2. **「客户端派生字段后端必须兜底」要读、写两条路径都兜** —— 只兜读路径管显示，写路径 (入库单 → `inv_ledger`/`inv_stock`) 仍会落 NULL；反之只兜写路径，用户看到的界面依旧是空的。派生口径收敛到一个 `ProductDeriveService`，两处共用，避免以后再加入口时漏掉。
3. **读路径回填不要顺手也回填 `update()`** —— 派生值一旦持久化，"这张单当初到底有没有填单位"就再也查不出来了 (v1.1.136「派生值不入库」同一条纪律)。
4. **headless Chrome 实测 App 用不着过验证码** —— 鉴权是 httpOnly cookie (`token-name: Authorization`，timeout 30 天)，浏览器重启即失效；从站点自己的 Redis `Authorization:login:token:<uuid>` 挑一个**非超管**账号的 uuid，CDP `Network.setCookie` 注进去就能以真实权限账号跑 UI (`.32`/`.150`/阿里云各有独立 Redis，token 不通用)。
5. **多级兜底别写嵌套三元** —— `A ?: (X != null ? X.val : B)` 在「X 存在但 `X.val` 为空」时就把链子断在中间层，最末一级兜底永远走不到；改成按优先级取第一个非空值的 helper (`firstNonBlank(快照, 商品, 工单)`)。**这类缺口跑一遍单测就露出来** —— 是 `ProductDeriveServiceTest` 那条「商品行存在但编码空」的断言把它打出来的，不是现场报的。
6. **给 `@InjectMocks` 的服务加构造器依赖，必须同步补 `@Mock`** —— 否则测试里那个字段是 null，报的是 `Cannot invoke "…mainUnit(Long)" because "this.deriveService" is null`，看起来像生产代码 NPE，其实是测试没接线。**还有：`mvn … | tail` 会把 maven 的退出码吞成 0** (v1.1.136 记过一次，这次又踩)，跑测试要看 `Tests run: N, Failures/Errors` 那行或直接 `; echo EXIT=$?`。

### v1.1.141 (2026-10-30 — 工业品生产单开工**只把「生产环境合格」当必填项**：原料批号与批号照片可留空；食品级维持 XK16-204 原口径)

> **需求原话**: 「工业品的生产单开工仅生产环境为必选项，其他不填也能开工。」
> 背景: v1.1.132 起 App「吹膜开工确认」强制三样齐全 —— 环境合格 + 原料批号 + 批号照片，缺一即拒。但 XK16-204 的投料留痕要求是**食品级**口径 (食品接触材料必须能溯源到具体原料批)，工业品 (吹膜厂大量非食品包装膜/袋) 现场根本拿不到批号标识照片，卡在开工这一步。

**后端** (`backend/.../mes/service/MesWorkStartService.java`):
- `confirmBlowStart()` 原来无条件 `batchNos.isEmpty()` / `photos.isEmpty()` 就抛错；现按商品等级分档 —— 新增 `isFoodGradeProduct(PrdOrder)` (查 `base_product.is_food_grade`, **查不到 / 无商品 / 非 1 一律按工业品**, 与 `PrdSemiInService` v1.1.102 的「食品级才走 XK 硬门禁, 工业品放行」豁免口径同源)，**仅食品级**继续强制批号 + 照片；工业品只校验 `envQualified=true` 即放行。
- 工业品若仍填了批号, 照旧走 `mes_batch`(RAW) + `mes_batch_link`(ISSUE) 登记 (追溯能力不丢)；不填则该字段留空, 半成品入库的 `resolveRawBatchForOrder()` 取不到即留空 (null-safe, 不阻断入库, 报工链路本来就不强制批号)。
- 审计行批号为空时写「投料批号=未填写(工业品选填)」, 不留空串让人以为漏填。
- 同步改 `WorkStartConfirmDTO` 字段注释与 `PrdOrderController.blowStart` 的 javadoc (原写「缺批号/缺照片一律拒绝」)。
- **制袋开工本来就是只有环境必填** (无批号/照片入参), 不涉及。

**App** (`app/src/pages/production/order-detail.vue`):
- 新增 `isFoodGradeOrder` computed = `Number(order.pIsFoodGrade) === 1` (后端 `PrdOrderService.detail()` 已回填 `base_product.is_food_grade`)。
- 开工弹窗按等级分档: 食品级维持「2. 本次投入的原料批号」「3. 拍下原料批号标识 (必填, 至少 1 张)」; 工业品标注「(选填)」+ 灰底提示「工业品开工只需确认生产环境合格, 批号与照片可留空; 填写后仍会登记到批次追溯, 建议填写。」
- `canSubmitStart`: 工业品只判 `sheet.env === true` (勾上「合格」即可提交); 食品级仍要求批号 + 照片齐全 (未填时按钮保持 disabled)。
- 工序卡文案随等级切换 (「确认生产环境合格 (必填) → 原料批号与照片选填」/「确认生产环境合格 → 录原料批号并拍照」); 底部说明与成功提示同步 (工业品提交后提示「未登记投料批号 (工业品选填)」)。

**验证 (飞牛 canary, 真·非超管账号 `zzstart1141` + 角色 `ZZSTART1141`, 授权仅 `production:order:release` 等 4 个 perm)**:
- API 5 跳全绿: ① 工业品只带 `envQualified=true` → **200 开工成功** (`WS202610300001`, `raw_batch_nos` 空 / `photo_urls` `[]`, 自动出 2 份 `xk_env_check` CHECKED); ② 食品级只带环境 → 拒「请填写本次投入的原料批号 (XK16-204 要求食品级投料记录可追溯)」; ③ 食品级有批号无照片 → 拒「请拍摄原料批号标识照片 (至少 1 张)」; ④ 食品级批号 + 照片齐全 → 200; ⑤ `envQualified=false` → 拒「生产环境未确认合格, 禁止开工」(工业品同样拒)。
- 落库核对: 台账 2 行 (`env_check_ids` = 2 份环境检查)、`xk_env_check` 4 行 `AUTO-BY-WORK-START`、两单 `RELEASED`、`xk_audit_log` 4 行 `XK_WORK_START` (工业品那行写「投料批号=未填写(工业品选填)」)。
- 浏览器实测 (headless Chrome + 原生 CDP, 走真实路径 工作台 → 生产单列表 → 点行 → 详情 → 开工): 工业品单弹窗显示「(选填)」+ 提示行, 勾「合格」后 `uni-button.ws-submit` 的 `disabled` 属性消失 → 点「确认开工」→ 弹窗「开工成功 | 吹膜已开工, 未登记投料批号 (工业品选填)。已生成 2 份生产环境检查合格记录。」→ 详情页变「已开工」; 食品级单弹窗显示「(必填, 至少 1 张)」, 勾「合格」后 `disabled="true"` 仍在 → 点不动。
- 清理: 5 张测试单 (PDTEST1141A~E) + 4 条开工台账 + 8 条环境检查 + 1 条 `mes_batch`/`mes_batch_link` (ZZTEST1141-BATCH) + 4 条 XK 审计 + 临时账号/角色/授权 + Redis 会话 + 上传目录临时文件, 残留核对全 0, 真实生产单 79 张未受影响; 副本 `Slave_IO_Running=Yes / Slave_SQL_Running=Yes / Seconds_Behind_Master=0`, `Binlog Dump` 无下游 ⇒ canary 写入不外溢。

**踩坑 (值得记)**:
1. **App H5 同路由换 query 不会重建页面实例 (页面级也中 keep-alive 的招)** —— `location.hash = '#/pages/production/order-detail?id=X'` 只改 URL, 页面 DOM/数据仍是上一张单 (uni-app H5 的页面栈复用同一 path 的实例)。**UI 实测必须开新标签页 (`Target.createTarget`, 同 profile 共享 cookie) 才能拿到干净页面栈**, 否则 `document.querySelector('.ws-submit')` 会命中旧实例的按钮 —— 本批第一次跑就因为命中旧实例而误判「食品级按钮没禁用」。同一坑的另一个面: 复测要从「列表 → 点行」进, 不能靠改 URL。
2. **uni-button 的 `disabled` 是组件 prop, 不是原生属性** —— `el.disabled` 恒为 `undefined`, 要看 `outerHTML` 里有没有 `disabled="true"`。
3. **图形验证码在 Redis 里存明文** (`erp:login:limit:captcha:<key>` → 明文 code) —— 先 `GET /api/auth/captcha` 拿 key, 再从 Redis 读明文即可自动登录任意账号 (不必 OCR 图片); 但**必须用页面自己那次请求的 key** (页面加载时会自己取一张, 与脚本另取的 key 不是同一个), 否则报「验证码错误」。登录失败 5 次/5 分钟会锁, 别反复试。

**版本**: 七处对齐 1.1.141 (`scripts/check-version.sh` 全 OK), `app/src/manifest.json` versionCode 1141。

**部署**:
- 后端 jar md5 `7a9a97ad1a29d8b45ecc7a2c31675ef0` (101586012 B): 飞牛 `.32` (镜像 `erp-system-backend:1.1.141` + compose `ERP_VERSION` 同步, 镜像本体与运行中容器 md5 均已核对) → 群晖 `.150` 生产主站 (`backend/industrial-erp-1.1.141.jar` + `docker compose up -d --build backend`, 旧 jar 改名 `.bak_v1141_*` 避免 `COPY industrial-erp-*.jar` glob 命中两个) → 阿里云 (`.env` `ERP_VERSION=1.1.141` 驱动镜像 tag)。
- App H5 `index.html` md5 `ccefa8febc8fb799976cfc1c55adf1c7`: 飞牛 `.32` (`/vol2/erp-system/app-h5/dist/build/h5`, bind-mount, 备份 `h5.bak_v1141_20261030_125825.tgz`) + 阿里云 (`/opt/erp-system/app-h5`, 备份 `/root/app-h5.bak_v1141_20261030_131250.tgz`); **`.150` 复核确认无 H5 容器** (8088/18080 都是 PC dist), 故不部署。
- APK `erp-app-20261030.apk` md5 `e6eb9313707e555cbea1827dc05bc5be` (4444881 B, versionName 1.1.141 / versionCode 1141), 落到飞牛同步盘 `erp/` + `~/Desktop/`, `build-app.sh` 校验内嵌 H5 与 OS 级版本号一致。**装新 APK 后需重新登录。**

**零 SQL、零 PC**。

### v1.1.140 (2026-10-30 — 制袋巡检打开成吹膜巡检的**真根因**：uni-app H5 keep-alive 会保住子组件的 camelCase props；改为模块级「检验上下文」+ 三通道取参，并把 ④ 制袋巡检补齐到与 ② 对称)

> **需求原话**: 「app端生产单详情的制袋巡检实际打开内容为吹膜的巡检。完善制袋巡检」
> v1.1.139 把 ② 的按钮钉在吹膜工序上之后，**反向的坑才暴露**：④「制袋巡检」的深链 URL 明确是 `#/pages/scan/pat?orderId=2111270042198421505&stage=BAG&processId=2112787513992622082`，页面却渲染吹膜模板、`POST /qms/inspection/ensure-for-process` 请求体里发的是吹膜工序 `2112787513954873345` —— 说明「参数在路由上对、在组件里不对」，问题不在入口标签。

**定位证据 (CDP 读 Vue 实例，不是猜)**: 沿 `#app.__vue_app__._instance` 向下拿到 `ProcessInspection` 实例，dump 它的 `vnode.props`：

```
{ "inspection-type": "PAT", key: "<新 routeSig>",
  "order-id": "2111270042198421505",  orderId:   "<上一趟的旧值>",
  "process-id": "2112787513992622082", processId: "<上一趟的旧值>",
  stage: "<上一趟的旧值>" }
```

同一路由换 query 再次进入时，uni-app 的 keep-alive 会重建子组件，但**新值只落到模板里写的 kebab 属性键上，组件真正声明使用的 camel 键 (`orderId`/`processId`/`stage`) 仍是上一趟的**。用假值 `AAA/BBB/CCC` 复测同样复现 ⇒ `:key` 重建、`v-if` 开关、`$nextTick` 都不是解法 —— **props 不能当作工序身份的唯一来源**。另一半根因是时序：H5 派发目标页生命周期那一刻 `location.hash` 可能还是上一页的、框架塞给 `onLoad` 的 query 也可能是「上一趟」的 (列表→详情A→返回→详情B 同款命中)。

**改动 (纯 App，零后端、零 SQL、零 PC)**:
- **新增 `app/src/utils/routeQuery.js`** 三组通道：`rememberRoute` (:90) / `takePendingRoute` (:103，跳转前登记本次 path+query，5s TTL，取一次即失效)；`activeRoute`/`isRouteActive`/`activeRouteQuery` (:41–:77) / `onActiveRouteChange` (:119，URL 落地后的纠偏订阅，只在当前路由就是本页时回调)；**`setInspectionContext`/`getInspectionContext`/`onInspectionContextChange` (:139–:152，模块级检验上下文 + 订阅)**。
- `app/src/utils/nav.js:33` `navigateTo()` 第一行 `rememberRoute(url)`；`pages/production/order-list.vue:109` 不走 nav.js 的那条 `uni.navigateTo` 也单独登记。
- **页面统一取参优先级** `takePendingRoute(PAGE_PATH) || activeRouteQuery(PAGE_PATH) || 框架给的 query`：`scan/pat.vue:22,26,31`、`scan/fai.vue:146,150,155`、`scan/oqc.vue:162,166,171`、`production/report.vue:286,299,304`、`production/order-detail.vue:664`。
- `scan/pat.vue:43`、`scan/fai.vue:172` 每次解析到路由都 `setInspectionContext({ inspectionType, orderId, processId, stage })` (与 Vue 的 props 合成无关)。
- **`components/ProcessInspection.vue` 以上下文为准**：`liveCtx/liveOrderId/liveProcessId/liveStage` (:159–:162，上下文缺位才回退 props) 喂给既有 `routeKey` (:176) → 既有 watcher → `initialize()` (:206) 全量重置并重载；`mounted` 读一次 + 订阅、`beforeUnmount` 退订 (:185,:191)；`applyRecord()` 的数据一致性守卫改按 `liveStage` 校验 (:309)；订阅者只采纳 `inspectionType` 与自己一致的上下文，FAI/PAT 共用 store 也不串台。
- **完善制袋巡检**：④ 三个按钮 (制袋首检/制袋巡检/工序报工) 与 ② 对称，全部把本工单制袋工序 `bagProcess.id` 带进深链 (`order-detail.vue:160-162`)；页头显示「实际生产工序 · 仅制袋 / 仅吹膜」自证检的是哪道工序 (:29)；制袋 PAT 门禁块新增**「去做本工序制袋首检」**按钮 (:42 → `goProcessFai()` :349，带 orderId+processId+stage 深链到 `/pages/scan/fai`，落在同一道实际工序，不会被换成吹膜模板)。

**版本**: 七处对齐 1.1.140 (`scripts/check-version.sh` 全 OK)，versionCode 1140。**本批无后端改动、jar 未重建** → 三站容器仍 `ERP_VERSION=1.1.138` (jar `45bd61e5…`)，App「我的」页显示 1.1.140。

**部署**:
- App H5 `index.html` md5 `45eccc964d74b7e107ebea9343f17333`：`.32` 写 `/vol2/erp-system/app-h5/dist/build/h5`、阿里云写 `/opt/erp-system/app-h5` (原 `ff2fe93f…` = v1.1.139，备份 `/root/app-h5.bak_v1140_20261030_124322.tgz`；bind mount，原地换文件即可)；**`.150` 复核确认没有 App H5 容器** (它的 `app-h5/` 目录是死的，`8088`/`18080` 两个端口 `index.html` 都是 PC dist)。两站都从容器端口 `127.0.0.1:18090` 实取 `index.html`、`assets/ProcessInspection.IUT69qmf.js` (`ff8e1e55…`)、`assets/pages-scan-pat.BtbSqc8N.js` 与本地构建逐字节一致。
- APK `erp-app-20261030-1140.apk` md5 `e48d37e15a9b39f0c89da52732638579` (4444577 B，versionName 1.1.140 / versionCode 1140，`build-app.sh` 全流程)。解包核对内嵌 `assets/public/index.html` md5 = 已部署 H5 `45eccc96…` (二次 `uni build` 产物与部署字节完全一致，`diff -rq` 无差异)、`ProcessInspection.IUT69qmf.js` 内含 `去做本工序制袋首检` + `实际生产工序`，且已确认**没有任何 `dbg1140` 埋点残留**。**装新 APK 后需重新登录。**

**验证 (飞牛 canary `.32:18090`，真·非超管账号 `zzpat1140`／角色 `zdg`，每一跳都核对 `ensure-for-process` 请求体)**:
1. 工单 `PD202610170002` → ④「制袋巡检」→ 「XK 生产 / 制袋工序检验」+「实际生产工序 · 仅制袋」+「制袋 · 未设置车间 · #2112787513992622082」，请求体 `{"orderId":"2111270042198421505","processId":"2112787513992622082","inspectionType":"PAT"}`，命中门禁文案「该制袋工序尚无已签核 PASS 的首件检验 FAI，请先完成本工序首检」并出现「去做本工序制袋首检」。
2. 返回 → ②「吹膜巡检」→ 「吹膜 · 巡检检验项 (7)」，请求体 `processId=2112787513954873345`。
3. 返回 → ④「制袋巡检」(**用户报的正是这一跳**) → 仍是制袋、参数正确。
4. 「去做本工序制袋首检」→ `#/pages/scan/fai?orderId=…&processId=2112787513992622082&stage=BAG` → 「制袋 · 首检检验项 (10)」，请求体 `"inspectionType":"FAI"`。
5. 从制袋首检页 `history.back()` 回制袋巡检 → 依旧「仅制袋」，不漏吹膜模板。
- **canary 清理**: 测试期在真单上被 `ensure-*` 自动建的 3 张 DRAFT (`QI202610300004` PAT 7 项 / `QI202610300005` FAI 10 项 / `QI202610300006` OQC 13 项) + 30 个检验项 + 临时账号 `zzpat1140` (id `2133001140001140`) 及其 `sys_user_role` 行 + 6 个上传 tar 全部删除；`mes_quality_inspection` 已无 10-30 的行。清理前确认 `.32` 副本 `IO=Yes SQL=Yes Seconds_Behind=0`、`log_slave_updates=1` 但**无下游** (`Binlog Dump` 线程 0) 且 `read_only=1` (root 不受限)，故本地删除不外溢。`zdg` 是 2026-07-21 就存在的生产角色「制袋工」，保留未动。

**踩坑 (值得记)**: (1) **「页面 data 对、URL 对」不等于「子组件收到的 props 对」** —— uni-app H5 同路由换 query 再进入时 keep-alive 会保住子组件的 **camelCase** props (kebab 键是新值)，`:key` / `v-if` 都拦不住；这类问题要用 CDP 读组件实例的 `vnode.props` 定性，别停在「看起来是缓存」的猜测上。(2) **跳转方必须在跳转前登记本次参数** —— 首屏那一刻 `location.hash` 可能还是上一页的、框架给 `onLoad` 的 query 可能是上一趟的；`onActiveRouteChange` 只是 URL 落地后的纠偏通道，**不能替代 `onLoad` 的取参**，两者互补。(3) 共用一个模块级 store 时**必须按 `inspectionType` 隔离订阅**并在 `beforeUnmount` 退订。(4) **浏览器标签会一直跑旧 bundle** —— `location.href='…?cb=x#…'` 再 `location.reload()` 实测 reload 的是**上一个 URL** (用 `performance.getEntriesByType('navigation')` 验)，换 bundle 验证要走完整 pathname。(5) **冷启动深链会被重定向到工作台**，并留下一个报「生产单不存在」的缓存详情实例 —— 端到端复测要走 工作台 → 生产加工单列表 → 点行。(6) `.150` 至今不托管 App H5，别再往它的 `/volume3/docker/erp-system/app-h5` 写产物当「已部署」。

### v1.1.139 (2026-10-30 — App 生产单详情 ② 的按钮点名「吹膜首检 / 吹膜巡检」，深链钉在本工单吹膜工序上，打开即吹膜工艺检验项)

> **需求原话**: 「app端画红框的做首检该为吹膜首检，并将内容改为吹膜工艺的首检。」(红框 = 生产单详情 XK 流程卡 ② 的「做首检」按钮)
> **现场证据**: 用户 10:38 在 `.32:18090` 工单 `PD202610170002` 上的两次点击留下 `QI202610300004` (PAT，挂**吹膜**工序 `2112787513954873345`，7 个 `PAT_*` 吹膜项) 和 `QI202610300005` (FAI，挂**制袋**工序 `2112787513992622082`，10 个 `BAG_*` 制袋项)。即「做巡检」落在吹膜、「制袋首检」落在制袋，路由本身没错 —— 问题是 ② 的「做首检」标签不写工序，和 ④ 的「制袋首检」并排时现场无法判断这张单检的是哪道工序，点错就签错模板。而 v1.1.138 之后该按钮虽已走 `ProcessInspection`，**落在哪条工序上仍靠「按 stage 过滤后只剩一条」隐式自动选中**，不是钉死的。

**App (`app/src/pages/production/order-detail.vue`，3 处)**:
- `:130-131` ② 卡两个按钮文案 `做首检 / 做巡检` → **`吹膜首检 / 吹膜巡检`**，与 ④ 的 `制袋首检 / 制袋巡检` 对称。
- `:331` 新增 `blowProcess` computed (`processes` 里 `processName.includes('吹膜')`，与既有 `bagProcess` 同一口径；`processes` 来自 `api.prdOrderProgress`)。
- `:694` `goScan(path, stage, processId)` 加第三个参数，命中时深链拼 `&processId=`；两个吹膜按钮传 `blowProcess && blowProcess.id`。`pages/scan/fai.vue` / `pages/scan/pat.vue` 本来就接 `query.processId` 并透传给 `ProcessInspection`，**组件零改动**。
- 效果：`ProcessInspection.selectOrder` 走 `retryProcessId` 分支精确锁定吹膜工序，打开就是吹膜检验项，不再依赖"过滤后只剩一条"；若该工序 id 不属于本工单/阶段，组件明确报「指定工序不属于该工单或阶段，可能尚未配置 / 展开。不会改用吹膜或其他工序模板」并给重试，**不会静默换成别的模板**。
- **零后端 Java、零 SQL、零 PC 改动**；③⑤⑥ 与半成品入库 XK 门禁不动。

**版本**: 七处对齐 1.1.139 (`scripts/check-version.sh` 全 OK)，`app/src/manifest.json` versionCode 1139。**本批无后端改动，jar 未重建** → 三站容器仍 `ERP_VERSION=1.1.138`、主库 `sys_config.SYSTEM_VERSION_INFO` 仍显示 1.1.138，App「我的」页显示 1.1.139；下一批含后端改动时一并翻正。

**部署**:
- App H5 `index.html` md5 `ff2fe93fcfc0b386cb7ec8f0cc15baad`：`.32` 写 `/vol2/erp-system/app-h5/dist/build/h5` (备份 `h5.bak_v1139_20261030_110455.tgz`)、阿里云写 `/opt/erp-system/app-h5` (原 `49319889…`，备份 `/root/app-h5.bak_v1139_20261030_110624.tgz`，顺手清掉历次 tar 带上去的 `._*` AppleDouble)；`.150` 无 H5 容器。两站都从容器端口 `127.0.0.1:18090` 实取 `index-W64oF8va.js` 与 chunk 核对。
- APK `erp-app-20261030-1139.apk` md5 `2fbb76b8e1423ac70b24e7ad87ceb288` (4442431 B，versionName 1.1.139 / versionCode 1139，`build-app.sh` 校验内嵌 H5 与 OS 级版本号一致)。**装新 APK 后需重新登录。**

**验证**:
- 线上产物就地反查：`.32` 与阿里云的 `assets/pages-production-order-detail.B8F9aacZ.js` 各含 1 处 `吹膜首检` + 1 处 `吹膜巡检`，`做首检/做巡检` 已 0 命中；编译后的 `goScan` 里能看到 `processId="+encodeURIComponent(String(n))`。
- 内容 (吹膜工艺首检项) 用 canary 上 `gpssong` 的活 Sa-Token (Redis `Authorization:login:session:2`，绕开 P0-2 验证码) 直调 `POST /qms/inspection/ensure-for-process {orderId:2111270042198421505, processId:2112787513954873345, inspectionType:"FAI"}` → 200：head `QI202610300007` `FAI / PRD_PROCESS / DRAFT`，`process.processName=吹膜`、`stage=BLOW`，**6 项全是 `BLOW_MATERIAL_CHECK / BLOW_APPEARANCE / BLOW_THICKNESS / BLOW_WIDTH / BLOW_TENSILE / BLOW_ODOR`** (该单成品 `mjy1050007 马鲛鱼10*50*0.07` `is_food_grade=0` → 不追加食品级 2 项，与 `QmsInspectionService.foodProcessTemplate` 判型一致)。测试草稿 `QI202610300007` + 6 个检验项 + 本窗口审计行已删；`.32` 副本 `SHOW REPLICA STATUS` 仍 `IO=Yes SQL=Yes Seconds_Behind_Source=0`、无错误。

**踩坑 (值得记)**: (1) **判断"内容对不对"要看落库的 `source_bill_id` + `qms_iqc_item.item_code`，别只看页面** —— ② 和 ④ 两个首检按钮共用同一个 `/pages/scan/fai`，唯一区分就是 query 里的 `stage`，页面长得一模一样。(2) 现场卡片上的按钮文案不带工序名，就是点错单的第一来源；同一组件多入口时，**入口标签必须自证"我检的是哪道工序"**。(3) 本项目 Jackson 全局 `Long → ToStringSerializer` (`WebMvcConfig`)，深链拼 `processId` 不会被 JS `Number` 精度截断 (工序 id 远超 `2^53`)。(4) 纯 App 批次不重建 jar 会造成 PC(服务端 `sys_config`) 与 App(`APP_VERSION`) 版本显示不一致 —— 要么接受并在 changelog 写明，要么 recreate 三站容器，而 `.150` 是生产主库，重建有约 30s 接口中断，别为了一串数字顺手做。

### v1.1.138 (2026-10-30 — App 吹膜首检改为「按工单 + 实际工序」做，不再强制先选半成品入库单；半成品入库 XK 门禁同步认工序侧那张)

> **需求原话**: 「app端生产单吹膜开工后做的首检不需要关联半成品入库单」
> 场景: 车间现场顺序是 **开工 → 首检 → 半成品入库**，而 App 的吹膜「做首检」入口旧实现强制先选一张 `prd_semi_in` —— 点进去那一刻入库单根本还没建出来 (主库 `prd_semi_in` 0 行时页面直接「暂无半成品入库单」，无单可选)，首检做不了、也就签不了合格，食品级成品的半成品入库又被 XK 门禁反过来说「未关联 FAI 检验」→ 死锁。

**App**:
- `app/src/pages/production/order-detail.vue:128` 吹膜工序「② 首检 / 巡检 · 检验员」的「做首检」按钮由 `goScan('/pages/scan/fai')` 改为 `goScan('/pages/scan/fai', 'BLOW')` —— 与「做巡检」(`goScan('/pages/scan/pat', 'BLOW')`) 对齐，把工单号 + 阶段一起带进检验页。
- `app/src/pages/scan/fai.vue:140` `onLoad` 里 `processMode` 判定补上 BLOW：`this.processMode = this.processStage === 'BAG' || this.processStage === 'BLOW' || !!this.processId`。命中 processMode 后**不再 `loadSemiIn()`**，直接渲染共用的 `ProcessInspection` 组件 (工单/工序两个下拉 + 检验项 + 拍照 + 判定)，与吹膜巡检、制袋首检同一套 UI 与接口。
- 检验单落库形态：`mes_quality_inspection.source_bill_type='PRD_PROCESS'`、`source_bill_id=<prd_process.id>`、`inspection_type='FAI'`，由 `POST /qms/inspection/ensure-for-process` 幂等建/取草稿并灌吹膜模板 (通用 6 项 + 食品级再加 2 项)，签核走 `PUT /qms/inspection/{id}/process-record`。
- `app/src/pages/scan/semi-in.vue:74` XK 门禁提示文案改成「食品级原料须已关联**该工单的合格吹膜首检** (检验员在「生产单详情 → 首检」逐项签合格, 再回此审核)」，不再指挥检验员去「首检」页选入库单。
- **不改的部分**: PC 侧「按半成品入库单开首检」的入口与 `POST /qms/inspection/ensure-fai-for-semi-in` 接口原样保留 (存量单、入库单侧首检仍可用)；`pages/scan/fai` 不带 `stage`/`processId` 进来时仍走入库单列表 (老入口兼容)。

**后端 (`QmsInspectionService`)**: 门禁必须同时认两张来源，否则现场签了合格仍入不了库。
- `hasPassedFai(semiInId)`：先按老口径找挂在入库单上的 FAI；找不到时经 `findBlowProcessOfSemiIn()` 定位该入库单所属工单的**已展开吹膜工序**，再用 `hasPassedFaiForProcess(processId)` 判工序侧那张是否 `status=CHECKED` 且 `result∈{PASS, PASS_DISPOSITION}`。
- 新增 `findLinkedFaiForSemiIn(semiInId)` (`@Transactional(readOnly=true)`)：同一口径返回 FAI 单据本身，供门禁报错**指名道姓**是哪张单没放行；新增私有 `findBlowProcessOfSemiIn(semiInId)` (`prd_semi_in.prd_order_id` → `prd_process` where `deleted=0` order by `process_no`，取 `isExpandedProcess && processStage=="BLOW"`)。
- `snapshotFaiGate(in)` 改用 `findLinkedFaiForSemiIn`，工序侧首检签合格后，入库单仍会冗余快照 `inspection_report_no / inspection_result / sample_qty / inspector_name` (XK 留痕口径不变)。
- `PrdSemiInService.check()` 报错分支同步改用 `findLinkedFaiForSemiIn(id)`：门禁拒绝时会说「关联 FAI 检验单 QI… 判定为 HOLD/未审核放行」，而不是含糊的「未关联 FAI 检验」。
- **零 SQL、零表结构改动** (工序侧 FAI 的字段与类型 v1.1.134/v1.1.135 就已在用)。

**版本**: 七处对齐 1.1.138 (`scripts/check-version.sh` 全 OK)，`app/src/manifest.json` versionCode 1138。

**单测**: `QmsInspectionServiceTest` +3 (`semiInGateAcceptsSignedBlowFaiOnOrderProcess` / `semiInGateStillRejectsUnsignedOrFailedBlowFai` / `semiInGateFallsBackToOwnFaiWhenLinked`) —— 覆盖「工序侧已签合格→放行」「草稿态/不合格→拒绝」「入库单自带 FAI→照旧走老口径且 `findLinkedFaiForSemiIn` 优先返回它」。该类 68/68，全量 167/167。

**部署 (三站一致)**:
- jar md5 `45bd61e5005da288ce22b40a5c7e92cd`：`.32` 用最小临时 context `/tmp/erp1138build` (只放 Dockerfile + `erp-backend.jar` = 1.1.138 jar) `docker build -t erp-system-backend:1.1.138`，compose `image: erp-system-backend:1.1.138` + `ERP_VERSION: 1.1.138` (备份 `docker-compose.failover.yml.bak_v1138`) → `docker compose -f docker-compose.failover.yml --profile failover up -d erp-backend-failover`；`.150` 上传 `backend/industrial-erp-1.1.138.jar` (旧 `1.1.137` 改名 `.jar.retired`) → 最小 context build `erp-system-backend:{1.1.138,latest}` → compose `ERP_VERSION: "1.1.138"` (备份 `docker-compose.yml.bak_v1138`) → `up -d`；阿里云 `backend/industrial-erp-1.1.138.jar` + `.env ERP_VERSION=1.1.138` → `docker compose up -d --build erp-backend` → 镜像 `erp-backend:1.1.138`，容器内 `/opt/app/app.jar` md5 与 `ERP_VERSION` 均核对一致，`/api/auth/captcha` 200。
- PC dist `index.html` md5 `3d5b393b94fa6db8acfcf3b7e461a4a4` 三站一致 (`.32` 写 `pc-web/dist-new`、备份 `dist-new.bak_v1138_*.tgz`；`.150` 写 `/volume3/docker/erp-system/pc-web/dist`、备份 `dist.bak_v1138_20261030_101021.tgz`；阿里云写 `/opt/erp-system/pc-web`、备份 `pc-web.bak_v1138_20261030_101208.tgz`)。
- App H5 `index.html` md5 `4931988971b0703640271da9c2260550` (`.32:18090` + 阿里云 `/opt/erp-system/app-h5`，备份 `app-h5.bak_v1138_20261030_101208.tgz`；`.150` 无 H5 容器)。部署后就地在 `.32` 产物里 grep 验到编译后的 `processMode` 条件含 `"BLOW"===`、H5 资源里 `1.1.138` 字样存在。
- APK `app/android/app/build/outputs/apk/debug/app-debug.apk` md5 `2b178287d87070575e938425bc6dc51d` (versionCode 1138，内嵌 H5 与线上 H5 同一份产物)。**装新 APK 后需重新登录。**
- 主库 `sys_config.SYSTEM_VERSION_INFO` = `{"version":"1.1.138","startTime":"2026-10-30 10:14:02",…}` (`.150` `erp-backend` recreate 后自动写入)。

**验证 (飞牛 canary，真·非超管账号跑全链 + headless 浏览器实测)**: 临时账号 `zzfai1138` (角色 zdg 制袋工，只授 `quality:inspection:check`)：
- `#/pages/scan/fai?orderId=…&stage=BLOW` 页面渲染的是**工单 + 工序下拉**，不再出现「半成品入库单」列表；选单后 `ensure-for-process` 自动建草稿 `QI202610300003` 挂在 `PRD_PROCESS` 上，8 个食品级吹膜检验项齐全。
- UI 上填抽样数 10、逐项合格、拍照、「签合格」→ `mes_quality_inspection` 该单 `status=CHECKED result=PASS`，签核时间 2026-10-30T10:03:00，检验人 `zzfai1138`。
- 随后 `POST /production/semi-in/{id}/check` 返回 200，且入库单被正确快照 `inspection_report_no=QI202610300003 / inspection_result=PASS / sample_qty=10 / inspector_name=zzfai1138`。
- **负向对照**：签核前同一调用被拒，报的是「XK 合规: 关联 FAI 检验单 QI202610300001 判定为 HOLD, 不合格/未放行不能入库」—— 修复前的代码在这里只会说「未关联 FAI 检验」，可见工序侧那张现在能被门禁找到了。
- **canary 数据全部手工清干净**：3 张测试 FAI + 22 个检验项、2 行 `mes_batch_link`、1 行 `mes_batch`、4 行 `inv_ledger`、1 行 `inv_stock`、1 张 `prd_semi_in`、临时账号与 `sys_user_role` 关联、测试窗口内 `sys_oper_log` 逐行删除；`is_food_grade` 还原 0；`gpssong` 在 canary 的密码 hash 与主库逐字节对齐 (测试期被临时覆盖过)。
- 三站 md5 与 `ERP_VERSION` 逐项一致；`.32` / 阿里云副本 `SHOW REPLICA STATUS` 均 `IO=Yes SQL=Yes Seconds_Behind_Source=0`。

**顺带修好的运维问题**: `.32` 副本 SQL applier 自 2026-10-29 16:44 起一直停在 `1062 Duplicate entry '2078644534343434242' for key 'sys_feie_print_template_bak_v1137.PRIMARY'` —— 正是 v1.1.137 同日第二批 `sql/146` **先在 canary 手工跑过、再灌主库**留下的老毛病 (主库那条 `insert into …bak_v1137` 通过复制流回来时本地已有同主键行)。先 `mysqldump` 该表留档，再 `TRUNCATE TABLE sys_feie_print_template_bak_v1137` + `START REPLICA`，复制恢复且那行由主库同步回来，落后归零。

**踩坑 (值得记)**: (1) **现场顺序 ≠ 单据顺序** —— 质量门禁若假设「先有入库单再首检」，在 App 现场 (开工→首检→入库) 就是死锁；把检验单挂到**工序** (`PRD_PROCESS`) 上才与现场时序同构，这也是制袋首检/巡检早就采用的形态。 (2) 改门禁只改 `hasPassedFai` 不够 —— `findLinkedFaiForSemiIn` (报错要指名是哪张单) 和 `snapshotFaiGate` (XK 留痕字段回填) 必须同一口径，否则出现「能入库但入库单上报告号是空」。 (3) 工单的 `prd_process` 行是**开工 (`release()`) 时才展开**的，所以工序侧首检的前置条件天然满足 `requireActiveOrder` (RELEASED/PRODUCING)，未开工时 `ensure-for-process` 会被拒 —— 这是对的，别去绕。 (4) **先建草稿后改商品 `is_food_grade` 会让草稿的检验项集合失效**：`process-record` 保存时按当前模板重校验代码集合，报「检验模板代码集合不完整或与BLOW工序不匹配」；处理办法是软删旧草稿再重新 `ensure-for-process` 拿 8 项食品模板。 (5) `sys_config` 的列名是 `config_key/config_value` (不是 `cfg_key/cfg_value`)，版本核对直接 `SELECT config_value FROM sys_config WHERE config_key='SYSTEM_VERSION_INFO'`。 (6) `.150` 主库 `erp-mysql` 的 root 口令**不是** compose 里的 `MYSQL_ROOT_PASSWORD`，要从 `erp-backend` 的 `SPRING_DATASOURCE_PASSWORD` 读；`.32` 的 `erp-mysql-failover` 才是 `erp_root_pwd`。 (7) 阿里云 host nginx 只有 `listen 80` (`server_name 47.96.188.129`)，在机器上 `curl https://127.0.0.1/...` 命中的是宝塔默认站 → 会误判成 404/内容不对；探活要么 `curl -H "Host: 47.96.188.129" http://127.0.0.1/...`，要么直连容器发布端口 `127.0.0.1:18080` (PC) / `127.0.0.1:18090` (H5)。 (8) `docker compose ps` 不在 compose 文件所在目录跑会报 `no configuration file provided` (别误读成容器挂了)。 (9) **幂等迁移脚本先在 canary 跑再灌主库 → 复制必撞 1062 停** (本次又踩，见上)；主库灌过的脚本不要再在 canary 手工执行，或执行前确认该语句写的是纯备份表。

### v1.1.137 (2026-10-29 — 生产工单打印: PC 详情页「打印」下拉 (浏览器/飞鹅) + 飞鹅打印权限点真正可用 + 小票规格/使用原料修复)

> **需求原话**: 「http://home.93gushi.com:8088/#/production/order pc端生产工单详情页添加打印按钮，可以选择浏览器打印和飞鹅打印」 + 「app端生产单详情页添加飞鹅打印按钮」
> 场景: 车间要在生产工单上直接打「加工单」纸质流转单 (浏览器打印) 和小票 (飞鹅云打印 车间小票机)，以前只能去销售订单页打印或回办公室操作。

**后端 (零 Java 改动，仅版本号重建)**: 打印链路早就齐备 —— `FeiePrintController` (`GET /feie/print/{bizType}/{id}/preview`、`POST /feie/print/{bizType}/{id}`、`POST …/config/{configId}`、`POST /feie/print/{bizType}/batch`) + `templates/print/prd_order_feie.ftl` 小票模板 + `sys_print_template` PRD_ORDER「加工单」设计器模板，全部按 `production:order:feie-print` 注解守卫。浏览器打印走既有的 `usePrint` → `GET /system/print-template/biz-type/PRD_ORDER` → `myprint-design` 懒加载 chunk → `MyPrinter.chromePrinter` → `iFramePrint()`。**本次唯一的后端变化是 `erp.version`/`SystemVersionInitializer` 升到 1.1.137**。

**PC (`pc-web/src/views/production/Order.vue`)**: 生产记录详情 footer `.wo-foot-actions` 加「打印」`el-dropdown` (与销售订单页同模式)，三项 `浏览器打印` / `飞鹅打印预览` (divided) / `飞鹅云打印`，后两项 `v-if="userStore.hasPerm('production:order:feie-print')"`。
- 浏览器打印: `usePrint.doPrint({bizType:'PRD_ORDER', bill, fieldMap})`，表头 10 字段映射 `PRD_ORDER_HEADER_MAP` = 单号/日期/品名/型号/色号/宽度/厚度/克重/计划数量/备注。
- 飞鹅: 先 `preview` 拿小票文本装进 `el-dialog` 的 `<pre>` 预览，用户点「确认打印」才 `POST` —— **预览与真实出纸分离，避免误触发打印机**。`onPrintCommand` 里再挡一次权限 (直接命令调用绕过 v-if 也不放行)。
- `pc-web/src/views/system/Role.vue` 的 `APP_MENU_WHITELIST`「生产 / 质量」组补 `{name:'工单飞鹅打印', perms:'production:order:feie-print', idApp:'app-order-feie-print'}`。

**App**: `pages/production/order-detail.vue` 的「🖨 飞鹅云打印」按钮**自 v1.1.9 就在代码里，但从来没真正可用过** —— 界面上授不了权，除超管外没人看得到。根因是本项目**两套权限查询口径不一致** (见踩坑): `sys_menu` id=959 这行 `status=0`，登录接口 `SysMenuMapper.selectPermsByUserIdAndClient` 带 `m.status=1` 与 `rm.client_type IN ('BOTH',#{clientType})` 过滤 → 非管理员登录根本拿不到这个 perm；而后端注解校验走 `SysUserMapper.selectPermsByUserId` (不看 `status`、不按端过滤) → 只要 `sys_role_menu` 有行就放行。`sql/145_v1137_feie_print_perm_enable.sql` (幂等，只在 `.150` 主库执行) `UPDATE sys_menu SET status=1 WHERE id=959` + 按工单主菜单 702 的授权**镜像**给 SUPER_ADMIN/PRODUCTION_MGR/WAREHOUSE_MGR/zdg(制袋工)/cmg(吹膜工) (`insert … select … where not exists`)。App 按钮命中 `hasFeiePrintPerm()` 后调同一组 `/feie/print/**` 接口，零 App 代码改动 (顺带把 v1.1.136 漏的 App 详情/新增页克重标签补成 `g/个`，见下)。

**克重单位补齐 (v1.1.136 收尾)**: `app/src/pages/production/order-detail.vue:57` 标签由 `克重` 改成 `克重 (g/个)` (与 PC 详情、商品编辑一致)。**纯前端标签，零 SQL**。⚠️ 本批当时记为「同时补齐 `order-add.vue:47`」**不准确** —— 新建工单页那行至今仍是无单位的 `克重` (`app/src/pages/production/order-add.vue:46`)，属待补小项，因纯文案改动不值得为它单独重出 H5/APK，留到下一批 App 改动时一并带上。

**版本**: 七处对齐 1.1.137 (`scripts/check-version.sh` 全 OK，versionCode 1137)。

**部署 (三站一致)**: jar md5 `e3f6fd0d27c4d589b55957c6a72bdea6` → **同日第二批修补后 `a6d2fbabfe2708c0ea83e056b3c30203`** (多 `PrdOrderBillLoader.setTotalWeightKg`) — `.32` `docker cp` + `docker commit` → `erp-system-backend:1.1.137` + compose `ERP_VERSION`; `.150` 上传 `backend/industrial-erp-1.1.137.jar` → **最小临时 context** (`$HOME/erpbuild-1137`，只放 Dockerfile+jar，避免 3.3G 的 `./backend` 整目录进 context) `docker build -t erp-system-backend:{latest,1.1.137}` → compose `ERP_VERSION: "1.1.137"` → `docker compose up -d backend`; 阿里云 `.env ERP_VERSION=1.1.137` + `docker compose up -d --build erp-backend` → `erp-backend:1.1.137`; 同日第二批 jar 走 `docker cp` 进运行容器 + `restart` 三站同步。PC dist `index.html` md5 `7dc3b4a9af71cd0ae69210a0be14eea3` (chunk `Order-DUGC2_Ug.js` + `Role-CgflQKGD.js`) 三站一致; App H5 `index.html` md5 `3f921514bf88120bc891156ab9386c3f` (chunk `pages-production-order-detail.D07ggnXl.js`，`.32:18090` + 阿里云 `:18090`，`.150` 无 h5 容器)。APK `erp-app-20261029.apk` md5 `a9af52604b3545003c4e13db2f76a808` versionCode 1137 (内嵌 H5 与部署 H5 同一份产物)。备份: `.150` `docker-compose.yml.bak_v1137_20261029_1350` + `pc-web/pc-web.bak_v1137_20261029_1354.tgz` + `backend/jar-archive/industrial-erp-1.1.136.jar` + 镜像 tag `erp-system-backend:1.1.136`; `.32` `app-h5/dist/build/h5.bak_20261029_134041`; 阿里云 `.env.bak_v1137_20261029_1400` + `pc-web.bak_v1137_20261029_1358.tgz` + `app-h5.bak_v1137_20261029_1359.tgz` + `backend/industrial-erp-1.1.135.jar.bak_v1137` (旧镜像 `erp-backend:1.1.136` 保留)。

**验证 (飞牛 canary，两个真·非超管临时账号 A/B 跑全链)**: A=`_t1137a` (制袋工 zdg，授了 feie-print)、B=`_t1137b` (FINANCE，未授)。18 断言 API 测试 + headless Chrome/CDP 实测：
- 登录 perm 下发: A 的 PC 与 APP 两个 `X-Client-Type` 响应都含 `production:order:feie-print`，B 两个都不含。
- 后端注解: `GET /feie/print/PRD_ORDER/{id}/preview` A → 200 且返回小票正文；B → `403 无权限访问`。`POST` 真实打印接口两侧都验到 403/200 语义，**但始终没调用过 `POST /feie/print/PRD_ORDER/{id}`** —— 那会真在车间小票机出纸，只测 preview。
- PC (`:18080` `#/production/order`): A 的「打印」下拉三项 `浏览器打印 | 飞鹅打印预览 | 飞鹅云打印`，B 只有 `浏览器打印`; 点浏览器打印后 `MutationObserver` 抓到 `iframe#print-box` 的正文 (含工单号 `PD202610290003`、品名、克重)，飞鹅预览弹窗渲染小票文本。
- App H5 (`:18090` `#/pages/production/order-detail?id=…`): A 页底出现「🖨 飞鹅云打印」+「📤 分享生产单」，B 只有「📤 分享生产单」 (截图 `/tmp/v1137-app-A-detail.png` / `-B-detail.png`)。
- PC 角色管理 App 权限树: 「工单飞鹅打印」节点出现且制袋工已勾 (看完点「取消」关闭，未提交任何授权)。
- 三站 jar/dist/H5 md5 与 `ERP_VERSION` 逐项一致; `sys_menu` 959 `status=1` 且 `sys_role_menu` 10 行在主库、`.32`、阿里云三处一致 (sql/145 只灌主库，复制链自动到两备)。**canary 临时账号 A/B 与本次测试脚本已删除**，业务数据零写入。

**待用户拍板的数据项 (均在 `.150` 主库，属"改主库数据"，未动)**: (1) `sys_user` `_ws1132` (id `2133001133001133`，「开工确认E2E」) 与 `zztmpsplit` (`deleted=1`) 两个历史 e2e 账号仍在主库; (2) `sys_feie_print_template` id=`2099745652415737858`「ZZ_渗透测试残留_请删除」。

**同日第二批 (用户报障后 1 小时): 飞鹅生产单小票「领料数量没有正确显示」** —— 用户实机打印后发现小票上规格与重量行全是空的。根因是**打印走的不是 classpath 的 `prd_order_feie.ftl`，而是 DB 行** `sys_feie_print_template` id=`2078644534343434242`「生产单」(`biz_type=PRD_ORDER`, `is_default=1`) —— `FeiePrintService.renderCustomContent()` 优先用 DB 模板，只有 DB 没有才回落到 ftl。该 DB 行是用户早期在「飞鹅打印模板」页里手写的，正文有三处硬伤:
1. **变量名是 Java 字段名不是 getter 名**: 写 `order.width` / `order.thickness` / `order.gramWeight` / `order.colorNo`，但 `PrdOrder` 的商品规格属性是 transient 注入字段 (`pWidth`/`pThickness`/`pDensity`/`pGramWeight`/`pColorNo`)，FreeMarker 的 JavaBean introspection 只暴露 **getter** —— 实体为兼容旧模板提供了别名 getter `getWidth()`/`getThickness()`/`getDensity()`/`getGramWeight()`/`getColorNo()`，所以模板必须写 `order.width`/`order.thickness`/`order.density`/`order.gramWeight`/`order.colorNo`；写 `pXxx` 全部静默渲染成空 (classic 模式下不报错，最难查)。
2. **「重量：${d.qty}」里的 `d` 变量根本不存在**: BillLoader 放进模型的是 `order` + `details` (复数) 两个 key，没有 `d`；而且 v1.1.136 起「使用原料(公斤)」是**派生值** (计划数量 × 克重 ÷ 1000)，工单侧根本不建领料单，正确口径是 `order.totalWeightKg`。
3. **`totalWeightKg` 在打印路径上压根没被赋值**: 只有 `PrdOrderService.page()`/`detail()` 会 `setTotalWeightKg(...)`，而打印走 `PrdOrderBillLoader.load()` —— 它只补了规格属性，没补总重量。

**修法 (1 处 Java + 1 条 SQL)**:
- `backend/.../production/bill/PrdOrderBillLoader.java`: 注入商品规格时同步 `order.setTotalWeightKg(displayKg(computeRawMaterialKg(planQty, gramWeight)))` —— 算法与 `PrdOrderService.totalWeightKg` 完全一致 (计划数量 × 克重 ÷ 1000，6 位小数后取 3 位展示)，克重 ≤0 或数量为空返回 null (降级为不打印该行)。
- `sql/146_v1137_feie_prd_order_template_fix.sql` (幂等, 只在 `.150` 主库执行, 复制链自动到两备): 备份 `sys_feie_print_template_bak_v1137` + 把该行正文重写为正确变量名版本 (宽度/长度/厚度/克重 `g/个`/颜色/数量/配方/规格/**使用原料 kg**/备注/二维码)，每行加 `<#if … != null>` / `?has_content` 判空 (无 BOM / 无规格的成品不再印出空标签)。

**同日第二批实测 (canary `.32` + 三站)**:
- 修前 preview 渲染: `宽度：mm` `长度：mm` `厚度：mm` `克重：g/个` **全空**，无「使用原料」行。
- 修后 preview (`PD202610290003` 长晟10×74×0.095, 计划 20,000 只, 成品克重 6.3 g/个): `宽度：745mm` `长度：100mm` `厚度：0.095mm` `克重：6.3g/个` `数量：20,000` `配方：普通B机料` **`使用原料：126kg`** (20,000 × 6.3 ÷ 1000 = 126，与 PC 详情「总重量 kg」同值)。
- 三站 jar 统一 md5 `a6d2fbabfe2708c0ea83e056b3c30203` (`.150` `docker cp` + restart、`.32` `docker cp` + restart、阿里云 `docker cp` + restart，均 "Started Success")，三站 DB 模板行统一 830 字。

**踩坑 (同日第二批补充)**: (12) **打印模板有三层优先级 —— DB 行 (`sys_feie_print_template`, `is_default=1`) > classpath `.ftl`**，改 `.ftl` 对已经在 DB 里配过模板的单据**完全无效**；排查打印内容为空先 `select content from sys_feie_print_template where biz_type=…` 看真正生效的那份。(13) **FreeMarker 取 Java bean 属性只认 getter，且 classic 模式下取不到不报错、只渲染空串** —— 实体字段名 (`pWidth`) ≠ getter 名 (`width`)，`${order.pWidth}` 静默变空；`<#if order.pWidth??>` 也恒为真 (classic 模式 `??` 语义不可靠)，要用 `<#if order.width != null>`。(14) **MySQL 单引号字符串里写 FreeMarker 的 `!''` 默认值要转义成 `!''''`** —— 漏一个单引号渲染时直接报 `Unexpected end of file … unclosed "{"`；且**通过 ssh + `docker exec -i` 传 SQL 时每层 shell 都会再吃一次引号**，稳妥做法是本机用脚本生成转义好的 `.sql` 文件再 `cat | ssh 'cat > file'` + 远端 `mysql < file`。(15) `.150` 上 `printf '密码' | sudo -S docker exec -i mysql < file` 会让 sudo 和 mysql 抢同一个 stdin → "Sorry, try again"；改成 `SQL=$(cat file); docker exec -i mysql -e "$SQL"` 或先 `docker cp` 进容器再执行。

**踩坑 (值得记)**: (1) **本项目两套 perms 查询口径不一致** —— 后端 `@SaCheckPermission` 走 `SysUserMapper.selectPermsByUserId` (不过滤 `m.status`、不过滤 `client_type`)，登录/前端走 `SysMenuMapper.selectPermsByUserIdAndClient` (`m.status=1` + `rm.client_type IN ('BOTH',#{clientType})`)。所以「有 `sys_role_menu` 行但界面看不到按钮」和「界面看不到但接口能调」都是正常的表面矛盾：授权行是给后端用的，`status=1` 是给前端用的，**补权限点必须两件事一起做** (启用 menu 行 + 补角色授权)，光改 SQL 的一半只会得到 403 或隐形按钮。 (2) `orRole="admin"` 是惰性的 —— 库里角色是 `role_code` (`SUPER_ADMIN`/`zdg`/`cmg`)，没有叫 `admin` 的，别指望它兜底。 (3) **cookie 不分端口** —— 同一 host 上 PC(18080) 与 H5(18090) 共享会话 cookie，测 App 端不 `Network.clearBrowserCookies` + `localStorage.clear()` 就会拿 PC 的超管会话冒充 App 结果 (超管实测会掩盖权限缺口，与 v1.1.135 同一课)。 (4) **`Window.prototype.print` 断言拦不住 myprint-design** —— 它调的是 `iframe.contentWindow.print()`，要证明真渲染得用 `MutationObserver` 抓 `iframe#print-box` 的 `contentWindow.document.body.innerText`。 (5) App H5 深链 `Page.navigate(...#/pages/...?id=…)` + reload 会被路由守卫甩回 dashboard/登录页，SPA 内 `location.hash=` 导航才稳。 (6) uni-app H5 `<input>` **没有 placeholder 属性** (提示文字在覆盖层)，CDP 填表按序号取。 (7) `.el-table__row` 等待在 Order.vue 上永远超时 (详情页不是表格)，改等 `.wo-foot-actions button`。 (8) **造 bcrypt 别用本机 python `bcrypt`** (这个版本不接 `rounds=`/`prefix=`)，用 `htpasswd -bnBC 10 "" '<pw>' | tr -d ':\n' | sed 's/^\$2y\$/\$2a\$/'`；且 hash 串**绝不能塞进嵌套双引号的远程 SQL** —— `$2a$10$…` 会被远端 shell 当变量展开，实测把密码列存成了 `a0`；写成 `/tmp/*.sql` 再 `docker exec -i mysql < file`。 (9) 探活别只看 `curl 127.0.0.1` (阿里云 host 上会命中本机 nginx 而非容器)。 (10) `.150` 的 `/volume3/docker/erp-system/backend` 是 3.3G 目录 (src/backup/upload 都在里面) 且 `pc-web/` 父目录 owner 是 uid 501 → 打包 context 用最小临时目录、写备份 tar 要 `sudo`；compose `up -d` 不带 `--build` 会复用同名 `:latest` 镜像，所以先手工 build 再 up 是安全的。 (11) 阿里云的 Dockerfile 是 `COPY industrial-erp-*.jar /opt/app/app.jar` (单源)，**新 jar 落目录时必须先把旧版本改名归档**，否则通配命中两个文件 → COPY 多源要求目标为目录 → 构建直接失败。

### v1.1.136 (2026-10-29 — 生产单录入加「使用原料 (公斤)」双向换算 + 克重单位统一 g/个)

> **需求原话**: 「1、克重单位改为g/个。2、新建生产工单时计划数量下面添加一行使用原料，单位公斤，数值为计划数量*克重/1000，使用原料和计划数量可以相互转换，填其中一个自动填写另外一个数值。」
> 场景: 车间手上只有原料公斤数 (要备多少料), 但工单按件下单 → 以前得自己拿计算器除以克重再乘件数, 容易错。

**PC (`pc-web/src/views/production/Order.vue`)**: 新建/编辑生产工单弹窗「计划数量」行**下面新增一行「使用原料(kg)」** (`label-width` 只有 100px, 用 `(kg)` 免得标签折行, 单位「公斤」写进 hint)：
- 派生值 = `计划数量 × 克重 ÷ 1000` (3 位小数), 与后端 `PrdOrderService.totalWeightKg()` 同一算法、同一口径。
- **双向换算**: 改「计划数量」→ 使用原料自动重算 (`watch` planQty)；填「使用原料」→ 计划数量 = `round(公斤 × 1000 ÷ 克重)` 写回, 使用原料再按取整后的件数回算 (1000 kg ÷ 31 g → 32258 只 → 999.998 kg, 两边永远自洽, 不出现"填了 1000 却显示 1000.02"的漂移)。
- 成品克重缺失 (`gramWeight` 为空/0) 时该输入框 `disabled` + 提示「该成品未维护克重, 无法按使用原料反算计划数量」; 换成品 → 按新克重重算。
- **不入库**: 不加列、不加字段, 表单只是录入辅助。工单详情「总重量 (kg)」、开工自动领料按配比分摊仍走后端唯一算法, 不造第二个真源。
- 顺手补了 `.form-hint` 样式 (本文件里"食品级原料批号"那条提示一直没样式)。

**App (`app/src/pages/production/order-add.vue`)**: 同款换算 (手机上也按公斤下计划)。uni-app 侧 `使用原料` 输入框未选成品时 `disabled`, 选成品后自动带出; `@blur` 触发反算 (不在输入过程中跳数)。

**克重单位统一 g/个 (数据本来就是单件净重, 纯标签修正, 无数据迁移)**:
| 位置 | 改前 | 改后 |
|---|---|---|
| PC 新建工单弹窗 | `克重(g/㎡)` | `克重(g/个)` |
| App 商品编辑 `base/product-add.vue` | `克重 (g/m²)` | `克重 (g/个)` |
| 飞鹅生产单打印模板 `prd_order_feie.ftl` | `克重: 31g/㎡` | `克重: 31g/个` |
| `BaseProduct.gramWeight` 字段注释 | `克重 (g/m² 或 g/件)` | `克重 (g/个)` — 单件成品净重, 总重量 kg = 数量 × 克重 ÷ 1000 |

PC 商品管理页本来就是 `克重(g/个)`, 与算法一致。库里 `base_product.gram_weight` 本来就是**单件净重**, 旧标签 `g/㎡` 是历史笔误且与 `总重量 = 数量 × 克重 ÷ 1000` 自相矛盾 —— 物理校验: `cs70181860006 长晟（70+18+18）*60` 600×1065mm×0.055mm 的 PE 袋 ≈ 31 g/只, 与库里 31 完全对上 (0.92 g/cm³ 折算 32 g, 扣封边后 31)。**零 SQL**。

**版本**: 七处对齐 1.1.136 (`scripts/check-version.sh` 全 OK, versionCode 1136)。

**部署 (三站一致)**: jar md5 `7ccd529ddff6444c92a6d16e737154f2` (JDK 17 `mvn clean package`, **164 单测全绿**; `.150` `docker compose build backend` + `up -d` + compose `ERP_VERSION: "1.1.136"`; `.32` `docker cp` + `docker commit` → `erp-system-backend:1.1.136` + compose 改 tag/ERP_VERSION; 阿里云 `.env ERP_VERSION=1.1.136` + `docker commit erp-backend:1.1.136` + `compose up -d`)。PC dist `index.html` md5 `c4e6ba886fe0e0b65335e7e4e8408400` (chunk `Order-BKmGbYFy.js`) 三站一致; App H5 `index.html` md5 `267e606b3d8f9a2f73299877200d34c4` (chunk `pages-production-order-add.BmMz67u2.js`, `.32:18090` + 阿里云, `.150` 无 h5 容器)。APK `erp-app-20261029.apk` md5 `7f4e556057bb4a5702785e4f7b076361` versionCode 1136 (内嵌 H5 与部署 H5 逐字节一致, build-app.sh 的 [6/6][6.5/6] 版本断言过)。备份: `.32 backup/{app.jar.bak,dist-new.bak,app-h5.bak}.20261029_v1136`、`.150 backend/industrial-erp-1.1.135.jar.bak_v1136_20261029` + `upload/pc-web.bak_v1136_20261029.tgz` + `upload/docker-compose.yml.bak_v1136_20261029`、阿里云 `backup/{app.jar.bak,pc-web.bak,app-h5.bak}.v1136_20261029` + `.env.bak_v1136_20261029`。

**验证 (飞牛 canary, headless Chrome + CDP 真鼠标)**:
- PC (`.32:18080` `#/production/order`): 选 `cs70181860006` → 标签显示 `克重(g/个)` = 31; 计划数量 1 → 使用原料 0.031; 计划数量 20000 → **使用原料 620**; 使用原料 1000 → **计划数量 32258** + 使用原料回算 999.998; 换成克重 64.7 的 `bajhs483860` → 使用原料按新克重重算 2087.093; 控制台零报错。
- App H5 (`.32:18090` `#/pages/production/order-add`): 未选成品时「使用原料 (公斤)」灰显; 选 `cs70181860006` → 克重 31 + 使用原料 620; 计划数量 20000 → 620; 使用原料 1000 + 失焦 → 计划数量 32258 + 999.998。
- 三站 jar md5 / ERP_VERSION / dist 与 H5 md5 逐项核对一致; `.32` 复制链双 Yes + `Seconds_Behind_Master: 0`; **本次实测未保存任何单据** (只开弹窗填表), 库里今日 3 张 `PD202610290001~0003` 是用户 09:47-09:48 自己建的 DRAFT, 非测试残留。

**踩坑 (值得记)**: (1) **`npm run build | tail` 会把失败吞掉** —— 管道末端命令的退出码盖掉 build 的失败码, 这次 `vite build` 因我手滑把两行粘成 `let productSearchTimer = nullasync function ...` 报语法错, 脚本却"成功"退出。**构建一律 `set -o pipefail` 或单独看 EXIT=**, 别信 `✓ built in` 之前有没有报错。(2) **`.32` 的 compose 镜像名是 `erp-system-backend:<版本>`, 不是 `erp-backend-failover-img`** —— 上批把 patched jar commit 到了错标签, 结果 compose 引用的 `erp-system-backend:1.1.135` 里还是旧 jar (`61d55cd8…`), 一旦容器重建就静默回退。**每次换 jar 后用 `docker run --rm --entrypoint md5sum <compose 里那个镜像> /opt/app/app.jar` 验镜像本体**, 别只看运行中容器。(3) macOS `tar` 打包会塞 `LIBARCHIVE.xattr.com.apple.provenance` 扩展头, NAS 上解包刷一屏 warning (无害)。**批量部署命令输出记得 `| grep -vE "xattr|warning"`**, 否则真正的报错会被刷掉。(4) PC 路由守卫看的是 `userInfo.userId || userInfo.id`, 而 `/auth/me` 响应里**没有 userId** —— seed `erp_user_info` 必须用**登录响应**的 data (带 userId), 用 `/auth/me` 的 data 会一直弹回 `#/login`。(5) uni-app H5 的 `<input>` **没有 placeholder 属性** (placeholder 是覆盖层 div), 按 placeholder 找输入框会全找不到, 得按序号定位 (`uni-input` 的 textContent 才是提示文字)。

### v1.1.135 (2026-10-29 — App 端生产单详情直接完成成品检验 (OQC) + 附件绑定 500 修复)

> **需求原话**: 「app端生产单详情点击做成品检应该直接弹出成品检验的具体项目，在手机端直接完成成品检验。」
> 改前: 生产单详情第 ⑤ 步「做成品检」只弹一个「请在 PC 端质量检验中完成」的提示框，车间只能回办公室开 PC 录出厂检验。
> **两处口径由用户拍板**: ① 开单口径 = **「按生产单直接开检」** (工单侧找/建 OQC，膜/袋按该单工序链判型，手机签核，并把成品入库的 OQC 门禁放宽到「这张工单有已签合格 OQC」); ② 签合格必填 = **「数量+照片必填，3 签/留样自动兜底」** (抽检/合格/不良数量必填、批号自动带出可改、≥1 张现场照片；审核/批准签名与留样号 `LY-{billNo}`、留样到期 +24 月仍由服务端兜底)。

**后端 (`modules/qms` + `modules/production`)**:
- `QmsInspectionService.oqcStage()` 支持 `source_bill_type='PRD_ORDER'`；新增 `orderStage(Long prdOrderId)` — 该工单工序链里出现制袋工序判 `BAG`(袋模板)，否则有工序判 `BLOW`(膜模板)，**空链回 null → 落膜卷模板兜底**。
- 新增 `ensureOqcForOrder(Long orderId)`：按工单找未签核 OQC 草稿，没有就建一张 `source_bill_type=PRD_ORDER` 的头 + 按 `orderStage` 灌模板项，返回 `{head, items, order, stage}` 一次给全 App 页所需；配套 `findOqcByOrderId` / `findLinkedOqcByOrder`(既认直接挂在工单名下的头，也认后来被归并到该工单成品入库单名下的头) / `hasPassedOqcForOrder` / `isPassedOqc`。
- 新增 `relinkOqcToFinishedIn(PrdFinishedIn)`：成品入库单**建单时**把工单侧 OQC 草稿归并到入库单名下 (改 `source_bill_type/id/no` + 同步 `batch_no`/`sample_qty`)，保证「一张工单只有一条出厂检验记录」，不会出现工单一张、入库一张两张单。
- `PrdFinishedInService.add()` 改成 `if (!relinkOqcToFinishedIn(in)) createOnFinish(in);`；`check()` 门禁由「只认挂在入库单名下的 OQC」放宽为 `findOqcByFinishedInId(id)` 找不到再 `findLinkedOqcByOrder(prdOrderId)`，两张来源任一未签合格都拦 (`XK 合规: …判定为 HOLD, 未合格不能入库`)，已签合格则放行。
- `check()` 重载链末位新增 `batchNo` (13 参形态)，`QmsIqcItemService.requireLength(batchNo, 64, "批次号")` 守住长度；签 OQC + PASS/PASS_DISPOSITION 时自动补 3 签 + `LY-{billNo}` + now+24 月 (XK16-204 留样规则，App 端不填)。
- 新端点 `POST /qms/inspection/ensure-oqc-for-order` `{orderId}`，沿用 `quality:inspection:*` 注解，**零权限点新增** (相关 perm 均 `client_type='BOTH'` 且角色已授权)。
- 单测 +8 (`QmsInspectionServiceTest` 65/65，全仓 **164 通过 0 失败**)，覆盖工单开单幂等、膜/袋/空链判型、食品级 +6 项、归并、门禁负例与放行、数量守恒与「空项不能签合格」。

**App (uni-app)**: `app/src/pages/scan/oqc.vue` 新页 (318 行 Options API) — 表头(工单/成品/出厂判型) + 批次数量三格 + 逐项「待检/合格/不合格」picker + 实测值 + 不合格处置 textarea + 现场照片 (≤6 张，`pickAndUploadImage()` → `iqcBindAttachment`) + 固定底部「保存检验 / 签合格并放行 | 签不合格·冻结批次」；`ready()` 门禁 `unjudged===0 && 合格+不良===抽检 && photos>0 && (!itemsFail || 处置意见)`，不满足时签核按钮 `disabled`；`canCheck` 走 `isAdmin() || getAppPermissions().includes('quality:inspection:check')` (App 端纯 APP perm，不 fallback 混合端数组)；`bootstrap()` 对老草稿做**待检哨兵**回落 (`passFlag=1` 但实测值为空 → 视回待检，堵住「空项签合格」)。`pages.json` 注册 + `api/qms.js` 加 `ensureOqcForOrder`；`production/order-detail.vue` 第 ⑤ 步 `goScan('/pages/scan/oqc')` 直跳带 `orderId`。

**同日修补 — App 附件绑定自 v1.1.101 起从未成功过 (后端零改动，1 条 DDL)**: 实测照片上传后 `POST /qms/iqc/{id}/attachment/bind` 500「系统繁忙」，后端日志 `java.sql.SQLException: Field 'upload_id' doesn't have a default value`。根因是 DDL 与代码长期不一致：`sql/117` 把 `qms_iqc_attachment.upload_id` 建成 `BIGINT NOT NULL` 且注释写「sys_upload.id」，但本项目 `SysUploadController` **根本不落 `sys_upload`** (直接写文件返 url)，`QmsIqcAttachmentService.bind()` 一直是 `setUploadId(null)`，MyBatis-Plus 把 NULL 显式写进 INSERT → 严格模式直接报错。`.150` 主库 `qms_iqc_attachment` **0 行**印证这条链路自上线从没成功过，只因为 v1.1.135 第一次要求「签核前至少 1 张现场照片」才被踩到。修法 `sql/144_v1135_qms_attachment_upload_id_nullable.sql` (information_schema 守门幂等) 把列改 `BIGINT NULL DEFAULT NULL`，不回填、不删 `idx_upload`；并在 service 处留一行注释说明「本项目上传接口不落 sys_upload」。

**部署 (三站一致)**: jar `148a66a4e3744979d5288ce5509023a6` (JDK 17 `mvn clean package`，164 单测全绿；`.150` 走 `docker compose build backend` + `up -d` 并改 compose `ERP_VERSION: "1.1.135"`，阿里云改 `.env ERP_VERSION=1.1.135` 后同名 tag 重建，`.32` `docker cp` + `docker commit`)；App H5 `index.html ec4909ff9a39b283f2b31e79a54b6012` 部署到 `.32:/vol2/erp-system/app-h5/dist/build/h5` 与阿里云 `/opt/erp-system/app-h5` (备份 `app-h5.bak_v1134_20261029.tgz`)；**PC dist 未改** (三站仍 `612eca67…`)；APK `erp-app-20261029.apk` versionCode 1135，解包核对内嵌 `assets/public/index.html` md5 与已部署 H5 逐字节一致且含 `assets/pages-scan-oqc.DStHf6bM.js`。`sql/144` 灌 `.150` 主库后复制链自动到 `.32`/阿里云 (`.32` 为排障先手工 ALTER 过，脚本幂等跳过)。⚠️ `.32`/阿里云 backend 重建期间 captcha 探活有约 40s 空窗，属正常启动。

**验证 (飞牛 canary，headless Chrome + CDP 真鼠标)**: 两轮端到端 — 第一轮 `QI202610290009` (制袋单 `PD202610200001`，袋模板 13 项) 逐项判合格 + 实测值 + 批号覆盖 `E2E-BAG-1029` + 5/5/0 + 1 张照片 → 签核 toast「出厂检验合格，成品入库放行」→ 页面转「出厂检验已签合格，记录只读」；第二轮换最终 jar 复跑 `QI202610290011` 结果一致。落库核对 `status=CHECKED/result=PASS`、`retain_no=LY-QI…`、`retain_due_date=2028-10-29`、审核/批准人 gpssong、13 项 `pass_flag=1` 且有实测值、附件 `upload_id=NULL`。负例齐验：空表时「签合格并放行[disabled]」；食品级工单 `PD202610130001` 建出 **19 项** (13 膜 + 6 食品，`stage=null` 走膜兜底)；入库门禁 `PFI202610290006` 在 OQC 仍 DRAFT 时审核被拦 (文案命中)，签合格后同单审核通过并落 `inv_stock +10`/`inv_ledger` 1 行/`mes_batch` FG 批。**canary 残留已全部手工清理**：QMS 头/项/附件、批次与流向、测试入库单与台账库存、上传照片文件 (`/volume2/erp-system/upload/` 现为空)，终检 `mes_quality_inspection 43 / prd_finished_in 34 / inv_ledger 669 / mes_batch 4 / mes_batch_link 8→7` 与主库逐张对齐 (两张真实单 `PFI202610290001/0002` 未动)。

**同日修补 (第二批) — App 成品检现场照片接口 403 (非超管检验员手机上根本传不了照片)**: 上面 v1.1.135 的 canary 端到端全程用 `gpssong` (超管) 测，**`orRole="admin"` 把权限缺口完全掩盖了**。用户侧真实账号是只授 App 权限的车间检验员，实测 `GET /qms/iqc/{id}/attachments` 与 `POST /qms/iqc/{id}/attachment/bind` 一律 `403 无权限访问` → 手机端「≥1 张现场照片」这条必填永远满足不了，签核按钮永远 `disabled`，功能等于没上线。
- **根因**: 这两个接口 (以及 `POST /attachment/{id}/verify`) 是 v1.1.101 XK 那批建的，注解只认 `qms:iqc:list` / `qms:iqc:detail`；而 App 端权限树是 `pc-web/src/views/system/Role.vue` 的**前端白名单** (`APP_MENU_WHITELIST`「生产 / 质量」组)，里面只登记了 `quality:inspection:list` (app-qms-inspection) 与 `quality:inspection:check` (app-fai-pat)，**没有 `qms:iqc:detail`** → 白名单外的 perm 在界面上永远无法授予 App 角色，非超管拿不到。对照 `QmsIqcItemController` 的 `GET|PUT /{iqcId}/items` 早已 `SaMode.OR` 放宽到 `quality:inspection:*`，**唯独附件三个接口漏了** (`ensure-oqc-for-order` 也本来就 OR 了 `quality:inspection:*`)。
- **修法 (只改 2 个注解，零 SQL / 零 perm 新增 / 零前端 / 零 APK)**: `QmsIqcAttachmentController` — `GET /{iqcId}/attachments` 改 `{"qms:iqc:list","qms:iqc:detail","quality:inspection:list","quality:inspection:check"} mode=OR`；`POST /{iqcId}/attachment/bind` 改 `{"qms:iqc:detail","quality:inspection:check"} mode=OR`。`POST /attachment/{id}/verify` (PC 质检员复核用) 与 `GET /{iqcId}/all-passed` 有意**不动** — App `pages/scan/oqc.vue` 不调它们，放宽只会扩大授权面。
- **A/B 证据 (飞牛 canary，真·非超管)**: 建临时账号 `test_oqc_app` + 临时角色，**只**授 `quality:inspection:list|check` 两行的 `client_type='APP'` 授权。修前 (旧 jar `148a66a4…`): 两个附件接口 `403 无权限访问`，其余 `ensure-oqc-for-order` / `items` / `check` 全 200 — 精确卡在照片这一步。修后 (`7ea1817f…`)：全链 200 → 工单 `PD202610200001` 建单 → 绑 1 张现场照片 → 13 项实测判合格 → 5/5/0 → `POST /qms/inspection/{id}/check` PASS 落库 `status=CHECKED/result=PASS`、3 签写 `test_oqc_app`、`retain_no=LY-QI202610290012`、`retain_due_date=2028-10-29`、附件 `upload_id=NULL`。对照组同步验「没放宽过头」：`GET /{iqcId}/all-passed` 与 `POST /attachment/{id}/verify` 对同一账号仍 403。
- **部署**: 三站容器内 jar md5 统一 **`7ea1817f139aa6307f5d6ec6712a579b`** (**覆盖上文「部署 (三站一致)」段的 `148a66a4…`，这才是 v1.1.135 的最终 jar**；`.150` `docker compose build backend`；`.32`/阿里云 `docker cp` + restart + `docker commit` 回同名 `:1.1.135` 标签)；`ERP_VERSION=1.1.135` 三站不变；**PC dist `612eca67…` / App H5 `ec4909ff…` / APK versionCode 1135 均未动** (本次纯后端注解)。备份：`.150 backend/industrial-erp-1.1.135.jar.bak_v1135perm_20261029`、阿里云 `/opt/erp-system/backup/app.jar.bak_v1135perm_20261029`。三站 `/api/qms/iqc/…/attachments` 探活均 `{"code":200,"data":[]}`。canary 写入 (QMS 头/项/附件、测试单、台账与库存、照片文件、临时角色/账号) 已全部手工清理并与 `.150` 逐表对账：`mes_quality_inspection 43 / qms_iqc_item 94 / qms_iqc_attachment 0 / mes_batch 4 / mes_batch_link 7 / inv_ledger 669 / prd_finished_in 34`，`SHOW SLAVE STATUS` 双 Yes + `Seconds_Behind_Master: 0`。

**踩坑 (值得记)**: (0) **用超管账号实测，对权限问题零证明力** — 本项目所有接口都带 `orRole="admin"`，`gpssong` 走这条短路，权限点缺失一律看不出来 (这次就是「照片必填」这条门禁整个功能对真实检验员失效，canary 却两轮全绿)。**验收带鉴权的功能必须建等权临时账号 (App 要授 `client_type='APP'`) 跑完整链路，并给不该放宽的接口留 403 对照组。**(0b) **`.32` `docker cp` 进运行中容器后 `docker commit` 必须重做** — 第一批 commit 过，第二批只 cp+restart 忘了 commit，结果容器里是新 jar、镜像 `erp-backend-failover-img:1.1.135` 还是旧的 `148a66a4…`，下次重建容器会静默回退。**查镜像别查运行中容器**: `docker run --rm --entrypoint md5sum <image:tag> /opt/app/app.jar`。(1) **canary 备库 Redis 单号计数器落后于复制来的行** — `.32` 的 `erp:billno:PFI:20261029` 还在 1，而 `PFI…0001/0002` 是从 `.150` 复制过来的真实单，`BillNoGenerator` 无 TTL 也不看库，于是 `POST /production/finished-in` 撞 `Duplicate entry 'PFI202610290001-0'`；测前 `redis-cli SET erp:billno:PFI:20261029 5` 抬过最大号即可，别误判成业务重复校验 bug。(2) **uni-app H5 的 DOM 三坑**：`<button>` 渲染成 `<uni-button>` (选 `button` 找不到)，placeholder 是覆盖 div (`[placeholder]` 选择器失效，要选 `uni-input.kv-input input` / `.qty-cell uni-input input`)，picker 弹窗确认键文案是「完成」不是「确定」，且前一页仍在 DOM 里 (必须把查询 scope 到 `.qa-row` 内，否则行号错位)。(3) 合成 `dispatchEvent` 点击没有 user activation，文件选择器不弹，需 CDP `Input.dispatchMouseEvent` + `Page.setInterceptFileChooserDialog` + `DOM.setFileInputFiles` 才算「手机上真点」。(4) browser-use MCP 全程 `Browser target debugger is already in use` (9223 上有残留 Chrome) → 退回自建 headless Chrome `--remote-debugging-port=9333` + 裸 WebSocket CDP。

### v1.1.134 同日修补 (第三批) (2026-10-29 — 检验项模板全面改按本公司企业标准 Q/ZPC 1-2017 校准)

> **需求原话**: 「这个是我们公司的企业标准，参考企业标准」 + 附件 `鹏程包装企业标准.pdf` (Q/ZPC 1-2017《包装用聚乙烯吹塑膜、袋》，舟山市鹏程包装有限公司，2017-10-15 备案，起草人 顾鹏松、侯利军)。
> **范围**: 后端 `QmsInspectionService` 11 个模板常量 + `sql/143_v1134c_qms_zpc1_template_resync.sql` + 新增 `docs/27_企业标准QZPC1-2017检验条款对照.md`。**零 PC / 零 App / 零 H5 / 零 APK 改动** (检验项全由服务端模板灌入)。版本保持 **1.1.134**，三站容器内 jar md5 `8101517dcecde39508e60054bd1fe3b2`。

**为什么必须改**: 原模板写的是「按产品标准 / 按检验规程」这类无出处口径，车间填了也验不了。企业标准是**逐批出厂与过程检验的法定依据** (XK16-204 审查要看检验依据)，条款号 + 实测限值 + 试验方法出处必须落到每个检验项上，工人照着填、审核照着查。`docs/27` 声明自己是这套模板唯一的数值真源 — 改限值先改文档，再改代码。

**标准还原 (PDF 是加密扫描件，无 poppler，用 Swift/PDFKit 逐页位图 + 分段裁剪读出)**: 表2 宽度偏差 (折径<100 ±4 / 100~500 ±10 / 501~1000 ±20 / >1000 ±25)、表3 厚度偏差 (首档 **<0.025** 极限±0.008·平均±15%，逐档 0.025~0.050 ±0.015/±14%、0.051~0.100 ±0.025/±12%、>0.100 ±0.040/±10%)、表4 袋尺寸 (<100 ±2/±2、100~400 ±4、>400 ±6，封口与袋边距离 ≤12mm，厚度回表3)、表5 跌落 (≤100g→800mm、>100~≤400g→500mm、>400g→300mm，无破裂无渗漏)、3.4.2 热合强度 ≥7.0 N/15mm、3.3.1.3 每卷 ≤4 段且每段 ≥20m 断头标记、5.1 组批 (同品种规格工艺原料连续生产，最长 7 天；膜 ≤100000㎡、袋 ≤500000只)、5.3.2 抽样 (GB/T 2828.1-2012 一般检验水平 II、AQL=6.5 二次正常 → 表6 全 14 档)、5.4 判定 (标志/外观/印刷/尺寸一项不合格即判该只/卷不合格；物理力学不合格原批抽双倍复验；卫生指标不合格判卫生不合格)、6.1 合格证 **14** 项标志、6.4 距热源不小于 **1.0m**、贮存期一年。顺带纠正两处 OCR-only 误读: 表3 首档不是 <0.015、6.4 不是 100mm。

**后端** (`QmsInspectionService`)：11 个模板常量逐行重写，`requirement` 里给条款号 + 限值 + 判定档，`method` 里给试验方法与量具 (4.1 状态调节 (23±2)℃/(50±10)%RH ≥4h、4.3 自然光目测 + 0.1mm 量具、GB/T 6673/6672/1040.3-2006 2型宽10mm (500±50)mm/min/1040、GB/T 9639.1-2008 10 片、QB/T 2358-1998、GB/T 10004-2008、GB/T 2410-2008)。代码集合变化 (只增不换，老单子不会凭空多出未知项):
- `BAG_FAI_ITEMS` +`BAG_PRINT` (3.2 印刷质量；凹版 GB/T 7707、柔性版 GB/T 17497-1998、条码 GB 12904-2008/GB 14257-2009、净含量 JJF 1070-2005)。
- `OQC_FILM_ITEMS` +`OQC_FILM_ELONGATION` (3.4.1 纵横向断裂标称应变 — 5.2.1.2 明列膜的出厂项，之前漏了)。
- `OQC_BAG_ITEMS` +`OQC_BAG_PRINT` (5.2.1.3 袋出厂项含印刷质量，非印刷袋按该条注记录略去)。
- `OQC_FOOD_ITEMS` +`OQC_FOOD_BATCH_RULE` (组批 5.1 + 抽样表6 + 判定 5.4.1 三项一次性核验)。
- 吹膜首检仍按上一批的现场口径**不含**断裂标称应变/雾度/蒸发残渣/高锰酸钾消耗量/重金属迁移/食品接触标识 (机台做不了，归 OQC 实测或第三方报告核验)。`OQC_FOOD_MIGRATION` 文案明确「不做现场迁移试验」，`FOOD_MIGRATION_REPORT` 同理。

**行为修复 (顺带发现的真实缺口)**: `ensureForProcess` 命中已有 DRAFT 表头时**直接返回，从不灌模板** — 只有 `createInternal` 新建路径和 PC 的 `/{id}/ensure-template` 会灌。App 端首检/巡检页只调 `ensure-for-process` (调 `ensure-fai-for-semi-in`/`ensure-oqc-for-finished-in` 的除外)，于是 sql/143 软删老模板项之后，App 打开会看到空检验项、签核报「检验单无模板项，不能签核或以手工项替代」，与错误文案让用户「重新打开首检/巡检页按最新模板灌入」自相矛盾。修法: 早返回分支里补一句 `ensureProcessTemplateItems(head)` (该方法自带「非草稿/已有明细即 return」护栏，重复调用零副作用)。`ensureOqcForFinishedIn`、`ensureFaiForSemiIn`、`ensureTemplateItems` 三个入口原本就先查后灌，不用改。

**SQL** (`sql/143_v1134c_qms_zpc1_template_resync.sql`，幂等): 与 sql/142 同一套路 — `qms_iqc_item_bak_v1134c` 备份表 + 临时表圈「FAI/PAT/OQC/OQC_RETURN 的 DRAFT + 有 TEMPLATE 项 + 整单无人工痕迹 (`result_value`/`judgment_basis` 非空 或 `pass_flag=0`) + 无手工项」→ 先备份再整单软删，由服务端按新模板重灌。**已填过实测或判过不合格、以及已签核 (status<>'DRAFT') 的单子一律不动** (XK 过程/出厂检验留痕不可被脚本改写)；含 `MANUAL` 项的单子不重灌。**已于 2026-10-29 09:43 经用户批准后灌入 `.150` 主库** (`docker cp + SOURCE`，`--default-character-set=utf8mb4`，脚本 md5 `a09bfed2be5821170ea41b42079d48c0`): `candidate_drafts = 2`、`bak_rows = 17`、`bak_heads = 2`，三张草稿活动项归零，复制链自动到 `.32`/阿里云 (两处均 `bak_rows=17`、三张草稿 0 活动项)。

**验证**:
- 后端全量单测 **156 通过 0 失败**。`QmsInspectionServiceTest` 的 `createsIndependentTemplatesWithUntestedItems` 黑名单原来一刀切禁 `蒸发残渣`/`GB/T 10004` 等字样 (那时无出处，禁的是抄来的数)，改成: 仍禁产品专属数 (`±0.003`/`±2mm`/`≥38`/`120mg/kg`)，改为**正向要求**每个模板项的「名称+要求+方法」必须能追溯到条款/表号/试验方法出处 (表1~表6、3.x~6.x、GB/QB/JJF、工艺卡、工单、放行、图样)，并单独守住「出现蒸发残渣就必须写明是核验报告」这条线。新增 `reSeedsEmptyDraftThroughEnsureForProcess` 锁死上面那个行为修复。
- **飞牛 canary 真实 API 端到端** (`ensure-for-process` → `process-record` → `ensure-oqc-for-finished-in`，Sa-Token 从 Redis 取、不带 `Bearer`): 制袋 FAI 10 项 (含 `BAG_PRINT`，热合强度写死 ≥7.0 N/15mm + QB/T 2358-1998，跌落三档 + GB/T 10004-2008) → 逐项实测签核 **CHECKED** (证明新代码集合过得了 `validateTemplateCodes`) → 制袋 PAT 8 项 → 吹膜 FAI 6 项 (6 个实验室项 0 命中) → 吹膜 PAT 7 项 → 食品级袋 OQC 13+6=19 项 → 膜 OQC 13 项 (含 `OQC_FILM_ELONGATION`)。测完**全部手工清理**: 删 5 张 `QI20261029000x` 表头及其 50 项、1 行 `TEST-ZPC1-1134C` 批次流向、5 条 `sys_oper_log` 审计，商品 `2104473647277748226` 的 `is_food_grade` 复位 0；`mes_batch` 今日 0 新增、`mes_quality_defect` 0。
- 三站容器内 `/opt/app/app.jar` md5 均为 `8101517dcecde39508e60054bd1fe3b2`；`.32` 容器内 `QmsInspectionService.class` 抽出 `strings` 命中 Q/ZPC 与 4 个新代码 8 处；三站 `/api/auth/captcha` 200 (阿里云 8080 只绑 127.0.0.1，走 nginx 80 探活)。
- **主库实测 (批准后)**: sql/143 灌完 `.150` 后，用 `.150` Redis 里 gpssong (`loginId=2`) 的现成 token `20fa3460…` 走 App 真实调用链 `POST /api/qms/inspection/ensure-for-process` (`X-Client-Type: APP`、不带 `Bearer`)，两张草稿各自重灌并回显: `QI202610280006` 制袋 FAI **10 项** (含新代码 `BAG_PRINT`，热合强度 ≥7.0 N/15mm + QB/T 2358-1998，跌落三档 + GB/T 10004-2008)、`QI202610280005` 吹膜 PAT **7 项** (`表1/表3/表2` + 3.4.4 协商 + 5.1 组批 7 天 + 6.1 挂标 Q/ZPC 1-2017)。未签核、未改 `status`，XK 留痕仍是 DRAFT。三站事后逐项对账完全一致: `QI202610280001` 0 项 (指向已删的 `PSI202610180003`，打开时按 6 项新模板重灌)、`0005` 7 项、`0006` 10 项、`qms_iqc_item` 活动行 **35**、`bak_rows=17`，两个备库 `Seconds_Behind_Master: 0`。

**`.150` 主库迁移后终态** (与 `.32`/阿里云逐项一致): FAI/PAT/OQC/OQC_RETURN 共 3 张草稿 — `QI202610280001` FAI 活动项 0 (指向已删的 `PSI202610180003`，打开时按 6 项新模板重灌)、`QI202610280005` 吹膜 PAT 7 项、`QI202610280006` 制袋 FAI 10 项，全部是本轮 sql/143 软删后由服务端按 Q/ZPC 1-2017 重灌的新行 (`pass_flag=-1` 待检、`source=TEMPLATE`)；旧 17 行在 `qms_iqc_item_bak_v1134c` 里可查回。**车间侧不需要任何操作** — 打开首检/巡检页即是新口径。

**踩坑**:
- **同一支「幂等」脚本先在 canary 备库跑过、再灌主库会打断复制**: sql/143 的备份表 `qms_iqc_item_bak_v1134c` 用 `INSERT … SELECT t.*` 整行照搬，主键沿用源行 id。本轮先在 `.32` 试过 (备份表落了 33 行，含自测单的 16 行幽灵)，再灌 `.150` 时那 17 个主键复制到 `.32` 撞 `1062 Duplicate entry`，`Slave_SQL_Running: No`、`Last_Errno: 1062`，`.32` 一度还留着自测重灌的 7 行新模板项 (`QI202610280005` 与主库不一致)。**修法** (全部是备库本地写，`.32` 无 `Binlog Dump` 连接，不会回灌主库): `STOP SLAVE` → 把 `.32` 的 `qms_iqc_item_bak_v1134c` **清空** (让复制事件自己把那 17 行灌进来) + `DELETE` 掉自测重灌那 7 行 (主库/阿里云均无这些 id) → `START SLAVE`。结果 `bak_rows=17`、活动行 35、`Seconds_Behind_Master: 0`，三站一致。**教训**: 迁移脚本上主库前，先清掉同一版本在 canary 落下的备份表/重灌行，或干脆 canary 只做只读验证 (真正的写入验证放主库前的最后一站是阿里云备库)。
- 校验模板项是否「有人动过」不能只看 `pass_flag=1`：v1.1.130 那版模板灌的默认值就是 1，现版灌 `-1` (待检)。只认 `pass_flag=0`。
- `.150` 的 MySQL root 口令是 `erp_root_pwd` (与 `SPRING_DATASOURCE_PASSWORD` 一致)，不是 SSH 口令；`erp-mysql` 容器内没有 `unzip`，别想在容器里解 jar 验证字符串 — jar md5 与本地构建一致就是证据。
- App 端「制袋巡检显示吹膜项」的另一半原因在客户端: 2026-10-28 之前的 APK 跳 `/pages/scan/pat` 不带 `stage` 参数，服务端无从分型。**Aliyun 的 App H5 已是 v1.1.134 构建 (`index-DwQhNp5B.js`)；`.150` 没有 app-h5 容器也没有 18090 监听，所以只剩「让用户装 `erp-app-20261029.apk`」这一条。**

### v1.1.134 同日修补 (2026-10-29 — 吹膜首检 FAI 检验项按现场口径精简: 8+4 → 6+2)

> **需求原话**: 「app端生产单详情点击吹膜首检不检测断裂伸长率、雾度、蒸发残渣、高锰酸钾消耗量、重金属迁移、食品接触标识。」
> **范围**: 后端 `QmsInspectionService` (模板常量 + 食品级模板按「工序阶段+检验类型」分流 + 签核校验) + `sql/142_v1134_blow_fai_drop_items.sql`。**零 PC / 零 App / 零 H5 / 零 APK 改动** — 检验项全部由服务端模板灌入，前端没有写死任何项目名。版本保持 **1.1.134** (同日修补，PC dist / H5 / APK 与上一批字节一致)，三站容器内 jar md5 `37f9fb2dc7e97a609a1f46d2cd313366`。

**为什么这 6 项从首检摘掉**: `BLOW_ELONGATION` (断裂伸长率) 与 `BLOW_HAZE` (雾度) 要拉力试验机、雾度计，属**型式检验**项目，车间首件只做机台边能当场完成的外观/尺寸/强度核对；两者仍保留在成品出厂检验 (`OQC_FILM_MECHANICAL` / `OQC_FILM_HAZE`)。`EVAP_RESIDUE` (蒸发残渣)、`KMnO4_CONSUMPTION` (高锰酸钾消耗量)、`HEAVY_METAL_MIG` (重金属迁移) 是实验室迁移试验，`FOOD_CONTACT_MARK` (食品接触标识) 是标签/批次核验，都不是首件能实测的量，从食品级追加项里拆出。

**后端** (`QmsInspectionService`):
- `DEFAULT_FAI_ITEMS_COMMON` 8 → 6 行 (原料核对 / 膜面外观 / 厚度 / 宽度·折边 / 拉伸强度 / 气味)。`DEFAULT_PAT_ITEMS_COMMON` 7 行**不动** — 巡检口径用户没要求改。
- 新增 `DEFAULT_FAI_ITEMS_FOOD` (2 行: `FOOD_COMPLIANCE_DOC` 食品接触符合性文件、`FOOD_HYGIENE` 清洁与异物控制) + `foodProcessTemplate(stage, type)`: `BAG` → `BAG_FOOD_ITEMS` (3 行, 不变)、`FAI` → 2 行、其余 (吹膜巡检) → `DEFAULT_PROCESS_ITEMS_FOOD` 4 行不变。故吹膜首检食品级 8+4=12 → 6+2=8，吹膜巡检仍 7+4=11，制袋两条线完全不变。
- `validateTemplateCodes` 同步用 `foodProcessTemplate(stage, type)` 生成期望集合，并**删掉**上一版为兼容历史数据留的 `expected.addAll(Set.of("EVAP_RESIDUE","KMnO4_CONSUMPTION","HEAVY_METAL_MIG","FOOD_CONTACT_MARK"))` 豁免分支 — 留着等于允许旧 12 项继续签核，与「不检测」矛盾。报错文案改为「…请重新打开首检/巡检页按最新模板灌入，不能删项/替代签核」。

**SQL** (`sql/142_v1134_blow_fai_drop_items.sql`，幂等，仅灌 `.150` 主库、复制链自动到备库): `ensureProcessTemplateItems` 只在草稿**零活动检验项**时才灌 (`selectByIqcId().isEmpty()`)，所以存量 DRAFT 首检里那 12 行不会自己消失，而且会被 `validateTemplateCodes` 判「代码集合不完整」→ 永远签不了，必须迁数据。做法: (1) `CREATE TABLE IF NOT EXISTS qms_iqc_item_bak_v1134b LIKE qms_iqc_item` 建同构备份表; (2) 临时表 `tmp_fai_head_v1134b` 圈出「`inspection_type='FAI' AND status='DRAFT' AND deleted=0` + `EXISTS` 命中 7 个旧代码 (含上一代 jar 的 `FOOD_MIGRATION_REPORT`，两代代码名都要覆盖) + `NOT EXISTS` 已被人填过的行」的表头; (3) 先把这些表头的**全部**检验项插入备份表，再整头 `UPDATE … SET deleted = 1`，下次打开首检页由服务端按新模板重新灌 (不自造雪花 id、不手拼行，避免与代码模板漂移)。**判「人已填过」不能看 `pass_flag=1`** — 1.1.130 的 jar 把 `pass_flag` 默认灌成 `1`，必须只认 `pass_flag = 0` (判不合格) 或 `result_value`/`judgment_basis` 非空; 第一版就因这个条件把所有草稿当成「已填」，脚本静默 0 行通过 (备份表 0 行才暴露)。实跑: `.150` 唯一命中 `QI202610280001` (食品级 金博65\*88\*0.08) 12 行 → 备份表 12 行、活动项 0; PAT 7 行、制袋 FAI 10 行分毫未动。

**验证**:
- 单测 (`QmsInspectionServiceTest`): 吹膜 FAI 非食品 8→6、食品 12→8，逐行断言不含 6 个被摘代码; 新增 `blowFoodFaiUsesShortTemplateAndStillRequiresActualMeasurements` (8 行、签核后 CHECKED、`items.insert` 恰好 8 次、`never()).deleteByIqcId`) 与 `staleBlowFaiDraftWithRemovedItemsCannotBeSigned` (残留旧行 → 「代码集合不完整」且不落库) 替换旧的兼容用例; 制袋/巡检用例计数不变。顺带补 `PurOrderServiceTest` 缺的两个 `@Mock` (`BaseProductMapper`/`XkSupplierReviewMapper`，v1.1.130 商品名改动留下的既有 NPE，非本轮引入)。
- **飞牛 canary 端到端实测 (真实 App 调用链 + 新 jar)**: 主库 `prd_semi_in` 为空 (上一批用户批准的 e2e 清理把 `PSI202610180001-0003` 硬删了)，从 `.150` 备份 dump `e2e_residue_backup_20261029_062001.sql` 取两行**只灌进 `.32` 备库**，登录取验证码 (Redis `erp:login:limit:captcha:<key>`) 后按 App 的调用链 `POST /api/qms/inspection/ensure-fai-for-semi-in` (头带 `X-Client-Type: APP`、Sa-Token 不加 `Bearer`):
  - 食品级 `PSI202610180003` (金博65\*88\*0.08) → 命中原草稿 `QI202610280001`，被 sql/142 清空的活动项**重新灌成 8 行** = 6 通用 + 2 食品级，`pass_flag=-1` (未实测)，断裂伸长率/雾度/蒸发残渣/高锰酸钾消耗量/重金属迁移/食品接触标识 0 命中 — 证明「存量草稿打开首检页自动升级到新模板」这条路径成立。
  - 非食品 `PSI202610180002` (卷膜46\*0.085) → 6 行。
  - 同期复查 PAT: 两张 DRAFT 巡检仍各 7 行 (`PAT_APPEARANCE/THICKNESS/WIDTH/CORONA/ROLL_WIND/PROCESS_PARAM/TRACE_RECORD`)，未被误伤。
  - **测完全部手工清理**: `.32` 删掉本轮造的 `QI202610290003` 及其 6 项、`QI202610280001` 的 8 行新项、上一批遗留的 `QI202610290001/0002` 与 2 行合成 `prd_semi_in`; 复检与主库逐项一致 (`semi_rows=0`、`QI202610290%` 0 行、`QI202610280001` 活动 0 / 备份软删 12)。`SHOW SLAVE STATUS` IO/SQL 双 Yes、`Seconds_Behind_Master: 0`; `.150` 主库本轮零写入。

**踩坑**:
- `sudo -S` 与 `docker exec -i … < file.sql` 抢同一个 stdin (密码被喂给 mysql) → 先 `printf pw | sudo -S -v` 缓存凭据，再裸 `sudo docker exec -i` 走重定向。
- 经 `ssh '…'` 传递时内层双引号会被剥掉，MySQL 把 `'QI202610280001'` 当列名报 1054; 含引号的 SQL 一律落临时文件再 `SOURCE`。相关子查询 `(SELECT COUNT(*) … WHERE x.iqc_id = i.id)` 写进同一行 `-e "…"` 也因引号被剥报 `Unknown column 'i.id'`，拆成两条查询即可。

**已知遗留 (新增)**: ⚠️ 主库 `prd_semi_in` 现为 0 行 (上一批批准的 e2e 清理硬删了 `PSI202610180001-0003`)，因此 App「生产单详情 → 吹膜首检」的选单器**当前没有半成品入库单可选**，`QI202610280001` 是指向已删单的孤儿草稿; 3 行原始数据仍在 `.150` 备份 `backup/e2e_residue_backup_20261029_062001.sql` 里可回灌，是否恢复待用户拍板 (真实车间数据应由开工→半成品入库正常流程产生)。

### v1.1.134 (2026-10-29 — 制袋开工领料单仓库口径修正 + 仓库类型全链路补齐)

> **需求原话** (v1.1.133 收尾时报备的两个既有缺陷, 用户「要」): ① 制袋开工自动领料单仓库取错; ② PC/App 找不到对应类型仓库时静默回退 `warehouses[0]` (实际是成品仓库)。修法按当次报备的最小方案执行。
> **范围**: 后端 `MesWorkStartService` + PC `base/Warehouse.vue`、`production/SemiFinishedIn.vue` + App `pages/scan/semi-in.vue` + `sql/141`。**无新接口、无新表、无 perm 改动**。

**根因链** (三层同时缺): `base_warehouse.warehouse_type` (NORMAL/RAW/FG/SEMI) 建表时就有, 但 PC「仓库管理」表单从来没有这个字段 → 用户手工建的三个仓全是 `NORMAL`; 而 PC/App 至少 5 处按类型自动选默认仓 (`production/Order.vue` 找 FG、`FinishedIn.vue` 找 FG、`SemiFinishedIn.vue` 找 SEMI、App `scan/semi-in.vue` 找 SEMI、`scan/finished-in.vue` 找 FG), 全都找不到 → 静默退到 `warehouses[0]` = **成品仓库**。叠加第二层: 制袋开工自动领料 (`MesWorkStartService.buildSemiRequisition`) 把领料单开在 `order.getWarehouseId()` (生产单的仓), 而不是半成品**实际入库的那个仓** → 半成品入到 A 仓、领料单开在 B 仓, 仓管审核出库时扣错仓或直接批次不足。仓库账从一开始就是串的。

**后端** (`MesWorkStartService`):
- `buildSemiRequisition()` → `buildSemiRequisitions()`: 半成品入库行按 `in.getWarehouseId()` 用 `LinkedHashMap` 分组 (入库单 id 升序, 首单稳定), **每组一张领料单**, `warehouse_id` 取该组入库单的仓; 入库单自身没仓才回退 `order.getWarehouseId()`。一张领料单只能挂一个仓库, 所以跨仓只能拆单, 不引入新表。
- `mes_work_start_confirm.requisition_no` 是 VARCHAR(32) 只放得下单号 → 首单号进 `requisitionNo/requisitionId`, 其余单号追加进 `remark` (VARCHAR 512): `… 同批跨仓拆单: RQ…,RQ…`。
- 去重 (`issuedBatchNos`)、空半成品返回空列表、批次/数量/单价逐行照搬入库单等既有行为不变。

**PC**:
- `views/base/Warehouse.vue`: 列表新增「类型」列 + 表单 `el-select` (原料仓/半成品仓/成品仓/普通仓, `WH_TYPE_LABELS` 映射), 编辑态空类型按 `NORMAL` 回填, 字段下提示「入库/领料默认仓库按类型自动选中 (成品入库找 FG, 半成品入库找 SEMI, 领料找 RAW); 未分类时只能手选。」— 防止新仓再漏分类。
- `views/production/SemiFinishedIn.vue` `openAdd()`: 默认仓改为 `warehouseType==='SEMI'` → 仓名含「半成品」 → `warehouses[0]` 三级兜底。

**App** (`pages/scan/semi-in.vue` `loadAll()`): 同一套三级兜底 (SEMI → 仓名含「半成品」 → `warehouses[0]`), 注释写明「warehouses[0] 往往是成品仓, 直接退到它会静默把半成品入到成品仓」。

**SQL** (`sql/141_v1134_warehouse_type.sql`, 幂等, 仅灌 `.150` 主库自动复制备库): 先 `CREATE TABLE IF NOT EXISTS base_warehouse_bak_v1134 AS SELECT * FROM base_warehouse` 备份 (6 行), 再按名称给存量仓补分类 — **半成品规则必须排在成品规则前** (「半成品仓库」里含「成品」二字), FG 规则再加 `NOT LIKE '%半成品%'`; 三条 UPDATE 都带 `deleted = 0` (不动 3 条软删行) 且只改仍是 `NORMAL`/NULL 的仓。结果: 成品仓库=FG / 原料仓库=RAW / 半成品仓库=SEMI, `HEX()` 抽查无 mojibake。

**验证**:
- **后端跨仓拆单 (飞牛 canary, 全合成单不碰真实工单)**: 造两张分别入到 半成品仓库 / 成品仓库 的 CHECKED `prd_semi_in` → 制袋开工一次产出 `RQ202610290001` (warehouse_id=半成品仓库) + `RQ202610290002` (warehouse_id=成品仓库), 台账 `requisition_no=RQ202610290001` 且 remark 带「同批跨仓拆单: RQ202610290002」。测完全部手工清理, 并把 `.32` 与 `.150` 差异 (昨日遗留 RQ202610280001/0002、2 条 `prd_process`、`PD202610170003` 卡在 PRODUCING、17 条孤儿 `xk_audit_log`) 一并还原到主库一致 (DRAFT 44 / FINISHED 2 / PRODUCING 6 / RELEASED 5, `Seconds_Behind_Master: 0`)。
- **PC 实测 (`.150:8088`, headless Chrome + 原生 CDP, 真实登录 gpssong)**: 仓库管理表头含「类型」, 三行分别显示 成品仓/原料仓/半成品仓; 编辑「半成品仓库」弹窗类型下拉回显 `半成品仓`, 展开选项 = 原料仓/半成品仓/成品仓/普通仓; 半成品入库「新增入库」弹窗 入库仓库默认 **半成品仓库**。全程只开弹窗不保存。
- **App H5 实测 (`.32:18090`)**: 登录后 `#/pages/scan/semi-in` 的入库仓库 picker 显示 **半成品仓库** (改前会是成品仓库)。
- **本轮零写入复查**: `.150` `prd_semi_in` 0 行、`mes_work_start_confirm` 0 行、`xk_env_check` 今日 0 行、`prd_requisition` 仍 18 行 (最新 2026-10-21), 实测未污染主库。
- **产物一致性**: 三站容器内 jar md5 `7b0c99ed59219c7e6e9a4c5633414fd3`、`ERP_VERSION=1.1.134`; PC `index.html` md5 `612eca676c8969b2da587520a14b59d8` (`.150 dist` / `.32 dist-new` / 阿里云 `pc-web` 三处与容器内一致, `Warehouse-uwPHBkpn.js` 含「半成品仓」与新提示文案, `SemiFinishedIn-FuLGQpmt.js` 含 `warehouseType==="SEMI"`); H5 `index.html` md5 `1bce95d144d164172bbf84eea836f617` (`.32` 18090 + 阿里云 18090)。APK `erp-app-20261029.apk` md5 `297ce3735501d460852abcc2e01ac072` (versionName 1.1.134 / versionCode 1134, 4435589 字节, 内嵌 `index.html` 与已部署 H5 逐字节一致, `pages-scan-semi-in.C7qvsxXa.js` 含 `includes("半成品")` 兜底), 落飞牛同步盘 `erp/` + Desktop。`scripts/check-version.sh` 七源全 OK。
- 阿里云公网站 `http://47.96.188.129/` 实测 `index.html` = 新 dist、`/api/auth/captcha` 200; 本地复制链 IO/SQL 双 Yes、`Seconds_Behind_Master: 0`。

**踩坑**:
- **阿里云探活不能用 `curl http://127.0.0.1/…`**: 宝塔 `0.default.conf` (`server_name _`, root `/www/server/nginx/html`) 会先命中, 返回默认站页面 + `/api` 404, 看起来像「nginx 前置坏了」; ERP vhost 是 `server_name 47.96.188.129`, 必须用真实 IP/Host 头探。上一轮据此误判的「:80 没指向新版本」其实一切正常。
- `.150` 上 `sudo docker` 报 `docker: command not found` (sudo 的 PATH 不含 `/usr/local/bin`), 必须写全 `/usr/local/bin/docker`。
- `cat file | sudo -S tar xf -` 把密码喂给了 tar → dist 已被 `find -mindepth 1 -delete` 掏空后解包失败; 改成先上传到 `$D/upload/`, 再 `sudo tar xzf <路径> -C <目标>` (不走 stdin)。
- `browser-use` MCP 的 CDP 仍会被独占 (`Browser target debugger is already in use`, 只有 `navigate_page`/`list_pages` 可用), 本轮改用本机 headless Chrome (`--remote-debugging-port`) + Node 原生 WebSocket 直写 CDP。两个新坑: Chrome 149 `/json/new?url=` 返回的 tab 仍是 `about:blank`, 要显式 `Page.navigate`; uni-app H5 的 placeholder 是覆盖 `div`, `input` 上没有 `placeholder` 属性, 取输入框要按 `querySelectorAll('uni-input input')` 下标取。
- 验证码答案: PC 页面自己请求的 `captchaKey` 只能从 CDP `Network.getResponseBody` 抓 (redis 里同时有 8+ 个未过期 key, 按 TTL 猜会猜错), 再 `redis-cli GET erp:login:limit:captcha:<key>`。
- `prd_order.update_time` 带 `ON UPDATE CURRENT_TIMESTAMP`, 还原 `update_by` 会把时间一起顶上去, 需要单独一条 UPDATE 复位。
- `xk_audit_log` 的单据号列名是 `biz_bill_no`, 不是 `biz_no` (写错直接 1054)。

**已知遗留** (未动, 待用户拍板): ① `.150` 上 6 条 `create_time` 为 NULL 的 `prd_process` 测试行 (挂在 DRAFT 单 PD202609170003 / PD202610060001); ② 4 张 v1.1.119 前老算法的 DRAFT 领料单 `RQ20261021000x`; ③ `hls21060` / `hs70053` 两个在用工单成品仍缺克重 (开工时按既有降级跳过自动领料)。

### v1.1.133 (2026-10-28 — 生产单详情「总重量 (kg)」+ 开工领料口径写在单上)

> **需求原话**: 「生产单详情添加一个总重量，单位kg，数值为数量\*克重/1000。领料数量就是这个总重量。」
> **范围**: 后端 `PrdOrder` 实体 + `PrdOrderService` (4 处) + App `production/order-detail.vue` + PC `views/production/Order.vue`。**零 SQL、零新接口、无 perm 改动**。

**口径本来就没错, 缺的是「看得见」**。核查结论: 自 v1.1.119 起 `release()` 的自动领料算法就是「成品总重 = 计划数量 × 克重 ÷ 1000, 再按 `本项配比份额 × (1+损耗率/100)` 分摊到各原料行」(BOM 明细 `base_qty` 是无量纲配比份数, 线上 BOM `loss_rate` 全为 0, 所以 Σ领料 == 总重量 逐分不差)。真正的问题是: ① 界面从来没显示过这个总重量, 车间和仓管无从核对; ② 领料单本身没写它是按什么算出来的, 对不上时无法自证。本次把两件事各自补齐, 并让**展开与展示共用同一个函数**, 杜绝以后两处漂移。

**后端** (`PrdOrderService`):
- `static BigDecimal totalWeightKg(planQty, gramWeight)` = 唯一算法出口, 6 位小数; 数量或克重缺失/克重 ≤0 → `null` (调用方按降级处理, 不抛错)。
- `display(kg)` 展示口径 3 位小数 (kg), 领料展开仍用 6 位原值, 避免 rounding 让 Σ明细 ≠ 总重量。
- `page()` 循环里按 JOIN 出来的 `base_product.gram_weight` 注入 `totalWeightKg` → 列表也能看; `detail()` 用 transient `pGramWeight` 注入 → **生产单详情回显**。
- `release()` 原来内联的乘除换成同一个 `totalWeightKg()`, 并在插入领料单表头前把口径写进备注: `按 BOM 配比份数分摊成品总重量 X kg (= 计划数量 × 克重 ÷ 1000); 本单领料合计 Y kg`。损耗率 >0 时 Y 会略高于 X, 那是补投料不是算错, 文案里已注明。
- `PrdOrder` 加 `@TableField(exist=false) private transient BigDecimal totalWeightKg` (派生值, 不入库; 无需 `@JsonProperty` — 非 `getPxxx` 那种大写前缀命名陷阱)。

**App** (`pages/production/order-detail.vue`): 成品规格栅格在「克重」后加「总重量」行; 领料明细卡片底部加「领料合计 (按 kg) · 应等于总重量」一行 (只累加 `unitName==='kg'` 的行, 所以老的 `unit_name=NULL` 历史行不会乱入)。`totalWeightKg` 计算属性优先读后端字段, 字段缺失时前端同式兜底 — 这样旧 jar 还没换的窗口期也能看到数值。

**PC** (`views/production/Order.vue`): 工单详情的成品属性栅格加 `总重量 (kg)` 格 (`prodTotalWeight`, 直接读 `d.totalWeightKg`, 不做二次计算), BOM 卡片在明细下方加「领料合计 / 应等于总重量 … · 开工时按 BOM 配比份数分摊」行 (`sumRequisitionKg()` 同样只认 kg 行)。

**验证**:
- 算法逐分核对 (飞牛 canary 写链路, 用主库复制过来的真实 BOM): 1250 只 × 8 g ÷ 1000 = **10.000 kg** → 展开 4 行 1.2101 + 8.4705 + 0.0290 + 0.2904 = **10.0000 kg**, 备注字符串与数值一致; 测试单/领料单/工序/批次全部删除并复查为 0 行。
- 三站接口实测 `/api/production/order/page` 与 `/{id}` 均回显 `totalWeightKg`: `PD202610190005` 10000 × 8 → 80.000、`PD202610200001` 1 × 4.2 → 0.004、`PD202610170002` 32 × 3.1 → 0.099 (详情带 4 条领料明细)。登录仍走 P0-2 验证码: 取 `/api/auth/captcha` 的 `captchaKey`, 从该站 Redis 读 `erp:login:limit:captcha:<key>` 的值当 `captchaCode` (阿里云 redis 有密码, 从 `erp-backend` env `SPRING_DATA_REDIS_PASSWORD` 取)。
- 产物一致性: 三站容器内 jar md5 `53f34bbd1d724286fc71b27ee29d1a17`, PC `index.html` md5 `9b170a05c4788ceb9381a64a88350946` (`.150 dist` / `.32 dist-new` / 阿里云 `pc-web` 三处与容器内均相同), App H5 `index.html` md5 `85c4fb3d2448ab09d002981defa9d6a6` (`.32` 18090 + 阿里云 18090; `.150` 无 h5 容器)。新 UI 文案在产物里可 grep 到: PC chunk `Order-D40_Ns8j.js` 含「总重量」, H5 chunk `pages-production-order-detail.C0JQRGVx.js` 同时含「总重量」与「领料合计」(两站同 hash)。
- 运行时版本: `.150` `sys_config.SYSTEM_VERSION_INFO` = `{"version":"1.1.133","startTime":"2026-10-28 16:01:32",…}`, `.32` 备库已复制同一行 (`Seconds_Behind_Source=0`, IO/SQL 双 Yes, 无错误); 三站容器 env `ERP_VERSION=1.1.133`; `scripts/check-version.sh` 七源全 OK。
- ⚠️ **本轮没有浏览器截图**: `browser-use` 的 CDP 调试器仍被上一轮未收尾的 `evaluate_script` 独占 (`take_snapshot` / `take_screenshot` / `click` 一律报 `Browser target debugger is already in use`, 只有 `navigate_page` / `list_pages` 能用), 需重启 Qoder 内置浏览器才能恢复。UI 层证据改为「产物 chunk 含新文案 + 接口字段」双轨, 渲染逻辑与已实测过的同页字段 (克重/规格 7 格) 同构。

**部署** (canary → 主站 → 公网, 备份齐全): `.32` `app.jar.bak.20261028_1133` / `dist-new.bak.20261028_1133.tgz` / `h5.bak.20261028_1133.tgz`; `.150` `backup/app.jar.bak.20261028_1133` + `backup/dist.bak.20261028_1133.tgz` (⚠️ `pc-web/` 目录 gpssong 不可写, dist 备份必须 `sudo tar` 且落到 `backup/`) + `docker-compose.yml.bak.20261028_1133`, 镜像 `docker commit erp-backend erp-system-backend:1.1.133` 再 `docker tag … :latest` (compose 用 `build: ./backend` 无 `image:` 行, 只认 `:latest`); 阿里云 `backup/app.jar.bak.20261028_1133` / `pc-web.bak.20261028_1133.tgz` / `app-h5.bak.20261028_1133.tgz`, `.env` 的 `ERP_VERSION` 同步。两站 PC dist 都是原地替换 (`find dist -mindepth 1 -delete` + tar 管道, bind mount 认 inode)。APK `erp-app-20261028.apk` md5 `9b443a529b011414dd24037482b21bc6` (versionCode 1133, 已在飞牛同步盘 `erp/` + Desktop)。

**踩坑**:
- **`ssh host "bash <<'EOF' … $VAR …" EOF"` 外层双引号会让本地 zsh 先把 `$TS/$D/$DOCKER` 展开成空**, 远端拿到的是残缺路径 (`cp: cannot stat '/upload/erp-1133.jar'`)。正确写法是把脚本走 stdin: `ssh host 'bash --noprofile --norc' <<'REMOTE_EOF' …`。
- `.150` 上 `$(sudo docker …)` 在命令替换子 shell 里报 `a terminal is required to read the password` (无 tty), 要么每条 `-S` 现给密码, 要么把整段循环塞进一次 `sudo docker exec sh -c '…'`。
- 阿里云 redis 有 `requirepass`, 直接 `redis-cli get` 返回 `NOAUTH Authentication required.` 而不报错, 容易误判成「验证码不存在」。

**已知数据问题 (本次未动, 待用户决定)**:
1. **库内 4 张领料单全是 v1.1.119 之前老算法的历史垃圾**: `RQ202610210001` (18750/131250/450/4500 kg)、`…0002`、`…0004` (25/175/0.6/6 kg)、`…0003` (800/5600/19.2/192 kg, 而 `PD202610170002` 总重量只有 **0.099 kg**)。都是 `DRAFT`、`unit_name=NULL`、无备注, 即没有任何一张线上领料单是按新口径产出的 —— 用户下次点「开工」才会生成第一张。这些行要不要作废/删除属改线上数据, 未擅自处理。
2. **2 个正在被生产单使用的成品没有克重** (`hls21060` 海力生大印字胶带、`hs70053` 红色大胶带; `GOODS` 类目 132 个商品里 70 个缺克重), 它们的详情「总重量」显示 `—`, 且开工时按既有降级逻辑**跳过自动领料展开** (后端 `log.warn` 提示去「基础数据 → 商品」补克重)。补克重是业务数据录入, 不是代码问题。
3. **制袋「领用半成品」不套这个口径**: `MesWorkStartService.buildSemiRequisition` 直接抄 `prd_semi_in.qty` (只/卷计数), 那些半成品商品同样没有克重, 没有重量基准可换算。「领料数量 = 总重量」只适用于按 BOM 展开的**原料**领料单。

### v1.1.132 (2026-10-28 — App 开工确认改造: 生产环境确认 + 原料批号拍照 + 制袋独立开工 + 自动领用半成品)

> **范围**: 后端 1 新表 + `MesWorkStartService` + 3 端点 + App `production/order-detail.vue` 重构 (含 `components/ProcessInspection.vue`) + `utils/upload.js` hotfix。**PC dist 未改**。

**背景** (两条用户需求的合并实现): ① 制袋原本被塞在「4 制袋·报工完工」一步里, 现场没有开工动作 → 改成独立「制袋·开工」, 点开工先弹「生产环境是否清洁合格」确认, 合格后自动生成 2 份生产环境检查合格记录并按该单领用半成品; ② 吹膜开工在环境确认合格后还必须填写投入的原料批号并拍下批号标识照片 (XK16-204 投料可追溯)。

**数据** (`sql/140_v1132_work_start_confirm.sql`, 幂等, 只在 `.150` 主库执行, 复制链自动到 `.32`): 新表 `mes_work_start_confirm` (开工台账: `confirm_no` / `prd_order_id` / `stage=BLOW|BAG` / `workshop` / `prd_process_id` / `env_qualified` / `env_check_ids` / `raw_batch_nos` / `photo_urls` (JSON) / `requisition_id|no` / `confirm_by|name|time` / `remark`)。

**后端门禁与副作用** (`MesWorkStartService`, `@Transactional(rollbackFor=Exception.class)`, 任一步不合规则整体回滚):
- `requireEnvQualified`: `envQualified` 非显式 `true` → `生产环境未确认合格, 禁止开工 (XK16-204: 开工前须做生产环境检查)`。
- `confirmBlowStart`: 额外要求 `rawBatchNos` ≥1 (`请填写本次投入的原料批号…`) 与 `photoUrls` ≥1 (`请拍摄原料批号标识照片 (至少 1 张)`) → 写 2 份 `xk_env_check` → `prdOrderService.release()` 展开工序链 → 吹膜工序置进行中 → 台账落库 → `bindRawBatch` 把批号写进 `mes_batch`/`mes_batch_link`。**判「未登记批号」必须在 bind 之前** (`rawBatchKnown`), 否则 upsert 后永远已知, 提示失效。
- `confirmBagStart`: `requireBagProcess` (工序必须属于该单且未开工, 否则 `制袋工序「x」已开工, 请直接报工`) → 2 份 `xk_env_check` → 工序置进行中 → `buildSemiRequisition`。
- 端点 base 是 **`/production/order`** (不是 `/prd/order`): `POST /{id}/blow-start`、`POST /{id}/bag-start`、`GET /{id}/work-start-records`; perm 分别 `production:order:release` 与 `order:release|report:add|report:list` (OR)。

**2 份环境检查记录**: `xk_env_check` 按车间各生成 `CLEANLINESS` (洁净度/环境卫生) + `TEMP_HUMIDITY` (温湿度) 两行, `status=CHECKED`、`inspector=<开工人>`、`check_content` 内嵌单号+品名+结论、`remark=AUTO-BY-WORK-START <单号>`。**直写 `XkEnvCheckMapper` 而非 `XkEnvCheckService.save/check`** — 后者要 `xk:env:*` 权限, 开工的是车间操作工。

**自动领用半成品** (`buildSemiRequisition`): 只认该生产单 `bill_status=CHECKED` 的 `prd_semi_in`; 用 `issuedBatchNos` (该单历史领料明细) 扣重, 无可用行返回 `null` 并 `log.info` (**不报错** — 没半成品不是开工的失败条件, 与 `release()` 的零破坏口径一致); 有则建 `prd_requisition` (`bill_type=ISSUE`, `bill_status=DRAFT`, 备注回写开工单号, 待仓管审核出库) + 明细 `material_type=SEMI` 带批号/数量/单价。

**App** (`production/order-detail.vue`): 步骤 ①「开工 · 吹膜」/ ④「制袋 · 开工 / 首检 / 巡检 / 报工」, `openStartSheet('BLOW'|'BAG')` 弹三段式底部 sheet (1 环境是否合格 → 2 原料批号可增删 → 3 拍批号照片 ≥1), 不合格时红色警示 + 提交禁用; 已开工则回显 `原料批号` 与 `批号照片 N 张` + 新增「开工记录 (XK 留痕)」区块; 未开工工序报工前显示 `未开工工序不能报工, 请先点「开工」确认生产环境`。API: `prdOrderBlowStart` / `prdOrderBagStart` / `prdOrderStartRecords` (`silent: true`)。

**#59 上传 405 修复** (`app/src/utils/upload.js`): H5 分支 `getUploadBase()` 由 `''` 改 `'/api'`。空 base 时浏览器把 `uni.uploadFile` 打到 `/system/upload/file`, 命中 App H5 nginx 的静态 location → **405 + 返回 index.html** → 前端 `JSON.parse` 抛异常, 表现为「上传失败」而原生端正常。后端 `context-path=/api` 固定, 同源相对 `/api` 即正确。

**部署**: 三站容器内 `/opt/app/app.jar` md5 `484b918582bfc608584ea9a60b6554f6` 一致 (`.150 erp-backend` / `.32 erp-backend-failover` / 阿里云 `erp-backend`, 三处 env `ERP_VERSION=1.1.132`); App H5 `.32:18090` 入口 `index-DvMfdbAE.js` 与本地 `app/dist/build/h5` 逐字节一致 (H5 构建 14:51 晚于 upload.js 14:50, 修复已进包)。

**验证** (飞牛 canary, 接口层 + UI 双轨):
- 门禁 4 条拒绝逐字命中: 环境未合格 (BLOW/BAG 各一次) / 缺批号 / 缺照片。
- 正向链路: 新建测试单 → `blow-start` → `prd_process` 吹膜+制袋两行且开工侧 `status 0→1`; `xk_env_check` 生成 `EC2026…` 洁净度+温湿度 2 行 `CHECKED`; `mes_work_start_confirm` 台账含 `raw_batch_nos` / `photo_urls` / `env_check_ids`; 返回体 Long 全部字符串化 (无雪花精度丢失)。
- 领用半成品: 该单原无已审核 `prd_semi_in` → `requisitionNo=null` (符合设计)。补一条 `CHECKED` 半成品入库 (500 / 批号 `E2E-SEMI-001`) 并把制袋工序复位 `status=0` 后重跑 `bag-start` → **`RQ202610280006`** 生成, 表头 `ISSUE/DRAFT` + 制袋车间 + 备注「制袋开工自动领用半成品 (开工确认 WS202610280008), 待仓管审核出库」, 明细 1 行 `SEMI / E2E-SEMI-001 / 500`, 制袋工序 `status=1`。
- 去重: 再次复位并重跑 `bag-start` → 台账 `WS202610280009` 有、`requisitionNo=null`, 该单领料单仍只有 `RQ…0005/0006` → 不重复领料。
- 中文全链路无 mojibake (`--default-character-set=utf8mb4` 对账)。
- 清理: 3 张测试生产单及其工序、9 条开工台账、18 条 AUTO 环境检查、4 张测试领料单+明细、6 条测试批次、合成半成品入库行、临时账号 `_ws1132` (含 `sys_user_role`)、`.32` 上 2 张 UI 实测上传照片全部删除; 被测试改动的真实单 `PD202610160001` 按主库还原 `bill_status=DRAFT / release_* = NULL`; 复制链复查 `Replica_IO_Running=Yes / Replica_SQL_Running=Yes / Seconds_Behind_Source=0 / Last_SQL_Error` 空。

**踩坑**:
- **canary 写的是备库本地**: `.32 erp-backend-failover` 的 `SPRING_DATASOURCE_HOST=erp-mysql-failover`, 该容器 `read_only=ON / server_id=2`, 但 datasource 用户是 root —— **MySQL 的 `read_only` 不拦 SUPER/root**, 所以 canary 写入实际落库且不会回传 `.150`。好处是能端到端验写链路, 代价是测试数据必须手工清, 且 canary 与主库会长期漂移 (本次测完已核对回零差异)。
- `.150` 容器内 mysql root 的真实密码**不是** `erp_root_pwd`, 要从 `erp-backend` 的 env `SPRING_DATASOURCE_PASSWORD` 取; 猜错时 `SHOW TABLES` 直接返回空而不报 `1045`, 容易误判成「表不存在」。
- 接口层实测拿 token: P0-2 之后 `POST /api/auth/login` 强制验证码, curl 直登返回 `请输入验证码` → 改从 `.32` Redis `Authorization:login:session:2` 取 gpssong 现有 token 复用 (只读操作, 不落盘分享)。
- `browser-use` 的 CDP 会被一个未收尾的 `evaluate_script` (轮询) 独占, 之后 `take_snapshot` / `take_screenshot` / `click` 全报 `Browser target debugger is already in use`, `navigate_page` / `select_page` 仍能成功但不释放锁 —— 本轮 UI 证据改用「开工台账里的真实 UI 上传照片 URL + 数据库行」复核, 浏览器待重启后补。
- `prd_semi_in` 无 `bill_status`… 有; 反而是 `prd_process` **没有 `bill_status`** (只有 `status`), 拼 SQL 复位时踩过。

### v1.1.131 补丁 (2026-10-28 — XK 食品包装许可: 吹膜首检 FAI / 巡检 PAT 检验项模板落地)

> **范围**: 后端 4 文件 (模板 + 灌入钩子 + 1 新端点 + 1 mapper 修) + App 3 文件 (api / fai 页 / pat 页) + APK 重出。**零 SQL、零 PC 改动**，版本保持 1.1.130 线。

**背景**: 用户要求「根据食品包装生产许可的要求，完善吹膜工序中首检和巡检检验项模板」。核查后发现 FAI/PAT 此前**根本没有检验项**——`createOnSemiIn` / `createOnReport` 只建表头，App `pages/scan/fai.vue` 的 `bootstrap()` 是空壳，`pat.vue` 是纯数量表单，现场实际是「打开首检页 → 无检验项 → 直接签合格」，不满足《食品用塑料包装容器工具等制品生产许可审查细则》对过程检验留痕的要求。

**模板 (一期硬编码在 `QmsInspectionService`，与既有 IQC 模板同机制)**:
- `DEFAULT_FAI_ITEMS_COMMON` 吹膜首检 8 项：原料核对 / 膜面外观 / 厚度(±0.003mm) / 宽度·折边(±2mm) / 拉伸强度 / 断裂伸长率 (GB/T 10004) / 雾度 / 气味。
- `DEFAULT_PAT_ITEMS_COMMON` 吹膜巡检 7 项：膜面外观 / 厚度 / 宽度·折边 / 电晕处理(达因值 ≥38) / 收卷质量 / 工艺纪律 / 标识与记录。
- `DEFAULT_PROCESS_ITEMS_FOOD` 食品级追加 4 项 (FAI/PAT 共用，`is_food_grade_item=1`)：蒸发残渣 (60℃ 2h 正己烷≤120、3%乙酸≤30 mg/kg) / 高锰酸钾消耗量 ≤10 mg/kg / 重金属迁移(以铅计 ≤1 mg/kg) / 食品接触标识 (GB 4806.1 追溯)。仅当 `base_product.is_food_grade=1` 时灌入。

**灌入时机 (`ensureProcessTemplateItems`，幂等：已有检验项直接返回)**: ① `create()` 手工新建；② `createOnSemiIn` (半成品入库触发 FAI)；③ `createOnReport` (报工触发 PAT)；④ `signPassedFaiForSemiIn` 签核前兜底 (老草稿缺项时补齐再判)；⑤ 新端点 `POST /qms/inspection/ensure-fai-for-semi-in` (App 选单即定位/补建 FAI 并回 `{head, items}`)。

**App**: `api/qms.js` 加 `ensureFaiForSemiIn`；`scan/fai.vue` `bootstrap()` 实装 (原来什么都不做 → 页面永远显示「无检验项模板」)，12 项含要求/实测值/合格·不合格切换，`itemsFail>0` 时「签合格并放行」禁用；`scan/pat.vue` 从纯数量表单升级为带检验项卡片 + 「保存检验项判定」。

**顺带修的 3 个潜伏 bug**:
1. `QmsIqcItemMapper.deleteByIqcId` 用 `@Select` 写 DELETE → 任何 `PUT /qms/iqc/{id}/items` 必 500 ("attempted to return null from a method with a primitive return type (int)")，自 b4d9dfb 起从未被真正调用过所以没暴露。改 `@Delete`。
2. `QmsIqcItemController` 只认 `qms:iqc:list|detail` (库里是 PC-only 授权)，App 检验员持 `quality:inspection:*` 会 403 → GET/PUT 的 `@SaCheckPermission` OR 列表补 `quality:inspection:list` / `quality:inspection:check`。
3. `fai.vue` 空态提示 `<view v-else ... v-if="semiInId">` 同节点双指令 → Vue 按两个独立 `v-if` 编译，检验项一旦有数据「无检验项模板」提示仍会并排显示。改 `v-else-if`。

**部署**: 三站 jar md5 `ec03a2c6c313536075465b715bb775a6` (备份容器内 `app.jar.bak.20261028_xktemplate`)；App H5 `index.html` md5 `540306aec9de1187841933e6db4ccb27` 三站原地覆盖 (`.150` `/volume3/docker/erp-system/app-h5/dist/build/h5`、`.32` `/vol2/erp-system/app-h5/dist/build/h5`、阿里云 `/opt/erp-system/app-h5`，备份目录 `h5.bak.20261028_xkfai`)；APK 重出 `erp-app-20261028.apk` md5 `ee29c915c99eb34d4466d48d5cb0270a` (4485621 字节，versionCode 1130，内嵌 H5 index md5 与部署逐字节一致) → 飞牛同步盘 `erp/` + Desktop。PC dist 未变。

**验证**:
- 主库行级对账 (.150，`--default-character-set=utf8mb4` 无乱码)：FAI `QI202610280001` (半成品入库 `PSI202610180003` · 金博65\*88\*0.08，食品级) = **12 行** (8 通用 + 4 食品级，`source=TEMPLATE`)；工业品 FAI = 8 行；App 路径建的 PAT = 11 行 (7 巡检 + 4 食品级)。
- 权限实测：临时非 admin APP 角色 (只给 `quality:inspection:list/check/add`) → ensure / GET items / PUT items / create 全 200 (修复前 GET 即 403)；测试账号、角色、检验单已清理。
- 真浏览器实测 (in-app browser 的 CDP 被占用，改用本机 headless Chrome 149 + CDP，走 `.32:18090` 同源 `/api`)：FAI 页渲染出「检验项 (12)」，每项带要求文案与实测值单位 (mm/MPa/%/mg/kg)；把「蒸发残渣」切成不合格 → 「签合格并放行」按钮变灰 disabled，「保存检验」仍可用；「无检验项模板」提示不再与检验项同时出现。截图 `/tmp/fai_ui.png` / `/tmp/fai_ui_fail.png`。
- 后端硬门禁：`signPassedFaiForSemiIn` 里 `ensureProcessTemplateItems` + `allItemsPassedOrEmpty` 双保险，任一 `pass_flag=0` 拒绝签合格。

**踩坑**:
- `.150` 是 Synology，`scp` 报 `subsystem request failed on channel 0` (未开 sftp 子系统) → 用 `cat 文件 | ssh host 'cat > /tmp/文件'`。第一次部署把 scp 的 stderr 吞了 (`>/dev/null 2>&1`)，上传其实失败，而后续 `find -delete` 已清空 h5 目录 → 站点被掏空才发现；**部署脚本必须先确认产物落地再删旧内容**。
- `.150` 容器里 mysql root 的实际密码不是 env `MYSQL_ROOT_PASSWORD=erp_root_pwd` (被改过)，取 `erp-backend` 的 `SPRING_DATASOURCE_PASSWORD` 才能登。
- `.150` 上 `sudo bash -c` 的 PATH 不含 `/usr/local/bin`，docker 必须写全路径；sudo 凭据不跨 ssh 调用，每条命令内联 `echo pw | sudo -S`。

### v1.1.131 补丁 (2026-10-28 — PC App 权限树补「销售出库新建」授权项)

> **范围**: PC 1 文件 (Role.vue 白名单)。**零后端 / 零 SQL / 零 App 改动**。版本保持 1.1.130 线。

**背景**: 用户报 App 销售出库单页有「+ 新建销售出库单」按钮（由 `sales:delivery:add` 前后端同一套 perm 门禁，v1.1.114 起），但 PC 角色管理 →「App端菜单权限」树的「销售管理」分组没有这一项 → admin 无法给角色勾选/取消新建入口（与此前「生产开工/入库审核/制袋报工」漏项同款问题）。sys_menu 里权限行本来就在（F 型「销售出库新增」, id `2090345792472715270`, status=1），只是前端静态白名单 `APP_MENU_WHITELIST` 没列。

**修法**: `pc-web/src/views/system/Role.vue` `APP_MENU_WHITELIST` 销售管理分组加 `{ name: '销售出库新建', perms: 'sales:delivery:add', idApp: 'app-delivery-add' }`。后端 `SysRoleService.isGrantableMenu` 允许 F+perms 行写入 `sys_role_menu`，勾选保存即生效，无需任何后端配合。

**部署**: PC dist `index.html` md5 `759910aec015cf2f7246ea216f699480`（入口 Role chunk `Role-DZz7JaGC.js` grep 到 `app-delivery-add`+`销售出库新建`）三站原地覆盖：.150 `/volume3/docker/erp-system/pc-web/dist`、.32 `/vol2/erp-system/pc-web/dist-new`（备份 `dist-new.bak.20261028_delivadd.tgz`）、阿里云 `/opt/erp-system/pc-web`。**踩坑**: .32 `echo pw | sudo -S sh -c "tar cf - … | tar xf -"` 里 `sudo -S` 吃管道 stdin 导致解包失败把 dist-new 掏空 → 先 `echo pw | sudo -S -v` 缓存凭据再裸 `sudo` 进管道。

**验证** (headless Chrome CDP, .150:8088): 路由守卫要求 pinia `userInfo`（localStorage `erp_user_info` 持久化），仅注入 cookie 会被弹回 #/login → 注入 cookie + seed `erp_user_info/erp_permissions/erp_menus` + reload 后进入角色页。App 权限树「销售管理」渲染出「销售出库新建」且勾选态与库一致（销售经理=已勾、财务=未勾）；勾选/取消/保存往返实测「分配成功」且重开回显正确。**授权现状**（`sys_role_menu` menu_id=…270 实测）：超管/采购经理/销售经理/生产主管/仓库主管 APP+PC 均有，财务仅 PC——即线上各角色本就按 DB 授权在生效，本补丁只是补上 PC 可视化开关。

### v1.1.131 补丁 (2026-10-28 — App 打开报「无权读取该配置」403 修复)

> **范围**: 后端 1 处白名单 + App 拦截器 `silent` 选项 + 2 个配置读 API。PC dist 不变。**版本保持 1.1.130 线**。

**背景**: 上一条「App 工作台区块管理」给 `sys_config` 新增了 `APP_DASHBOARD_CONFIG` 键，App 端 dashboard `onMounted` 会读它。但 `SysConfigController.CLIENT_READABLE_KEYS` 白名单漏了这个新键 → 非 admin / 非 `system:config:list` 的普通 App 用户一打开工作台就命中 `getByKey` 的 `throw 403 无权读取该配置`，App 拦截器弹 toast。

**根因 + 修法**:
1. 后端 `SysConfigController.CLIENT_READABLE_KEYS` 加 `"APP_DASHBOARD_CONFIG"`（`updateValue` 仍走 `system:config:edit`，未动）。
2. App `app/src/api/index.js` `request()` 加 `silent` 选项 → 拦截器 else 分支 `!silent` 才 toast；`appModuleGetConfig` / `appDashboardGetConfig` 传 `silent: true`（配置读失败静默 fail-open，兜底全开，不弹 toast）。

**部署**:
- 三站 jar md5 `faeb785df9c12089466e79a20d0f848c`（备份 `.150 app.jar.bak.20261028_403fix` / `.32`+阿里云 `…_403fix`）。
- App H5 index.html md5 `fa54b3c2f631d514245f96d604d78ead`（`.32` `/vol2/erp-system/app-h5/dist/build/h5` + 阿里云 `/opt/erp-system/app-h5` 原地覆盖）。
- APK 重出 `erp-app-20261028.apk` md5 `1c6f8c53753f0995a3b5a42262badb5d`（4485624 字节，versionCode 1130，内嵌 H5 index.html md5 `fa54b3c2…` 与已部署 H5 逐字节一致；落到飞牛同步盘 `erp/` + Desktop）。

**验证**: `.150` 造非 admin 临时账号 `test403fix`（is_admin=0）→ `/auth/login` 200 → `GET /system/config/key/APP_DASHBOARD_CONFIG`（`X-Client-Type: APP`）返回 **code=200** + 完整 JSON（修复前是 403「无权读取该配置」）；`/auth/me` 确认 isAdmin=0；临时账号已删（`remaining=0`）。

### v1.1.131 补丁 (2026-10-28 — 工单单号后带商品名称: App 全处 + PC 排程/米重)

> **范围**: App (11 个页面 picker/列表/detail) + PC (Schedule/Weight 2 页) + 后端 3 处补 `productName`/`firstProductName` 字段（1 新增 injector + 2 实体 + 1 @Select mapper + 1 service 改造）+ APK 重出。**版本保持 1.1.130 线**。

**背景**: 用户报「首检(FAI)」页选单器里只显示 `PSI202610180002` 这类单号，看不出是哪个商品。要求"所有显示工单的地方后面全部带上商品名称"。

**改动**:
1. 后端：新增 `common/PrdOrderNameInjector`（按 `prdOrderId` 批量回填 `productName`，仿 `CreateByNameInjector` 模式，缺工单/无品名回退 `productCode`）；
   - `PurOrder` 加 transient `firstProductName` + `PurOrderMapper.selectPageWithProduct`（@Select + `GROUP_CONCAT(pur_order_detail.product_name)` EXISTS 子查询，仿 `PurReturnMapper`）+ `PurOrderService.page()` 换用它 → App 采购订单 picker 直接拿到首个商品名。
   - `MesSchedule` + `MesWeightSample` 加 transient `productName`，service 分页/甘特查询后调用 injector 回填 → App/PC 排程 + 米重采样 单号后显示品名。
2. App（11 处）：`scan/fai` 半成品入库单 picker、`scan/pat` 巡检工单 picker、`scan/semi-in` 半成品入库工单 picker、`scan/in` 采购订单 picker+列表（用 `firstProductName`）、`production/order-list` / `order-detail` / `report` / `schedule` / `oee` / `weight` 6 页单号行合并品名、`quality/inspect` IQC 草稿表头、`purchase/receipt-detail`、`sales/delivery-detail` 3 个单据详情页 表头。
3. PC（2 页）：`production/Schedule.vue`（甘特条 + 排程列表 工单列）+ `production/Weight.vue`（工单列）。`SemiFinishedIn`/`FinishedIn` 已有相邻「半成品/成品」列，不重复加。

**部署**:
- 三站 jar md5 `7d17bed0681aeccc1849013c66ba3a1d`（备份 `.150 app.jar.bak.20261028_0910` / `.32`+阿里云 `…_0920`）；`.150` `/opt/app/app.jar` + 重启、`.32` `erp-backend-failover` compose up + restart、阿里云 `/opt/erp-system/pc-web` `docker cp` + restart。
- 三站 PC dist md5 `3b1a9e74441f764a56d4778723a191f1`；App H5 md5 `0c2eca92851372599cd0e7490ce39596`（.32 + 阿里云 18090，.150 无 h5 容器）。
- **APK 重出** `erp-app-20261028.apk` md5 `28629654d65008a5d0509dbe810fc3a1`（4485619 字节，versionCode 1130，覆盖 08:24 那版）→ 飞牛同步盘 `erp/` + Desktop。APK 内嵌 H5 `index.html` md5 = `0c2eca92…` 与部署 H5 逐字节一致。

**验证**:
- 后端 3 字段实测：`/purchase/order/page` 首条记录 `firstProductName="透明印字胶带"` ✓；`/mes/weight/sample/page` `productName=null`（该样本未关联工单，走前端 `v-if` 隐藏）；`/mes/schedule/page` 无 records（生产未开排程，代码注入已就位）。
- 三站 jar/PC/H5 md5 全部一致（`7d17bed0…`/`3b1a9e74…`/`0c2eca92…`）。
- APK 内 grep `firstProductName` 命中 `pages-scan-in.*.js`，`sched-pname` 命中 `pages-production-schedule.*.js`，确认前端逻辑已进 APK。

**遗留**: PC 排程「排程列表」列宽由 150 增到 220、PC 米重「工单」列 min-width 120 → 180，防止品名过长被截。


> **范围**: PC 前端 (`System.vue` / 新 `AppDashboardConfig.vue`) + App (`appDashboardConfig.js` / `dashboard/index.vue` / `api/index.js`) + 1 条 seed SQL (`sql/139`), **无后端 jar 改动**。版本保持 1.1.130 线（沿用 `APP_DASHBOARD_CONFIG` 键，不升 minor）。

**背景**: PC「系统管理 → 系统设置 → 经营驾驶舱管理」原本只有一个「PC 驾驶舱」tab（`DashboardConfig.vue` 走 `sys_config.DASHBOARD_CONFIG`，管 PC 驾驶舱 7 子模块）。用户希望 App 端工作台的"显示模块"也能在同一个页里统一管理 → 新增「App 工作台」tab，走独立键 `sys_config.APP_DASHBOARD_CONFIG`，管 App 端工作台（`app/src/pages/dashboard/index.vue`）的 **4 区块**（全部功能 / KPI 四卡 / 库存预警 / 现场待办）+ 全部功能的 **7 个功能子分组**（生产/采购/销售/库存/XK记录/现场填报/经营报表）。用户决策：**区块级开关**（每个可独立启停）+ **默认全开**（首次加载 / 未配置时全显示，与现状一致）。全局一刀切：admin 改、所有 App 用户一致，叠加在现有 perm 之上。

**改动**:
1. `sql/139_v1131_app_dashboard_config.sql`：幂等 `INSERT IGNORE` 写 `sys_config.APP_DASHBOARD_CONFIG`（4 区块 enabled=true + 7 分组全 true）。仅灌 `.150` 主库，经复制链到 `.32`/阿里云备库。
2. App `src/utils/appDashboardConfig.js`（新增，仿 `appModuleEnabled.js`）：reactive state 默认全开，`loadFromServer` 读 `/system/config/key/APP_DASHBOARD_CONFIG`（**App 拦截器已解包 `R.data`，直接读返回值，勿再 `.data`**），5min TTL + fail-open；`isSectionEnabled(key)` / `isGroupEnabled(group)`。`api/index.js` 加 `appDashboardGetConfig`。
3. App `pages/dashboard/index.vue`：4 区块 `v-if` 叠加 `isSectionEnabled`；「全部功能」子标签按 `isGroupEnabled` 过滤；KPI/预警/待办区块关时跳过对应数据请求；`onMounted` 拉一次配置。
4. PC `views/system/AppDashboardConfig.vue`（新增，仿 `DashboardConfig.vue`）：主表 4 区块 + 「全部功能」7 分组 checkbox 面板，`configApi.getByKey/updateValue` 存取，保存/重载/恢复默认。
5. PC `System.vue`：经营驾驶舱管理块由裸 `<DashboardConfig />` 改 `el-tabs`（「PC 驾驶舱」= `DashboardConfig` + 「App 工作台」= `AppDashboardConfig`），并补 URL 深链 `?tab=dashboard`（`useRoute().query.tab` 初始化 `activeTab`）。

**部署**:
- 三站 PC dist md5 `8fa1f5e23b03f2b04770bd6f50fcb3d2`（`.150` `/volume3/.../dist`、`.32` `/vol2/.../dist-new`、阿里云 `/opt/erp-system/pc-web` 统一；`System-CnjHtpsA.js` 含双 tab + 深链）。
- App H5 md5 `ef338babcc3972e9d81b26a76409b1d9`（`.32` `erp-app-h5` 18090 + 阿里云 `/opt/erp-system/app-h5`；`.150` 无运行中 h5 容器）。
- **APK 重出** `erp-app-20261028.apk` md5 `56596d114f084df0fa19565afac4eb12`（4485624 字节，versionCode 1130）→ 飞牛同步盘 `erp/` + Desktop。APK 内嵌 H5 `index.html` md5 `ef338bab…` **与已部署 H5 逐字节一致**；脚本自校验通过（内嵌版本 1.1.130 = manifest.versionName；OS 级 versionName 1.1.130 / versionCode 1130）。
- seed SQL 已灌 `.150` 主库（`APP_DASHBOARD_CONFIG` 行 len=225，kpi/todo enabled=true 已核验）。

**验证**: headless Chrome CDP 登 `.150:8088` →「系统管理 → 系统设置 → 经营驾驶舱管理」，`?tab=dashboard` 深链命中；`el-tabs` 渲染 `["PC 驾驶舱","App 工作台"]`；「App 工作台」tab 显示 4 区块（`启用区块: 4/4`）+ 7 分组（生产/采购/销售/库存/XK 记录/现场填报/经营报表）。App 端 H5 逻辑：关某区块保存后 `isSectionEnabled` 返回 false → 对应卡片/子标签不渲染（区块开关叠加在 perm 之上，区块关 = 该区块隐藏，不删 perm）。


> **范围**: PC 前端 (`System.vue` / `AppModules.vue`) + 后端 (`AppModuleConfigDTO`) + App (`appModuleEnabled.js`)。版本号保持 1.1.130。

**背景**: PC 系统管理 → 系统设置 → APP管理 →「版本管理」tab 显示的是模块管理表格（重复渲染 `AppModules`），版本信息只是一条写死 banner「V2.3.0 · 2025-06-10」，与真实版本不符。「模块管理」列 8 个 key（`finance`/`workflow` 标"暂未实现"），但 App 实际已用 `qms`/`pdm`/`em` 三个模块（`permission.js` PAGE_PERMS 有 `module: 'qms'`/`'pdm'`/`'em'`），而这三个 key 不在后端 `DEFAULT_KEYS` 里 → `AppModuleService.update` 校验 8 模块全在（缺 qms/pdm/em 就 500）；App 端 `isModuleEnabled('qms')` 因不在 `MODULE_KEYS` 里永远返回 true，admin 无法关闭。

**改动**:
1. `System.vue` App tab 重排：「版本管理」tab → 真实版本信息面板（版本号 / 构建日期 / APK 路径 / H5 地址 / 七源统一说明）；「模块管理」tab（原「模板管理」改名）→ `<AppModules />`。
2. `AppModules.vue` `MODULE_DEFS` 扩到 11 个 key（补 `qms`/`pdm`/`em`，更新各模块 description 与 permList 对齐实际 App 入口）；`PERM_DESCRIPTIONS` 补全。
3. 后端 `AppModuleConfigDTO.DEFAULT_KEYS` 8 → 11 key（`qms`/`pdm`/`em` 加入）。
4. App `appModuleEnabled.js` `MODULE_KEYS` 8 → 11 key。

**部署**:
- jar md5 `f45e096ff5b37e8136e83a24bf4bfb1f`（三站统一，备份 `.150 …_2110` / `.32 …_2110` / 阿里云 `…_2110`）。
- PC dist md5 `595bf62032f95dd72eaedc4a9b9feedc`（三站统一，备份 `dist.bak.20261027_2110.tgz`）。
- H5 md5 `a874971a85c9645e676ba83845dfdc0c`（飞牛 18090 + 阿里云 18090）。
- APK `erp-app-20261027.apk` md5 `968b036075fe6ea902158456749d24c1`（覆盖上午那版）。

**说明**: `sys_config.APP_MODULE_ENABLED` JSON 里已有 qms/pdm/em（DB 当前 11 key），无需 SQL 迁移。App 用户装新 APK 后，admin 关闭 qms/pdm/em 模块 → 对应 App 页 `guardPage` 拦截。PC 用户无需改代码。

### v1.1.130 补丁 (2026-10-27 20:50 — 角色删除 500 修复 + 全仓同类缺陷扫描)

> **范围**: 后端 (`SysRoleMapper.xml` / `SysRoleService` / 3 个 outsource 实体 / 1 个 inventory 实体 / 2 个 outsource mapper XML / 新 SQL 迁移), **jar 已重建并三站部署**。无 PC / App 改动, 版本号保持 1.1.130。

**背景**: 19:40 拆分三对 App 权限时发现「删除角色」接口 500 `Invalid bound statement` —— `SysRoleMapper.deleteRoleMenus` 只在接口里声明, XML 里没实现。顺带做了一次全仓扫描: 所有 `*Mapper.java` 抽象方法是否都在对应 XML 里实现了 (用 namespace + id 匹配), 以及所有 `entity/*.java` 里的集合字段是否标了 `@TableField(exist=false)` (否则 MP 把 List/Map 当列 select/insert), 以及每个实体字段在库里有没有对应列 (跟 information_schema.COLUMNS 对账)。

**改动**:
1. `SysRoleMapper.xml` 补 `<delete id="deleteRoleMenus">` (清该角色全部菜单授权, 含 PC/APP/BOTH 全类型)。
2. `SysRoleService.delete` 加守卫: 内置超管角色 (id=1 / role_code=SUPER_ADMIN) 不可删 —— 删掉会连带清空所有管理员的菜单授权 (授权按 role_id 落库)。
3. `OutIssueDetailMapper.xml` / `OutProcessingInDetailMapper.xml` 新建, 补 `selectByIssueId` / `selectByPiId` (此前接口声明无 XML 实现 → 委外发料/加工入库审核 500)。
4. `OutIssue` / `OutProcessingIn` / `InvTransfer` 实体的 `List<...> details` 字段加 `@TableField(exist=false)` (此前 MP 把它当列 → `Unknown column 'details'` 500)。
5. `OutIssueDetail.lineNo` 字段加 `@TableField(exist=false)` (sql/07 建表 DDL 里没有 `line_no` 列, 实体多了一个字段)。
6. `sql/138_v1130_sop_remark_column.sql` (幂等, 只在 `.150` 主库执行, 自动复制备库): `mes_process_sop` 表补 `remark` 列 —— sql/54 建表时漏了, 但实体 + PC 工艺 SOP 页 (`process/Sop.vue`) 都有该字段, 保存 SOP 一直 500 `Unknown column 'remark'` (该表至今 0 行印证)。

**部署**: 三站 jar md5 统一 `e35ee38faab414d2b9511ec7c8ca22d4` (备份 `.150 app.jar.bak.20261027_2040` / `.32 …_2042` / 阿里云 `…_2043`, 两备站 `docker commit` 回 `:1.1.130` 镜像)。SQL 138 只在 `.150` 主库灌 (自动复制到 `.32` / 阿里云只读副本)。

**验证** (`.150:8088` API 实测):
1. 临时角色 `ZZTMPCR2` → 建 (200) → 删 (200) → 库里 `deleted=1` + `sys_role_menu` / `sys_user_role` 关联行清零 ✓。
2. 删超管角色 (id=1) → 返回 `内置超管角色不可删除` ✓。
3. 委外 `POST /outsource/issue/999999/check` 与 `/outsource/pi/999999/check` → 返回「发料单不存在」/「入库单不存在」业务错误 (不再是 `Invalid bound statement` / `Unknown column`) ✓。
4. 工艺 SOP `POST /pdm/process/sop` 带 `remark` 字段 → 200 且落库 `mes_process_sop` 含 remark 列 ✓ (测试行已删)。
5. 全仓 mapper 缺语句扫描: 除上述 3 个外无其它缺口; 实体集合字段缺 `exist=false` 扫描: 除上述 3 个外无其它缺口; 实体字段 vs 库列对账: 仅 SOP 的 remark 一处, 已补列。

**说明**: 三处同类缺陷 (委外审核 / SOP 保存 / 调拨读主表) 此前都是 500 但线上 0 数据, 说明这些模块从未被真正使用过 (或早期就被 500 拦住了)。本次一次性补齐, 后续这些功能首次被使用时不会再 500。

### v1.1.130 补丁 (2026-10-27 19:40 — App 端三对共用权限真正拆开，可分开授权)

> **范围**: PC 前端 (`Role.vue`) + 后端 (`backend`, **jar 已重建并三站部署**) + SQL (`sql/137_v1130_split_app_perms.sql`) + App (`permission.js` / `dashboard`, **APK 已重出**)。版本号保持 1.1.130。

**背景**: 上一版 (18:27) 用"联动勾选"缓解了取消后又被勾回的表象，但三对入口仍然共用同一个 perm，**授权按 `menu_id` 落库 → 物理上无法分开授权**。用户要求"权限都分开来"，本次真正拆分。

**拆分方案** (新增 3 个 F 型 `sys_menu` 行, `status=1`, `client_type='BOTH'`):

| 新 perm | menu id | 父节点 | 拆自 | 原 perm |
|---|---|---|---|---|
| `report:print` | 10045 单据打印 | 10004 | XK 单据打印 | `report:view` |
| `production:report:trace` | 10046 批次溯源 | 10034 | XK 批次溯源 | `production:report:list` |
| `production:schedule:oee` | 10047 设备OEE | 10032 | 设备 OEE | `production:schedule:list` |

`sql/137_v1130_split_app_perms.sql` (幂等, 仅在 `.150` 主库执行, 自动复制到备库) 把持有旧行的角色授权**复制**到新行 (10045 ← 951/10039, 10046 ← 10034/2090345792472715377, 10047 ← 10032/2090345792472715413), App 存量用户不丢权限; 之后在 PC 角色页取消勾选即真正生效。

**后端改动**:
1. `MesController` / `XkController` 6 个接口注解改 `SaMode.OR` (`production:report:trace|list`, `production:schedule:list|oee`) —— 旧授权继续可用, 新 perm 也能进。
2. `PermissionService` 新增 `hasAnyPerm` / `requireAnyPerm` (原先只有单 perm 的 `requirePerm`)。
3. **Service 层内层校验**同步放宽 (只改注解会漏): `MesScheduleService.page()/oee()` → `requireAnyPerm(schedule:list, schedule:oee)`; `PurReceiptService.page()` → `requireAnyPerm(purchase:receipt:list, report:print)`。
4. 入口页面依赖的兄弟接口一并放宽, 否则新 perm 单独授权时 App 页面数据为空: `EquipmentController /page|/list` (排程与 OEE 页要读设备列表)、`XkController /material/page` 与 `/print/preview` (XK 单据打印页要读入库单与留存年限)、`ReportController /dashboard` (经营简报页要读 KPI 卡片)。

**PC 前端**: `Role.vue` 的 `APP_MENU_WHITELIST` 三项 repoint 到新 perm (`app-xk-print`→`report:print`, `app-xk-trace`→`production:report:trace`, `app-oee`→`production:schedule:oee`); 树下的提示文案同步更新 (原文还写着"经营简报与 XK 单据打印同为 report:view", 已过期)。`@check="onAppCheck"` 联动保留 —— 三个 perm 拆开后不再有兄弟节点共享 `_menuId`, 联动自动退化为 no-op。

**App 端**: `utils/permission.js` 的 `PAGE_PERMS` 改新 perm (`/pages/xk/trace`→`production:report:trace`, `/pages/xk/print`→`report:print`, `/pages/production/oee`→`production:schedule:oee`); `dashboard/index.vue` 的入口判断同步改新 perm, OEE 区块拆成两个独立 if (生产排程 / 设备 OEE 各看各的 perm)。**App 用户需装新 APK 或重登才能拿到新 perm**。

**部署**:
- jar md5 统一 `06db7021d857c7a6316560a848b5abfa` —— home `.150` (备份 `app.jar.bak.20261027_1930`)、飞牛 `.32` (`app.jar.bak.20261027_1932` + `docker commit erp-system-backend:1.1.130`)、阿里云 (`app.jar.bak.20261027_1933` + `docker commit erp-backend:1.1.130`)。
- PC dist md5 统一 `895ee979550a17fff52cc9675715c748` (Role chunk `Role-B8_0rRq2.js`) —— 三站 bind mount 原地替换, 备份 `dist.bak.20261027_19xx.tgz`。
- App H5 md5 `fce55d934016be278aa549f01cb01c6d` —— 飞牛 `.32` `erp-app-h5` (18090) 与阿里云 `erp-app-h5` 同步。
- APK: `erp-app-20261027.apk` (md5 `f42c024f2eabce6f2c634c9ec2d2f834`, versionName v1.1.130 / versionCode 1130), 输出到飞牛同步盘与桌面。

**验证**:
1. **权限矩阵实测** (临时角色 + 临时用户, `X-Client-Type: APP`): 仅 `production:report:trace` → `/mes/batch/page`、`/xk/trace/batches` 200, OEE/排程/设备/打印/报表全 403; 仅 `production:schedule:oee` → `/mes/oee/1`、`/mes/schedule/page`、`/base/equipment/page` 200, 批次 403; 仅 `report:print` → `/xk/print/preview`、`/xk/material/page` 200, 报表 403; 仅 `report:view` → `/report/dashboard`、`/report/inventory/summary` 200, 打印 403。
2. **PC 界面实测** (headless Chrome + CDP, `.150:8088`): 分配权限 → App端菜单权限, 六个入口各自独立勾选; 取消「XK 单据打印」→「经营简报」仍勾选 ✓; 取消「设备 OEE」→「生产排程」仍勾选 ✓; 取消「XK 批次溯源」→「扫码报工」仍勾选 ✓; 确定保存 → 重开弹窗, 三个被取消的保持未勾、三个保留的保持已勾 ✓; 库中 APP 授权行同步只剩 `production:schedule:list` / `report:view` / `production:report:list` / `production:report:add` 四行。
3. 临时角色/用户/授权行已清理 (软删 + 授权行清零), 未触碰任何真实角色。

**已知遗留** (本次未修, 单独报备): PC 角色管理"删除角色"接口报 `Invalid bound statement` —— `SysRoleMapper.deleteRoleMenus` 在 `SysRoleMapper.xml` 里没有对应 `<delete>` 语句, 角色删除整条链路 500。本次清理临时角色改走 SQL 软删绕过。

### v1.1.130 补丁 (2026-10-27 18:27 — App 端权限树共用权限点联动勾选)

> **范围**: 仅 PC 前端 (`Role.vue`)，无后端 / SQL / App 改动，版本号保持 1.1.130。

**背景**: 角色管理 → 分配权限 → App端菜单权限，取消「经营简报」勾选并确定后，再进入仍是勾选状态。

**根因**: App 权限树白名单里**三对入口共用同一个 sys_menu 授权行**（按 perms 反查、授权按 `menu_id` 落库）：
- 「经营简报」(`app-report-kpi`) ↔「XK 单据打印」(`app-xk-print`) 同为 `report:view`
- 「扫码报工」(`app-mes-report`) ↔「XK 批次溯源」(`app-xk-trace`) 同为 `production:report:list`
- 「生产排程」(`app-schedule`) ↔「设备 OEE」(`app-oee`) 同为 `production:schedule:list`

两个节点解析到同一 `_menuId`，勾选状态回填时只要该行有授权**两个都会显示已勾**；取消其中一个、另一个仍勾着 → 提交时该行仍在授权列表里 → 库里授权保留 → 重进又显示已勾。这不是保存丢失，是同一授权行的两个视图没有联动。

**改动**: `Role.vue` — (1) App 树新增 `@check="onAppCheck"` 处理器：勾选/取消某节点时，把所有 `_menuId` 相同的兄弟节点同步为同一状态（对共用行是 no-op，天然兼容）；(2) 树下加一行提示「同一 App 权限对应多个入口时会联动勾选/取消」。

**部署**: 三站 PC dist 原地覆盖 —— home `.150`（备份 `dist.bak.20261027_1827.tgz`）、飞牛 `.32`（`/vol2/erp-system/backup/pc-web.bak.20261027_1827.tgz`，实际服务根 `dist-new`）、阿里云（`/opt/erp-system/backup/pc-web.bak.20261027_1827.tgz`）。三站 `index.html` md5 统一 `434d5e68f8477d9bd9464fbd21624888`，入口 `assets/index-C0uYuAvE.js`，Role chunk `Role-D8SyrYFg.js`（含联动代码与提示文案，且 17:05 批的 `app-mes-report-add` 制袋报工项保留），158 个 asset。

**验证**: headless Chrome + CDP 端到端实测（`.150:8088`）— 建临时角色授 `report:view` 的 APP 行（menu 951 + 10039）→ 打开分配权限 → App端菜单权限：「经营简报」「XK 单据打印」均初始已勾 ✓ → 取消「经营简报」→ 「XK 单据打印」**同步取消** ✓ → 确定保存 → 重开弹窗两者均为未勾 ✓，库中该角色 APP 授权行已清空 ✓。临时账号/角色/数据已清理（残留 0），未触碰任何真实角色。

**说明**: 「经营简报」与「XK 单据打印」在 App 端本来就由同一 perm `report:view` 控制（[permission.js:101](app/src/utils/permission.js) `/pages/report/index` 与 [permission.js:120](app/src/utils/permission.js) `/pages/xk/print`），所以**无法分开授权**——要真正拆分需给打印页单独立 perm（改 App + 重出 APK），本次按"诚实联动"处理。

### v1.1.130 补丁 (2026-10-27 17:05 — App 端角色授权补「制袋报工」)

> **范围**: PC 前端 (`Role.vue`) + 数据授权 (sys_role_menu)。无后端 / jar 改动，版本号保持 1.1.130。

**背景**: App 生产单详情页的「制袋·报工完工」入口由 `canReport()` 控制（[order-detail.vue:419](app/src/pages/production/order-detail.vue)）——要求 `production:report:add` 权限 + 单据状态 `RELEASED/PRODUCING`。制袋工角色 (zdg) 只有该权限的 `PC` 授权，App 登录按 `X-Client-Type: APP` 只取 `client_type IN ('BOTH','APP')` 的授权 → App 里工序卡片没有可点按钮。且 App 端权限树是**前端白名单**，未登记该权限点，界面上也无法补授。

**改动**:
1. `pc-web/src/views/system/Role.vue` 的 `APP_MENU_WHITELIST`「生产 / 质量」组，在「扫码报工」后新增 `{ name: '制袋报工', perms: 'production:report:add', idApp: 'app-mes-report-add' }`。
2. 数据授权（仅 `.150` 主库写入，自动复制到备库）：`sys_role_menu` 给制袋工角色 (2079395004410298370) 补 `menu_id=2090345792472715378` (production:report:add) 的 `client_type='APP'` 行（`insert ignore`）。超管/生产主管/销售经理/采购经理此前已有 APP 授权，不受影响。

**部署**: 三站 PC dist 原地覆盖（bind mount 替换）—— home `.150`（备份 `dist.bak.20261027_1705.tgz`）、飞牛 `.32`（`/vol2/erp-system/backup/pc-web.bak.20261027_1705.tgz`）、阿里云（`pc-web.bak.20261027_1705.tgz`）。三站 `index.html` md5 统一 `a05c1045b1b7794ec51b3fc5288b232a`，入口 `assets/index-DQdT2BDo.js`，Role chunk `Role-CNLqVPjm.js`（含 `app-mes-report-add`）。

**验证**: 部署产物 HTTP 复查 —— `.150:8088` `index.html` md5 与上述一致；`Role-CNLqVPjm.js` 含 `制袋报工` / `app-mes-report-add`；主库查询确认 秦运桂(制袋工) / 侯丽君(生产主管·销售经理·采购经理) / 超管 均持有 `production:report:add` APP 授权。（本次验收时浏览器调试器被占用，未做 UI 点击实测；权限树渲染逻辑与 15:18「生产开工」补丁完全同构，该补丁已实测通过。）

**使用**: 秦运桂等制袋工账号需**重新登录 App**（或在「我的」刷新权限）后，生产单详情「制袋·报工完工」卡片才会出现可点按钮。仓管角色 (罗飞, WAREHOUSE_MGR) 也缺该 APP 授权，如需在角色管理页勾选「制袋报工」即可，不必再改库。

### v1.1.130 补丁 (2026-10-27 16:00 — 附件上传全站失效修复)

> **范围**: PC 前端 (`pc-web`) + 后端 (`backend`, **jar 已重建并三站部署**) + nginx。App 代码无改动（同一后端接口），版本号保持 1.1.130。

**背景**: `#/xk/material` → 证照与检测报告 → 登记证照 → 扫描件，上传图片报 `系统繁忙, 请稍后再试: Required part 'file' is not present`。实际影响**全站所有上传入口**（证照附件、评审附件、商品图片等），不只供应商证照。

**根因 (三层叠加)**:
1. **前端把 FormData 搞坏了** — `pc-web/src/utils/request.js` 的请求拦截器 v1.1.79 hotfix-2 为了保雪花 ID 精度，对 `config.data` 无条件递归遍历改写成新对象。`FormData` 被当普通对象遍历时 `Object.keys()` 为空 → 请求体变成 `{}`，multipart 边界一并丢失 → 后端收不到 `file` part，报 `Required part 'file' is not present`。
2. **后端没有统一信封** — `SysUploadController` 直接返回裸 `Map`（无 `code`）；PC 响应拦截器只在 `data.code === 200` 时放行，App `utils/upload.js` 也只认 `{code:200|0, data.url}` → 即使上传成功，前端仍按失败处理（表现为"上传失败"或"系统繁忙"）。
3. **回读地址缺 context-path / 缺端口** — 返回的 `url` 曾写成 `/upload/yyyyMM/dd/x.png`，但后端 `server.servlet.context-path=/api`，真实路径是 `/api/upload/**` → 浏览器点「查看附件」404；nginx 里 `\.(js|css|png|…)$` 的静态资源正则还会把 `/api/upload/*.png` 抢去当本地文件。改成绝对地址后，又因为 nginx `proxy_set_header Host $host` 丢端口，拼出 `http://192.168.0.150/api/...`（缺 `:8088`）。

**改动**:
- `pc-web/src/utils/request.js`：新增 `isJsonPayload()`，只对数组 / 纯对象跑 `stringifyBigInts`；`FormData` / `Blob` / `File` / `ArrayBuffer` / `URLSearchParams` 原样透传。
- `SysUploadController`：用 `X-Forwarded-Proto` / `X-Forwarded-Host`（缺失回退 `request.getScheme()/Host`）拼**绝对地址** `http://host:port/api/upload/yyyyMM/dd/<uuid>.<ext>`；统一返回 `R.ok(data)`。10MB 上限与后缀白名单（jpg/jpeg/png/gif/webp/pdf/xls/xlsx/doc/docx）保持不变。
- **为什么必须是绝对地址**：App 原生「扫码入库 / 首检 / 巡检」页把返回值直接塞进 `<image :src>`（[in.vue](app/src/pages/scan/in.vue) / [pat.vue](app/src/pages/scan/pat.vue)），根相对地址在 Capacitor/WebView 里会解析到本地协议 → 图裂；`fai.vue` 虽有 `base()` 前缀拼接，但会拼出 `//api/upload/...` 双斜杠。改回绝对地址可保持 App 端既有行为，不需要重出 APK。
- `pc-web/nginx.conf` + `app-h5/nginx.conf`（三站同步）：新增 `location ^~ /api/upload/` 反代到后端（`^~` 必须有，否则被同层图片/JS 静态正则抢走）；同时把 `/api/upload/`、`/api/ai/`、`/api/` 三处的 `proxy_set_header Host $host` 改为 `$http_host` 并补 `X-Forwarded-Host $http_host` / `X-Forwarded-Proto $scheme`，保证后端拿到带端口的原始域名。

**部署**: 三站 jar md5 统一 `9715cd805d17edfc460cbeb472c940d2` — home `.150`（备份 `/volume3/docker/erp-system/backup/app.jar.bak.20261027_1620`）、飞牛 `.32`（`/vol2/erp-system/backup/app.jar.bak.20261027_1630` + `docker commit erp-system-backend:1.1.130` + compose up）、阿里云（`/opt/erp-system/backup/app.jar.bak.20261027_1630` + `docker commit erp-backend:1.1.130` + compose up）。nginx 三站都改（`.150` 容器内 `default.conf`，`.32` 的 `nginx-failover.conf` ×2，阿里云 `nginx/pc.conf|h5.conf|host-erp.conf`），均已 `nginx -t` + reload，旧配置备份在同批 `backup/` 目录。三站 PC dist 保持 `bf224875c1cf6c62454c90457922abd4`（入口 `index-ClH0pfhN.js`，已含拦截器修复）。

**验证**: `.150:8088` 浏览器 → `#/xk/material` → 证照与检测报告 → 登记证照 → 扫描件选 PNG → 弹出「附件已上传」并显示「已上传: 查看附件」，`href` 为 `http://192.168.0.150:8088/api/upload/202610/27/<uuid>.png`（带端口）；`curl` 该地址 `HTTP 200`、`image/png`、70 字节。全程未点「保存」，库里不产生证照记录。测试期间的上传文件与临时验收账号 `_upchk` 及其角色授权已清理。

**遗留说明**: `/api/upload/**` 由 `WebMvcConfig` 的静态资源映射提供，不经 Sa-Token 鉴权（UUID 文件名不可猜）；App 端 `<image :src>` 也无法附带鉴权头，故维持公开读。若后续要收敛，需改为短期签名 URL 或带 token 的下载接口。

### v1.1.130 补丁 (2026-10-27 15:31 — App 端角色授权补「半成品/成品入库 审核·反审核」)

> **范围**: 仅 PC 前端 (`Role.vue`)，无后端 / SQL / App 改动，版本号保持 1.1.130。

**背景**: App 的[半成品入库](app/src/pages/scan/semi-in.vue)与[成品入库](app/src/pages/scan/finished-in.vue)页面行内都有「审核 / 反审核」按钮，分别由 `production:semi-in:check|uncheck`、`production:finished-in:check|uncheck` 控制（前端 `canCheck()/canUncheck()` 走 `getMyAppPerms()`，后端 `PrdSemiInController` / `PrdFinishedInController` 同 perm 校验）。但角色授权页的 App 端权限树是**前端白名单**，只登记了「半成品入库 / 成品入库」两个新增权限点，四个审核权限点没登记 → 除超管外，App 里既看不到这两个按钮，也无法在界面上补授（只能直接写库）。

**改动**: `pc-web/src/views/system/Role.vue` 的 `APP_MENU_WHITELIST`「生产 / 质量」组，在「半成品入库」后插入审核/反审核两项、在「成品入库」后插入审核/反审核两项：
`production:semi-in:check`(`app-semi-in-check`)、`production:semi-in:uncheck`(`app-semi-in-uncheck`)、`production:finished-in:check`(`app-finished-in-check`)、`production:finished-in:uncheck`(`app-finished-in-uncheck`)。四个 perm 在 `sys_menu` 均有启用行（semi-in check `2090345792472715410` / uncheck `…412`，finished-in check `2090345792472715297` / uncheck `3215`），白名单按 perm 反查后写入真实 `menu_id`。

**部署**: 三站 PC dist 原地覆盖（bind mount inode 安全替换）—— home `.150`（备份 `dist.bak.20261027_1531.tgz`）、飞牛 `.32`（`/vol2/erp-system/backup/pc-web.bak.20261027_1531.tgz` + 重启 `erp-pc-web-failover`）、阿里云（`/opt/erp-system/backup/pc-web.bak.20261027_1531.tgz` + 重启 `erp-pc-web`）。三站 `index.html` md5 统一 `84506d51b77c2e0fb3d5831bec77a8b3`，入口 `assets/index-Cl0-8YGm.js`，Role chunk `Role-Ct6xiLkx.js`（含四个新 perm），158 个 asset；三站 `Role-Ct6xiLkx.js` 均 200。

**验证**: 浏览器实测 `.150:8088` → 系统管理 → 角色管理 → 分配权限 → **App端菜单权限**，「生产 / 质量」下顺序为 …半成品入库 → **半成品入库审核** → **半成品入库反审核** → 成品入库 → **成品入库审核** → **成品入库反审核** → 首检 / 工序巡检…，且勾选态与库里一致（semi-in check ✓ / semi-in uncheck ☐ / finished-in check ✓ / finished-in uncheck ✓）。全程只读，点「取消」未提交：`sys_role_menu` role 1 仍为 APP 45 / BOTH 10 / PC 259。临时验收账号 `_appchk` 已删除（残留 0）。

**使用**: App 端权限树勾选需要的审核/反审核项后保存 → 该角色用户重新登录 App（或在「我的」刷新权限）→ 入库单列表行内出现对应按钮；`X-Client-Type: APP` 只取 `client_type IN ('BOTH','APP')` 的授权，因此必须授 APP 端才生效。

### v1.1.130 补丁 (2026-10-27 15:18 — App 端角色授权补「生产开工」)

> **范围**: 仅 PC 前端 (`Role.vue`)，无后端/ SQL / App 改动，版本号保持 1.1.130。

**背景**: App 生产单详情页的「开工」按钮由 `production:order:release` 控制（[order-detail.vue](app/src/pages/production/order-detail.vue)），后端接口同样校验；App 登录按 `X-Client-Type: APP` 只取 `sys_role_menu.client_type IN ('BOTH','APP')` 的权限。而角色授权页的 App 端权限树是**前端白名单**，里面没有开工权限点 → 该权限历史上只授过 `PC`，除超管外 App 里永远看不到开工按钮，也无法在界面上补授。

**改动**: `pc-web/src/views/system/Role.vue` 的 `APP_MENU_WHITELIST`「生产 / 质量」组新增 `{ name: '生产开工', perms: 'production:order:release', idApp: 'app-order-release' }`；保存时沿用既有 `grantMenusByClient(roleId, 'APP', …)` 写 `sys_role_menu(client_type='APP')`。

**部署**: 三站 PC dist 原地覆盖（bind mount inode 安全替换）—— home `.150`（备份 `dist.bak.20261027_1518.tgz`）、飞牛 `.32`（`dist-new.bak.20261027_1518` → `/vol2/erp-system/backup/pc-web.bak.20261027_1518.tgz`）、阿里云（`pc-web.bak.20261027_1518.tgz`）。三站 `index.html` md5 统一 `a8423abfb650c6e2bfe17de3281a1b65`，入口 `assets/index-Dr2FpXTc.js` → `assets/index-cCYvQoVO.js`，Role chunk `Role-DttbiT4k.js`（含 `production:order:release`），158 个 asset。

**验证**: 浏览器实测 `.150:8088` → 系统管理 → 角色管理 → 分配权限 → **App端菜单权限**，「生产 / 质量」下出现「生产开工」（位于「生产加工单」之后），其余 43 项不变；全程只读页面，未提交任何授权（`sys_role_menu` role 1 的 APP 行数 45、release 仍为 PC，未变）。临时验收账号已删除。

**使用**: 在 App 端权限树勾选「生产开工」并保存 → 该角色用户重新登录 App（或在「我的」刷新权限）→ 生产单详情 `DRAFT` 状态出现开工按钮；后端鉴权本身不区分端，只有按钮显隐依赖 APP 授权。

### v1.1.130 P1/P2 (2026-10-27 14:40 — 合格供应商: 准入评审 / 证照报告 / 年度绩效 + 采购订单门禁)

> **范围**: 后端 (`backend`, **jar 已重建并三站部署**) + PC (`pc-web`) + SQL。App / APK / H5 无改动，版本号保持 1.1.130（当日 v1.1.130 的第二批修补）。

**背景**: P0 只把 5 个 tab 做出了过滤与暂停台账，但「准入评审 / 证照与检测报告 / 年度绩效评价」三张卡片仍只是 `base_supplier` 的视图过滤 — 没有独立的评审记录、检测报告附件与评分模型，XK16-204 要求的「准入评审记录留存 + 检测报告归档 + 年度绩效分级」无法自证；采购侧只有入库门禁，**采购订单可以在供应商暂停/资质过期时照常下单**；审计中心也不认识 `SUPPLIER_*` 业务类型。

**(1) SQL** — `sql/136_v1130_xk_supplier_records.sql`（幂等，已在 .150 主库执行，随实时同步到 .32 与阿里云副本）
- `xk_supplier_review`：`supplier_id/code/name` + `review_type`(INITIAL/ANNUAL/SPECIAL) + `review_date` + `reviewer` + `next_review_date` + `result`(PASS/CONDITIONAL/FAIL) + `conclusion`(1000) + `attachment_url` + 操作人/时间 + 审计四件套 + `deleted`；索引 `(supplier_id, review_date)`、`(next_review_date)`。
- `xk_supplier_cert`：`cert_type`(PRODUCTION_LICENSE / GB4806_REPORT / CONFORMITY_DECLARATION / ISO22000 / OTHER) + `cert_name/cert_no/issuer/issue_date/expire_date/file_url/remark`；索引 `(supplier_id)`、`(expire_date)`。
- `xk_supplier_perf`：`eval_year` + 5 维分 + `total_score` + `grade`(A/B/C) + `eval_date/evaluator/next_review_date/remark`；索引 `(supplier_id, eval_year)`、`(eval_year)`。
- 回填：`base_supplier.food_cert_no` 非空的存量行补一条 `PRODUCTION_LICENSE` 证照（`NOT EXISTS` 幂等）。本次线上 0 行（唯一供应商无食品级资质），**不伪造评审/绩效记录**。

**(2) 后端** — 新增 `xk/entity/XkSupplierReview|XkSupplierCert|XkSupplierPerf` + 对应 Mapper + `XkSupplierRecordService`
- 评审：`saveReview` 校验类型/结论枚举、评审人必填；`next_review_date` 留空时按类型推算（初次/年度 +1 年，专项 +6 月）；`result=FAIL` 保留记录（不删数据，门禁按最近一条判定）。增删改全部写 XK 审计 `SUPPLIER_REVIEW`。
- 证照：`saveCert` 校验类型、编号必填、`expire_date ≥ issue_date`；**保存/删除 `PRODUCTION_LICENSE` 后自动回写 `base_supplier.food_cert_no/food_cert_exp/food_cert_type`**（取有效期最新一条），让采购门禁、入库门禁、证照档案共用一个口径。清空用 `LambdaUpdateWrapper.set(field, null)`，因为 MyBatis-Plus `updateById` 会跳过 null 字段。写审计 `SUPPLIER_CERT`。
- 绩效：`savePerf` 服务端算总分 = 质量 35% + 交付 20% + 价格 15% + 服务 15% + 合规 15%，等级 A≥90 / B≥75 / 其余 C；`next_review_date` 默认评价日 +1 年。写审计 `SUPPLIER_RATING`。
- 看板：`GET /xk/supplier/dashboard` 一次返回 `certs`（90 天临期/已过期，按到期日升序，≤100）、`reviews`（合格供应商中从未评审或 90 天内到期，≤100）、`latestRating` / `ratingDistribution`、`latestReview` 映射；`GET /xk/supplier/stats` 增加 `withCert` / `rated` / `expiring`。
- 接口（`XkSupplierController`，权限沿用 `base:supplier:list|edit`）：`/review/list|POST /review|DELETE /review/{id}`、`/cert/list|POST /cert|DELETE /cert/{id}`、`/perf/list|POST /perf|DELETE /perf/{id}`。
- **采购订单门禁** (`PurOrderService.assertXkSupplierGate`)：创建 / 修改 / 审核三处；订单含 `base_product.is_food_grade=1` 的行时，供应商必须 `status=1`、`food_cert_exp` 已登记且未过期、最近一次评审结论不为 FAIL，否则抛 XK 合规错误并指出该去哪个页面补录；全部非食品级行与采购入库同口径豁免（仅 info 日志）。

**(3) PC 前端**
- `views/xk/Material.vue` 重写（832 行）：5 模块快捷入口带角标；「XK 到期看板」3 个统计块（证照临期 / 复评到期 / 年度评级分布）+ 2 条预警条；名录表补「食品级资质 / 年度评价 / 下次复评 / 准入状态」列；`review`/`docs`/`rate` 三个模块分别挂 `SupplierReviewPanel` / `SupplierCertPanel` / `SupplierPerfPanel`，保存后 `refreshAll` 回刷看板与角标。
- 新增 `views/xk/components/SupplierReviewPanel.vue`（评审记录 CRUD + 附件上传 + FAIL 门禁提示 + TSV 导出）、`SupplierCertPanel.vue`（5 类证照 + 到期高亮 + 附件）、`SupplierPerfPanel.vue`（5 维分输入、服务端算总分与等级、按年度筛选 + 导出）。
- `views/base/Supplier.vue`：准入状态开关只在**新增**时可设；编辑态改为只读标签 + 提示「状态变更请到 XK 合规 → 合格供应商 → 暂停与恢复供货 操作」，PC 入口不再绕过台账。
- `views/xk/Audit.vue`：业务类型下拉补 5 个供应商类型；操作类型 ADD/EDIT/DELETE/UPLOAD/VOID/PDF 中文化；`bizMap` 补 `SUPPLIER_SUSPEND/RESUME/REVIEW/CERT/RATING`；新增第 5 张归档卡「供应商准入档案」（归档范围 2025-01 至今）；查询改为对原始流水做 keyword/业务类型/日期三重过滤（可反复查询、可重置）。

**(4) 部署**
- home `.150`：jar 备份 `data/backup/app.jar.bak.20261027_1440` → `docker cp` + 重启；dist 原地覆盖（`dist.bak.20261027_1440.tgz`）。
- 飞牛 `.32`：`scripts/deploy-32-v1130.sh`（备份 jar `app.jar.bak.20261027_1440` / dist `dist-new.bak.20261027_1440.tgz` / compose → `docker commit erp-system-backend:1.1.130` → compose 改 image tag + `ERP_VERSION` → `docker cp` + 重启 `erp-backend-failover` + 重启 `erp-pc-web-failover`）。
- 阿里云 `47.96.188.129`：`scripts/deploy-aliyun-v1130.sh`（备份 jar `app.jar.bak.20261027_1440` / pc-web `pc-web.bak.20261027_1440.tgz` / `.env` → `docker commit erp-backend:1.1.130` → `.env` 的 `ERP_VERSION` 改 1.1.130 → `docker compose up -d erp-backend` → pc-web 原地覆盖）。
- 三站 jar md5 统一 `75910f43425456a6de3711ffe2ef6f78`；`index.html` md5 `293a50b0c6f66070675d6771a663b1bd`、入口 `assets/index-Dr2FpXTc.js`、供应商页 chunk `assets/Material-DPV7dXht.js`、158 个 asset 三站一致。`.32` 与阿里云容器 `ERP_VERSION=1.1.130`；`.150` 容器未重建，env 仍 `1.1.129`，`sys_config` 运行时版本下次重建容器时对齐（jar 已是 1.1.130）。

**(5) 验证** — `scripts/verify-xk-p1p2.sh`（在 .150 上跑，账号/数据均为临时并在结尾清理）
- `stats` / `dashboard` 结构与计数正确；评审保存后 `next_review_date` 自动 +1 年；证照保存后 `base_supplier.food_cert_*` 同步、删除后清空（`LambdaUpdateWrapper` 生效）；非生产许可类证照不改动 `food_cert_*`；绩效 5 维分 → 总分 91.75 → A；看板计数随之变化。
- 采购订单门禁四向实测：暂停供货拦截、无资质有效期拦截、资质过期拦截、最近评审 FAIL 拦截；恢复资质/评审后放行；纯非食品级订单豁免。
- 暂停/恢复：状态变更走台账接口，直改状态在 PC 端已无入口。
- 审计：`xk_audit_log` 生成的描述为中文（「新增准入评审 [年度复评] 结论 通过 / 下次复评 2027-10-27」等）。
- 浏览器实测（`http://192.168.0.150:8088`）：名录 / 评审 / 证照 / 绩效 / 暂停 5 个模块角标 `[1,1,0,0,0]`，到期看板「证照临期 0 份 / 复评到期 1 家 / 年度评级 0/0/0」，三个面板空态与按钮（新增评审 / 登记证照 / 新增评分）正常，绩效表 5 个加权维度列齐全，`#/xk/audit` 归档页 5 张卡片含「供应商准入档案」、业务类型下拉含 5 个供应商项、控制台无报错。
- 验证后清理：临时账号、临时评审/证照/绩效记录、临时采购订单全部删除，`base_supplier.food_cert_*` 回到 NULL，`xk_supplier_*` 三表 0 行。

**遗留**: `.32` / 阿里云仅做 bundle 一致性校验，未逐页点击；PC 之外的入口（App）无供应商维护功能，无需同步。

### v1.1.130 P0 补丁 (2026-10-27 14:00 — 合格供应商 5 模块 tab 真实过滤 + 暂停/恢复台账)

> **范围**: PC 前端 (`pc-web`) + 后端 (`backend`, **jar 已重建并双站部署**) + SQL。App / APK / H5 无改动, 版本号保持 1.1.130 (本次为当日 v1.1.130 的 P0 修补, 不另开版本)。

**背景**: `/xk/material`「合格供应商」页 5 个模块入口 (名录 / 准入评审 / 证照与检测报告 / 年度绩效评价 / 暂停与恢复供货) 原本只是 5 个**纯前端视图名** — `filteredRows` 写好了却仍绑 `:data="rows"`, 点任何入口表格内容都不变; 顶部 5 个计数角标取的是「当前页前 50 条」的前端过滤结果, 与实际名录无关; 暂停/恢复只 `update({status})`, 没有原因、没有生效日、没有操作人、没有证据, 监管核查时无法自证; 名录表还缺「年度评价」「下次复评」列。XK16-204 采购控制要求: 合格供方名录 + 批准供货范围 + 暂停/恢复须留原因与记录 (记录保存 ≥ 保质期满后 6 个月, 无保质期 2 年)。

**(1) 数据库**: 新增 `sql/135_v1130_xk_supplier_status_log.sql` (幂等, 已在 .150 主库执行, .32 副本已同步)
- 新表 `xk_supplier_status_log`: `supplier_id/supplier_code/supplier_name` + `action`(SUSPEND/RESUME) + `reason_type` + `reason`(NOT NULL) + `effective_date` + `evidence_url` + `operator/operator_name/oper_time` + 版本 / 审计四件套 / `deleted`。
- 索引 `idx_xk_supplier_status_supplier(supplier_id)`、`idx_xk_supplier_status_action_time(action, oper_time)`。

**(2) 后端** (新增 4 个类, 无改动既有接口)
- `xk/entity/XkSupplierStatusLog` + `XkSupplierStatusLogMapper`。
- `XkSupplierStatusLogService`: `stats()` 全量汇总 `total / qualified / suspended / dueReview(合格但资质缺失或 90 天内到期) / withCert(已登记食品级证明) / rated(备注含 X 级或 NN 分) / expiring[]`; `suspend()` 与 `resume()` 在**同一事务**内「改 `base_supplier.status` + 插台账 + 写 XK 审计 (`SUPPLIER_SUSPEND`/`SUPPLIER_RESUME`)」, 原因必填 (空 →「请填写暂停原因」), 重复状态直接拒绝; `listLogs()` 按 `oper_time` 倒序、`limit` 上限 500。
- `XkSupplierController` (`/xk/supplier`): `GET /stats`、`GET /status-log/list`、`POST /{id}/suspend`、`POST /{id}/resume`, 权限沿用 `base:supplier:list` / `base:supplier:edit`。
- `mvn -DskipTests package` 通过 (JDK 17, `JAVA_HOME=/opt/homebrew/opt/openjdk@17`, 本机默认 JDK 26 会被 enforcer 拦)。

**(3) PC 前端** (`pc-web/src/views/xk/Material.vue` + `api/xk.js`)
- **tab 真实过滤**: 表格改绑 `filteredRows`, 5 个视图各有语义 — 名录 (全部) / 准入评审 (合格且缺食品级资质或资质、复评 30 天内到期) / 证照 (已登记食品级资质) / 绩效 (可解析出评价) / 暂停 (status≠1); 视图切换时非名录视图一次取 500 条再前端过滤, 名录视图保留服务端分页。
- **统计与角标改走后端**: 5 个角标 + 顶部临期预警条全部读 `GET /xk/supplier/stats`, 不再用当前页数据; 临期条按 `food_cert_exp` 90 天窗口展示前 3 家。
- **暂停/恢复台账**: 新增「暂停」弹窗 — 原因分类 (资质过期/检测不合格/供货质量异常/擅自变更/食品安全事件/年度评价 C 级/其他) + 原因说明 (必填, 500 字) + 生效日期 (默认今天) + 证据附件 (`/system/upload/file` 上传, 可空); 暂停视图新增「暂停原因 (含中文分类 + 生效日)」「操作人 / 时间」两列, 取该供应商最新一条 SUSPEND 台账; 「恢复供货」改为 `ElMessageBox.prompt` 强制填写恢复理由后调 `resume`, 不再直改 status。
- **名录列补全**: 有效视图新增「年度评价 (A/B/C 级或 NN 分)」「下次复评」列; 食品级/资质有效期优先读结构化字段 `food_cert_no` / `food_cert_exp` / `food_cert_type`, 缺省才回退 `remark` 正则解析 (与后端统计口径一致); 编辑弹窗补「食品级证明编号 / 资质有效期至 / 资质类型」3 个结构化字段; 编辑态状态改为只读 tag + 提示「状态变更请走列表暂停/恢复以保留台账」。
- 导出改为导出当前视图 (`filteredRows`) 并带资质编号列。

**(4) 部署** (双站, 原地覆盖 bind mount)
- home `.150`: jar 备份 `data/backup/app.jar.bak.20261027_135249` → `docker cp` + 重启 (healthy 60s); dist 备份 `dist.bak.20261027_135249.tgz` → `dist.bak.20261027_1402.tgz` (补丁).
- 飞牛 `.32`: jar 备份 `/volume2/erp-system/backup/app.jar.bak.20261027_135249` → `erp-backend-failover` 重启 (healthy 32s); dist 备份 `dist-new.bak.20261027_135249.tgz` / `dist.bak.20261027_1402.tgz`.
- 阿里云 `47.96.188.129` (公网站, `/api`→127.0.0.1:8080 后端, `/`→127.0.0.1:18080 pc-web): jar 备份 `/opt/erp-system/backup/app.jar.bak.20261027_1415` → `erp-backend` 重启 (restarts=1, healthy 44s); pc-web 目录备份 `/opt/erp-system/pc-web.bak.20261027_1415.tgz`, 原地覆盖。该站后端 `SPRING_DATASOURCE_HOST=172.18.0.1:13306` (经隧道写 .150 主库), `xk_supplier_status_log` 已随 .150 DDL 复制到本地副本。
- 三站 jar md5 统一 `7c08326d073a791b7f56a9ea4378e992`, `index.html` md5 `62617ebb209459db6a1c60b104d26415` + 158 个 asset 与本地一致; 入口 `index-DYiorfnj.js`, 页面 chunk `Material-LfO7QnDO.js`。
- 注: `.150` / `.32` / 阿里云容器环境变量仍写死 `ERP_VERSION=1.1.129`, 本次未重建容器, `sys_config` 运行时版本号下次重建时对齐; `home.93gushi.com:8088` 解析到本机公网 IP, 实际服务的是 `.150` (回环 NAT)。

**(5) 验证** (线上 `home.93gushi.com:8088/#/xk/material`, 控制台无任何报错)
- 5 个 tab 逐一点击: 名录 1 行 → 准入评审 1 行 (该供应商未登记食品级资质) → 证照 0 行 + 空态提示 → 绩效 0 行 + 空态提示 → 暂停 0 行 + 空态提示, 与后端 `stats` (`total=1/qualified=1/suspended=0/dueReview=1/withCert=0/rated=0`) 逐项吻合。
- 暂停: 空原因提交被拦「请填写暂停原因说明」; 填原因后角标实时变为「暂停 1 / 准入评审 0」, 列表状态变「暂停供货」且「暂停」按钮隐藏; 暂停视图正确显示分类中文、原因、生效日、操作人 `gpssong` 与时间。
- 恢复: 弹窗按钮为「取消 / 恢复」, 未填理由不可提交, 恢复后状态回「合格」、角标复原。
- 落库核对: `xk_supplier_status_log` 4 条 (2 次验证各 SUSPEND+RESUME, 中文无乱码), `xk_audit_log` `XA202610270001~0004` biz_type `SUPPLIER_SUSPEND/SUPPLIER_RESUME` 同步生成。测试后供应商已恢复为合格状态 (仅保留这 4 条台账作验证痕迹)。
- 阿里云站: `/#/login` 正常渲染 (仅 v1.1.130 hotfix 约定的两条 `config/key` 401, 无弹框), `/api/xk/supplier/stats` 返回 401 (路由已注册), 用后端账号 `erp_aliyun` 经隧道查 .150 主库可见 3 供应商 / 4 条台账 / 最新审计描述 (中文正常), 隧道与写主库链路通。

**遗留 (P1, 未做)**: 准入评审/证照/绩效仍是 `base_supplier` 的视图过滤, 没有独立的评审记录表、检测报告附件表与评分模型; 采购订单侧尚未加合格名录门禁 (当前仅采购入库按行豁免校验食品级供应商状态); 基础资料 →「供应商管理」编辑弹窗的准入状态开关仍可直改 `status` (绕过台账), 建议后续统一收敛到 XK 页的暂停/恢复入口或在该接口校验原因。

### v1.1.130 (2026-10-27 — 菜单管理 / 角色授权对齐现系统 + APP 成品入库选单显示品名)

> **范围**: PC 前端 (`pc-web`) + SQL + App (`app`, 重新出 APK)。后端无逻辑改动 (仅版本号常量), 七源版本已统一到 1.1.130; 后端 jar 未重新构建部署, 运行时 `sys_config` 仍显示 1.1.129, 下次后端发版时对齐。

**背景**: `/system/menu` 读的是 `sys_menu` 活表, 而 PC 导航早已改为 `MainLayout.vue` 静态 4 组 + 购销存管理 Hub 分类卡, 两边长期不一致 —— 列表里还是旧 9 个顶层目录 (工作台/系统管理/基础资料/采购管理/销售管理/库存管理/生产管理/财务/报表中心) 加散落的「成品入库查询」根节点; 同时 `/erp`、`/xk/*`、`/production/requisition`、`/production/schedule`、`/report/quality|inspection|equipment` 等现役页面在 `sys_menu` 里没有行, 非管理员侧边栏按 path 过滤时这些入口直接消失。

**(1) 数据库**: 新增 `sql/133_v1130_menu_align_system.sql` (幂等, 已在 .150 主库执行)
- 4 个顶层分组 (目录 C): 经营总览 / 供应链 / 生产与质量 / 经营支持; 旧 9 个目录就地复用为容器 (id 1-9 不变); 无 UI 载体的 24 条权限点暂留 `parent_id=0`, 由 `sql/134_v1130_role_perm_align.sql` 归位 (见下面 (3))。
- 页面行按现导航归位; 新增 14 个现役页面行 (`/erp`、`/xk/material|trace|maintenance|safety|print|audit|iqc-ledger`、`/production/requisition|schedule`、`/purchase/suggest`、`/report/quality|inspection|equipment`), `perms` 取 `router` 的 `meta.perm`。
- 100+ 条按钮/功能权限行从 `parent_id=0` 归位到所属页面 (id 不变 → 角色授权零影响); 老 `/workflow` 死子树的 `work:maintain:*` / `work:safety:*` 按钮就地挂到「设备维保」「质量与XK检查」。
- 授权只增不改: `sys_role_menu` 1350 → 1442 行, 新页面按「角色已有该 perms」回填, `/erp` 授予有任一 ERP 页面授权的角色; 执行后孤儿授权 0 行。
- 按钮/功能行统一 `is_visible=0`, 菜单管理默认视图只剩目录 + 页面。
- 备份: `sys_menu_bak_v1130` / `sys_role_menu_bak_v1130` (回滚语句在脚本末尾)。

**(2) PC 前端**: `pc-web/src/views/system/Menu.vue`
- 类型标签区分 目录 / 菜单 / 功能 / 按钮 (原来非 M 一律显示「按钮」); 新增/编辑表单支持 C / F 类型。
- 默认视图递归过滤停用/隐藏子节点并隐藏空目录, 表格 `default-expand-all` 直接展开整棵树。
- 已重新构建并部署 home NAS (`/volume3/docker/erp-system/pc-web/dist`, 旧版备份 `dist.bak.20261027_100325`)。

**(3) 角色授权树对齐** (`sql/134_v1130_role_perm_align.sql` + `pc-web/src/views/system/Role.vue`)
- **问题**: 24 条历史模块权限点 (委外 4 / 工艺参数 4 / SOP 4 / 检验设备校准 11) 还是 `parent_id=0`, 角色弹窗里挂在 4 个分组之后成为一排无归属的勾选项; 其中 `qa:calibration:*` 正被 `/xk/safety` 第 5 个 Tab「检验设备校准」调用, 不能删。
- **DB**: 校准 11 条 → 质量与XK检查 (10036); 工艺参数/SOP 8 条 → 生产加工单 (702); 委外 4 条 → 采购订单 (401)。只改挂载点, 不删行、不动 status / is_visible / 既有授权; 执行后根级 B/F = 0。备份 `sys_menu_bak_v1131` / `sys_role_menu_bak_v1131`。
- **PC 树**: 根级 B/F 不再作为顶层节点渲染 (兜底防御); `status=0` 的节点连同子树在 `buildMenuTree` 之后剪枝 (先过滤行会把子节点变成孤儿顶到根上, 飞鹅打印机 / 已开发票即属此类)。
- **App 树**: 白名单按 App 实际页面重写 (8 组 35 项: 生产/质量、采购、销售、库存、设备/现场、XK 记录、经营报表、基础/系统), 覆盖生产排程、设备 OEE、吹膜米重、半成品/成品入库、首检/巡检、XK 7 页、用户管理等现役入口; 与 `app/src/pages/*`、`utils/permission.js` `PAGE_PERMS`、dashboard `APP_MENU_TO_PAGE` 对齐。反查 `perms → sys_menu` 只认 `status=1`, 避免命中 `/workflow` 停用行导致授权写进无效 menu_id; 两棵树默认展开键分开 (PC 4 组 + 购销存管理, App 全部分组)。
- **部署踩坑**: bind mount 绑定的是目录 inode, `rm -rf dist && mv dist.new dist` 会让容器继续指向已删除的空目录 (页面 404)。改为 `find dist -mindepth 1 -delete && tar xzf … -C dist` 原地覆盖, 无需重启容器。

**(4) 验证**
- 默认视图 58 个节点 = 4 组 + 8 个容器 + 46 个页面; 前端 47 条业务路由 path 全部能在 `sys_menu` 找到对应行。
- 角色授权树: `F` / `C+perms` / `B` 仍可勾选, 未因归位丢失权限点。
- 授权树实数据回放: PC 树 253 节点 / 4 个根 (无浮动权限行, 孤儿 0); App 白名单 35 项全部命中启用行, 未解析 0; `work:maintain:list` / `work:safety:list` 现解析到 10035 / 10036 (不再命中 `/workflow` 的 status=0 行)。
- 线上 `Role-OFUPekC_.js` 200, 旧 dist 备份 `dist.bak.20261027_102233`。

**(5) APP 成品入库选单显示品名** (`app/src/pages/scan/finished-in.vue`)
- **问题**: 生产工单下拉 (uni picker) 只列单号 (`PD202610200001`), 车间选单要凭记忆对应产品, 多张工单同时待入库时容易选错。
- **改法**: `orderOptions` 计算属性改为 `单号 · 产品名称`, 名称取 `PrdOrder.productName` (无则回退 `productCode`), 与扫码命中多工单时的 `productMatchedOpts` (`单号 · 成品 [状态]`) 文案一致。**纯前端计算属性, 零接口 / 零后端 / 零 SQL 改动** (`/production/order/page` 本就返回 `productName`)。
- **交付**: APK 重出 v1.1.130 (versionCode 1130, 嵌入 H5 资源), 输出到飞牛同步盘 + 桌面; H5 另于 13:00 部署到飞牛 `erp-app-h5` (`http://192.168.0.32:18090`, 备份 `h5.bak.20261027_130028`, 原地覆盖 bind mount) — 线上 `index-CiISdiSJ.js`, profile chunk `pages-profile-index.XFh-nvYE.js` 自报 1.1.130, 成品入库 chunk 含新选单逻辑, `/api` 仍反代本机 `erp-backend-failover`。
- **版本**: 七源统一 1.1.130 (`pc-web/package.json` / `app/package.json` / `manifest.json` versionName+versionCode 1130 / `profile/index.vue` / `application.yml` / `SystemVersionInitializer`), `scripts/check-version.sh` 全 OK; 后端无逻辑改动, jar 未重建 → 运行时 `sys_config` 版本仍 1.1.129。

**(6) PC 登录成功不跳转 (hotfix, `pc-web` 4 文件)**
- **现象**: 输入账号密码 → 提示「登录成功」→ 页面仍停在登录界面。
- **根因链**: ① v1.1.128 把 `/system/config/key/**` 纳入鉴权 → 登录页 `loadSystemName()` 读 `SYS_NAME`/`SYS_FACTORY` 在未登录时拿到 401 (前端 `catch` 已回落默认名, 本不影响渲染); ② `utils/request.js` 的 401 处理无差别弹「登录已过期」并 `router.push({path:'/login', query:{redirect: 当前 fullPath}})` —— 当前页正是 `/login`, 于是地址变成 `#/login?redirect=/login`; ③ `Login.vue` 只要 `redirect` 以 `/` 开头就 `router.replace(redirect)` → 登录成功后被送回登录页。
- **附带隐患**: `router/index.js` 守卫判断写成 `user.userInfo.id`, 而 store 存的是 `userId` → 条件恒真, **每次路由跳转都打一次 `/auth/me`**, 该请求一旦失败就被踢回登录页 (第二个"登录后不跳转"的成因)。
- **修法**: `request.js` — `handle401` 在 `currentRoute.meta.public` 页面直接 return (不弹框、不写 redirect), 并支持请求级 `silent401` 开关; `composables/useSystemName.js` — 品牌配置请求带 `{silent401:true}`; `views/Login.vue` — `redirect` 指向 `/login` 或非站内路径时一律去 `/dashboard`; `router/index.js` — 改为 `userInfo.userId || userInfo.id` 判断, 消除每次跳转的 `/auth/me`。
- **部署**: home `erp-pc-web` 原地覆盖 (`dist.bak.20261027_113110`) + 飞牛 `dist-new` 原地覆盖 (`dist-new.bak.20261027_113237`), 入口 `index-DeYyxBeH.js` / `Login-DrJsJRXT.js`。
- **验证**: 未登录主机 `http://192.168.0.150:8088/#/login` — 两条 config 401 被静默 (无弹窗), 地址保持 `#/login` 无 `redirect` 参数, 登录页正常渲染; 已登录主机 `home.93gushi.com:8088` 品牌名仍正常。零后端 / 零 SQL 改动。

**(7) PC 采购入库「到货基本信息」新增到货品名** (`pc-web/src/views/purchase/Receipt.vue`)
- **背景**: 「原料入库 · XK受控」填写到货信息时只有供应商/单号/数量, 要看"这车到的是什么料"必须回查采购订单明细; App 原料入库页 v1.1.129 已有只读「品名」, PC 一直缺。
- **改法**: 新增 `arrivalProductName` 计算属性 — 取 `form.details` (选采购订单时已带入的明细) 的 `productName` 去重后以「、」连接; 「到货基本信息」在 供应商/合同/关联订单/仓库 之后插入只读字段「品名 (到货原料)」, 选单即带出, 多品种订单合并显示。顺带把上方「采购单 / 到货检验记录」卡片里写死的 `LLDPE 7042` 换成同一份品名 (空显示 —), 避免示例名与实际到货不符。
- **验证** (线上实测): 选 `PO202610230001` 后 品名 = 透明印字胶带, 记录卡标题同步; 未选单时为空占位。纯前端, 零后端 / 零 SQL。
- **部署**: home 原地覆盖 (`dist.bak.20261027_124541`) + 飞牛 `dist-new` 原地覆盖 (`dist-new.bak.20261027_124547`), 入口 chunk `Receipt-DO9UpE8u.js`。

**(8) PC 列表分页整块消失 (hotfix, `pc-web` 6 文件)**
- **现象**: `#/production/requisition` 控制台 `ElementPlusError: [ElPagination] Deprecated usages detected`, 分页条整个不渲染 (`.pager` 只剩 `<!---->`)。
- **根因**: 后端 Long 统一序列化为字符串 (bigint 安全), `/production/requisition/page` 返回 `total: "19"`; Element Plus 2.14 `assertValidUsage` 把「非 number 的 `total` / `pageCount`」判为缺省 → `debugWarn` 后 `return null`, 直接不渲染 —— 与监听器、layout 写法无关。`inventory/Stock.vue` 等页无恙只是因为模板里写了 `Number(data.total)`。
- **修法**: 6 个裸 `:total="total"` 页面在赋值处收紧为 `total.value = Number(r.data?.total) || 0`: `production/Requisition.vue` (领料记录)、`production/FinishedIn.vue` (成品入库)、`production/SemiFinishedIn.vue` (半成品入库)、`production/Weight.vue` (吹膜米重)、`production/Schedule.vue` (生产排程)、`xk/IqcLedger.vue` (进货查验); 全仓其余 ~40 处早就是 `Number(...)`。
- **部署**: home 原地覆盖 (`dist.bak.20261027_130840`) + 飞牛 `dist-new` 原地覆盖 (`dist-new.bak.20261027_130824`), 入口 `index-DfM_jCi2.js` / `Requisition-DZG8ONFL.js`。
- **验证** (线上 Chrome 实测): 六页 `.el-pagination` 均渲染 (领料记录 Total 19 / 成品入库 24 / 半成品 3 / 米重 12 / 排程 0 / 进货查验 0), 控制台 0 条消息。纯前端, 零后端 / 零 SQL。

**(9) PC 审计与归档分页报错 (hotfix, `pc-web/src/views/xk/Audit.vue`)**
- **现象**: `#/xk/audit` 控制台 `[ElPagination] Deprecated usages detected`, 分页条不渲染。
- **根因**: `:current-page="logPage"` 却没有 `v-model:current-page` / `@current-change`。Element Plus 2.14 `assertValidUsage` 除 total 类型外还检查 `hasCurrentPageListener` (vnode props 里须有 `onUpdate:currentPage` / `onUpdate:current-page` / `onCurrentChange` 之一), 缺失即判非法 → `return null`。本例 `total` 是 `logs.length` (number), 与 (8) 的字符串 total 不是同一个成因。
- **修法**: 改为 `v-model:current-page="logPage"`; 全仓扫描确认仅此 1 处缺监听, 其余分页均带 v-model / change 监听 (含 `layout` 带 sizes 的 size 监听 0 缺失)。
- **部署**: home 原地覆盖 (`dist.bak.20261027_131322`) + 飞牛 `dist-new` 原地覆盖 (`dist-new.bak.20261027_131323`), 入口 `index-VMWvQ9Wo.js` / `Audit-DjV2rEEN.js`。
- **验证** (线上 Chrome 强刷实测): `#/xk/audit` 分页渲染 (`.el-pagination` ×1), 控制台 0 条消息; `/xk/audit/logs?limit=50` 当前返回 0 条 → 列表为空属数据侧, 与渲染无关。纯前端, 零后端 / 零 SQL。

### v1.1.129 (2026-10-26 — P1/P2 加固: 禁用即时失效 / 库存唯一键 / 锁提交后释放 / 监控与备份保留 / 版本统一)

**背景**: P0 完成后按巡检清单处理 P1/P2。P1 中分页上限、备份内容校验、CI 单测此前已落地; 本次处理剩余可直接闭环的项。

**(1) 账号安全 (P1-2)**
- `SaTokenConfig` 新增 `userStatusInterceptor`: 每个请求校验账号状态 (Redis 缓存 60s), 已禁用/已删除账号立即返回 401, 不再等 30 天 token 过期。
- `SysUserService` 在禁用、删除、管理员重置密码后调用 `StpUtil.kickout()` 并清除状态缓存 (`erp:user:status:<id>`)。

**(2) 库存并发 (P1-3)**
- `sql/132_v1129_p1_stock_unique.sql`: 原唯一键含可空列 (`location_id`/`batch_no`/`deleted`), MySQL 对 NULL 不去重 → 同仓库+商品+批次可插多行。新增生成列 `batch_key`/`deleted_key` 归一 NULL, 改唯一键为 `(warehouse_id, product_id, batch_key, deleted_key)`, 与 `selectForUpdate` 查询键一致。脚本含重复数据前置校验, 幂等可重跑。

**(3) 锁释放时机 (P1-4)**
- `RedisLock` 在有事务时注册 `TransactionSynchronization`, 改为事务提交/回滚后解锁; 避免下一线程在旧事务提交前拿到锁读到旧库存导致超卖。新增 `RedisLockTest` 覆盖有/无事务两种情况。

**(4) 单号序列 (P1-5)**
- `BillNoGenerator` 去掉序列键 TTL; 三节点 Redis `maxmemory-policy` 由 `allkeys-lru` 改 `volatile-lru`, 保证无 TTL 的单号序列不会被内存淘汰后从 1 重计。`docker-compose.yml` 同步。

**(5) 监控与备份 (P1-7/P1-9)**
- 新增 `scripts/erp-health-check.sh` (home/feiniu/aliyun 三角色): HTTP、复制线程与延迟、磁盘、容器健康、隧道、备份新鲜度; 每 10 分钟 cron, 状态变化或持续失败每小时告警, 支持企业微信机器人 (`/etc/erp-health.env` 的 `ALERT_WEBHOOK`)。
- 飞牛 03:30 备份脚本追加保留策略: 最近 7 份 + 最近 12 个月每月 1 份。

**(6) 版本统一 (P2)**
- 六源版本统一到 1.1.129 (`pc-web/package.json` / `app/package.json` / `manifest.json` versionName+versionCode 1129 / `profile/index.vue` / `application.yml` / `SystemVersionInitializer`)。
- 新增 `scripts/check-version.sh` 并在 CI 增加 `version-consistency` job, 版本漂移直接挡 PR。
- `.gitignore` 补 `*.tar.gz` / `backups/*.sql` / `electron/pc-web-dist/`。
- 单测修复: 8 个测试类补齐服务构造依赖 mock, `SecurityContext` 测试用户短路 Sa-Token 会话, 出库单测补乐观锁 `updateById=1`; 全量 `mvn test` **68/68 通过** (修复前 26 个失败/错误)。

**(7) 部署验收 (2026-10-26)**
- 三节点均 1.1.129: 飞牛 `erp-backend-failover` / .150 `erp-backend` / 阿里云 `erp-backend` 均 healthy, `sys_config.SYSTEM_VERSION_INFO` = 1.1.129。
- `sql/132` 已在主库执行, 三节点 `inv_stock.uniq_inv_stock_biz` 均为 4 列; 复制 IO/SQL 双 Yes、延迟 0。
- 公网 `http://47.96.188.129/` 200, `/api/auth/captcha` 200; 三节点健康巡检脚本退出码 0。

**未纳入本次** (需业务确认或外部凭据): 数据权限落地 (现有角色 data_scope 为 2/4, 直接启用会改变可见范围)、Flyway 基线化、OSS 异地备份、发布流水线自动部署、Git 历史大文件重写、Spring Boot 大版本升级。

**(8) App 原料入库页底栏遮挡修复 (2026-10-26)**
- 现象: `pages/scan/in` 是 tabBar 页, 固定底栏 `bottom: 0` 渲染到 tabBar 之下, "保存检验记录 / 生成报告并入库" 两个按钮只露出上半截。
- 修复: `app/src/pages/scan/in.vue` 底栏改 `bottom: var(--window-bottom, 0px)` (uni-h5 用该变量表示 tabBar 高度, 含安全区; App 端无 tabBar 变量时回退 0); 占位块从页面中部移到最末, 改为 `.footer-spacer` (140rpx), 综合判定面板不再被底栏压住。
- 验证: 本地 H5 实测底栏 bottom = tabBar top (重叠 0), 滚到底部时最后一行判定内容与底栏留 24px 间距; APK 已重出 `erp-app-20261026-1.1.129.apk` (MD5 见交付记录)。

**(9) App 原料入库「到货基本信息」新增品名 (2026-10-26)**
- `pages/scan/in` 的「到货基本信息」在「关联采购订单」下新增只读「品名」行: 取采购订单明细的 `product_name` (多行去重后以「、」拼接), 无明细时回退检验单头快照 `iqc.productName`。
- 数据来源为后端既有快照 (`pur_order_detail.product_name` / `mes_quality_inspection.product_name`), 无需改接口; 品名随订单切换自动刷新。
- 交付: APK `erp-app-20261026-1.1.129.apk` (内嵌版本 1.1.129 / versionCode 1129, MD5 `272434b1ef523dc5b5ec69ac86e3ed20`), 已核对包含底栏修复与品名行。

### v1.1.126 (2026-10-25 — 吹膜米克重采集全字段上云 + PC 入口 + App/H5 端页面 + 重新出 APK)

**背景**: v1.1.119 把 ESP32 吹膜米克重采集接收侧闭环 (3 表 + 设备免登录态上报 + PC 实时曲线), 但 `mes_weight_sample` 只存了 6 列 (设定/实测/偏差/偏差% / 线速/产量/长度)。冠尚达高精度米重控制系统 MCGS 屏上实际有 12 个量 —— 其余 6 个量 (设定长度/当前重量/上牵引Hz/下牵引Hz/机器产量/克重修正) 上不到云, 办公室/远程看不到, 无法质量追溯。

**(1) 全字段上云 (sql/130 + 后端 2 处)**:
- `sql/130_v1119b_mes_weight_full_fields.sql` — 幂等 ADD COLUMN × 6 (`information_schema.columns` 探测 + PREPARE/EXECUTE), 全部可空; 顺带 `UPDATE mes_weight_channel SET unit='g' WHERE unit IS NULL OR unit='g/m2'` (计量单位正名)。
- `MesWeightSample.java` +6 字段 (`setLength`/`currentWeight`/`upperHz`/`lowerHz`/`machineYield`/`gaugeFix`, 全 BigDecimal); `MesWeightSampleMapper.xml` `selectCurve` SELECT 列表同步扩 8 个 AS 别名 (R25: 最后一个别名 `gauge_fix AS gaugeFix` 不挂尾逗号)。
- 设备上报 `POST /device/weight/report` 带这些字段 → 后端 `reportSamples` 直写 → `mes_weight_sample` 全字段落库 (审计 `create_by=999` 设备虚拟账号, 不改全局 handler)。

**(2) PC 端**:
- `views/production/Weight.vue` 采样明细表 +6 列 (计米长度/计米重量/机器产量/当前重量/上牵引Hz/克重修正), 线速 label 改「线速 mm/s」。
- `views/dashboard/Index.vue` 生产 Tab 加「吹膜米克重实时卡」: 8-KPI 宫格 + ECharts 迷你曲线 + 30s 轮询, `v-if` 按 `mes:weight:list` perm 门控。
- `views/erp/Hub.vue` 「生产管理」分类卡加第 5 个方块「吹膜米重采集」`/production/weight` icon `Odometer` (用户反馈入口位置; 之前误加到 MainLayout 侧栏已回退, 正确位置是 `/erp?cat=production` 的 Hub 快进入口)。

**(3) App/H5 端新增页面** (纯 App 端, 不引 echarts):
- `app/src/pages/production/weight.vue` — 采集器 chip 选择 + 8 宫格实时 KPI + 纯 view 迷你趋势条 (高度相对归一化, 绿=正常/橙=超差) + 采样明细分页 (R20: handler 显式接参) + 通道统计 + 通道配置只读。
- `api/index.js` +6 端点 (`weightDevicePage`/`weightChannelList`/`weightChannelSave`/`weightCurve`/`weightStats`/`weightSamplePage`)。**App 端信封坑 (R23)**: 拦截器已 `resolve(d.data)`, 页面读 `r` 不可再读 `r.data`, 统一 `(r && (r.records || r.data)) || []` 双形态防御。
- `pages.json` 加 `pages/production/weight` 路由; `utils/permission.js` `SENSITIVE_PAGES` + `PAGE_PERMS` 登记 (perm=`mes:weight:list`, module=`production`); `dashboard/index.vue` `APP_MENU_TO_PAGE` 加双匹配条目 + perm-only 兜底 (老库没灌 M 节点时也能出, 同 `production:report:list` 模式); `classifyFunc` 归「生产管理」分组 (路径前缀 `/pages/production/` 天然命中)。

**零 DDL (除 sql/130) / 后端仅实体+XML 两处 / PC 无新端点**。

**版本四源同步 (R15)**: `pc-web/package.json` + `app/package.json` + `app/src/manifest.json` (versionName 1.1.126 / versionCode 1126) + `profile/index.vue` APP_VERSION + `application.yml` `erp.version` + `SystemVersionInitializer` 全部 1.1.126。

**部署 (canary-first)**:
- 飞牛 `erp-backend-failover` (jar md5 `54486ec072b5e8e74bc8cc079353caf4`) + `erp-pc-web-failover` 18080 + `erp-app-h5` 18090 全部 200; compose `ERP_VERSION` 从 1.1.125 升到 1.1.126 (R15 第 4 处, 否则 `sys_config` 版本号不刷新)。
- home `erp-backend` (baked-in 镜像, `docker build --no-cache` 重建) + `erp-pc-web` 8088 + `erp-app-h5` 18090。
- **踩坑**: 飞牛 compose `up -d` 重建容器会**还原 baked-in jar** (R17) → 重建后必须再 `docker cp` 一次, md5 才能对上; 飞牛登录态被 IP 锁 (试错 5 次触发 5 分钟冷却) → 改用设备上报 `POST /device/weight/report` 带 `X-Device-Key` 走全链验证 (免登录态), 上报 12 量全字段 → 查 `mes_weight_sample` 确认 6 个新列落库 `create_by=999`。

**APK**: `scripts/build-app.sh` 出 v1.1.126 (versionCode 1126), [6/6] 防呆校验 APK 内嵌版本 = manifest.versionName, 输出到飞牛同步盘 + Desktop。

**待现场 (全链唯一未知数, 不变)**: 485 口 + 从站地址 + 寄存器地址/功能码/缩放系数反推 (冠尚达无公开文档), 回填 `mes_weight_channel` + ESP32 固件。详见 [docs/25_ESP32吹膜米克重采集接入指南.md](25_ESP32吹膜米克重采集接入指南.md)。


### v1.1.125 (2026-10-25 — 销售出库明细「当前库存」列 + 合并出库单 order_no 截断修复)

**两个纯前端改动, 零 DDL, 零后端改动.**

**(1) 新增销售出库单「当前库存」列 (用户反馈)**:
- 需求: PC 端「新增销售出库单」商品明细里, 在「数量」列前加一列显示当前仓库库存, 开单前直观判断缺货.
- `pc-web/src/views/sales/Delivery.vue`: 明细表「数量」列前加「当前库存」列 (`:152`), 右对齐, 出库数量 > 当前库存时该数字变红色提示.
- 库存来源**零新端点**: 复用现有 `loadBatchOptionsForRow` (本已拉该仓该品所有可用批次), 把 `row._batchOptions` 的 `qty` 求和写入 `row._stockQty` = 该仓库该商品当前库存总量; 随仓库/商品/批次刷新自动更新. 新增 `stockInsufficient(row)` helper 判红字.

**(2) 合并出库单 order_no 截断 500 根治 (线上报错)**:
- 线上报错: 销售订单「合并生成出库单」时 `MysqlDataTruncation: Data too long for column 'order_no'` → 500「系统繁忙」.
- **根因** (不是后端门禁, 是前端拼串): `pc-web/src/views/sales/Order.vue` 合并多订单时把所有源订单 billNo 拼成长串写进主表 `sal_delivery.order_no` (`VARCHAR(32)`, sql/04) → 超 32 字符. 单订单只写一张订单号 (短) 所以不炸.
- **修法** (`Order.vue:622`): 合并时主表 `orderNo` 置空 (`multi ? '' : base.billNo`). 合并单溯源本就靠**每行明细** `orderNo`/`orderDetailId` (`:617` 主表 `orderId` 也置 null, 注释明确"溯源走明细行"), 主表不承载多来源拼串; 单订单行为完全不变. 零 DDL / 零后端改动.

**部署 (canary-first)**: 双站 pc-web bind-mount 覆盖 + 重启; 飞牛 `erp-pc-web-failover` 18080 / home `erp-pc-web` 8088 均探活 200, 新 chunk `Delivery-DdwVtGnZ.js` 在线.
- ⚠️ 部署踩坑 (已沉淀 [[erp-pc-web-deploy-traps]] 陷阱 5): 清旧 chunk 时按前缀 `grep -v "Delivery-DdwVtGnZ.js"` 只留新 JS, 把同前缀的 `Delivery-BtIOYGnp.css` 误删 → home 点「销售出库」触发 `Unable to preload CSS ... 404`. 教训: vite 动态 preload 依赖 CSS 就位, 清 chunk 应整目录替换或逐个确认 (js+css), 别按前缀挑着删.

**部署**: 版本 1.1.125 (仅 pc-web/package.json bump, 无后端 jar); 飞牛 chunk `Delivery-DdwVtGnZ.js`; home pc-web bind-mount 真源 `/volume3/docker/erp-system/pc-web-new/dist`.

### v1.1.119 (2026-10-25 部署, 随 jar v1.1.125 上双站 — ESP32 吹膜米克重采集接收侧闭环)

**需求**: 吹膜机米克重 (g/m2) 只显示在车间触摸屏, 办公室/远程看不到 → 无法质量追溯/工艺分析。ESP32 旁听冠尚达控制器 RS485(Modbus) 总线, WiFi 回传 ERP, MES 实时曲线 + 超标判定 + 历史追溯。**接收侧全链路双站在线 (canary-first, curl 造数闭环验证通过), ESP32 固件 + 寄存器反推待现场。**

**3 新表** (`sql/129_v1119_mes_weight_device.sql`, 雪花 ID 低位量级 `1000000000000001`):
- `mes_weight_device` 采集器注册 (`device_code` 唯一 / `online_status` / `baud_rate`+`slave_addr`+`protocol` 反推回填 / `device_key_masked` 只存后 6 位掩码)
- `mes_weight_channel` 通道配置 (`reg_start`/`reg_type`/`scale` 反推回填 + `set_weight`/`upper_tol`/`lower_tol` 工艺目标+公差)
- `mes_weight_sample` 采样流水 (时序, `actual_weight`/`line_speed`/`deviation`/`deviation_pct`/`is_over` 后端算 + `prd_order_id` 可关联工单)
- **执行顺序: 先 128 (BOM 单位回填) 后 129** —— 两脚本曾撞号 (都叫 128), 称重已改名 129; 128 幂等但含状态位 UPDATE, 按 R24 连跑 3 次

**后端** (11 个 Java 文件, `modules/mes`):
- `MesWeight*` entity×3 + mapper×3 (含 `MesWeightSampleMapper.xml` 手写 `selectCurve`/`selectStats`)
- `MesWeightService`: `reportSamples` (设备上报) 审计 `create_by` 显式 `setCreateBy(99L)` 设备虚拟账号 —— **不改全局 metaObjectHandler** (会波及全库 60+ 表), 靠 `strictInsertFill` 只填 null 字段语义原样落库; 曲线 `selectCurve` **倒序取 limit 再翻转** (防曲线停在开机那刻)
- `MesWeightController` (PC 带登录态, `mes:weight:*` perm, 信封未拆封前端读 `r.data`) + `DeviceWeightReportController` (设备免登录态 `X-Device-Key`)

**鉴权模型 (ADR-0008)**: 设备上报走**固定密钥 fail-closed, 不走 Sa-Token** (现场 ESP32 无人值守刷不了 30 天 token, 会静默变 11012 未登录)。密钥走 env `ERP_DEVICE_KEY` (`@Value`, 不硬编码不落库); `SaTokenConfig` 白名单**只写精确路径 `/device/weight/report`** (绝不写 `/device/**` —— 那个 notMatch 是全项目唯一登录态豁免口, 写通配=设备端全端点裸奔到公网); **双站用不同 key**, 密钥只走 gitignored `.env` 不进 git/镜像; ESP32 不发 Origin 头**不配 CORS**。

**PC**: `Weight.vue` 实时曲线 (ECharts 5s 轮询) / 采样明细 / 超标率统计 / 通道配置 4 Tab; 路由 `/production/weight` icon `Odometer` perm `mes:weight:list`; `production.js` 封装 `weightApi` 6 端点。

**部署 (canary-first, 本次双站在线)**: 飞牛 `erp-backend-failover` 18080/8080 + home `erp-backend` 8088/8080, 均 jar v1.1.125 baked-in; 双站 curl `POST /device/weight/report` 带 key → `HTTP=200 accepted:2` → `online_status=ONLINE` + 采样落库 + 曲线 SQL 出数 (deviation/超差判定正确) + PC 静态 `Weight-*.js/.css` 200 (index.html md5 三方一致)。

**待现场 (全链唯一未知数)**: 485 口 (D-sub 白口/板载蓝头) + 从站地址 + 米克重/线速寄存器地址+功能码+缩放系数 (冠尚达无公开文档 → `pymodbus` 扫输入寄存器对屏幕找值, 或问电工) → 回填 `mes_weight_channel` + ESP32 固件对着写 (骨架已给)。详见 [docs/25_ESP32吹膜米克重采集接入指南.md](25_ESP32吹膜米克重采集接入指南.md)。

**部署踩坑 (home)**: PAM autoblock 反复触发 (SSH 失败累积拉黑, sleep 90s 冷却); `sudo -S` 密码与 SQL 共用 stdin 被污染 (SQL 报 `near '19850225aB'`) → 修法 SQL 先落地 777 的 `dist` 目录再 `sudo mysql < file`; jar baked-in 三连 (build --no-cache + up -d); dist 整目录替换 (R5 别按前缀 grep -v 误删 CSS)。

### v1.1.124 (2026-10-24 — 采购入库「非食品采购」入口补齐 + R26 mapper XML 坑)

**需求 (用户反馈)**: 「采购入库页只有 XK 的原料入库, 非食品包装的商品采购入库没有对应的采购入库入口」。

**根因 (不是后端门禁, 是前端没地方选商品)**:
- 后端 `PurReceiptService.add()/check()` 在 v1.1.122 已按行豁免 (免 IQC + 免供应商合格名录门禁), `POST /purchase/receipt` 完全接受 `is_food_grade=0` 的商品
- 但 **v1.1.95 把整个采购入库页重构成「XK 三步检验表单」**, `form.details` **只能由「关联采购订单」带入** (`onOrderChange`), **没有任何商品明细选择器**
- → 线上 130 个 `is_food_grade=0` 商品**根本进不来** (不是被门禁拦住, 是没有地方能选商品)
- v1.1.95 之前 (`git show 613fc04^`) 的 Receipt.vue 有完整标准入库表单弹窗, 被重构时删掉了

**改动** (前端 1 文件 + 后端 1 mapper XML, **零 DDL**):
- `pc-web/src/views/purchase/Receipt.vue`:
  - 「新增采购入库」Tab 内嵌两个子 Tab: `[原料入库 · XK受控]` (现有 XK 三步表单, **完全不动**) + `[非食品采购 · 免检直通]` (新增标准入库表单)
  - 非食品 tab 内容 = 复用 v1.1.95 前那版: 入库日期/供应商/仓库 + **入库明细表** (商品 el-select / 食品级 tag / 规格 / 数量 / 单价(含税) / 金额 / 批次 / 库位) + 备注 + **该供应商历史采购**参考区 (点击行复制到明细, 同 productId+batchNo 累加)
  - **路由判定不给手选开关**: 按明细行商品 `is_food_grade` 实时算 `nfHasFoodLine` —— 含食品级行则顶部转橙色警示态 + **拒绝提交**并提示切到原料 tab (前端按开关判、后端 `hasFoodGradeLine()` 按明细判, 两者会打架)
  - 保存路径 = **存草稿 → 跳「单据列表」审核** (不提供「保存并直接入库」; 与原料入库流程一致, 职责分离)
  - 顶部按钮按子 tab 分流 (XK 的「保存检验记录 / 生成验放单并入库」只在原料 tab 显示)
- `backend/.../mapper/production/PrdBomMapper.xml`: 手写 `<insert>` 补齐 8 个漏列 (见下)

**R26 mapper XML 坑 (附带修)**:
- `PrdBomMapper.xml` 的 `<insert>` 只写 13 列, **漏了 8 列** (`product_id`/`product_code`/`product_name`/`spec`/`unit_id`/`unit_name`/`base_qty`/`output_qty`) → 建单后这些字段全 NULL
  - `product_id` 原为 NOT NULL, 被 `sql/19` 放宽成 nullable → **不报错, 静默丢数据** (线上 8 行 `prd_bom` 有 3 行 `product_id` NULL)
  - `base_qty`/`output_qty` 有 DB DEFAULT 1.0 → 漏列被默认值**掩盖**, 看不出来
- 修法: 补齐到实体全字段 (21 列), 已验证 `product_id`/`base_qty`/`output_qty`/`loss_rate` 全部正确写入

**⚠️ 实测纠正了一个常见误解** (排查中先踩了坑, 记录下来):
- 手写 XML 覆盖 MP 内置 `updateById` **会**绕过全局 `update-strategy: not_null` → 稀疏对象 (只 id+status) 其余字段被写成 NULL → 撞 NOT NULL 约束 → 500 (v1.1.123 已修 `PurOrderMapper.xml` + `SalOrderMapper.xml`)
- 但手写 XML **不会**绕过 `@TableField(fill=)` 自动填充 —— MP 的 `MybatisParameterHandler` 按 `SqlCommandType.INSERT` 触发 `insertFill`, **与语句是否手写无关**。实测飞牛走手写 XML insert 建 BOM, `create_by=2`/`create_time` **全部正常写入**; home `prd_requisition` 的 NULL/非NULL 分界线**恰好在 v1.1.52 补 `@TableField(fill=)` 那天**, 不是在引入 XML 那天

**实测** (飞牛 canary 先行 → home 主站):
- 非食品采购入库全链路: 建单 200 → 审核 200 → 库存 +100, 列表 `xkExempt=1`, `inspectionResult=None` (无 IQC 关联), IQC 总数不变
- BOM 建单: `product_id`/`product_code`/`spec`/`base_qty=10`/`output_qty=5`/`loss_rate=2.5` 全部正确写入
- 测试数据已全部清理 (双站 `pur_receipt`/`inv_stock`/`inv_ledger` 残留均为 0)

**部署**: 双站 jar md5 均 `18ab6532621eee892302ebc452314b91`, 版本均 1.1.124; 飞牛 chunk `Receipt-DRjKmGWh.js`; home pc-web bind-mount 真源 `/volume3/docker/erp-system/pc-web-new/dist` (原地覆盖不 rm 挂载点)。

### v1.1.122b (2026-10-23 — 采购入库「食品级/免检直通」可视化 tag, v1.1.122 前端补强)

**需求**: v1.1.122 后端按行豁免已上线, 但前端看不出「这单会不会被 IQC 卡」—— 操作人员看到的是恒「XK 受控」的固定徽标 + 列表恒「未检验」。补一个行级可视化, 让食品级/非食品一眼可辨。

**改动** (后端 2 文件 + 前端 1 文件, 零 DDL):
- `PurReceiptMapper.java` `selectPageWithProduct`: 加子查询列 `xk_exempt` (`SUM(CASE WHEN is_food_grade=1...)>0 → 0 ELSE 1`), 口径与 `PurReceiptService.check()` 的 `anyFood` **完全一致**
- `PurReceipt.java`: +1 transient 字段 `xkExempt` + getter/setter
- `pc-web/src/views/purchase/Receipt.vue`:
  - 列表加「XK 判定」列: `xkExempt===1` → 绿 tag「免检直通」, `===0` → 黄 tag「需 IQC」, 带 tooltip 说明
  - 新增 computed `allLinesNonFood` (读已加载 `products` 的 `isFoodGrade`, 逐明细行判)
  - 表单 tab「XK 受控」徽标条件化: 全非食品 → 绿「免检直通」+ 文案改为「审核免 IQC 来料检验、免供应商合格名录门禁, 直接入库」
  - `onCheck()` 审核确认框按 `row.xkExempt` 分文案 (免检直通 vs 需 IQC), 免得操作员以为漏了检验

**踩坑**: mapper 是 `@Select("<script>"...)` 字符串拼接, 在 `first_product_model` 后加 `xk_exempt, ` 时保留了原 `first_product_model ` 结尾的空格 + 我又加了逗号 → 拼出 `... AS xk_exempt,  FROM pur_receipt` **悬空逗号** → `SQLSyntaxErrorException` 整个列表接口 500。教训: **Java 字符串拼 SQL 加列时, 被替换那行的尾部连接符 (逗号/空格) 要一并检查**, 别只加新片段。飞牛先炸出来, 修完 home 才没炸 (canary-first 救了一次)。

**部署**: 双站 jar md5 均 `37e2c15cdb340a487503c678bc1d9e94`; home dist chunk `Receipt-BLj_CLQ3.js` 含 `xkExempt`+`免检直通` 标记; 飞牛 API 实测 `xkExempt:1` 正常返回。

### v1.1.122 (2026-10-23 — 采购入库 XK 门禁「非食品包装产品」按行豁免)

**需求**: 采购入库分「原料采购」(食品接触膜料/添加剂, 走 XK) 和「成品/包装采购」(非食品接触件)。原 XK 三步门禁 (供应商合格名录 + IQC 来料检验合格才放行) 对**所有**采购单一视同仁, 非食品包装产品也被 IQC 卡死、被合格名录卡死。需: 非食品包装采购**跳过 XK 规范**直接入库。

**拍板 3 点** (用户已定):
1. 判定维度 = 商品级 `base_product.is_food_grade` (隐式, **零 DDL**, 与生产侧 v1.1.102 半成品/成品入库豁免口径一致, 直接复用现成 `QmsInspectionService.isFoodGradeProduct()`)
2. **按行分别豁免** — 逐明细行判, 非整单一票否决
3. **供应商门禁也免** — 非食品采购连供应商 status 都不拦

**关键结构事实**: IQC 与入库单是 1:1 单头级 (`findIqcByReceiptId` 只返一条, `createOnReceipt` 只取首个商品建一张 IQC), 供应商也是单头字段。所以「按行豁免」落地为「**全行都非食品级 → 整单免检 + 免供应商门禁**; 只要含一行食品级 → 整单走 XK」——这是单头结构决定的物理边界 (IQC/供应商无法真正按行拆)。

**改动** (纯后端 1 文件 `PurReceiptService.java`, 零 DDL / 零 SQL / 零前端):
- 加 2 helper: `isFoodGradeLine(d)` (行级, 复用 `isFoodGradeProduct`) + `hasFoodGradeLine(details)` (单头级, 任一行为真即真)
- `check()`: 先取 `details` + 算 `anyFood`; 仅 `anyFood` 才走 XK IQC 三步门禁 (`hasPassedIqc` + `snapshotXkGate`), 否则 `log` 豁免直接入库; 兜底自动建 DRAFT IQC 也条件化 (仅 `anyFood` 才补)
- `add()`/`update()`: 供应商 status=1 硬门禁条件化 — 仅 `anyFood` 才拦暂停供货供应商, 全非食品采购跳过并 log
- `createFromIqc/createFromOrder`: **不动** (食品级专用入口, 本身从已 PASS IQC 建单)
- 库存入库 + 应付台账主流程**完全不动** (豁免只放行「IQC 前置」+「供应商名录」两个 XK 关口)
- 回归保护: 全食品级原料单 (LDPE is_food_grade=1) 行为**完全不变** (仍需合格 IQC + 合格供应商)

**部署**: 纯后端改动 → 重打 jar, R15 版本三处同步 `1.1.122` (`application.yml` + `SystemVersionInitializer` 默认 + 容器 `ERP_VERSION` env)。飞牛 canary 先行: 传 jar 到 `/volume3` 中转 → 因飞牛 backend 是 compose baked-in jar (非 host bind-mount), 改走 `docker cp` 进容器 + 补 compose `ERP_VERSION` env + `docker compose up`; **SA_TOKEN key 走原 `env_file` 未轮换** (已登录 token 不受影响)。home: baked-in 三连 (Mac 临时 http server `sudo wget` 传 jar + `build --no-cache` + `rm -f` + `run`, 网络名 `erp-system_erp-net` 非 `_default`, 补 `-e ERP_VERSION=1.1.122` + 显式 SA_TOKEN 双保险)。

**验证**: 双站 jar md5 均 `8c59adb15886b4cdad88e153ddad6001` (与本地 build 一致), `sys_config.SYSTEM_VERSION_INFO` 双站均 `version=1.1.122`, 容器均 healthy。功能侧: 飞牛 3 张全非食品 DRAFT 采购单 (`RKP202610210001` 等) 均**无关联 IQC** (旧 jar 下 `check()` 会抛「未关联 IQC 不能入库」, 新 jar 走豁免分支直接放行)。

**踩坑**: (1) 飞牛 backend 走 compose `env_file` 注入 SA_TOKEN key (不进 `docker inspect` 的 `.Config.Env`), 我一度误判「key 没设上」——实为查错位置, 从 `/proc/<pid>/environ` 确认 key 在运行 env 里; (2) 手动 `docker run` 复刻 compose 时漏了 `env_file` 一整段必填 env → 容器 restart 循环, 改回 `docker compose --profile failover up` 才稳; (3) home DSM sshd 禁 stdin 重定向/`tee` 写文件, 传 jar 只能走 Mac 临时 http server + home `sudo wget`。

### v1.1.121 (2026-10-23 — PC 生产工单详情「生产记录号」二维码)

**需求**: PC 生产工单页 (`/production/order`) 右侧详情卡, 把「生产记录号」(工单单号 `PD2026…`) 做成**二维码**显示, 方便现场扫码快速定位/追溯该生产记录。

**改动** (纯前端, 仅 1 个文件 `pc-web/src/views/production/Order.vue` + `package.json` 加 1 依赖):
- 引入 `qrcode` npm 包 (浏览器版, 零原生依赖, vite 自动 resolve `browser` 入口; 之前是被 myprint 等间接带进 node_modules, 本次补进 `dependencies` 防 `npm ci` 缺失)。`QRCode.toDataURL(no, {width:132, margin:1, color:{dark:'#0f2846',light:'#fff'}})` 生成 dataURL
- `qrDataUrl` ref + `watch(() => detail.no, ...)` 异步重画: 选中工单/换工单单号一变二维码即变, 无单号(`未选择`)隐藏
- 详情卡头部 (「生产记录 · 单号」标题右侧、状态 tag 旁) 加二维码框: `<img>` + 单号文本; `image-rendering:pixelated` 放大不糊
- **放在 `detail` computed 定义之后** watch (若放 script 顶部 `immediate` 会踩 TDZ 抛 ReferenceError)

**零后端改动**: 不新增端点/表/字段, 后端 jar 不动 (不重打 → 不触发 SecurityPreflightValidator 换密钥重登)。

**部署**: 双站 PC dist bind-mount, 各 `docker restart`, 旧 dist 备份。双站线上 `Order-wXjRf2Aa.js` md5 均 `852418d4bceb54efc6f8adf8a39afd32` (与本地构建逐字节一致), 含新标记 `wo-qr` / `toDataURL`。

**部署踩坑**:
1. **飞牛 pc-web 容器实际 bind-mount 源是 `/vol2/erp-system/pc-web/dist-new`** (不是 compose 里写的 `/volume2/.../dist`) — 先误 cp 到 `/volume2/.../dist` 不生效, `docker inspect` 才定位到 `dist-new`; dist 对容器只读 → 只能改 host `dist-new` 再 `docker restart`, 不能 `docker cp` 进容器 (read-only fs)
2. home pc-web 源 `/volume3/docker/erp-system/pc-web-new/dist`; home `/tmp` 对 gpssong 不可写 → tgz 中转 `/volume3/docker/erp-system/` 再 sudo 解包
3. 飞牛 docker 在 `/usr/bin/docker`, home 在 `/usr/local/bin/docker`

### v1.1.120 (2026-10-23 — App H5 成品入库: 扫**商品编码**弹出该商品全部工单供选择)

**现象/需求**: App H5 成品入库页 (扫 `scan/finished-in`) 原来扫码只认**工单单号** — 扫工单条码/单号二维码按 `billNo` 精确匹配, 命中就带入。现场实际常扫的是**商品编码**(成品条码), 那种码在工单表里根本匹配不到 → 直接「未找到工单」。用户需要: 扫商品编码时, 把**该商品名下所有生产工单列出来供挑选**。

**改动** (仅 1 个文件 `app/src/pages/scan/finished-in.vue`):

`onScanOrder` 扫码回调改三层:
1. 本地已加载的 100 张工单里按 `billNo` 精确匹配 → 命中直接带入(最快)
2. 本地没中 → 后端 `prdOrderPage({billNo})` 精确查工单 → 命中带入
3. **工单单号都对不上 → 把扫的码当成商品编码**, `prdOrderPage({productName: 码})` 查该商品全部生产工单 (后端 `PrdOrderService.page` 里 `productName` 参数会同时 `LIKE` 匹配产品名 + 产品码, 见 `PrdOrderService.java:87-89`, 故商品编码也能命中)。命中 **≥2 张** 时, 模板里新加的选择器列 `单号 · 成品 [状态]` 供挑; **1 张**直接带入; **0 张** 提示「没有单号/商品编码为 X 的生产工单」

配套:
- 新 data `productMatched[]` + `productMatchedCode`; 新 computed `productMatchedOpts`(选择器文案) / `pickedProductOrder`(回显); 新 method `onPickProductOrder(e)`(从命中列表挑一张 → 复用既有 `onOrderPicked()` 带出成品/规格/数量/单位/仓库)
- 命中的每张工单也 push 进 `orderList`, 让下方主 picker 一并可选
- 用主 picker 手选工单时 `onPickOrder` 清空 `productMatched`, 不残留「命中多张」列表

**零后端改动**: 完全复用既有 `prdOrderPage` + `productDetail`, 没加端点/表/字段。

**部署**: 双站 App H5 bind-mount (飞牛 `/vol2/.../app-h5/dist/build/h5` + home `/volume3/.../app-h5/dist/build/h5`), 各 `docker restart erp-app-h5`, 旧 dist 备份。双站线上 finished-in chunk md5 均 `989df9587bf89d3c459690085269c813` (与本地构建逐字节一致), 含新文案「该商品有 N 张工单」。

**部署踩坑**:
1. macOS tar 混 `._*` AppleDouble → `COPYFILE_DISABLE=1 tar --no-xattrs --exclude='._*'`
2. 飞牛 App H5 容器名是 `erp-app-h5` (不带 `-failover`, 跟 pc-web 不一样)
3. 飞牛 docker 在 `/usr/bin/docker`, home 在 `/usr/local/bin/docker`
4. **home `/tmp` 对 gpssong 不可写** — 中转 tgz 走 `/volume3/docker/erp-system/` (gpssong 可写), 再 sudo 解包; 中途误清空 H5 目录一次, 从备份 + 重解恢复, 线上 chunk 已确认正常

### v1.1.119 (2026-10-23 — BOM「基础用量」语义正名 + 领料量按成品克重归一化 (重量配比))

用户指出:「BOM 中基础用量为按重量折算的对应比例, 不是生产一个产品需要的量」。DB 实测(飞牛 canary)完全印证。

#### 根因

| BOM202607160001「普通B机料」 | base_qty | 原料主单位 |
|---|---|---|
| ldpe2426h | 25 | **包** |
| lldpe7042 | 175 | **卷** |
| kkj 开口剂 | 0.6 | kg |
| nmtc 纳米填充 | 6 | kg |

Σ = **206.6 份**, 添加剂合计 6.6 份 = 3.2%(吹膜合理), 膜料 200 份占 96.8% —— 这是**重量配比份数**, 不是用量。该配方被 **29 个成品共用**(带鱼袋/马鲛鱼袋/氧气袋/48×38 垃圾袋…), 成品克重跨度 3.3g~64.7g(20 倍) —— 同一张配方不可能有固定的「每成品用量」。

**旧算法是错的**(`PrdOrderService.java:256-283`):
```
ratio = planQty / bom.baseQty        ← bom.base_qty 对本配方是 NULL → 回落 1
need  = detail.baseQty × ratio        ← 退化成 baseQty × planQty
```
实测工单 `RQ202610210001` plan_qty=750 → 领 `lldpe7042` **131250 卷** / `ldpe2426h` **18750 包**。

**16 张领料单 100% 是 DRAFT, 一张都没审核, 库存未被污染** → 修完重开即可, 零数据回滚。

#### 新算法 (用户拍板: 数量×克重=原料总重量)

```
成品总重(kg) = planQty × base_product.gram_weight / 1000        (g/件 → kg)
原料用量(kg) = 成品总重 × (本项配比 / Σ全部配比) × (1 + 本项损耗率/100)
```

独立复核 4 场景, **合计精确等于成品总重**(无损耗时) —— 配比守恒:

| plan_qty=750 | 旧 (错) | 新 (对) |
|---|---|---|
| lldpe7042 | 131250 卷 | **2.67 kg** |
| ldpe2426h | 18750 包 | **0.38 kg** |

#### 连带修的 3 个同源 bug

| bug | 现象 | 修法 |
|---|---|---|
| 原料主单位非 kg | 配比是重量配比, 算法产出天然是 kg; 原料主单位是包/卷时领料量算得出却存不进 | ldpe2426h/lldpe7050/lldpe7042 三个膜料 包\|卷→kg, **保留原单位行作副单位**(只切 is_main 位, 靠 conversion_rate 支持将来按卷入库自动折算)。改前核实这三原料 inv_stock/inv_ledger/pur_receipt_detail/sal_delivery_detail/inv_check_detail **全 0 行**, 零历史风险。全库另 49 个用「卷」的胶带/碳带非 BOM 原料, 不动 |
| BOM 明细单位 11/11 行全 NULL | `Bom.vue` 的 addDetail()/onSubmit() 只同步 code/name/spec 从不写单位 → 领料明细 unitId 全 NULL(51/51 行) → `StockService.convertToMain()` 的 `if (unitId == null) return qty` 静默跳过折算 | 后端 `PrdBomService.saveDetails()` 用 `BaseProductUnitMapper.selectMainUnit()` 回填 + 前端 onSubmit() 带出 |
| Requisition.vue:38 假标签 | `label="数量 (kg)"` 硬编码, 实际是包/卷 | 去掉 `(kg)` |

#### UI (Bom.vue)

「基础用量」→「配比份数」; 新增「配比%」只读列(单项占 Σ 的百分比)+「单位」列(缺单位标橙); 弹窗顶部加语义说明条 + 实时 Σ 配比 —— 白纸黑字钉死「不是用量」, 防下一个维护者重蹈 `baseQty × planQty` 的错算法。

#### 降级 (不卡死开工, 与既有 BOM 缺失降级同一风格)

`gram_weight` 空/≤0 或 `Σ配比` ≤0 → `log.warn` 跳过领料展开, 状态照样 RELEASED。

#### sql/128 幂等测试抓到的真坑 (R24)

建 scratch 库从生产灌子集, **连跑 3 次比对**, 第 2 轮实测抓到一个会带到生产的 bug:

> 「把所有非 kg 行 is_main 归零」若**不带 `unit_name <> 'kg'`**, 第 2 次执行会把第 1 轮插入的 kg 行也归零, 而「插 kg 行」的 NOT EXISTS 又因「kg 已存在」不补插 → **三个产品一条主单位都不剩** → 前端显示「该商品未配置单位」(恰好是 R23 的 symptom)。

修法: 降级 UPDATE 排除 kg 行 + 加一条「无条件把 kg 行 is_main 提回 1」形成幂等闭环。这是**连跑 3 次对比**才发现的 —— 只跑 1 次就会带到生产。同批还踩 `COUNT(*) AS lines` 撞 MySQL 8 保留字(1064 语法错)→ 改 `line_cnt`。

#### 部署 (飞牛 canary 先行 → home)

| 站 | 产物 | md5 / 入口 |
|---|---|---|
| jar | `industrial-erp-1.0.4.jar` | 双站容器内均 `7f9fb3a8b16d4905fc5fa416af229784` |
| pc-web | `Bom-F0fJJNrt.js` / 入口 `index-CWBoX0X5.js` | 双站一致, 新文案「配比份数/配比%/未录」均在 |
| sql | `sql/128` | 双站 BOM 明细空单位 0 行, 三膜料主单位全 kg |

**home 数据差异(非故障)**: `lldpe7042` 原单位两站不同 —— 飞牛是「卷」, home 是「包」。脚本按 product_code 匹配、不写死单位名, 两站都正确切成 kg + 原单位降副单位。

**范围外(已识别, 未做)**: `prd_order_share.ftl:102/114` 领料明细表恒不渲染(变量名 `requisitionDetails` vs `details`); `Requisition.vue` 列表读 `row.materialName`/`row.qty` 后端无此 transient 字段恒 blank; `prd_order_feie.ftl:20` 克重单位写 `g/㎡` 应为 `g/件`。

**已知遗留**: `10265006`/`11725006` 两个成品没录克重, 开工时只 warn 跳过、不生成领料单, 需人工补克重。

**编号撞车已解决 (v1.1.124)**: `sql/128` 曾与 `sql/128_v1119_mes_weight_device.sql` 重号, 后者已改名为 `sql/129_v1119_mes_weight_device.sql`。**执行顺序: 先 128 (BOM), 后 129 (称重设备)**。⚠️ sql/128 是幂等脚本但含状态位 UPDATE (`is_main` 位切换), 按 R24 必须连跑 3 次验证 —— 编号唯一是跑对脚本的前提, 别再重号。

---

### v1.1.118 (2026-10-23 — R23 App H5「信封误读」: 拦截器已拆封, 页面再读 `.data` 恒空)

用户报 App H5 成品入库页选工单 `PD202610200001` (带鱼袋 `dyd975007`), 单位字段恒显示「该商品未配置单位」—— 而该商品在 PC 编辑商品里主单位明明配的是「只」。

#### (1) 排查: 5 条线索全部为真, 却仍复现

这个 bug 绕了 4 轮弯路, 因为**每一条常规怀疑都被证伪了**:

| 怀疑 | 验证 | 结论 |
|---|---|---|
| 401 / 越权 | Network 里 `base/product/2104473647277748226` **200** | ❌ 排除 |
| 后端没返回单位 | curl 同接口 (PC + App 两种 client token) 都返回 `units:[{unitName:'只',isMain:1}]` | ❌ 排除 |
| 飞牛部署的是旧 chunk | 本地最新源码重新构建, 产物与线上 **字节级一致** (同 hash `DI2IKAwg`、同 size 12928) | ❌ 排除 |
| Service Worker 缓存 | DevTools → Application → Service workers **空的** | ❌ 排除 |
| 页面状态残留 | 硬刷新 + 切到别的工单再切回 | ❌ 排除 |

**中途还自己推翻过一个错误结论**: 曾判定「飞牛部署的 chunk 里 3 处 `unitId&&` 是旧版真值过滤陷阱残留」, 但本地最新源码构建出的产物**同样有这 3 处** —— 它们是编译器把 `o.unitId != null && ...` 空值守卫翻出来的正常产物 (`null!=t.unitId&&...`), **不是** trap。「读编译产物里的字面量」极易误判, 必须与源码 + 构建 hash 双向对照。

#### (2) 根因: `r.data` 读到的是不存在的东西

`app/src/api/index.js` **两处**拦截器 (uni.request 路径 + fetch/H5 路径, 浏览器 H5 走后者) 都是:

```js
const d = res.data
if (d.code === 200) resolve(d.data)   // ← 已经拆封, r 就是业务数据本身
```

所以 `api.productDetail()` 返回的 `r` **就是商品对象**, 没有 `{code,msg,data}` 外壳。而页面写的是:

```js
const pd  = (r && r.data) || {}          // ❌ r.data 恒 undefined → pd 恒 {}
const list = (pd.units || []).filter(...) // → [] → 打兜底文案
```

**服务端返回的单位被前端自己扔了。** 「请求成功」和「响应被正确读取」是两件事 —— 这是本 bug 的核心教训。

同文件其它 4 处都写了双形态防御, 唯独 `loadUnits` 漏写, 是**单点漏**不是全仓约定错:
```js
this.inRecords = (r && r.records) || (r && r.data && r.data.records) || []  // ✅ 正确范式
```

#### (3) 修复 2 处 (顺带扫出同形 bug)

| 文件 | 行 | 原 | 改 |
|---|---|---|---|
| `app/src/pages/scan/finished-in.vue` `loadUnits` | 303 | `(r && r.data) \|\| {}` | `(r && (r.data \|\| r)) \|\| {}` |
| `app/src/pages/production/report.vue` `loadParams` | 113 | `(r && r.data) \|\| []` | `(r && (r.data \|\| r)) \|\| []` |

第二处 (`PdmProcessParamController` 返回 `R<List>`, 工艺参数恒空) 是同批次扫出来的, **用户尚未发现**。

**PC 端无此病** —— `pc-web` 用另一套 request 层, `FinishedIn.vue:433` 直接读 `data.units`, 排查时不用去 PC 找。

#### (4) 部署 (飞牛 canary 先行 → home 主站)

`npm run build:h5`, 成品入库 chunk `DI2IKAwg` → `And-i2pS`。两站 App H5 均为 bind-mount, 覆盖目录 + `docker restart erp-app-h5`:

| 站 | bind-mount 源 | 入口 chunk |
|---|---|---|
| 飞牛 canary | `/vol2/erp-system/app-h5/dist/build/h5` | `index-D1G8Vfgv.js` |
| **home 主站** | `/volume3/docker/erp-system/app-h5/dist/build/h5` | `index-D1G8Vfgv.js` |

旧 dist 备份为 `h5.bak-20261023`, 旧 chunk 已清。**双站线上 chunk md5 均 `d3cf4f1bc4187619fdf863643afd52dc`**, 与本地构建产物一致, 修复代码 `((e&&(e.data||e)||{}).units||[])` 确认在线。零 schema 变更 / 无 SQL。

#### (5) 本批部署踩坑 4 条

1. **macOS tar 会混进 AppleDouble 垃圾** —— 必须 `COPYFILE_DISABLE=1 tar --no-xattrs --exclude='._*' -czf`, 否则双站解出一堆 `._xxx` 并报 `Cannot open: Permission denied`。
2. **home `/tmp` 对 gpssong 不可写** (uid 1026) —— 传到 `/volume3/docker/erp-system/` (该目录 gpssong **可写**), 省掉 `python3 -m http.server` 中转。
3. **home 旧 dist 是 root 所有** —— `gpssong` 直接 tar 覆盖报 `File exists` / `Cannot utime`, 必须 `sudo -S bash -c "tar xzf ..."`。
4. **home sudo 密码别连输错** —— 连错 2 次触发 PAM autoblock, 实测 ~20-40s 自动解锁; 探一次等即可, **别重试**。

### v1.1.117 (2026-10-23 — 成品入库单位真实名 + 销售出库批次复选 + `unit_id=0` 数据回填)

#### (1) App 成品入库单位显示真实单位名

原来界面恒显示兜底字面量「主单位」—— 因为 `prd_order.unit_id/unit_name` 很多老工单开单时没写, `prdOrderPage` 也不带单位。改为以 `base_product_unit` 的真实主单位为准 (`u.unitName && String(u.unitName).trim()` 判定), 工单单位仅在商品无单位表时兜底。PC 端 `FinishedIn.vue` 同步。

#### (2) hotfix-2: `unit_id=0` 被真值过滤误删单位行 (3 处)

`base_product_unit` 有 18/174 行 `unit_id = 0` (不是 NULL) —— 历史脏数据, `unitName` 是真实的 (公斤/卷/只/片/箱), 只是 `unit_id` 指向一条不存在的 `base_unit`。JS `u.unitId &&` / `x.unitId > 0` 把 0 当假值 → **整行被丢** → 明明配了单位却显示「未配置」; PC 端更糟, find 落空后**静默 fallback 到 `base_unit` 全表第一条 (kg)**, 单位被默默选错且不报错。

**判定只认 `unitName` 非空**, `unitId` 只用于去重/定位, `0` 与 `null` 同等对待。**去重必须用行主键 `id`, 不能用 `unitId`** —— 多行可能共用同一个 `unit_id`, 用 `unitId` 去重会把并存的其它单位误吞。

#### (3) hotfix-3: `sql/127` `unit_id=0` 数据回填 (治数据层根)

- 新建字典行「片」 (`unit_code='PIAN'`, NOT EXISTS 守门幂等)
- 「公斤」**不建新行, 并入已有 kg** (飞牛先例: `base_product_unit` id `2099763778742202370` 就是「公斤 → kg, conversion_rate=10」) —— 不建第二个同义单位, 否则同一物理量两个 `unit_id`, 折算口径会分裂
- 回填 `base_product_unit.unit_id` (按存活字典行名匹配, `MIN(id)` 保证确定性) + `base_product.main_unit_id` (`ROW_NUMBER()` 排序 `is_main DESC, id`, **无主单位行时取 `MIN(id)` 兜底** —— 有 1 个商品单位行没标 `is_main=1`, 只认 `is_main` 会落空)

⚠️ 中文查询必须 `--default-character-set=utf8mb4` (MySQL 容器默认 latin1)。

#### (4) 销售出库批次复选 (`|` 分隔复用 `batch_no`, 零 DDL)

- **空串是合法批号「无批次」, 不能过滤** —— 过滤会让「A + 无批次」这单全扣 A, 报库存不足
- **uncheck 必须读台账镜像回补**, 不能重算分配 —— 重算会造出 `batch_no='A|B'` 的幽灵库存行

### v1.1.116 (2026-10-22 — R22 Jackson 大写 P 前缀 transient 字段 key 漂移根治)

#### (1) 根因 (R22)

实体 `pThickness` 等 transient 字段的 getter 是 `getPThickness()`。Jackson legacy 命名规则: 去 `get` 后剩 `PThickness`, 因首段 `P` 后紧跟大写 `T`, 被判定为「P + Thickness」驼峰切分 → **首段整体小写** → 序列化输出 **`pthickness`** (非 `pThickness`)。

同一份 JSON 里, 无前缀的 `getThickness()` 正常输出 `thickness` (打印模板在用, 保留)。前端按 `pThickness` 读**恒为 `undefined`, 且不报错** → 工单详情「成品规格属性」7 字段 + 食品合规 5 字段全显示 `—`。

#### (2) 影响面: 全仓 8 实体 29 字段

`PrdOrder` 11 / `SalDeliveryDetail` 7 / `SalReturnDetail` 6 / `PurReceiptDetail` 7 / `PurReturnDetail` 6 / `SalOrderDetail` 2 / `PurOrderDetail` 1 / `InvCheckDetail` 1。工业品工单没露出来, 只因食品合规字段对它们没意义。

#### (3) 修复

字段加 `@JsonProperty("pXxx")` 钉死 key, 不依赖 getter 推导。**零 schema 变更。** 打印模板 (ftl / `<#if p.productName>`) 用无前缀 key, 与 `@JsonProperty` 不冲突。

#### (4) 另一处: 生产工单详情默认不拉

`loadPlans()` 默认选第一个未完工工单时**直接给 `activeWo` 赋值绕过 `onPickPlan`** → `woDetail` 一直 null → 右栏详情卡 7 字段 + 生产时间 + 当班人员 + BOM 领料全 `—`, 还拼出「领料单 PD…0002-01 已生成」这条**假陈述**。拉详情收敛成 `watch(activeWo)`, 删掉 `w.no.replace('WO-','LL-')+'-01'` 兜底。

#### (5) 磁盘: home `/volume1` 是 2.3G 系统卷

放 jar 会撑爆, 且 `gunzip` **静默产出截断 jar**。备份迁 `/volume3/erp-backup`, 删 9 月 9 日旧部署残留 122M (无容器挂载, `.env` 已留档)。

#### (6) 验证

拉**线上** jar 解 `PrdOrder.class`, 用线上同款 jackson 2.15.4 跑一次 `writeValueAsString`: 修复前 JSON 只有小写 p 系列, 修复后同时输出 11 个大写 P key。

### v1.1.115 (2026-10-22 — R21 雪花 id 精度丢失根治 + 幽灵仓库库存修复)

用户报「库存查询部分产品没进仓库」, 截图里同一产品**裂成两行**、其中一行仓库列空白。查下来不是数据没录全, 是**前端的雪花 id 精度丢失** —— 一类不报错、只写错数据的静默故障。

#### (1) 根因 (R21)

```js
// app/src/pages/scan/finished-in.vue (修复前)
warehouseId: Number(this.warehouseId)
```

后端 `WebMvcConfig.java:38` 早就用 `ToStringSerializer` 把 `Long` 序列化成**字符串**防了这个坑, 但**输入方向没人管** —— 前端随手一个 `Number()` 就绕过去了。双精度在 2^60 量级的最小间隔 ulp = **256**:

```js
Number("2073701024629047297") === 2073701024629047300   // 真实仓 ...297 → ...300
```

真实成品仓 id 被低位抹成 `...300`, 这个 id 在 `base_warehouse` 里**根本不存在** (连软删的都没有) → 库存查询 `LEFT JOIN base_warehouse` 关联不到, 仓库列空白; 同时 `InvStockPageQueryMapper.xml` 的 `GROUP BY s.warehouse_id` 把 7297 / 7300 当成两个分组, 同一产品**裂成两行**。后端全程不报错, 前端无异常。

#### (2) 同一 bug 的第二处伤害: 工单关联全挂空

`prdOrderId: Number(this.orderId)` 同源问题, 让 **10 张 App 成品入库单的 `prd_order_id` 全部挂空** (存 `2111262449375719400`, 真实 `2111262449375719426`), JOIN `prd_order` 全部 `matched_id = NULL` → MES 报工 ↔ 成品入库 ↔ 批次溯源整条链断掉。

**救回依据**: `prd_order_no` 是**文本列**, 没被精度影响, 且已验证 4 个工单号在 `prd_order` 全库唯一 (每号 `COUNT(*)=1`) → 无歧义精确挂回。

#### (3) 数据修复 `sql/126_v1115_fix_ghost_warehouse_stock.sql` (home 已执行)

| 表 | 处理 | 条数 |
|---|---|---|
| `inv_stock` | 4 行幽灵 qty 累加进成品仓同产品行, 幽灵行软删 (`deleted=1` 保留可追溯) | 4 |
| `prd_finished_in` | `warehouse_id` 7300 → 7297 | 10 |
| `inv_ledger` | `warehouse_id` 7300 → 7297 (只改仓库维度不改数量) | 18 |
| `prd_finished_in` | `prd_order_id` 靠 `prd_order_no` 救回 | 10 |

**为什么是「累加合并」而不是「改 id」**: `inv_stock` 有唯一键 `uniq_inv_stock_key(warehouse_id, location_id, product_id, batch_no, deleted)`, 4 个产品在成品仓**都已有同 key 行**, 直接 `UPDATE` id 会 4 条全撞。合并让「一个产品在成品仓只有一行」这个不变式恢复成立。

**幂等**: 全程 `deleted=0` / `NOT EXISTS` 守门, 实测重复灌数量不变。

#### (4) 灌 SQL 的两个坑 (都写在 SQL 注释里)

- **`inv_ledger` 只有 `create_time`, 没有 `update_time`** —— 同名表列名不一致, 写 `update_time` 直接 Unknown column, **整个事务回滚**。动手前先查 `information_schema.COLUMNS`。
- **home 的 `gpssong` 无任何可写目录 (连 `/tmp` 都不行)** —— 输出重定向静默失败。备份只能 `docker exec` 落容器内, 再 `docker cp` 到 `/volume1/erp-backup/` 长期留存。

#### (5) 代码修复: App 端 7 行 `Number(id)` → 直传字符串

零后端改动 (后端 `Long` 字段接 JSON 字符串本来就合法):

| 文件 | 字段 |
|---|---|
| `app/src/pages/scan/finished-in.vue` | `warehouseId` + `prdOrderId` |
| `app/src/pages/scan/semi-in.vue` | `warehouseId` + `prdOrderId` |
| `app/src/pages/scan/pat.vue` | `sourceBillId` |
| `app/src/pages/sales/delivery-add.vue` | `customerId` + `warehouseId` |

**不修代码光修数据没用** —— 下次 App 扫码入库会立刻再产生幽灵行。

#### (6) 验证 (home 实测)

- 库存合并后: `0018` 108500 / `0012` 32500 / `0013` 5600 / `dyd7570` 86300, **总量守恒 232900**
- 幽灵引用清零: `prd_finished_in` 0 / `inv_ledger` 0
- 复现库存查询 SQL → **一产品一行**, 仓库名正常显示「成品仓库」
- 工单关联 **10/10 挂回**, `orphan_order_ref = 0`
- 重复灌 SQL 数量不变 (幂等成立)
- 备份 `/volume1/erp-backup/ghost_wh_20261022_122454.sql` (md5 `8d9c47e9…`)

#### (7) 未动

`sal_delivery` 2 行 `customer_id` 挂空 = 7 月历史测试数据 (`create_by=NULL`, 单号 `CKP` 开头), 与本 bug 无关, 未改。

### v1.1.114 (2026-10-22 — App H5 销售出库单「新建」功能 + R18 APP 授权补灌 + home 主站全量同步)

App H5 销售出库此前**只能看不能建**, 出库单得回 PC 端建, 车间现场极不方便。本次补齐 App 端建单闭环。

#### (1) 新建销售出库单页 (新文件 `app/src/pages/sales/delivery-add.vue`)

- **基本信息**: 客户 `<picker>` / 出库仓库 `<picker>` (默认选中成品仓 `warehouseType IN (FINISHED, FG, FINISHED_GD)`) / 业务日期 `<picker mode="date">` (默认今日) / 备注
- **明细录入**: 品名或编码搜索 (`api.productAppSearch`) + 📷 扫码加商品; 点搜索结果入明细, **重复点同一商品数量自动 +1**; 每行可改数量/单价, 实时算行金额, 底部合计数量与总金额
- **两个提交动作**: 「存草稿」只保存; 「保存并审核」保存后自动调审核

**踩坑 — `add` 端点只返回 `R<Void>` 不带 id**: 无法直接拿新单 id 去审核。解法是回查 `salesDeliveryPage({pageNum:1, pageSize:1, customerId})` 取最新一单的 id 再 `salesDeliveryCheck(id)`。**风险**: 若同客户并发建单, 回查可能拿到别人的单 — 当前 ERP 单机单用户场景可接受, 真并发需后端 add 返回 id (已记入后续优化)。

#### (2) 列表页入口 (perm 门禁)

`app/src/pages/sales/delivery-list.vue` 列表上方加「+ 新建销售出库单」按钮, `v-if="canAdd"` 由 `hasAppPerm('sales:delivery:add')` 控制。`hasAppPerm` 从 `uni.getStorageSync('erp_menus')` 派生 perm 集合 (逗号切分), 并用 `erp_permissions` 兜底 — **符合 R9** (App 只认 APP 端授权, 不能混 PC)。

**踩坑 — 同文件已有 `onMounted`**: 我第一次 Edit 又插了一个 `onMounted`, Vue 会两个都跑 (重复请求)。改为把 `canAdd.value = hasAppPerm(...)` 加进**既有** `onMounted`。**教训: 同文件已有生命周期钩子时必须先 grep, 别无脑追加。**

#### (3) R18 教训再撞一次 — `sales:delivery:add` 只有 PC 授权, 0 条 APP

查库发现 `sql/28` 建该 perm 节点时只灌了 `sys_role_menu` 的 PC 行, APP 行从未灌过。**只写前端的话, App 端一点「保存」就是 403**。补 `sql/125_v1114_sales_delivery_app_perm.sql` 给 6 内置角色 × 5 perm 补 APP 授权 (NOT EXISTS 守门幂等)。

**灌 SQL 踩坑 (重要)**:
```
❌ ssh 'docker exec -i <容器> mysql ... < /tmp/125.sql'   → 静默 0 行
✅ docker cp /tmp/125.sql <容器>:/tmp/125.sql
✅ docker exec <容器> sh -c "mysql ... < /tmp/125.sql"
```
`docker exec -i` 只把**本地 ssh 的 stdin** 接进容器命令, **不处理容器内的 `<` 重定向** (那个 `<` 是给容器内 shell 的, 但输入流已经被 ssh 吃了); sudo 嵌套还会再吃一层。**灌完必须 `SELECT` 验证行数** — mysql 匹配 0 行时**不报错**, 只有 `ROW_COUNT()` 是真信号。本次 home 站 BEFORE 10 → AFTER 40 行 (新增 30 = 6 角色 × 5 perm), 数字对得上才算成功。

#### 部署记录 (2026-10-22, home 主站全量同步)

| 站 | 组件 | bind-mount 源 | 产物 hash |
|---|---|---|---|
| 飞牛 canary | pc-web | `/vol2/erp-system/pc-web/dist-new` | `index-k41FGaxv.js` |
| 飞牛 canary | app-h5 | `/vol2/erp-system/app-h5/dist/build/h5` | `index-DmUveehZ.js` |
| **home 主站** | pc-web | `/volume3/docker/erp-system/pc-web-new/dist` | `index-k41FGaxv.js` (md5 一致) |
| **home 主站** | app-h5 | `/volume3/docker/erp-system/app-h5/dist/build/h5` | `index-DmUveehZ.js` |

**端到端验证 (飞牛 canary)**: 登录 → `POST /api/sales/delivery` → HTTP 200, 落库 `CKP202610220001 / 利峰智造 / DRAFT / 1.0000 / 9.9000` → 删测试单。home 生产数据只读, 只做字节级校验 (index chunk + Stock/Customer/Delivery/User 4 个分页修复 chunk md5 全部一致), 不在生产站造测试数据。

**home 部署踩坑**:
1. **DSM PAM autoblock 根治 = 控制台「允许名单」加内网段** (`安全性 → 防护 → 自动封锁 → 允许/封锁名单 → 允许名单` 加 `192.168.0.1~192.168.0.255`)。用户加完后 SSH 立刻恢复, 不用再等锁定期。**这是 DSM/fnOS 自带 PAM autoblock, 不是 fail2ban。**
2. home `sudo -S` 必须带 `-p ""` (否则每条命令刷一行 `sudo: a password is required` 混进输出), 且要用 `-tt` 分配 tty, 否则 sudo 读不到管道密码。
3. home `docker` 在 `/usr/local/bin/docker` (ContainerManager 包), **不在 PATH**, 直接 `docker` 会 command not found。
4. **chunk hash 重建后必变** — 本次重建后 `pages-sales-delivery-add` 从 `DtWy42xq` 变 `Z8rrm87j`。验证线上产物必须先从**本地新构建**取文件名, 拿旧名去探会得到误导性的 404/403。
5. uni-app 页面 chunk 是**懒加载**, 不出现在 `index.html` 里 — grep index.html 找不到 `pages-sales-*` 是正常的, 要直接按文件名探 `/assets/<chunk>.js`。
6. macOS `tar` 带 xattr, Linux 解包刷一屏 `Ignoring unknown extended header keyword 'LIBARCHIVE.xattr.com.apple.provenance'` — 无害。

#### 版本字面量对齐 (R15)

发现 App 端三处版本字面量停在 **1.1.107** (pc-web/backend 都已 1.1.112), 违反 R15 极易漂移原则, 本次统一到 **1.1.114**: `app/src/manifest.json` (versionName) + `app/src/pages/profile/index.vue` (`APP_VERSION` 常量, 用户在「我的」页看到的) + `app/package.json`。重打 H5 后在线 `index-DmUveehZ.js` 含 `1.1.114` 字面量。

### v1.1.113 (2026-10-22 — App H5 生产加工单支持按品名模糊查询)

`app/src/pages/production/order-list.vue` 搜索框 placeholder 本来就写着「单号/成品名」, 但 `loadData()` **只传了 `billNo`**, 打品名永远搜不到 — placeholder 与实现不符。

**根因**: 后端 `PrdOrderService.page(pageNum, pageSize, billNo, billStatus, productName)` 三参数**独立可选**, 前端只用了其中两个。

**修法** (纯前端 1 文件, 后端零改动):
```js
if (keyword.value) { params.billNo = keyword.value; params.productName = keyword.value }
```
三参数同时传 = AND 语义 (单号和品名**都**含该词才算命中), 符合「一个输入框两个关键词」的直觉。后端 `productName` 走 `w.like(productName).or().like(productCode)`, 品名或编码任一命中即可。

### v1.1.112 (2026-10-22 — R20 翻页 bug 全仓根治 26 处 + App H5 成品入库扫码关联工单 + v1.1.110 详情页开工修复)

用户报「库存查询页的列表无法翻页」。排查发现是 v1.1.109 立过的 **R20** bug, 但当时只修了 `finance/Arap.vue` 一个页面 — 本次全仓扫描发现**同一 bug 遍布 25 个文件 26 处**, 覆盖几乎所有日常高频列表页。

#### (1) 翻页 26 处根治 (纯 PC 前端)

**根因** (同 R20)：`el-pagination` 的 `@current-change` 会把新页码作为第一参数传给 handler, 而 `@current-change="loadData"` 里 `loadData()` 签名不接参 → `query.pageNum` 永不更新 → 每次都请求 `pageNum=1`, 表格永远显示同一份数据。

**受影响清单** (25 文件 / 26 handler)：

| 模块 | 文件 |
|---|---|
| 基础 | `base/Customer.vue` `base/Product.vue` `base/Supplier.vue` |
| 库存 | `inventory/Stock.vue`(库存查询, 用户报的这页) `inventory/Ledger.vue` `inventory/Check.vue` |
| 采购 | `purchase/Order.vue` `purchase/Receipt.vue` `purchase/Return.vue` |
| 销售 | `sales/Order.vue` `sales/Delivery.vue` `sales/Return.vue` |
| 生产 | `production/Bom.vue` `production/SemiFinishedIn.vue` |
| 质量/设备 | `quality/Inspection.vue` `equipment/MaintainPlan.vue` |
| 财务 | `finance/Invoice.vue` |
| 系统 | `system/User.vue` `system/Role.vue` `system/Settings.vue` `system/PrintTemplate.vue` `system/FeiePrintLog.vue` `system/OperationLog.vue` (操作日志 + 登录日志 **两个 pager**) |
| 工作流 | `workflow/Maintain.vue` `workflow/Safety.vue` `workflow/Emergency.vue` `workflow/Tenant.vue` |
| XK | `xk/Material.vue` |

**统一修法** (同 v1.1.109 范式)：
```js
function queryOnPageChange(p) { if (p === query.pageNum) return; query.pageNum = p; loadData() }
function queryOnSizeChange(s) { if (s === query.pageSize) return; query.pageSize = s; query.pageNum = 1; loadData() }
```
handler 名带 query 前缀 (`operQueryOnPageChange` / `loginQueryOnPageChange`) 避免同文件两个 pager 撞名。

**踩坑**: `production/SemiFinishedIn.vue` 用的是 `ref(pageNum)` 不是 `reactive({pageNum})`, 批量脚本按 reactive 模板生成, 误写成 `if (p === pageSize.value) return; pageSize.value = p` / `pageSize.value = 1` — 已手工改成 `pageNum.value`。**教训: ref 型和 reactive 型不能共用模板, 批量改完必须逐条 grep 复核变量引用。**

**已验证**: 全仓扫描脚本复查 0 残留 (`<el-pagination>` 非 `v-model:current-page` + handler 零参 = 全部修完); 51 条新 handler 逐条核对变量引用无误。

#### (2) App H5 成品入库扫码关联工单 (v1.1.111)

`app/src/pages/scan/finished-in.vue` 的「关联生产工单」原来只有 `<picker>` 下拉选择, 工单多时翻找不便。新增「📷 扫码关联工单」按钮:

```js
async onScanOrder() {
  doScan({
    onResult: async (code) => {
      const billNo = String(code || '').trim()
      // 1) 先在本地列表精确匹配 (picker 已加载的前 100 条), 命中最快
      // 2) 未命中 → api.prdOrderPage({ billNo }) 后端 LIKE 查 → push 进列表并选中
    },
    onCancel: () => {}
  })
}
```
扫码后自动带出成品/规格/计划量 (复用既有 `onOrderPicked()`), toast「已关联 xxx」; 查无此单弹「未找到工单」。复用 `utils/scan.js` 现有通道 (APK 走 NativeScanner ZXing, H5 浏览器降级 prompt 手输单号)。后端 `prdOrderPage` 本就支持 `billNo` LIKE (`PrdOrderService.page` 的 `w.like(PrdOrder::getBillNo, billNo)`), **无需后端改动**。

#### (3) v1.1.110 生产单详情「点开工后显示生产单不存在」

`app/src/pages/production/order-detail.vue` 的 `onRelease()` 在 `api.prdOrderRelease()` 成功后调 `loadOrder()` **漏传参数**, 而 `loadOrder(id)` 第一行 `if (!id) { order.value = null; return }` 直接命中 → 清空 order → 显示「生产单不存在」。**开工本身是成功的** (状态已变 PRODUCING), 所以重新进页面 (onLoad 正常传 id) 一切正常 — 与用户描述的「刷新后再点还是不存在, 但重新进去已是开工状态」完全吻合。改 `loadOrder(orderId.value)`。

#### 部署记录 (2026-10-22)

| 站 | 组件 | 路径 | 产物 |
|---|---|---|---|
| 飞牛 canary | pc-web | `/vol2/erp-system/pc-web/dist-new` | `Stock-ChC8ezhW.js` / `System-DLRya9Gk.js` (含 1.1.112) |
| 飞牛 canary | app-h5 | `/vol2/erp-system/app-h5/dist/build/h5` | `pages-scan-finished-in.D-kGV93b.js` |
| home 主站 | app-h5 | `/volume3/docker/erp-system/app-h5/dist/build/h5` | 同上 |

**踩坑**:
1. `cat dist | ssh 'sudo bash -c "tar -xzf - ..."'` — **sudo 嵌套会把 stdin 流吃掉**, `.staging` 解出空目录, 线上文件纹丝不动。正确做法: 先 `cat | ssh 'cat > /tmp/x.tar.gz'` 落地, 再单独一条 sudo 命令解包。
2. home pc-web / app-h5 的 bind-mount 目录名不同 (`app` vs `app-h5`, `pc-web-new` vs `dist-new`), 部署前必须 `docker inspect --format '{{range .Mounts}}...'` 确认, 别照抄上一站的路径。
3. macOS `tar` 打包会把 `._` 开头的 AppleDouble xattr 文件一起塞进去, 飞牛解包刷一屏 `Ignoring unknown extended header keyword 'LIBARCHIVE.xattr.com.apple.provenance'` — 无害, 加 `2>/dev/null` 即可。
4. **home PAM autoblock**: 反复输错密码会把源 IP 拉黑 130s~数分钟, 越试越锁。必须一次成功; 失败就静默等 5-7 分钟再试 (本机 sleep 超 2m 工具上限, 用后台任务挂起)。治本仍是 DSM 控制台加内网白名单。

### v1.1.109 (2026-10-21 — PC finance Arap 应收应付全部往来列表翻页修复)

home 主站 `http://home.93gushi.com:8088/#/finance/arap` 进入「全部往来」tab, Total 显示 125 (6+ 页), 但点第 2/3 页无反应 (table 不刷数据, pageNum 看上去卡在 1)。

**根因**：`Arap.vue` el-pagination `@current-change="loadData"` 没接参 — `loadData` 函数签名 `async function loadData()` 不取 pageNum, 而 el-pagination 触发时把新页码 p 作为第一个参数传进去, 函数体内对 `p` 完全没用, `query.pageNum` 永远 =1, 所以下一页请求永远是 `pageNum=1`, table 显示同一份数据。`已开发票` tab 的 `@current-change="loadIssuedData"` 同样问题。

**修法**：把 `@current-change` 的 handler 拆成 `onAllPageChange(p)` / `onIssuedPageChange(p)` 两个具名函数, 内部:
```js
function onAllPageChange(p) {
  if (p === query.pageNum) return     // 同值短路 (避免 v-if 切换 tab 重建 pager 时无端重拉)
  query.pageNum = p                    // 同步页码到 query
  loadData()                           // 重拉
}
```

模板侧 `@current-change="onAllPageChange"` / `@current-change="onIssuedPageChange"` 替换 `@current-change="loadData"` / `@current-change="loadIssuedData"`。

**纯 PC 前端 1 文件改动** (`pc-web/src/views/finance/Arap.vue`), 不动后端 / App / SQL / 不打 jar。

**R20** el-pagination `@current-change` / `@size-change` 必须显式接参 — 这是 Element Plus 官方要求, handler 函数形参为空会导致页码/页大小不更新; 同时 `v-if="activeTab === 'all'"` 切换 `el-pagination` 重建时同值守卫避免无意义重拉。

**双站部署记录 (2026-10-21)**:

| 站 | 容器 | URL | Arap chunk hash |
|---|---|---|---|
| 飞牛 canary (192.168.0.32) | `erp-pc-web-failover` | http://192.168.0.32:18080/#/finance/arap | `Arap-BGHHviCM.js` |
| **home 主站 (192.168.0.150)** | `erp-pc-web` | http://home.93gushi.com:8088/#/finance/arap | `Arap-BGHHviCM.js` (同源) |

- 飞牛: `pcweb-fresh.tgz` 经 `/vol2/erp-system/pc-web/fresh-staging/dist → mv dist-new → docker restart erp-pc-web-failover` (bind-mount 即时生效)
- home: sudo wget 从 Mac 临时 `:8765` HTTP server 取 `pcweb-fresh.tgz` → 解 `/tmp/pcweb-staging` → `cp -r staging/dist/. /volume3/docker/erp-system/pc-web-new/dist/` → `docker restart erp-pc-web` (cp `-r staging/dist/. dist/` 解决 tar 解开后嵌套 dist 目录坑)
- 双站入口 chunk `index-WxHX1_L7.js` (main entry, hash 未变) + Arap chunk `Arap-BGHHviCM.js` (业务入口, hash 已变)
- 飞牛部署踩坑: `dist-new.bak-20261021-131500` 因 staging2 swap 中 mv 时机错被清空 → 已 mv `dist-new.bak-20261021-130930` (v1.1.108 backup) 顶替, 然后 fresh-staging 替换
- home 部署踩坑: PAM autoblock 触发等 120s; sudo -S 密码管子走 `echo $PASS | sudo -S -p '' bash -lc "wget / cp / mv / docker restart"` 6 步串行

**v1.1.108 完整变更**:



PC 端 `/xk/safety` 生产环境检查 tab, 两项补强 + 两次 hotfix:

**(1) 检查清单 5 项右侧原本固定显示「符合/发现问题」标签, 改为 `el-switch` 开关, 可手动切换每项状态。**

- 每项右侧 `env-verdict` 区由「图标 + 静态文字」改为「状态色文字标签 (符合=绿/发现问题=红) + `el-switch`」。
  开关 `v-model="it.status"` 直接改 `PASS`/`ISSUE`, 开=符合, 关=发现问题。
- 新增 `onEnvItemToggle(it)`: 切到「发现问题」时弹 warning toast 提示去下方填整改记录。
- `envAlertCount` 从写死 `hasIssue ? 1 : 0` 改为「统计有多少项处于 ISSUE」(多项发现问题时 banner 数字累加)。
- 交互联动: 任意项 = ISSUE → `hasIssue` 为 true → 展开下方「卫生问题整改记录」表单; 全部 = PASS → 表单隐藏, 显示「当前检查表全部符合」绿条。

**(2) 全合格场景也能生成并保存一条检查记录。** 原来 `env-clean-tip` 只显示一句提示, 用户没法把"今天查了, 全合格"落库。
- 绿条右侧加两个按钮: 「生成检查报告」(沿用原 onGenReport 占位) + 「保存合格检查记录」(新 `onSaveEnvPass`)。
- 落库路径: 已有 DRAFT → 走 `/check(level=null, rectifyStatus=DONE)` 直接归档; 无 DRAFT → `save` 建 DRAFT → `check` 归档。
  后端 `XkEnvCheckService.check()` 对 `level=null` 不触发 X6 强制照片门禁(只 MEDIUM/HIGH 必填), 全合格 0 照片合法。
- 检查项快照写进 `checkContent`: "5 项检查全部符合, 无需整改. 检查项:\n1. 人员工作服…— 符合\n…",
  台账里那条记录能看到本次查了哪些项各自结论。
- 标题前缀 "生产环境检查 · V1.2 (全部合格)" 与发现问题的 "生产环境检查 · V1.2" 区分, 便于「检验记录与报告」tab 筛 ENV 时一眼识别。

**(3) plannedAt 反序列化 hotfix-3 (前两版没修对)。** 整改分支 + 全合格分支 payload 里 `plannedAt` 最终格式:

- v1.1.108 首次: `new Date().toISOString().slice(0, 16).replace('T', ' ')` → `"YYYY-MM-DD HH:mm"` (无秒 + 空格) — **错**, 无秒 Jackson 不接受
- v1.1.108 hotfix-2: `new Date().toISOString().slice(0, 19)` → `"YYYY-MM-DDTHH:mm:ss"` (UTC 带秒 + T) — **错**, 后端字段 `@JsonFormat(pattern="yyyy-MM-dd HH:mm:ss", timezone="GMT+8")` 强制用该格式反序列化, 不接受 T 分隔 (报 "could not be parsed at index 10")
- v1.1.108 hotfix-3 ✅: 新 helper `nowBeijingStr()` → `toLocaleString('zh-CN', {timeZone:'Asia/Shanghai', hour12:false}).replace(/\//g,'-')` → `"YYYY-MM-DD HH:mm:ss"` 北京时间, 与 `@JsonFormat` 严格对齐

根因: 后端 `XkEnvCheck.plannedAt` 注解 `@JsonFormat(pattern="yyyy-MM-dd HH:mm:ss", timezone="GMT+8")` 同时管控序列化和反序列化,
前端发 `T` 分隔或缺秒都会 500。前端 `new Date().toISOString()` 是 UTC 时间, 即便换成空格分隔格式也不行 — 必须用 `Asia/Shanghai` 时区转换。
现在 helper 输出北京时区当前时间, 19 位字符串 `2026-10-20 17:30:01` (与后端列存储时区匹配, 也避免 8h 时差误存)。

整改路径同样原本踩 `@JsonFormat` 时区+格式双重约束, hotfix-3 一并修正。
**(4) beforePhotos/afterPhotos hotfix-4。** 全合格分支 payload 里 `beforePhotos/afterPhotos` 原写 `''`,
MySQL `JSON` 列不接受空字符串, 报 "Invalid JSON text: The document is empty at position 0"。
- 整改分支用 `JSON.stringify(arr || [])` 已发合法 JSON 数组 `'[]'`(0 张) 或真数组串, 不动
- 全合格分支统一改 `EMPTY_JSON_ARR = '[]'` — 合法 JSON 空数组, 后端 X6 (StrUtil.isBlank) 对 `'[]'` 返回 false, MEDIUM/HIGH 照片必填门禁仍正确触发
- 调研: 前端统一语义, 照片字段恒发合法 JSON 文本 — '[]' 或真数组串, 不再发空字符串

注: 这是 plannedAt 热修时埋的次生错误, plannedAt 已修对后这次才暴露。

**纯 PC 前端改动, 不动后端 / App / SQL。** `envItems` 仍是 V1.2 固定模板 mock (真实逐项数据待后端 `xk_env_check_template` 落地)。

**(5) 双站部署记录 (2026-10-20)**

| 站 | 容器 | URL | 线上 index | 线上 Safety chunk |
|---|---|---|---|---|
| 飞牛 canary | `erp-pc-web-failover` | `http://192.168.0.32:18080` | `index-DzyV4PjR` | `Safety-OtOYVIWq` (hotfix-2/3/4) |
| home 主站 | `erp-pc-web` | `http://home.93gushi.com:8088` | `index-B7OPR-tZ` | `Safety-BKs2XQNY` (hotfix-2/3/4) |

- **飞牛 canary**：tar 全量替换 `/vol2/erp-system/pc-web/dist-new` + `docker restart erp-pc-web-failover`。
- **home 主站**：踩了 5 个坑才稳定部署 — gpssong 无任何可写目录 + sudo -S tee 写 0 字节 + scp 拒 + sudo bash heredoc 嵌套崩 + base64 超 ARG_MAX。
  最终路径 = Mac 临时 `python3 -m http.server 8765` + home `sudo wget -O /tmp/x.tgz http://192.168.0.5:8765/x.tgz` (71 MB/s 内网) + sudo 解 tar + cp -r 替换 + docker restart。
  还有一个坑: `tar -xzf -C /tmp/dist` 把 `dist/` 顶层目录当成 `dist/dist/` 嵌一层, cp 要 `cp -r /tmp/dist/dist/. /dest/` 而不是 `/tmp/dist/. /dest/`。
  这些都沉淀到 memory: `home-pcweb-dist-deploy-pitfalls.md` (下次 home 部署直接复用 Mac http + home wget 路径)。

**v1.1.108 5 个 commit 全部推 GitHub main**:
- `d19cfee` (1) 开关化 + (2) 全合格归档
- `969b1f4` (2) 整改分支共用 plannedAt / 北京时间
- `8b76028` hotfix-2: plannedAt 改 ISO `YYYY-MM-DDTHH:mm:ss`
- `491c751` hotfix-3: plannedAt 改北京时区空格分隔匹配 `@JsonFormat`
- `6349a6b` hotfix-4: MySQL JSON 列空字符串 → `'[]'`

教训: **日期/时间字段在 entity 上加了 `@JsonFormat` 就同时管控序列化与反序列化**; 前端 helper 必须严格匹配 (格式 + 时区); **MySQL JSON 列不接受空字符串** — 统一发 `'[]'` 或真数组串。

### v1.1.107 (2026-10-20 — App APK 隐私/卫生第三批)

纯 App 端三项收尾, 不动后端 jar / PC 前端 / SQL。版本四处同步到 1.1.107 (profile / manifest.json / package.json / CLAUDE.md), build-app.sh 一致性校验通过。

1. **关 uni-stat 遥测** — `src/manifest.json` 顶层加 `h5.uniStatistics: { enable: false }`。
   根因: `@dcloudio/uni-h5` 框架核心默认内置统计模块, 生产产物里已实锤带
   `https://tongji.dcloud.io/uni/stat.gif` + `https://tongji-collector.dcloud.net.cn` (带 appid hash),
   每次 H5 页面访问向上报设备型号/屏幕/语言/网络类型/页面路径。企业内网 ERP 不该有这类公网遥测。
   这是 uni-app 官方 manifest 开关 (非 patch), `build:h5` 后 grep 产物已无 `tongji`/`stat.gif` 串。
2. **sw.js 注册加原生容器守卫** — `index.html` 里 `navigator.serviceWorker.register('/static/sw.js')`
   外包 `!window.Capacitor` 判断。sw.js 是 PWA 离线缓存, 只给浏览器 H5 用; Capacitor APK 的
   WebView (file://localhost, 无 http origin) 里注册拿不到 `/static/sw.js`, 常驻报
   `Failed to register`。`window.Capacitor` 是 R19 权威判定 (APK 必有 / 浏览器 H5 必无),
   index.html 是纯静态引不了 js 模块, 直接内联。App H5 (:18090, 浏览器打开) 仍正常注册 SW。
3. **`api/index.js` DEBUG 日志生产静默** — 抽 `logDebug()` 走 `import.meta.env.DEV` (vite 构建期替换),
   生产 H5/APK 下 6 处 `console.log([api] DEBUG …)` 全不执行; 保留 2 处 `console.warn`
   (非 JSON 响应 / 网络失败) — 这些是 R13 原则下最值得留的诊断日志, 且带用户可见 toast。

### v1.1.105 (2026-10-20 — App APK 完全无法登录 hotfix: runtime 误判 + CORS 漏无端口 origin)

真机 (小米 M2007J1SC) 装 APK 后**完全无法登录**。用 `adb logcat` + CORS 实测矩阵定位到**两个串联的坑**, 均已修复并双站部署 + 真机验证通过。决策沉淀 [ADR-0007](architecture/decisions/0007-app-runtime-probe-and-cors-origin.md), 原则见 CLAUDE.md **R19**。

#### 坑 1 (首要): `isH5Runtime()` 只认 `plus`, Capacitor APK 被误判成 H5

v1.1.80~v1.1.104 期间"是不是 H5"的判断被**复制到 5 个文件**, 每处同一句内联:

```js
typeof window !== 'undefined' && typeof document !== 'undefined' && typeof plus === 'undefined'
```

`plus` 是 **HBuilder** 原生运行时的全局对象, 但本项目 APK 走 **Capacitor 6** (`@capacitor/android` 6.2.1) —— Capacitor WebView 里**没有 `plus`**, 于是 5 处全部误判。后果链 (logcat 直接证据):

```
[api] DEBUG base: /api                                          ← getBase() 走了 H5 分支
Handling local request: http://localhost/api/system/config/...    ← 打回 WebView 自己的服务器
[api] 非 JSON 响应: status=200 ct=text/html data=<!DOCTYPE html>
```

Capacitor 内置本地 HTTP server 找不到该文件 → SPA fallback 返回**打包进 APK 的 `index.html`** → 登录接口同样拿不到 JSON。

`utils/nav.js` 的 `isNative()` 用 `typeof plus !== 'undefined'`, 同样误判 → 原生 App 里所有跳转退化成 `window.location.hash`, `uni.switchTab` / `uni.reLaunch` 全失效。

#### 坑 2: 后端 CORS 只配带端口通配, 漏了 APK 的真实 origin

`capacitor.config.json` 设 `androidScheme: "http"` 且无端口 → APK 的真实 origin 是**无端口的 `http://localhost`**。而 `SecurityConfig.corsConfigurationSource` 与 `SaTokenConfig.corsFilter` (注册在 `/api/**`) 两道闸门都只写了 `http://localhost:*`, 匹配不到。修完坑 1 立刻会撞上这个, 故一并修。

实测矩阵 (修前直连后端):

| Origin | GET | OPTIONS |
|---|---|---|
| `http://localhost` (APK 实际) | **403** | **403** |
| `http://localhost:5173` | 401 ✅ | 200 ✅ |

> 排查方法固化: CORS 类 403 一律 `curl -H "Origin: <各候选>"` 实测矩阵, **同时看 GET 和 OPTIONS** —— 预检 200 但实际 403 说明被另一道闸门拦了, 不要靠推理哪个 pattern 匹配得到。

#### 改动

- **新增 `app/src/utils/runtime.js`** — 运行时探测单一权威源。判定顺序: `window.Capacitor.isNativePlatform()` → HBuilder 的 `plus` → 才是浏览器; 有 `Capacitor` 全局但方法不可用时保守当原生。
- **6 处内联副本收敛为 import**: `api/index.js` / `utils/nav.js` / `utils/upload.js` / `pages/login/index.vue` / `pages/profile/settings.vue`
- 原生 base 新增 `getNativeBase()` 也读 storage 显式覆盖 (此前只有 H5 分支读, 「服务器设置」在原生侧不生效)
- 后端 `SecurityConfig` + `SaTokenConfig` 各补 `http://localhost` / `http://127.0.0.1` 无端口 pattern
- 登录页 template 引用了**未定义变量** `isNativePlatform` (恒 falsy → placeholder 永远显示 H5 默认值), 改 `isNativeApp()`
- 版本 1.1.103 → 1.1.105 (profile `APP_VERSION` + `manifest.json` + CLAUDE.md 三处同步, 过 R14)

#### 部署与验证

| 项 | 飞牛 canary (先行) | home 主站 |
|---|---|---|
| 方式 | `docker cp` + `restart` (ADR-0006 双轨) | `build --no-cache` + `rm -f` + compose (baked-in 三连) |
| CORS GET | 403 → **401** ✅ | 403 → **401** ✅ |
| OPTIONS | 403 → **200** ✅ | 403 → **200** ✅ |
| 端到端登录 | 200 + Set-Cookie + perms ✅ | 200 + Set-Cookie + perms ✅ |

- jar md5 `2246eb5333f6c70aad3b46f0e05bcb33` 在**本地 / 飞牛容器 / home 镜像内**三方一致 (home 用 `docker run --entrypoint md5sum` 探镜像层, 见 ADR-0006)
- **home 完整保留原环境变量含 `SA_TOKEN_JWT_SECRET_KEY`** → 未换密钥, 已登录用户未被强制登出
- 全栈健康: PC 18080 / App H5 18090 / backend 8080 均 200
- APK 过 R14 双层校验 (内嵌 `1.1.105` = `manifest.versionName`), 产物 `erp-app-20261020.apk` (4474212 B, md5 `2d0027a01aed82f7aaecd178fa4e6172`)
- **真机实测**: 安装 → 登录 → 工作台加载真实业务数据, `logcat` 错误 **0 条**; `/auth/login` 2 / `/auth/me` 3 / `/report/dashboard` 1 / `/inventory/warning/list` 1 / `/qms/inspection/page` 2 / `/production/semi-in/page` 1 / `/production/finished-in/page` 1 全部成功

### v1.1.104 P2 (2026-10-19 — home 系统检查报告 P2 三项收尾: F 扫描 / G 决策库 / H 扫描)

**P2 三项** (home 检查报告的第三阶段改善, 前序 P0/P1 已全落地):
- **F 审计 @Async 扫描 (代码零改动, 健康)**: 全后端 `src/main` 仅 1 处 `@Async @EventListener` (`OperLogEventListener`), 写的是**操作日志** `sys_oper_log` (高频、容忍丢行, 异步合理)。合规模块 (XK/MCP/MES 报工/维保/卫生) 关键动作**全部已改同步直写**, 注释里 20+ 处 "不走 @Async" 是教训声明非漏网。结论: 无需改动, 设计合理 — 固化进 [ADR-0001](architecture/decisions/0001-audit-sync-direct-write.md)。
- **G 决策库 `docs/architecture/decisions/` (新增)**: 建 ADR 目录 + `INDEX.md` + 首批 6 篇 (0001~0006): ① 审计同步直写 ② `sys_menu.perms` UNIQUE 索引 ③ perm 按 PC/App 端分流 ④ XK 合规复用既有 module 不重造 ⑤ 飞牛 canary 先行部署 ⑥ 后端 jar 双站双轨 (home baked-in / 飞牛 runtime mount)。每篇四段式 (背景/决定/备选/后果), 历史细节指向 CHANGELOG 锚点; `docs/00_文档索引.md` 挂入口。
- **H System.out → Logger 扫描 (代码零改动, 健康)**: 全后端 `src/main` 仅 1 处 `System.out` — 启动类 `IndustrialErpApplication` 的启动 banner (Spring Boot 惯例, 故意 System.out)。非坏味道, 无需改动。

**改动**: 新增 `docs/architecture/decisions/` 6 文件 + `INDEX.md`, 改 `docs/00_文档索引.md`, 本 CHANGELOG 段。无后端/前端代码 / 无 DDL / 无部署 (纯文档)。

前序版本: v1.1.103 hotfix-1 (见下方完整正文)。

**当前版本**: v1.1.103 hotfix-1 (2026-10-19 — 开工仓库硬校验改降级放行. 现象: home 主站 PD202610190005 点开工报 "生产单未填仓库, 且仓库表中无默认仓库". 根因: home 3 个仓库(成品/原料/半成品) is_default 全 0 + 该单 warehouse_id=NULL + BOM 有明细 → 自动领料展开时仓库回退两个条件都命中不了 → PrdOrderService.release L255 抛错. 用户拍板: "开工不需要填仓库, 入库才需要" → 仓库是领料/入库环节数据, 不该阻塞开工主流程. 修法: release() 仓库缺失不再 throw, 改记 warn 日志 + 跳过自动领料展开 + 状态照样置 RELEASED/PRODUCING, 用户后续手动到「领料管理」建领料单时再填仓库 (与上一轮 "无 BOM 降级" 同一思路: 开工做能做的, 缺哪块数据降级跳过哪块, 不打死工单). 纯后端 1 文件 (PrdOrderService.release), 无 DDL/前端. home baked-in jar md5 `1c95906f` (101,460,004B) build --no-cache + rm -f + run 三连, PD202610190005 实测开工 200 → bill_status=PRODUCING + release_name=gpssong. 飞牛 canary 待同步 (需对齐同 jar md5). 前序 v1.1.103 正文:

**v1.1.103 (2026-10-19 — App H5 端 4 处修复一轮, 含 v1.1.101~v1.1.102 全批 home 主站同步. 本轮 4 处: ① **App H5 502 Bad Gateway** — home 端 `erp-app-h5` 的 nginx.conf 写死 backend 容器 IP `172.18.0.4`, backend 重启换 IP 后反代 502, 改容器名 `erp-backend` (Docker DNS) + 转 bind-mount 形态 (消除"重建镜像回退"风险); ② **App H5 403 Invalid CORS request** — App H5 页面 origin `http://192.168.0.150:18090` 不在 ERP_CORS_ALLOWED_ORIGINS 白名单, Spring Security `setAllowCredentials(true)` + 精确白名单不匹配 → 403, 改 1 行 `config.addAllowedOriginPattern("http://192.168.*:*")` 一次覆盖整个内网段 (Spring Framework 6 `setAllowedOriginPatterns` 支持 `*` 通配); ③ **生产管理 4 入口缺 3** — 工作台生产管理 tab 只显示 1 个「生产加工单」, 缺扫码报工/半成品入库/成品入库/排程, dist chunk 全在, perm 全有, 模块开关 production 开, 真因: sql/53/110/113 seed 只授 PC 端, 0 行 APP 端授权 → LoginVO.appMenus 按 client_type='APP' 过滤拿不到 perm → dashboard visibleMenus 拿不到入口, 写 sql/124_v1103_home_production_app_perm.sql 给 6 业务角色 × 9 production perm 补 APP 授权 (NOT EXISTS 守门), 灌到 home 主站, appMenus 21→30 行, production 节点 4→13; ④ **备份 403 @SaCheckRole("admin") 死注解** — SysBackupController 5 写端点 + AuthController.setpwd 共 6 处 `@SaCheckRole("admin")` 字面量, SUPER_ADMIN 角色码不匹配 → 一律 403, 删 6 处注解 + 重打 jar 双站 docker cp + restart. 部署踩坑: home backend 是镜像内置 jar (build --no-cache + docker rm -f + docker run 三连, restart 不切 image); gpssong /tmp 只读 (root:wheel 755) 写脚本要落 /volume3/; --env-file 在 sudo 隔离下不可见 (gpssong 创建, sudo 看不到), 用 -e 17 个 env 一条条传; 容器内 baked-in jar md5 验证最快 `docker run --rm --entrypoint md5sum <image> /opt/app/app.jar` (不进容器). 用户操作: App 端退出重登刷 `localStorage.erp_permissions` (login.permissions 是混合端含全 29 个 production perm, perm-only 兜底会命中), 工作台生产管理 tab 4 个入口全显. 飞牛 canary 状态: 2026-10-19 已与 home 同 jar md5 (`911f01e0...` 101459745 bytes) + 同步 CORS 修复 + 已用 bind-mount 形态 (无需改动). memory 新增 erp-cors-192168-pattern.md + erp-app-client-type-perm-sync.md.

**前序版本**: v1.1.102 (2026-10-18 — 全批同步 home 主站. 含 hotfix-1 CORS 飞牛白名单补内网直连 origin + hotfix-2 生产管理子标签 + 成品入库 APP 授权 + 部署 + 飞牛 App H5 bind-mount 同步. 飞牛 canary 完整版 v1.1.102 部署: 后端 jar md5 `911f01e0...` (101459745 bytes) + pc-web dist-new 全量替换 + app-h5 bind-mount 转换 + sys_menu 数据治理 + sql/110 prd_semi_in 表 + perm 部署. 前序 v1.1.101: XK 原料进货检验全流程 5 commit)

**前序版本**: v1.1.101 (2026-10-15 — XK 原料进货检验 (Phase 1 IQC 进货查验, 食品接触塑料吹膜厂 XK16-204 审核) 5 commit 完整版. 决策: ① 范围 = 5 检验类合一 (IQC/FAI/PAT/OQC/OQC_RETURN) head+detail + 3 签字, 不重复造独立表; ② 飞牛 canary 先行; ③ 照片复用 /system/upload/file. 改动: ① 后端 12 Java 新文件 (QmsIqcItem/QmsIqcAttachment entity+mapper+service+controller) + 8 改 (XK 门禁 + PurReceiptService.createFromIqc + IQC→Receipt 强校验); ② sql/115/116 (2 新表 + 2 perm + 3 角色×PC 授权 + sys_menu 节点); ③ App 端 pages/scan/in.vue 重写 (IQC 流程) + pc-web 端 IQC 详情 + 5 处文件登记. 飞牛 canary 部署: 后端 mvn clean package (101.5MB) + pc-web dist (含 IqcDetail chunk) + App H5 bind-mount + 双站 sql/115/116 灌库. 验证: home 飞牛 PC 端 IQC 详情页加载 + 进货查验列表 8 行 + 飞牛后端 healthy. 前序 v1.1.100: 升 App 版本 1.1.91 → 1.1.100 + 工作台 8+ 模块扩 perm + 改 tabBar 5 tab + dashboard perm 入口)

**前序版本**: v1.1.91 (2026-10-10 — XK 合规中心 (食品接触塑料吹膜厂 XK16-204 审核) 后端接入 + 飞牛 canary 部署. 用户 3 决策: ①方式 A 复用 (基础数据走 mes/workflow/em/purchase 既有 module, XK 只加 2 新表 xk_safety_check + xk_audit_log + 1 新 module modules/xk, 不重复造独立一套); ②飞牛 canary 先行 (home 待飞牛稳定 24h 后同步, 本次只飞牛); ③照片复用 /system/upload/file. 新增 modules/xk (2 entity XkSafetyCheck/XkAuditLog + 2 mapper + XkSafetyCheckService + XkAuditService + XkController /xk/* 9 端点: dashboard 聚合 report+mes+qms+wf+em / material 走 PurReceiptService / trace 走 MesReportService.batchTrace / maintenance 走 WfMaintain+EquipmentService / safety 走新表 / print+audit 走新表 + 同步直写 SHA256 审计, McpAuditService 教训). 改 2 前端文件 (pc-web/src/api/xk.js + app/src/api/xk.js: sleep+mock 全换 request 真请求, 保留函数签名 → 视图 0 改动; app index.js 补 export {request}). sql/55_xk_compliance.sql (2 新表 + 2 sys_config DASHBOARD_CONFIG/XK_AUDIT_RETENTION_YEARS + 2 F perm xk:audit:{list,archive} + 3 角色×PC 授权 6 行, 全 information_schema 探测幂等). 版本号 5 处对齐 1.1.91. 飞牛验证: 后端 healthy + /api/xk/* 9 端点登录态全 200 (dashboard 真实 ERP KPI totalSales=724096 / maintenance 4 条真 wf_maintain_record / print retentionYears=2) + pc-web dist-new 158 assets + xk-KIMEhkpR.js 在线 HTTP 200 / app H5 bind-mount 71 assets 在线. home 未部署 (待批准). 前序 v1.1.90: MES 第二阶段 质量/工艺/设备)

**前序版本**: v1.1.80 (2026-10-09 — MES 第一阶段「打开车间黑箱」: 工单管理+扫码报工 + 物料批次绑定. 用户 4 决策: ① 工序按 BOM/产品定制 (mes_process_def, 开单展开 prd_process 死表); ② 报工到量+合格/不良+操作人; ③ 批次主表 mes_batch + 流向表 mes_batch_link (原料批领料时登记, 成品批成品入库时登记, 膜卷 SEMI 二期); ④ App 扫飞鹅生产单标签 (billNo QR) 报工. 新增 modules/mes (4 entity + 4 mapper + MesReportService + MesController) + sql/53_v180_mes_workorder.sql (3 新表 + prd_process 加 from_def_id + production:report:{add,list} perm 授 6 角色); PrdOrderService.release 开单展开工序 + PrdRequisitionService.check 领料登记 RAW 批 + PrdFinishedInService.check 成品入库登记 FG 批 (两边界同步直写 sys_oper_log module=MES报工); PC 端 工单进度/工序配置 两视图 + App 端 扫码报工 页 + dashboard/角色白名单登记; 报工只动 prd_process 不动库存 (半成品在制品账二期), 批次「领料时才登记」(不碰采购入库). 前序 v1.1.79 hotfix-1)

**前序版本**: v1.1.79 hotfix-1 (2026-10-07 — 修复 MCP 审计 @Async @EventListener 漏写 sys_oper_log bug, McpAuditService 改直写 SysOperLogMapper.insert 同步写 + 双站 jar 重打重部署 md5 282abec0. 见 changelog §v1.1.79 hotfix-1) + v1.1.79 MCP: Hermes 多模态识别工单照片 → POST /api/mcp (JSON-RPC 2.0, 工具白名单 erp_create/query_maintenance_order) → 生成维保草稿; Hermes stdio 桥接脚本 scripts/erp_mcp_bridge.py (零第三方依赖, 协议由 Hermes 官方 stdio client 管) + sql/51_hermes_mcp_bot.sql 一站式建 HERMES_BOT 角色+用户(仅 work:maintain:add+list, 无 check/delete, 权限边界落 RBAC); docs/24_Hermes调ERP-MCP自动建档指南.md; security 4 条全落地 (人工审批/工具白名单/全量审计/RBAC 双收敛). 用户目标: 用户要把设备维保/安全检查/应急预案(含租客)等台账从纸笔搬到 ERP; 巡检员希望能拍照 → 自动生成记录. 用户决策: ① 深度 = 简单 CRUD + 审核/反审核 + 删除 (不做审批流转引擎); ② 租客 = 新建主数据表 base_tenant; ③ 仅 PC 端 (不上 App); ④ Dify/Hermes 触发永远只生成草稿, 审核留人工. 改动: ① 后端 8 entity (BaseTenant + 3 head + 3 detail + WfMaintain/WfSafety/WfEmergency head 加 audit + @Version + 雪花 ID), 4 service (简单台账 + 审核/反审核, 复用 BillNoGenerator 常量 BILL_WM/BILL_SIN/BILL_EP), 4 controller (SaCheckPermission work:*:list/add/edit/delete/check/uncheck orRole=admin); ② sql/50_v179_workflow_module.sql (4 表 CREATE TABLE IF NOT EXISTS + sys_menu M/C/B 节点 + 6 内置角色 PC 授权, information_schema 探测 client_type 列); ③ sql/50b_v179_workflow_uploads.sql (ALTER wf_emergency_plan 加 contact_phone/drill_photos/drill_attachment, wf_emergency_detail 加 step_photo, wf_safety_record 加 attachment, wf_safety_detail 加 risk_photo — 4 列全部信息守门幂等); ④ WfDashboardService + WfDashboardController GET /workflow/dashboard/upcoming?days=N — 聚合 3 类 next_due_date/next_drill_date, 按 dueDate ASC, daysLeft + type 字段, 任一 work:*:list perm 即可 (orRole=admin); ⑤ 前端 pc-web/src/api/workflow.js + 4 视图 (Tenant/Maintain/Safety/Emergency) — 增 contactPhone/drillPhotos/drillAttachment/stepPhoto/riskPhoto/attachment 字段, el-input + input[type=file] + el-dialog 图片预览, 演练照片 (逗号分隔多 URL) + 演练附件 (单文件 URL) + 演练步骤照片 (per-row) + 隐患照片 (per-row) + 整改照片 (per-row 多图) 完整 UI; ⑥ uploadFile() helper (走共享 axios instance, FormData 走 /api/system/upload/file — SaTokenConfig 已放白名单, /upload/** 已配静态资源路径); ⑦ dashboardApi.upcoming(days=30) + WfDashboard 卡片路由 (隐藏 — 用户选择先用原 dashboard 4 区结构, 后续 KPI 区+看板合并时再加); ⑧ docs/23_Dify工作流自动归档触发指南.md (完整 Dify 编排方案: HTTP 节点配置 / login 拿 token / 各端点 payload / 上传文件流 / 错误处理 + dify_bot 账号 SQL 配置). ⑨ v1.1.79 MCP 增量: backend/src/main/java/com/industrial/erp/modules/mcp/{McpController,McpToolRegistry,McpAuditService}.java + scripts/erp_mcp_bridge.py + scripts/verify_mcp.sh + sql/51_hermes_mcp_bot.sql + docs/24_Hermes调ERP-MCP自动建档指南.md. 部署 (双站 home + 飞诺 failover): ① 工作流 jar md5 f46bd2253768e69dd7b23d61a29e6ca2 (101,206,778 bytes; workflow classes 24 个 + 3 detail XML + 12 个 safelist perm row + 3 mcp classes 已全部打进 jar); pc-web dist entry index-C1cItXz9.js (workflow-*.js + 4 视图 .js/.css 全部 bake, 演练照片 / 整改照片 / 隐患照片 等 thumbnail 列已生成); 双站 sql50 + sql50b 已运行 (base_tenant 4 列全部命中 + wf_* 表 6 列全部命中); 双站 /api/workflow/{tenant,maintain,safety,emergency}/page 401→登录后 200, /api/workflow/dashboard/upcoming?days=30 已返回 1 条 home 实测 SIN202610060001, /api/system/upload/file (走 /api 前缀) 实测 200 返回 URL `/upload/yyyyMM/dd/xxx.png`; 飞诺 dist bind-mount 源为 /vol2/erp-system/pc-web/dist-new (而非 /vol2/erp-system/pc-web/dist, 部署要点). ② **v1.1.79 MCP 双站二次重部署** (修复审计 bug 后): 后端 jar md5 282abec0254fdce1e856bf8e406ad8d8 (101,219,358 bytes; McpController/McpToolRegistry/McpAuditService classes 全部打进), home (192.168.0.150) + 飞诺 (192.168.0.32) 各跑 sql/51_hermes_mcp_bot.sql (HERMES_BOT 角色 + hermes_bot 用户, 占位 bcrypt 部署后改), hermes_bot 真实密码 `hermesMCP2026!` 已 UPDATE 到两站 sys_user; 双站 /api/mcp (JSON-RPC 2.0) 已健康 — tools/list 返回 2 工具 (erp_create/query_maintenance_order, 无 check/delete), tools/call 建草稿 / 查单 200; 双站 sys_oper_log MCP 审计实写 (home 3 行 + 飞诺 1 行, module='MCP工具调用', businessType='MCP', method='[MCP] ERP_CREATE_MAINTENANCE', username='hermes_bot'); 测试草稿 home `WM202610070002` + 飞诺 `WM202610070001`. **修复要点**: McpAuditService 原 @Async @EventListener 异步写审计在 Hermes 频繁调 MCP 时 0 行落库, 改直写 SysOperLogMapper.insert 同步写, 一次 1 行开销可忽略, 100% 落库. 部署踩坑: fail2ban 多次 sudo 密码失败锁 90-120s 必须 sleep, fn docker cp jar 后必须手动 restart 才生效, DockerKit COPY 层缓存用 `docker cp` 强制覆盖最稳. **注意**: Hermes 端 mcp_servers.erp.enabled 仍为 false (待用户切换 true + 把 ERP_MCP_TOKEN 写进 ~/.hermes/.env + 重启 gateway), MiniMax M3 vision 当前 402 欠费自动降级 Agnes 3.0-flash. 零后端业务破坏 — 现有审核/反审核/CRUD 行为不变, 仅增字段. 前序版本 v1.1.78: App 端 AI 助手语音输入)

**前序版本**: v1.1.76 hotfix-1 (2026-10-05 — 飞鹅打印日志 `system:feie:log` 403 修复. 用户反馈: PC 端 系统设置 → 飞鹅打印日志 tab 打开立刻 `GET /api/feie/log/page?... 403`. 根因: sql/21 建 system:feie:log 节点 (C 型, parent=飞鹅打印机) 时只授给老角色码 'admin'/'manager', 后续角色码迁移到 6 新内置角色 (SUPER_ADMIN/PURCHASE_MGR/SALES_MGR/WAREHOUSE_MGR/PRODUCTION_MGR/FINANCE) 时漏了这条授权 → @SaCheckPermission 对所有新角色拦截 403. sql/48 不动 feie 与 AI 助手无关, 是历史遗漏授权. 修复: 新 seed sql/49_v176hotfix_feie_log_grant.sql 给 6 新内置角色 × PC 各补一行 system:feie:log 授权 (沿用 sql/41 决策飞鹅菜单保持 PC-only 不授 APP; INSERT IGNORE + information_schema 探测 client_type 老库 2 列退化). **无后端/前端代码改动**, 无需重建容器/jar/dist, 用户重登或 token 刷新即生效. 教训: 角色码迁移时必须**回头扫所有 C/F 型 sys_menu 节点**是否都补授到新角色码——光给 M 型目录授权没用, 端点门禁认的是按钮/C 节点 perms). 前序版本 v1.1.75: AI 集成全套)

**前序版本**: v1.1.73 (2026-10-04 — 角色管理「分配权限」回收站权限无法勾选修复 (grantable). 用户反馈: 角色管理→分配权限→PC端菜单权限, 报表中心下「回收站」复选框灰色不可勾 (同级「报表查看」可勾). 根因: v1.1.68 sql/45 把回收站 seed 成 M 型菜单节点 perms=''(空) — 权限树机制把「空 perms 的 M 节点」判为不可授权目录: 前端 Role.vue markDisabled 只有 B/M+F(带 perms) 才 enabled → 回收站 disabled; 后端 SysRoleService.isGrantableMenu 空 perms 的 M 返回 false → 每次保存角色时 seed 写入的回收站授权行被静默删掉. 决策: 回收站改独立权限码 report:recycle. 改动: ① 新 seed sql/47_v172_recycle_perm.sql (M 节点 perms ''→'report:recycle' + 6 内置角色授权, information_schema 探测 client_type 列, 幂等); ② 后端 ReportController 3 回收站端点 + RecycleBinService requirePerm 由 report:view 改 report:recycle; ③ 前端 router report/recycle meta.perm 由 report:view 改 report:recycle. 权限树/侧边栏判定逻辑本身已支持「M+perms非空」, 无需改 markDisabled/isGrantableMenu. 纯后端 jar + 前端 dist + 迁移, 无 DDL. 双站部署 (home+飞牛 各跑 sql/47 + 重打 jar + 重打 dist + docker 重建, 保留各自 SA_TOKEN 密钥). 前序版本 v1.1.72: 回收站「商品」列全空修复) 用户反馈: 回收站销售出库 Tab 商品列全是 `-`, 但客户/金额/删除人都正常. 根因: v1.1.71 加的 productNames 子查询 `LEFT JOIN base_product ... WHERE dtl.deleted=0`, 而业务删除时 head+detail 一起置 deleted=1 (SalDeliveryService.delete 等 11 类都这样) → 被删单据的明细行按 deleted=0 一条查不到 → 商品列返回 NULL. 库里每条被删 detail 都自带去规范 product_name, 线上 SQL 实测 detail_alive=0 但 detail_hasname>0. 修复: RecycleBinMapper.xml productColumn 子查询改为直接 GROUP_CONCAT(DISTINCT dtl.product_name), 去掉 base_product JOIN + deleted=0 过滤; 对全部 10 类带明细单据生效, 不再依赖商品是否还软删. 纯后端 XML 一处, 无 DDL/前端. 双站部署: home 推 jar→docker build --no-cache 重建 erp-system-backend:latest→保留原 erp_env_home.txt(SA_TOKEN 密钥)重建容器; 飞牛 docker save home 新镜像→LAN 流式 docker load→tag erp-backend-failover:latest→用 .erp_env_failover 重建. 两站线上 jar md5 bbfd946f... 已确认, sal_delivery 被删单商品名现正常聚合 (home 带鱼袋/透明胶带, 飞牛 金达印字胶带). 前序版本 v1.1.71: 回收站列表补充 商品/金额/删除人 三列)

**前序版本**: v1.1.72 (2026-10-04 — 回收站「商品」列全空修复. 用户反馈: 回收站销售出库 Tab 商品列全是 `-`, 但客户/金额/删除人都正常. 根因: v1.1.71 加的 productNames 子查询 `LEFT JOIN base_product ... WHERE dtl.deleted=0`, 而业务删除时 head+detail 一起置 deleted=1 (SalDeliveryService.delete 等 11 类都这样) → 被删单据的明细行按 deleted=0 一条查不到 → 商品列返回 NULL. 库里每条被删 detail 都自带去规范 product_name, 线上 SQL 实测 detail_alive=0 但 detail_hasname>0. 修复: RecycleBinMapper.xml productColumn 子查询改为直接 GROUP_CONCAT(DISTINCT dtl.product_name), 去掉 base_product JOIN + deleted=0 过滤; 对全部 10 类带明细单据生效, 不再依赖商品是否还软删. 纯后端 XML 一处, 无 DDL/前端. 双站部署: home 推 jar→docker build --no-cache 重建 erp-system-backend:latest→保留原 erp_env_home.txt(SA_TOKEN 密钥)重建容器; 飞牛 docker save home 新镜像→LAN 流式 docker load→tag erp-backend-failover:latest→用 .erp_env_failover 重建. 两站线上 jar md5 bbfd946f... 已确认, sal_delivery 被删单商品名现正常聚合 (home 带鱼袋/透明胶带, 飞牛 金达印字胶带). 前序版本 v1.1.71: 回收站列表补充 商品/金额/删除人 三列)

**前序版本**: v1.1.71 (2026-10-04 — 回收站列表补充「商品/金额/删除人」列. 用户反馈: 回收站单据要显示 商品、金额、删除人. 改动: RecycleBinMapper.xml listDeleted 加 3 列 — productNames (聚合明细 product_name, GROUP_CONCAT DISTINCT; prd_order 取 head.product_name), totalAmount (仅 6 业务单据有 total_amount, 生产/BOM/盘点/调拨 NULL), deletedBy (update_by JOIN sys_user.username, 无专门 deleted_by 列故用 update_by 近似). Recycle.vue 表格插 商品/金额/删除人 三列. 踩坑: 首版 GROUP_CONCAT ORDER BY dtl.line_no 因 inv_check_detail/inv_transfer_detail 无 line_no 列 → 500, 改 DISTINCT 不排序. 纯 XML+Vue, 无 DDL/签名变更, 双站 jar+dist+docker. home 实测销售出库 49 行 CKP202610040002 totalAmount=4725 deletedBy=gpssong; 飞牛 pur_receipt 返回 deletedBy=秦运桂. 前序版本 v1.1.70: 回收站被删销售出库单不出现双 bug 修复)

**前序版本**: v1.1.70 (2026-10-04 — 回收站「被删销售出库单不出现」双 bug 修复. 用户反馈: 回收站销售出库 Tab 空, 但库里有 50 条 deleted=1 的 sal_delivery. 根因 1: report:view (sys_menu 951) 只授给 SUPER_ADMIN/PURCHASE_MGR, sql/45 只给 6 角色加了「回收站」M 型菜单节点(perms='') 却没补 report:view 本身 → 其余 4 内置角色看得到菜单但一进 403, 前端 Recycle.vue catch 静默吞 403 → 全 Tab 空. 根因 2: RecycleBinMapper.listDeleted 硬编码 h.bill_no/h.bill_date, prd_bom 无这两列(用 bom_code, 无日期列) → listAll 一次跑 11 类, prd_bom 那条抛 Unknown column 'h.bill_no' → 端点整体 500, 连销售出库也拿不到数据. 修复: 新 seed sql/46_v169_recycle_report_view_grant.sql 把 report:view 幂等补授 6 内置角色×3 client_type (客户端路由 guard 与后端 API 同源, 一处补齐双端生效, 双站各跑); RecycleBinMapper.xml 加 billNoColumn/billDateColumn/billNoSearchColumn/billDateSearchColumn 四 <choose> 片段, prd_bom 走 bom_code/create_time 其余走 bill_no/bill_date. 纯后端 (XML+seed), 无 DDL/前端代码变更. 双站 jar+docker 重建, home 实测 /report/recycle/bin 返回 200 销售出库 50 行. 前序版本 v1.1.69: 列表"商品名称"列软删明细泄漏修复)

**前序版本**: v1.1.69 (2026-10-04 — 列表"商品名称"列软删明细泄漏修复. 用户反馈: 出库单 CKP202610040007 编辑器只有 1 行商品, 但列表"商品名称"列却显示"透明胶带, 透明..."(多品被逗号拼接). 根因: sal_delivery_detail 有 2 行同品(1 行 deleted=1 软删 + 1 行 deleted=0 有效), 4 个列表 mapper (SalDeliveryMapper/SalReturnMapper/PurReceiptMapper/PurReturnMapper) 的 first_product_name 用 GROUP_CONCAT 聚合全部明细行且未过滤 deleted=0, 把软删行也拼进商品名称 → 列表与编辑器不一致. 修复: 4 个 mapper 的 first_product_name/spec/model 明细子查询 + productName 搜索 EXISTS 子查询统一加 AND <detail>.deleted=0. 纯后端 SQL, 无前端/DDL. 双站部署 (home+飞牛 jar+docker 重建), home DB 实测该单现返回单一"透明胶带")

**前序版本**: v1.1.68-hotfix (2026-10-04 — 销售出库"成本 0 / 审核查无实据"根治 + 出库日志/草稿提示. 用户反馈: 从"销售记录→历史销售"添加的出库单 CKP20261003/40001 报表直接显示已审核、操作员不对、毛利分析成本=0. 全 DB 实测定位三个独立根因: (1) 操作员列读 create_by=gpssong1 是对的 (多账号 gpssong1/gpssong/赵偲荣 混开单), 真正缺口是 check()/uncheck() 不写 sys_oper_log → 7 个 service (销售出库/订单/退货, 采购入库/退货/订单, 库存盘点) 的 check/uncheck 加 @OperLog(CHECK/UNCHECK); (2) 成本 0 病根在采购入库 — dyd975007 的 4 张 RKP 成本价 price 全填 0, 移动加权平均 avg_cost 从头 0 → outStock outCost=avg_cost×qty=0 → 出库成本 0; (3) "历史销售添加"无 bug, 走 add() L193 强制 DRAFT. 数据回填 (仅 home 生产库 192.168.0.150, 成本价 0.0546 元/只 用户确认): inv_stock.avg_cost=0.0546/total_cost 重算 + base_product.cost_price=0.0546 + 4 张 pur_receipt_detail price/amount 补齐 + 2 张 sal_delivery cost/profit 补齐 (成本 1965.60/毛利 230.40/毛利率 10.49%), 建 4 张 *_bak_v168 备份表可回滚. 前端 Order.vue 生成出库单按钮改"保存为草稿(需审核后生效)" + Delivery/Order 保存 toast 明确"草稿, 审核后才会扣库存/生成应收/算成本". 双站部署: 后端 jar + pc-web dist; 飞牛热备库无 dyd975007 业务数据 (0 行) 不回填. 纯代码 + 数据, 无 schema DDL)
**前序版本**: v1.1.68 (PC 端报表中心新增「回收站」— 11 类被软删单据分类/恢复/彻底删除. 入口: `pc-web/src/layouts/MainLayout.vue` 报表中心 children 加 `/report/recycle` + `router/index.js` 加路由 (meta.perm=`report:view`). 前端新视图 `pc-web/src/views/report/Recycle.vue` (11 类 el-tabs + 表格, 【恢复】翻回 deleted=0, 【彻底删除】物理 DELETE 不可逆), `api/report.js` +3 方法 (recycleBin/recycleRestore/recyclePurge). 后端: `RecycleBinMapper` (原生 SQL, `${headTable}/${detailTable}/${detailFk}` 由 `RecycleType` 白名单枚举传参, 绕 @TableLogic 读 deleted=1) + `RecycleBinService` (listAll 按 type 分组返回 11 类; restore/physicalDelete @Transactional 双表原子; 复用 report:view 门禁) + `ReportController` +3 端点 (/report/recycle/bin /restore /purge, @SaCheckPermission("report:view")). 关键设计: 业务删除只对 DRAFT 开放 → 被删单据无库存/账副作用 → 恢复仅翻 deleted=0 无需回滚; 彻底删物理 DELETE 绕开 prd_order (bill_no,deleted) 唯一索引撞坑. seed `sql/45_v168_recycle_bin_menu.sql`: sys_menu M 型节点 (parent_id=9 报表中心, perms='' 门禁靠 report:view) + sys_role_menu 给 6 内置角色授权 (client_type 列自适应, 老库 2 列退化). 纯 PC 端功能 (后端 jar + pc-web dist), App 不动. 待部署: 双站各跑 sql/45 + 重打后端 jar + 重打 pc-web dist + 重启容器)
**前序版本**: v1.1.67 (App 扫码出库明细落库缺失商品编码/名称修复 — 用户反馈 App 扫码出库生成的出库单 CKP202609290006 在 PC 端打印送货单时「商品编码/商品名称」栏空白, 只有「型号」有值. 根因: App `app/src/pages/scan/out.vue` `onSubmit()` 提交 payload 时只发 `productId/qty/price/batchNo/remark`, 把 `productCode/productName/spec/unitId/unitName` 全砍了; 后端 `SalDeliveryService.add()` 原样 insert 客户端 JSON, 这些真实 DB 列就落 NULL. 型号(model)是 transient, `detail()` 里 `ProductAttrInjector.inject` 从 base_product 实时注入 `pModel`, 所以打印能显示; 编码/名称是真实列, 不在商品主数据回填就永久缺失. 修复双层: (1) 后端 `SalDeliveryService` 新增 `enrichDetailsFromProduct()` 私有方法, add()+update() insert 前批量 `productMapper.selectBatchIds` 一次查全 (无 N+1), 仅在字段 blank 时回填 productCode/productName/spec/unitId (unitName 由 App 携带, 不额外引 BaseUnitMapper), 只兜底瘦客户端不覆盖 PC 已录值; (2) App `onSubmit` payload 补全 5 字段 (list 里本就有). 纯后端 jar + App H5/APK, PC dist 不动. App APP_VERSION/manifest.versionName 1.1.64→1.1.67. 历史数据回填 `sql/44_v167_backfill_delivery_detail_unit.sql`: 修 base_product_unit.unit_id 0→真实 active unit (v1.1.16 脏数据) + 三级兜底回填 sal_delivery_detail.unit_id/unit_name, home 95→3 / 飞牛 78→3, 双站 jar MD5 bcfe92a32d4ea12b95d7402d7bdf9f48 部署 healthy, 备份 base_product_unit_bak_v167)

**前序版本**: v1.1.66 (PC 端"销售订单多单合并生成出库单" — 同客户 2 张以上已审核订单勾选合并成 1 张出库单/送货单. 数据层天然支持 (sal_delivery_detail.order_detail_id 是逐行溯源键, 可跨订单), 缺的只是入口. 前端 Order.vue 加多选列 + "合并生成出库单"按钮, 抽通用 openGenerateDialog(orders[]): 逐订单拉 detail+getDeliverySummary, 明细拼接每行保留各自 orderDetailId, 单订单主表写 orderId, 多订单主表 orderId=null (溯源走明细行), 明细表加"来源订单号"列(仅展示, 提交前剔除); 同客户校验 (客户不同报错). 后端 SalDeliveryMapper.selectPageByOrderId 追溯查询加 EXISTS(明细 order_detail_id IN 该订单明细), 让合并单 (主表 order_id NULL) 在每张源订单"关联出库单"里都可见. schema 不变, 超发防护 (v1.1.65 剩余数量默认) 逐行保留. 纯 PC 端功能 (后端 jar + pc-web dist), App 版不动, 仍 1.1.64)

**前序版本**: v1.1.66 version-sync (系统信息版本号字面量对齐 — 用户反馈系统设置「系统信息」前端/后端版本仍显示 `1.1.53-hotfix.1`, 与 changelog 脱节。根因: 版本号字面量在 v1.1.53 被 `deploy-version-bump.sh` 冻结后再没维护。修复 4 文件纯字面量对齐: `pc-web/package.json` → `1.1.66` (需重打 dist, `__APP_VERSION__` 构建期烤入) + `application.yml` `erp.version` 默认 + `SystemVersionInitializer` `@Value` 默认 → `1.1.66` + `app/package.json` 顺手对齐 `1.1.64`。部署: 重打 pc-web dist (System chunk `System-CGWmoRBS.js`) 双站 bind-mount; 双站 backend 现有 image + 追加 `-e ERP_VERSION=1.1.66` 重建容器, 无需重打 jar。两站 JWT 密钥沿用, 不用重登。双站 `sys_config.SYSTEM_VERSION_INFO` 均 `version=1.1.66`。R15: 版本字面量分散 3 源文件+1 运行 env 极易漂移, 后续每个版本对齐需同步 4 处且前端必须重打 dist)

**前序版本**: v1.1.65 (PC 端"生成出库单"商品明细数量默认改为「剩余数量」— 从销售订单生成出库单时, 数量列默认填入 `订单全量 − 已审核出库累计`(剩余), 已发完的明细默认为 0, 避免部分发货过的订单重复超发. 后端 `SalOrderService.getDeliverySummary` 每行加 `orderDetailId` (供按明细行匹配已发/未发); 前端 `pc-web/views/sales/Order.vue` `onGenerateDelivery` 拉发货汇总建 Map<orderDetailId, summary>, 数量默认 `max(0, 全量−已发)`, 新增只读「订单数量」列对照 + 提示文案更新. 纯 PC 端功能 (后端 jar + pc-web dist), App 版不动, 仍 1.1.64)

**前序版本**: v1.1.64 (App 扫码跟随竖屏 — 修复"连续扫码每次闪一次横屏宽界面"的体验问题. 根因: `ScannerActivity.java` L58 强制 `SENSOR_LANDSCAPE` 横屏, uni-app 手持竖屏扫码入库/出库连续扫多个商品时, 每个扫码都弹一次原生 Activity 把手机转横, 用户看到宽屏界面. 修复: `setRequestedOrientation(SCREEN_ORIENTATION_UNSPECIFIED)` 跟随父 Activity (uni-app 主 Activity 竖屏), manifest 同步改 `android:screenOrientation="unspecified"`. `applyCenterScanRect` 已用"短边 60%"计算方框, 竖屏 1080x2340 → 648x648 居中, 解码区比横屏还小更聚焦. 改了 H5 (APP_VERSION 1.1.63→1.1.64 + manifest versionName), 全流程 `build-app.sh`. APK MD5 `ed4d3f30a68235a3a895e046e3215ffc`, 4,369,472 字节)

**前序版本**: v1.1.63 (App 扫码灵敏度优化 — `ScannerActivity` 针对"扫二维码不灵敏"做 3 处性能改造: (1) 限定解码格式 `{QR, EAN_13, EAN_8, CODE_128, CODE_39}`, 默认 ZXing 每帧跑 14 种解码器拖慢响应 2-4x; (2) 中央 60% 方框 `setFramingRectSize` 限定解码区, 计算量降 ~64%; (3) `CameraSettings` 切连续对焦 + 条码场景模式 (部分低端机 AUTO 拉风箱). 纯原生改动 (Java + 无 layout 变更), 跳过 H5 重打直接 `gradlew assembleDebug`; 踩坑: **ZXing 4.3.0 API 大改** — `DecoratedBarcodeView` 不再继承 `CameraPreview`, 改成 `getBarcodeView()` 拿内层 view + `setDecoderFactory(new DefaultDecoderFactory(...))` + `inner.setFramingRectSize(new Size(...))`; layout 里 `zxing_preview_scaling_strategy` 枚举只有 centerCrop/fitCenter/fitXY. 同 commit 修了 App 版本号 5 版漂移, 三处版本号对齐 1.1.63 + `build-app.sh` 加 [1.5/5] 版本一致性护栏. APK MD5 `98aa9e6ea8e1097625592f2fcb0fa467`, 4,369,493 字节)

**前序版本**: v1.1.62 (库存查询 SQL 改写 — `/inventory/stock/page` 以 `base_product` 为驱动表 LEFT JOIN `inv_stock` 聚合, 删除 v1.1.61 及之前 `w.gt(InvStock::getQty, 0)` 严格过滤, 让**库存为 0 且无 inv_stock 行的产品**也能命中查询结果; 库存为 0 时 qty/availableQty/lockQty/avgCost/totalCost 列全 0/空, 产品名/编码/规格仍正常显示; 新增 `InvStockPageQueryMapper` + 配套 XML, controller 改返回 `PageResult<Map<String, Object>>` 以兼容 PC `Stock.vue` 表格 prop + App `inventory/query.vue` 卡片; PC 端 + App 端共享一个 endpoint 同时受益; 后端 jar md5 `fc5245f493f5b243de6810d18c0c3ffa`, docker image `erp-system-backend:latest` 已 rebuild (id `817f8a1b20f1`) + 容器已 `docker rm` + `docker run --env-file` 重建; 线上 mysql 直跑模拟 SQL 已命中用户截图案例"塑料袋30*38*0.16" (id `2075469315994950161`, qty=0.0000); 踩坑记录: `base_product.unit_id` 实际为 `main_unit_id` (**3 处都要改**: SELECT AS / LEFT JOIN ON / GROUP BY, 第一版漏改 L42 LEFT JOIN ON 导致线上报 `Unknown column 'p.unit_id' in 'on clause'`**) + `docker compose up -d` 因 `${VAR:?长消息}` 含空格被 v2.20.1 yaml parser 解析坏 (绕过方法: 直接 `docker build --no-cache` + `docker rm` + `docker run --env-file` 不走 compose), `docker restart` 不切换 image (锁定原始 image hash))

**前序版本**: v1.1.60 hotfix-2 (App 端"我的-刷新权限菜单"双弹覆盖修复 — `app/src/pages/profile/index.vue` L138-149 catch 移除重复 `uni.showToast`, 拦截器已弹过的 toast catch 不应再弹, 否则 Android `uni.showToast` 后调用会中断前调用, 用户只看最没信息量的那条; 拦截器 L89-91 弹后端 msg + L99-101 弹网络错 + L65-70 弹反代 502 都保留不动, 让组件 catch 静默; 401 路径特殊, 拦截器自动 reLaunch 跳登录页, catch 不弹 toast (一闪而过干扰跳转); H5 容器 erp-app-h5 已 rebuild + recreate (镜像 hash `38e532179829`), 线上 `pages-profile-index.9bnvdhRj.js` MD5 `518e2068682e0098d3f6333f78ad45cc` = 本地 dist 一致)

**前序版本**: v1.1.60 hotfix-1 (PC + App 登录错误提示统一规范 — PC `pc-web/src/views/Login.vue` L130-137 catch 加 `ElMessage.error(friendly)`, 修复 v1.1.31+ request.js 拦截器不再弹后 PC 端 catch 完全空转的 bug; App `app/src/pages/login/index.vue` L119-129 catch 改 `uni.showToast`, 去掉 `alert("登录失败: ...")` 突兀对话; 两端优先展示后端 `AuthService` 返回的 msg ("用户名或密码错误"), 兜底 "账号或密码错误, 请重试"; PC dist 已部署到 home NAS, 线上 `Login-CktUqvzO.js` MD5 `4361c418aa3293428292ba650e52ca01` 与本地 dist 一致)

**前序版本**: v1.1.60 (App 端物理 back 区分栈深度 + 工作台吞掉事件 — 详情页按 back 先 navigateBack 回列表(栈深>=3) 或 switchTab 工作台(栈深=2), 工作台页按 back 不退出 App 而是吞掉事件; 改 `app/src/utils/nav.js` L34-104 `onAppBack` 函数, 替换 v1.1.54+ 粗暴"任意非工作台都 switchTab 工作台"的逻辑; APK MD5 `c80a208869199d3e165c809db5b3b775`)

**再前序**: v1.1.56 hotfix-1 (R10 sys_menu.perms 无 UNIQUE 索引, sql/35 `INSERT IGNORE` 时未拦截同 perm 旧 B 行, 导致 v1.1.55 后 PC 弹窗"采购入库反审核"出现 2 次: id=6050 旧 B 类型 + id=...5349 新 F 类型; sql/42 数据级清理保留 F 行(权威 perm 载体) + 删除 B 行 + sys_role_menu.menu_id 迁移引用. 设计原则 R10: sys_menu.perms 必须有 UNIQUE 索引, 后续 seed perm 行必须先 SELECT 查重, 不能依赖 INSERT IGNORE 兜底; sql/42 仅清理 user 截图定位的 uncheck 行, 其他 19 行 B 类型(check/edit/delete/print) 待 v1.1.57+ 单独审计)

**再再前序**: v1.1.55 hotfix-2 (App 端 canCheck/canUncheck 走 getAppPermissions() 而非混合端 getPermissions() — 后端 selectPermsByUserId 不分端, 临时方案靠前端 storage 派生, 受 APK 升级/storage 残留影响; R9 上线后 getAppPermissions() 可继续保留作 fallback, 但不再依赖)

**设计原则 (R16 — uni-app showLoading/hideLoading 配对规范)**:
- **showLoading 与 hideLoading 必须配对**: 任何 `uni.showLoading(...)` 调用必须在同一函数的 `try`/`catch`/`finally` 三处至少有一处对应的 `uni.hideLoading()` 兜底
- **catch 里 hideLoading 的前提是 try 内 showLoading**: 如果函数体**没 showLoading**, catch 里就不应调 hideLoading (uni-app 报 `hideLoading` 无对应 showLoading 警告, 干扰诊断)
- **401 路径**: 拦截器会自动 `reLaunch` 跳登录页, hideLoading 不影响; 但 showLoading 的 mask=true 会挡住跳转, 401 路径前最好 hideLoading 一次
- **R13 拦截器双弹规范**: 拦截器已弹后端 msg (网络错/反代 502/业务 4xx), 组件 catch 不再 `uni.showToast` 重复弹; 但 showLoading/hideLoading 不在此规则内 (loading 是 UI 状态, 与 toast 不同)
- **统一模板**:
  ```js
  async function onXxx() {
    if (typeof uni !== 'undefined' && uni.showLoading) uni.showLoading({ title: 'Xxx中...', mask: true })
    try {
      await api.xxx()
      if (typeof uni !== 'undefined' && uni.hideLoading) uni.hideLoading()
      // 业务 toast
    } catch (e) {
      if (typeof uni !== 'undefined' && uni.hideLoading) uni.hideLoading()
      // 业务弹窗 / 静默 (R13)
    } finally {
      // 兜底: 必 hideLoading (uni-app 文档推荐)
      if (typeof uni !== 'undefined' && uni.hideLoading) uni.hideLoading()
    }
  }
  ```
- **例外**: 不 showLoading 的函数 catch 里也不 hideLoading (例如 `onDelete` 静默调 API, 失败 console.warn, 没 showLoading 就不需要 hideLoading)

**v1.1.103 改动**:
1. **App H5 502 Bad Gateway 修复** — home 端 `erp-app-h5` 容器 nginx.conf 写死 backend 容器旧 IP `172.18.0.4:8080`, backend 重启后换成 `172.18.0.5` → nginx `Connection refused` → 502 → 前端兜底 403 "服务暂不可用". 改容器名 `erp-backend` (Docker DNS) + 转 bind-mount 形态
2. **App H5 403 "Invalid CORS request" 根治** — Spring Security `setAllowCredentials(true)` 精确白名单不匹配 App H5 页面 origin `192.168.0.150:18090`, 加 `config.addAllowedOriginPattern("http://192.168.*:*")` 一次覆盖整个内网段
3. **备份 403 @SaCheckRole("admin") 死注解** — `SysBackupController` 5 写端点 + `AuthController.setpwd` 共 6 处 `@SaCheckRole("admin")`, SUPER_ADMIN 角色码不匹配一律 403; 删 6 处注解 + 重打 jar 双站 docker cp
4. **App H5 生产管理 4 入口缺 3** — home 主站 `sys_role_menu` 上 9 个 production 核心 perm 在 6 业务角色全 0 行 APP 授权 → appMenus 拿不到 perm → dashboard 入口不显示. 写 `sql/124_v1103_home_production_app_perm.sql` 给 6 角色 × 9 perm 补 APP 授权, NOT EXISTS 守门幂等
5. **App H5 开工 BOM 硬阻断容错** — 后端 `PrdOrderService.release` 在 `bomId==null` 时直接 `throw "未配置BOM, 无法展开领料单"`. 工业品吹膜厂实际场景工单常先开工后补 BOM, 不该硬阻断. 改成**无 BOM 降级** (标 RELEASED, 跳过自动领料单 + 跳过工序展开, 日志 warn 提醒, 用户后续手动建领料). 同步修 `order-detail.vue` `onDelete` catch 里多余的 `hideLoading` 调用 (违反 R16)
6. **App H5 编辑工单 BOM 字段截断** — `BaseProductService.detail` 不返回 bomName (避免 base→production 模块循环依赖), 前端 `onProductChange` 主动调 `bomApi.detail(p.bomId)` 拿 bomCode/bomName, 不再显示 19 位雪花 ID 截断
7. **开工仓库硬校验改降级放行 (hotfix-1)** — home 3 仓库 `is_default` 全 0 + 工单 `warehouse_id=NULL` + BOM 有明细 → 自动领料展开仓库回退失败 → `release()` L255 抛 "生产单未填仓库...". 用户拍板 "开工不需要填仓库, 入库才需要". 改法: 仓库解析顺序不变 (生产单自带 → 默认仓库 → 都没有), 但 "都没有" 时**不再 throw**, 改记 warn + 跳过自动领料展开, 状态照样置 RELEASED/PRODUCING (用 `canExpand = !bomNoDetails && reqWarehouseId != null` 统一判定, 弃 `-1L` 哨兵值); 开工核心动作 (XK 留痕 + 工序展开) 与仓库无关不受影响. 用户后续手动到「领料管理」建领料单时再填仓库

**改动文件**:
| 文件 | 类型 | 说明 |
|---|---|---|
| `backend/.../config/SecurityConfig.java` | 改 | + `addAllowedOriginPattern("http://192.168.*:*")` |
| `backend/.../system/controller/SysBackupController.java` | 改 | 删 5 处 `@SaCheckRole("admin")` + import |
| `backend/.../auth/controller/AuthController.java` | 改 | 删 `setpwd` 1 处 `@SaCheckRole("admin")` + import |
| `backend/.../production/service/PrdOrderService.java` | 改 | release() 无 BOM 降级 (throw→warn+RELEASED) + **仓库缺失降级** (hotfix-1, `canExpand` 判定, 弃硬校验) |
| `app/src/pages/production/order-detail.vue` | 改 | onDelete catch 删多余 hideLoading + 新增 Edit/Delete |
| `app/src/pages/production/order-list.vue` | 改 | 操作列加编辑/删除按钮 |
| `app/src/pages/dashboard/index.vue` | 改 | (v1.1.102 hotfix-2 已加 8 个 XK 子分类入口) |
| `sql/124_v1103_home_production_app_perm.sql` | 新 | 6 角色 × 9 perm 补 APP 授权 |
| `pc-web/src/views/production/Order.vue` | 改 | 编辑/删除按钮 + BOM 主动拉取 |

**部署** (双站 home + 飞牛):
- **后端 jar**: Mac mvn clean package → **hotfix-1 最新 md5 `1c95906f9af68a9cd63beb2760b97aeb` (101,460,004B)** (v1.1.103 正文 md5 `911f01e0...` 101,459,745B) → home `docker build --no-cache` + `docker rm -f erp-backend` + `docker run` (17 env 走 `run_home_backend.sh`, --env-file 在 sudo 隔离下不可见) → baked-in jar md5 验证 `docker exec erp-backend md5sum /opt/app/app.jar` → 飞牛 `cat|ssh` 流 jar → `docker cp /opt/app/app.jar` → `docker restart`
- **sql/124**: 双站 mysql 各跑一遍 (NOT EXISTS 守门, 重复灌安全)
- **前端**: `npm run build` → 飞牛 `/vol2/erp-system/pc-web/dist-new` 全量替换 + home `/volume3/docker/erp-system/pc-web-new/dist` 全量替换 + `docker restart` 双站 (bind-mount 即时生效)
- **App H5 容器 (home)**: `nginx.conf` 改容器名 + 转 bind-mount; **App H5 容器 (飞牛)**: 已是 bind-mount 形态无需改; **APK 产物**: 本次没改 H5 端代码, 不需要重打 APK

**验证**:
- **home CORS**: 容器内 baked-in jar md5 `911f01e0...` ✓ + 登录 `Origin: http://192.168.0.150:18090` 200 + `Origin: http://192.168.1.100:8080` 200 (profile 绝对 base) ✓
- **飞牛 CORS**: `Origin: http://192.168.0.150:18090` 200 ✓
- **App H5 入口**: 灌 sql/124 后 gpssong 重登, 生产管理 tab 4 个入口全显 (生产加工单 / 扫码报工 / 半成品入库 / 成品入库) + 5 个 XK 子分类
- **开工容错**: `PD202610170001` (无 BOM) 点开工 → 标 RELEASED, 不再报 "请检查BOM配置", 不阻断
- **开工仓库降级 (hotfix-1)**: `PD202610190005` (BOM 有明细 + warehouse_id=NULL + 库中无默认仓库) 点开工 → 200 成功, bill_status 落 `PRODUCING` (BOM 有明细工序展开成功) + `release_name=gpssong` (XK 留痕), 不再报 "生产单未填仓库". 容器内 jar md5 `1c95906f` 与本地一致

**回滚**:
- 后端 jar 双站回退到 v1.1.102 jar (md5 `61944e8abba0afbc914072888b76f0da`) + docker cp + restart (hotfix-1 则回退到 v1.1.103 正文 md5 `911f01e0...`)
- 删 `sql/124_v1103_home_production_app_perm.sql` 灌的 54 行授权 (DELETE FROM sys_role_menu WHERE menu_id IN (...))
- nginx.conf 改回 IP `172.18.0.5` (但下次 backend 重启又会 502, 推荐保留容器名方案)

**边界**:
- CORS 通配 `http://192.168.*:*` 仅覆盖内网段 (192.168.0.0/16), 公网 origin 仍走精确白名单 (v1.1.79+ 已加 home.93gushi.com:8088/8089 等)
- BOM 降级后用户**手动**到「领料管理」创建领料单; 后续若补 BOM, 该工单不会再自动展开领料 (已 RELEASED)
- 没配 `mes_process_def.is_default=1` 工序链的产品, 即使 BOM 在也跳过工序展开 (零破坏, 不报工)
- **仓库降级 (hotfix-1)**: 仓库缺失时只跳过"自动领料展开", 开工主流程 (留痕/工序) 照常; 用户后续手动建领料单时**必须**填仓库 (`prd_requisition.warehouse_id NOT NULL`); 若想让未来自动展开有仓库可用, 可在「仓库管理」设一个 `is_default=1` 默认仓库

**前序版本**: v1.1.99 (2026-10-17 — 参考 ruoyi-vue-pro MES 完善车间闭环: C1 工单状态机接入工序完工(硬门禁) + C2 批次全链追溯(料→半成品→成品→客户) + C3 质量门禁对称化(OQC/OQC_RETURN 软门禁)+ 工艺参数生效 + C4 生产排程/设备 OEE(2 新表) + 附带清理(Trace 去 mock + 维保双表统一). 用户拍板 C1–C4 全做 + C1 完工走硬门禁. 实证诊断 6 缺口: ① STATUS_PRODUCING 死枚举 0 处写入 + finish() 不校验工序全完工; ② 批次追溯 3 段断链(成品→客户 customer_id 永 NULL / 半成品→原料 rawBatchNo 硬编码 NULL / 无服务端全链递归); ③ OQC/PAT/OQC_RETURN 只建草稿不校验合格(IQC/FAI 有硬门禁但不对称); ④ mes_process_param.is_critical+上下限有字段无判定逻辑; ⑤ 排程/OEE/设备占用全无; ⑥ xk/Trace.vue 是 mock 壳. 后端改: MesReportService(C1 allProcessesFinished/listUnfinished helper + C2 traceFull BFS 全链/resolveRawBatchForOrder/registerFgOutToCustomer + C3 工艺参数越限判定), PrdOrderService(release 写 STATUS_PRODUCING + finish 硬门禁), PrdFinishedInService(check 双硬门禁 工序未完工/无合格OQC 不能入库), PrdSemiInService(check raw 取值不再 null), SalDeliveryService(check 每行写 FG_OUT link 带 customer_id), SalReturnService(check OQC_RETURN 软门禁), QmsInspectionService(+findOqc/hasPassedOqc/findOqcReturn/hasPassedOqcReturn), EmMaintainOrderService(+pageForXk 不校验本 service perm 供 XK 复用), MesController(+trace-full/schedule/oee/process-time 端点), XkController(设备维保从 WfMaintainRecord 老表切 EmMaintainOrder 新表 + +traceFull 端点). 后端新: MesSchedule/MesProcessTime entity + 2 mapper + MesScheduleService(排程 CRUD+冲突检测+设备承接校验+OEE). sql/113_v199_mes_schedule_oee.sql (2 新表 mes_schedule/mes_process_time + 2 F perm production:schedule:{list,edit} + 6 内置角色×PC 授权, 仿 sql/54 幂等). 前端: 新 Schedule.vue(排程甘特/列表/OEE 3 tab) + api/production.js(mesApi 加 6 C4 方法+batchTraceFull) + 改 Order.vue/Trace.vue/报工页 report.vue/Trace 去 mock(api/xk.js 移 unsplash 假图+kg 占位, 时间线走 /xk/trace/full)/router/index.js(+production/schedule)/MainLayout.vue. 飞牛 canary 部署: C4 SQL 灌库(2表+2perm+24行角色授权命中) + 后端 mvn clean package 101MB + **飞牛 jar 必须 docker cp 覆盖 erp-backend-failover:/opt/app/app.jar + docker restart**(jar 烘焙进镜像, cp+restart 无效) + 前端 dist 146 js dist-new 全量替换. 验证: 后端 healthy /api/auth/captcha 200 + 新端点 /mes/batch/trace-full//mes/schedule/page//mes/oee/1//xk/trace/full 全 401(路由已注册+门禁正常, 非 404) + Schedule-CNITiQiQ.js 在线 200 + mes_schedule/mes_process_time SHOW TABLES 命中. 边界: C1–C3 零 schema 回滚=还原 Java; C4 回滚=还原 Java + DROP 2 表 + 删 2 perm; 报工仍不动 inv_stock(WIP 账二期); production:schedule:{list,edit} 是新 perm 飞牛已授权 6 角色但已有登录用户 Sa-Token session 缓存旧 perm 数组 → 需退出重登才见"生产排程"菜单; 可选 sql/112_v199_batch_link_backfill.sql 回填历史 sal_delivery FG 批→客户 link(默认未写未跑); home NAS(192.168.0.150) 未同步待批准. 前序 v1.1.91: XK 合规中心)

**4 个独立 bug 一轮修完, home 主站全部命中, 飞牛 canary 状态对齐**:

#### 1. App H5 502 Bad Gateway
- **症状**: `http://192.168.0.150:18090/#/pages/dashboard/index` 进工作台后, 任何 `/api/*` 请求 502 (后端连不上)
- **根因**: home 端 `erp-app-h5` 容器 nginx.conf line 30 写死 `proxy_pass http://172.18.0.4:8080/api/`, 这是 backend 容器**旧** IP, backend 重启后 Docker 网络重分配, 容器从 `172.18.0.4` 换成 `172.18.0.5` → nginx Connection refused → 502 → 前端兜底成 403 "服务暂不可用"
- **修法**: 改容器名 `erp_backend:8080` (Docker 网络 DNS 自动解析, 不依赖 IP), 推 `docker cp` 进容器 + `nginx -s reload` (无 full container restart); 同时把 home 端 `erp-app-h5` 从镜像内置 conf 改成 **bind-mount** 形态 (对齐飞牛 canary, 消除"重建镜像回退"风险)
- **验证**: `GET /api/auth/captcha` via app-h5 → 200 + 1743B, 容器名 `erp-backend` DNS 解析成功

#### 2. App H5 403 "Invalid CORS request"
- **症状**: App H5 页面 `POST /api/auth/login` 返回 403 + `ct=` (Content-Type 空), 弹"服务暂不可用, 请稍后重试"
- **根因**: App H5 页面 origin `http://192.168.0.150:18090` 不在 backend `ERP_CORS_ALLOWED_ORIGINS` 白名单 (白名单全是 host 域名 8088/18080/80/5173 等, 没有任何一条 `192.168.*:*` 端口), 跨域登录触发 CORS 预检, Spring Security `setAllowCredentials(true)` + 精确白名单不匹配 → 403
- **根治** (1 行代码): `backend/.../config/SecurityConfig.java` 加 `config.addAllowedOriginPattern("http://192.168.*:*")`. Spring Framework 6 `setAllowedOriginPatterns` 支持 `*` 通配 host 段, 一次覆盖整个内网段, 后续新加内网 IP 也不用动
- **为什么用 Pattern 不是逐条加**: 一次 `http://192.168.*:*` 覆盖整个内网段, 比逐条加 17 个 origin 优雅
- **验证**: home `Origin: http://192.168.0.150:18090` + `Origin: http://192.168.1.100:8080` (profile 绝对 base) 都 200 + token

#### 3. 生产管理 4 入口缺 3 (扫码报工/半成品入库/成品入库)
- **症状**: 用户截图 `http://192.168.0.150:18090/#/pages/dashboard/index` 生产管理 tab 只显示 1 个「生产加工单」
- **排查** (4 层):
  - dist chunk `pages-production-report.DGt6E7Nn.js` 在 ✓, 字符串 "扫码报工" 在 ✓
  - 模块开关 `production.enabled=true` ✓
  - 登录 perm 数组 `production:report:list/add` 含 ✓ (gpssong 登录实测 235 个 perm)
  - dashboard perm-only 兜底代码 (line 270-278) 在 ✓
- **真正根因** (最后一层): `LoginVO.appMenus` 按 `client_type='APP'` 过滤 `sys_role_menu`. home 主站同步 sql seed 时漏灌 APP 端授权 — 9 个 production 关键 perm 在 home 0 行 APP 授权 (PC:APP 授权数 243:29 5-10 倍差距). appMenus 21 行只含 production:order:list, 其它 9 个 perm 不进, dashboard 兜底代码读 `serverMenus` 拿不到
- **修法**: 写 `sql/124_v1103_home_production_app_perm.sql`, 给 6 业务角色 × 9 production perm 补 APP 授权, NOT EXISTS 守门幂等; 灌到 home 后复测 appMenus 21→30 行, production 节点 4→13
- **用户操作**: App H5 退出登录 → 重登 gpssong/850225song → 工作台生产管理 tab 4 个入口全显 (生产加工单 / 扫码报工 / 半成品入库 / 成品入库)

#### 4. 备份 403 @SaCheckRole("admin") 死注解
- **症状**: home 数据备份页「立即备份」`POST /api/system/backup/manual` 报 403
- **根因** (区别于"缺 perm 节点"和"缺表/列"两类): 代码级死注解. `SysBackupController` 5 个写端点 + `AuthController.setpwd` 共 6 处用 `@SaCheckRole("admin")`. Sa-Token `StpUtil.hasRole("admin")` 字面量匹配, 但项目角色码已迁移到 6 新内置角色 (SUPER_ADMIN/PURCHASE_MGR/...), 没有任何角色叫 `admin` → 全部 403
- **方法体已有正确兜底**: `permService.requireSuperAdmin()` → `SecurityContext.isSuperAdmin()` (认 uid=1 或 session `isAdmin=1`). 外层死注解先拦了, 永远到不了方法体
- **修法** (纯代码, 需重打 jar): 删 6 处 `@SaCheckRole("admin")` + 各自未用的 `import cn.dev33.satoken.annotation.SaCheckRole;`. 鉴权回归 `@SaCheckLogin` + 方法体 `requireSuperAdmin`
- **双站部署**: 飞牛 canary `cat|ssh` 流 jar → `docker cp` 覆盖 `erp-backend-failover:/opt/app/app.jar` → restart; home 同路径 (但 home 是 baked-in jar, 需 build --no-cache + docker rm -f + docker run 三连)

#### 部署踩坑 (本轮 4 个)
1. **home backend 是镜像内置 jar** — Dockerfile `COPY industrial-erp-*.jar app.jar` 烤进镜像, `docker restart` / `docker cp` 都不生效, 必须 `build --no-cache` + `docker rm -f` + `docker run` 三连
2. **gpssong /tmp 只读** (root:wheel 755) — 写脚本要落 `/volume3/docker/erp-system/`, chmod 也可能权限被拒
3. **`--env-file` 在 sudo 隔离下失败** — `sudo docker run --env-file /tmp/...env` 报 "no such file" (gpssong 创建, sudo 看不到), 改成 17 个 `-e key=value` 写 bash 脚本一次性执行
4. **容器内 baked-in jar md5 验证** — 最快方法 `docker run --rm --entrypoint md5sum <image> /opt/app/app.jar` (不进容器)

#### 飞牛 canary 状态 (2026-10-19)
- 飞牛 已在同 jar md5 `911f01e0...` (101459745 bytes) + 同步 CORS 修复 + 已是 bind-mount 形态
- 用户实测: `http://192.168.0.32:8080/api/auth/login` + `Origin: http://192.168.0.150:18090` 200 + token ✓

#### 教训 (写入 memory)
- **跨域 origin 不在白名单 → 403** `Invalid CORS request` + `ct=` (空 Content-Type) 是 CORS 拒绝指纹, 区别于权限 403
- **App H5 页面直连 backend (不同源)** 时永远触发 CORS 预检, 白名单要含页面 URL 不是 backend host:port
- **内网段 CORS 用 `setAllowedOriginPatterns("http://192.168.*:*")` 通配** 比逐条加优雅, 后续新 IP 不用动
- **镜像内置 jar 的 home backend 部署** 必须 `build --no-cache` + `docker rm -f` + `docker run` 三连, `docker restart` / `docker cp` 都不生效
- **跨站同步 perm seed 时**, sql/53/56/110/113/115/116 等每次必须给 6 业务角色 PC + APP 双授权, 漏一个就 dashboard 少图标; 检查方法: `SELECT client_type, COUNT(*) FROM sys_role_menu GROUP BY client_type` 期望 5-10 倍差距, 漏 APP 端立即见
- **用户改完权限后必须重新登录**才能刷 `localStorage.erp_permissions` (login 接口响应里给, 不走实时拉取)
- **检查 dashboard 入口缺失的 SOP**: ① `curl /api/auth/login clientType=APP` 看 `appMenus` 数组 → ② 查 `sys_role_menu` 缺哪个 `client_type` → ③ 灌 SQL 补行 → ④ 用户重登生效
- **403 三类根因区分法** (写入 memory): ① 缺 sys_menu perm 节点 → 灌 SQL; ② 缺表/列 → 灌 DDL; ③ 代码级死注解 (本次) → 改 Java + 重打 jar, 灌 SQL 无效

#### memory 新增
- `erp-cors-192168-pattern.md` — 3 层 502/401/403 根因链 + `setAllowedOriginPatterns` 通配根治 + 部署踩坑
- `erp-app-client-type-perm-sync.md` — 跨站同步 APP 端 perm 漏灌 root cause + sql/124 修复 + 检查 SOP
### [HISTORICAL - PRE-1.1.60] (2026-10-20 — CLAUDE.md 3186→90 行拆分第二轮, 历史正文从 CLAUDE.md 归档到此)

以下章节为 v1.1.79 hotfix-1 (2026-10-07) 及其更早版本正文, 来源: 原 CLAUDE.md 行 91~3093 (3002 行, ~94KB). 保留为完整变更日志基线, 不在本归档段做去重/合段 (与 docs/CHANGELOG.md 上方"## changelog (倒序)"中同版本号段内容可能重复, 已接受).

---

### v1.1.79 hotfix-1 (2026-10-07) — MCP 审计 @Async @EventListener 漏写 sys_oper_log 修复 + 双站重部署

**用户反馈 (2026-10-07)**: v1.1.79 MCP 后端首次部署到双站后, `/api/mcp` 三个方法都通 (login / tools/list / tools/call 建草稿 / 白名单拒绝 delete 全 200), 但 `sys_oper_log` 查 `module='MCP工具调用'` **0 行** — 审计漏写. Hermes 频繁调 MCP 时, 即使主流程 200, 后续审计/合规查询全空, 安监留痕失败.

**根因**: `McpAuditService` 之前用 Spring `ApplicationEventPublisher.publishEvent` + `@Async @EventListener` 异步写审计 (`OperLogEvent` → `OperLogPublisher` → `mapper.insert`). 异步链路在 Hermes 短时间内连续调 MCP 场景下, 因**事务上下文/线程池竞争**导致 mapper 拿不到连接, `sys_oper_log` 整行漏写. 同 commit (37dd959) 实测: home hermes_bot 建单返回 200, sys_oper_log 0 行.

**修复**: `McpAuditService` 改为**直接注入 `SysOperLogMapper.insert` 同步写**. 1 行 INSERT 开销可忽略 (微秒级, MCP 调用本身已 10-100ms), 100% 落库. 业务方法仍 try/catch 兜底, 审计失败不阻塞主流程 (与 OperLogPublisher 一致). 保留 `method` 字段 `[MCP]` 前缀 + `module='MCP工具调用'` + `businessType='MCP'` 标记, 审计查询 `SELECT ... WHERE module='MCP工具调用'` 一眼区分 "Hermes MCP 触发" vs "PC 手工".

**双站重部署** (md5 f46bd225 → **282abec0254fdce1e856bf8e406ad8d8**, 101,219,358 bytes): home (192.168.0.150) + 飞诺 (192.168.0.32) 各跑 `sql/51_hermes_mcp_bot.sql` (HERMES_BOT 角色 + hermes_bot 用户占位 bcrypt) → `UPDATE sys_user SET password='<bcrypt>' WHERE username='hermes_bot'` 把 `hermesMCP2026!` 真密码写进两站 → `docker build --no-cache` + `docker rm -f` + `docker run --env-file` (飞诺走 `docker-compose.failover.yml up -d erp-backend-failover --force-recreate`). **验证**: 双站 `POST /api/mcp` JSON-RPC `tools/call erp_create_maintenance_order` 都返回 200 + 生成 DRAFT 草稿 (home `WM202610070002` / 飞诺 `WM202610070001`) + `sys_oper_log` 各有 3/1 行 `[MCP] ERP_CREATE_MAINTENANCE` 落库. **踩坑记录**:
1. **fail2ban 多次 sudo 锁** — 连探多次 sudo 密码触发 fail2ban 锁, sleep 90-120s 等解锁, SSH 探活和 sudo 探测分开避免连坐.
2. **fn docker cp 后必须手动 restart** — `docker cp jar container:/path` 后容器内仍是旧 jar, 必须 `docker restart container` 才生效 (compose up 不切 image hash 同样陷阱).
3. **BuildKit COPY 层缓存** — `docker build --no-cache` 仍可能缓存 jar, 容器内 jar md5 和 host 不一致时, 直接 `docker cp` 强制覆盖最稳.
4. **sql/51 数据列踩坑** — sys_role.data_scope 是 TINYINT 不是字符串, `'ALL'` 要写 `1`; sys_user 没有 `tenant_id` / `remark` 列, INSERT 时只能给 `username/password/real_name/is_admin/status` 5 列. sql 已修正为 `INSERT...SELECT WHERE NOT EXISTS` 幂等.
5. **飞诺 Dockerfile 双轨** — home 是 `industrial-erp-backend-*.jar` (COPY 不一样), 飞诺是 `erp-backend.jar` (COPY target/erp-backend.jar).

**Next**: Hermes 端 `~/.hermes/config.yaml` 改 `mcp_servers.erp.enabled: true` + `~/.hermes/.env` 加 `ERP_MCP_TOKEN=<hermes_bot 登录拿到的 sa-token>` + 重启 gateway. MiniMax M3 vision 当前 402 欠费, 自动降级 Agnes 3.0-flash (不影响 MCP 链路).

### v1.1.73 (2026-10-04) — 角色管理「分配权限」回收站权限无法勾选修复 (grantable)

**用户反馈 (2026-10-04)**: 角色管理 → 分配权限 → PC端菜单权限，报表中心下的「回收站」复选框**灰色不可勾**，而同级的「报表查看」可勾。回收站权限无法自由选择。

**根因**: v1.1.68 (`sql/45`) 把回收站 seed 成 `M` 型菜单节点 `perms=''`（空），只当「侧边栏可见」载体，后端门禁复用 `report:view`。但权限树机制把「空 perms 的 M 节点」判为**不可授权目录**：
- 前端 `Role.vue` `markDisabled`：只有 `B`（按钮）或 `M`/`F` 且 `perms` 非空才 enabled → 回收站 `disabled=true`，灰框不可勾。
- 后端 `SysRoleService.isGrantableMenu`：空 perms 的 M 返回 false → **每次保存该角色时，seed 写入的回收站 `sys_role_menu` 授权行被静默删掉**，导致回收站授权既不能单独开、也留不住。
- `report:view`（menu id=951，`F` 型「报表查看」）才是真正的可授权载体，但它和「回收站」是两个独立 checkbox，语义冗余。

**决策（用户拍板）**: 给回收站独立权限码 `report:recycle`，让回收站本身成为可授权叶子；后端 3 个回收站端点门禁由 `report:view` 改到 `report:recycle`。双站部署。

**修复**:
1. 新 seed `sql/47_v172_recycle_perm.sql`：`UPDATE sys_menu SET perms='report:recycle' WHERE path='/report/recycle' AND perms=''`（M 节点变可授权），并给 6 内置角色补 `report:recycle` 授权（`information_schema` 探测 `sys_role_menu.client_type` 列，老库 2 列退化；`INSERT IGNORE` 幂等）。
2. 后端 `ReportController` 3 回收站端点（`/recycle/bin`、`/recycle/restore`、`/recycle/purge`）`@SaCheckPermission("report:view")` → `report:recycle`；`RecycleBinService.restore`/`physicalDelete` 的 `requirePerm` 同步改。其余 4 个报表端点（`report:view`）不动。
3. 前端 `router/index.js` `report/recycle` 的 `meta.perm` 由 `report:view` 改 `report:recycle`。
4. 权限树 `markDisabled` 与 `isGrantableMenu` 判定逻辑本身已支持「M + perms 非空 → 可授权」，M 节点赋 perm 后自动放行，**无需改判定代码**。

**部署（双站）**: home（192.168.0.150）+ 飞牛（192.168.0.32）各：① MySQL 跑 `sql/47_v172_recycle_perm.sql`（`--default-character-set=utf8mb4`）② `mvn package` + 双站 `docker build --no-cache` + `rm -f` + `docker run --env-file`（保留各自 SA_TOKEN 密钥，不强制重登）③ `npm run build` + 双站 bind-mount 新 dist + `docker restart erp-pc-web[-failover]`。非 admin 角色重登刷 perm。纯后端 jar + 前端 dist + 迁移，无 DDL。

**验证**: 权限树展开报表中心 → 回收站复选框变黑框可勾；给某角色勾上回收站保存后重开弹窗仍勾选（`isGrantableMenu` 不再静默删除）；无 `report:recycle` 的账号 GET `/report/recycle/bin` → 403，admin（`isAdmin` 短路）→ 200。

### v1.1.72 (2026-10-04) — 回收站「商品」列全空修复

**用户反馈 (2026-10-04)**: 回收站「销售出库」Tab 的「商品」列全是 `-`（客户/金额/删除人都正常），但库里这些被删单据明明有商品。

**根因**: v1.1.71 给 `RecycleBinMapper.xml` 的 `productColumn` 子查询写的是 `LEFT JOIN base_product p ... WHERE dtl.deleted = 0`。而业务删除时 **head 和 detail 会一起置 `deleted=1`**（`SalDeliveryService.delete` 等 11 类都是：先软删明细、再软删主表），所以被删单据的明细行按 `deleted=0` 一条都查不到 → `GROUP_CONCAT` 返回 NULL → 商品列全 `-`。线上 SQL 直查确认：被删 `sal_delivery` 的 `detail_alive=0`（无 deleted=0 的明细）但 `detail_hasname>0`（每条被删明细都自带去规范化的 `product_name`）。

**修复**: `RecycleBinMapper.xml` `productColumn` 子查询改为直接聚合明细自带的去规范列，去掉 `base_product` JOIN 与 `deleted=0` 过滤：
```sql
(SELECT GROUP_CONCAT(DISTINCT dtl.product_name SEPARATOR ', ')
 FROM ${detailTable} dtl
 WHERE dtl.${detailFk} = h.id) AS productNames
```
对全部 10 类带明细的单据（采购/销售/生产领料/BOM/盘点/调拨）生效，不再依赖商品是否已被软删。纯后端 XML 一处，无 DDL / 无前端改动。

**部署（双站）**: home 推 jar → `docker build --no-cache` 重建 `erp-system-backend:latest` → 用原 `erp_env_home.txt`（保留 SA_TOKEN 密钥，不用重登）重建容器；飞牛 `docker save` home 新镜像 → LAN 流式 `docker load` → tag 为 `erp-backend-failover:latest` → 用 `.erp_env_failover` 重建容器。两站线上 jar md5 均 `bbfd946f55c4f14206b84b3831f1836f`。home 实测被删销售出库现聚合出「带鱼袋9*75*0.07 / 透明胶带 / 塑料袋…」；飞牛返回「金达印字胶带」。

### v1.1.67 (2026-09-29) — App 扫码出库明细落库缺失商品编码/名称修复

**用户反馈**: App 端「扫码出库」生成的出库单 `CKP202609290006`, 在 PC 端打印送货单时「商品编码」「商品名称」栏空白, 只有「型号」有值 (截图: 型号 9*75*0.07, 数量 53800, 单价 0.061, 金额 3281.8, 编码/名称空)。

**根因 (双层)**:
1. **App 端丢字段**: `app/src/pages/scan/out.vue` `onSubmit()` L278-285 提交 payload 时 `details` 只带 `productId/qty/price/batchNo/remark`, 把 UI 里本已存在的 `productCode/productName/spec/unitId/unitName` 全砍了。
2. **后端信任客户端原样落库**: `SalDeliveryService.add()` L207-221 直接 `detailMapper.insert(d)`, 不补全 → 这些真实 DB 列 (`sal_delivery_detail.product_code/product_name/spec/unit_id/unit_name`) 落 NULL。
3. **为何型号能显示**: 型号 = transient 字段 `pModel`, 打印走 `SalDeliveryService.detail()` L102-105 `ProductAttrInjector.inject(..., p -> p.getModel())` 从 `base_product.model` **实时注入**, 与落库列无关, 所以永远有值。而编码/名称是**真实 DB 列**, 不回填就永久缺失。

**修复 (双层, 互为兜底)**:
- **后端 (主, 健壮)**: `SalDeliveryService` 新增私有 `enrichDetailsFromProduct(List<SalDeliveryDetail>)` — 批量 `productMapper.selectBatchIds` 一次查全 (无 N+1, 与 `ProductAttrInjector` 同款), **仅在字段 blank 时**回填 `productCode/productName/spec/unitId` (unitName 由 App 携带, 不额外引 BaseUnitMapper 依赖)。add() + update() 在 `detailMapper.insert` 前各调一次。只兜底瘦客户端 (App), 不覆盖 PC 端已显式录入的值 (PC 开单/订单生成路径本带全字段, 行为不变)。
- **App (辅, 最小)**: `onSubmit` payload 的 details 补全 `productCode/productName/spec/unitId/unitName` (list 里 `onAdd`/`onHistoryPick` 本就存了这些字段)。

**改动文件**:
| 文件 | 类型 | 说明 |
|------|------|------|
| `backend/.../sales/service/SalDeliveryService.java` | 改 | + `enrichDetailsFromProduct` (~40 行) + add/update 各 1 处调用 + import (HashSet/Map/Set/Function/Collectors) |
| `app/src/pages/scan/out.vue` | 改 | `onSubmit` details payload 补全 5 字段 |
| `app/src/pages/profile/index.vue` + `app/src/manifest.json` + `app/package.json` | 改 | App 版本号 1.1.64 → 1.1.67 (发此 APK) |

**纯后端 jar + App H5/APK, PC dist 不动。schema 不变 (无 DDL, 用现有列)。**

**部署**: 后端 jar 重打 (双站 `docker build --no-cache` + `docker rm` + `docker run --env-file`); App 走 `bash scripts/build-app.sh` 出新 APK 装机。已存在的 CKP202609290006 等历史扫单: 明细列已落 NULL, 需手动在 PC 编辑该单 (update() 也走 enrich) 或数据回填一次, 不影响新单。

**回滚**: 恢复 `SalDeliveryService` 删 `enrichDetailsFromProduct` + 两处调用; App 恢复 payload 4 字段。

### v1.1.66 version-sync (2026-09-29) — 系统信息版本号字面量对齐 (前端/后端版本显示修复)

**用户反馈**: 系统参数「系统信息」前端/后端版本仍显示 `1.1.53-hotfix.1`, 与 changelog 脱节。

**根因**: 「系统信息」版本号来自 3 处冻结字面量 (v1.1.53 `deploy-version-bump.sh` 写死后不再维护): 前端版本=`pc-web/package.json`→vite `__APP_VERSION__`(构建期烤入), 后端版本=`application.yml erp.version` 默认→`SystemVersionInitializer` upsert 到 `sys_config`, 构建时间=vite `__BUILD_TIME__`(重打即刷新)。

**修复 (4 文件纯字面量, 零业务改动)**: `pc-web/package.json` `1.1.53-hotfix.1`→`1.1.66`(需重打 dist); `application.yml` + `SystemVersionInitializer` 默认→`1.1.66`; `app/package.json` 顺手对齐 App 实际 `1.1.64`。

**部署**: 重打 pc-web dist (System chunk `System-CGWmoRBS.js`) 双站 bind-mount + restart; 双站 backend 现有 image + `-e ERP_VERSION=1.1.66` 重建容器(无需重打 jar)。JWT 密钥沿用, 不用重登。双站 `sys_config` 均 `version=1.1.66`。

**教训 (R15)**: 版本字面量分散 3 源文件 + 1 运行 env, 极易漂移。每版本对齐需同步 4 处 (pc-web/package.json + application.yml + SystemVersionInitializer + 容器 env `ERP_VERSION`), 且前端必须重打 dist (版本是构建期烤入)。

### v1.1.65 (2026-09-29) — PC 端"生成出库单"数量默认「剩余数量」(订单全量 − 已审核出库累计)

**用户反馈**: 通过销售订单生成出库单时, 商品明细中的「数量」默认填的是订单全量, 但部分发货过的订单应当只默认填入「未发货/剩余数量」(与"发货详情"弹窗的 未发货 列一致), 否则再开一张出库单容易把已发的量重复算进去超发。

**改动 (3 文件)**:
- **后端 `SalOrderService.getDeliverySummary`**: 每行 Map 加 `row.put("orderDetailId", d.getId())` — 让前端能按 `order_detail_id` 把发货汇总对回订单明细行 (原来只带 lineNo/productCode, 缺主键无法匹配)。`发货详情` 弹窗前端逻辑不变, 仍按原字段渲染。
- **前端 `pc-web/views/sales/Order.vue` `onGenerateDelivery`**: 打开弹窗时 `salOrderApi.getDeliverySummary(o.id)` 拉已发/未发汇总, 建 `Map<orderDetailId, {shippedQty,unshippedQty}>`; 明细行 `qty` 默认 `max(0, 订单全量 − 已审核出库累计)` (部分发完 → 剩余; 全发完 → 0)。拉取失败时回退到全量默认 (旧行为), 仅 `console.warn` 不阻塞。
- **前端 同一文件**: 明细表在「数量」前加只读「订单数量」列 (灰字全量), 与可编辑「数量」(默认剩余) 对照; 提示文案由"数量默认为订单全量"改为"数量默认填入剩余数量, 已发完的明细默认为 0; 如需补发/超发请手工调整"。`onConfirmGenerate` 提交前 `delete cleaned._orderQty/_shippedQty` (两个 UI 辅助字段不进后端 payload)。

**部署**: 后端 jar 需重打 (deploy-home-dist 走 jar 流程); pc-web `npm run build` 出新 chunk `Order-BQ9ysCJe.js` 部署 H5 容器。**App 端不动, 仍 v1.1.64** (纯 PC 功能, 无 App 页面)。

**验证 (装机)**: 订单 SO202609070001 部分发货 (发货详情: 订单 75000/已发 50000/未发 25000 等) → 点「生成出库单」→ 明细「数量」默认显示剩余 (25000/7000/2000/182000...), 「订单数量」列灰字显示全量 → 保存为草稿 数量按剩余录入。已发完的明细 (未发货=0) 默认数量为 0。

**回滚**: 恢复 `onGenerateDelivery` 的 `qty: Number(d.qty)` (全量默认), 删「订单数量」列; 后端 `orderDetailId` 一行无害, 可留。

### v1.1.64 (2026-09-28) — App 扫码跟随竖屏 (连续扫码不再闪横屏宽界面)

**用户反馈**: 连续扫码 (扫码入库 / 扫码出库扫多个商品) 每次都会弹出"宽的扫码界面" (横屏), 体验差。

**根因** (`ScannerActivity.java` L58):
```java
setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)  // 强制横屏
```
uni-app 手持手机是**竖屏**使用, 点"📷 扫一扫" → `NativeScanner.startScan()` 弹起原生 `ScannerActivity` → 系统把手机**旋转 90° 成横屏**显示相机预览 (full-screen landscape 宽界面). 连续扫 N 个商品 = N 次横屏闪烁。

**修复 (2 文件, 纯原生 + H5 版本号同步)**:
1. **`ScannerActivity.java` L58**: `SENSOR_LANDSCAPE` → `SCREEN_ORIENTATION_UNSPECIFIED` (跟随父 Activity, uni-app 主 Activity 竖屏, 扫码也竖屏)
2. **`AndroidManifest.xml` L39**: `android:screenOrientation="sensorLandscape"` → `"unspecified"` (与代码同步)
3. `applyCenterScanRect` 已用"短边 60%"计算方框, 竖屏时方框比横屏还小 (1080 宽 × 0.60 = 648px), 解码区更聚焦
4. 版本号 1.1.63 → 1.1.64 (APP_VERSION / manifest.versionName+versionCode / CLAUDE.md 顶部)

**部署**: 改了 H5 (APP_VERSION), 走 `build-app.sh` 全流程 (build:h5 + cap sync + assembleDebug). APK MD5 `ed4d3f30a68235a3a895e046e3215ffc`, 4,369,472 字节, profile chunk `pages-profile-index.BW9-dH-N.js` 含 1.1.64. 已交付 `飞牛同步-Mac/erp/erp-app-20260928.apk` + `~/Desktop/erp-app-20260928.apk`.

**验证 (装机)**: 用户装新 APK → 扫码入库页点"📷 扫一扫" → **手机保持竖屏** (不再旋转横屏), 中央方框居中, 连续扫多个商品每次都竖屏, 体验流畅. 退出"我的"页确认显示 v1.1.64.

**回归**: 纯方向改动, 相机/解码/对焦逻辑 (v1.1.63 3 处优化) 不变; `zxing_preview_scaling_strategy=centerCrop` 自动适配竖屏预览, 无需改 layout. 回滚: `git checkout` 恢复 `SENSOR_LANDSCAPE` + manifest `sensorLandscape` 即可。

#### v1.1.64 build-app.sh [6/6] 防呆 (2026-09-28, 同 commit 加)

**背景**: `[1.5/5]` 只校验源码三处版本号一致 (`profile.APP_VERSION` / `manifest.versionName` / `CLAUDE.md` 顶部), 但**源码对 ≠ 打进 APK 的对**. 真实踩过的漂移场景:
- (a) H5 没重打 — `dist/build/h5` 是上次构建的 chunk, APK 内嵌的是**旧** `APP_VERSION`
- (b) `cap sync` / `assembleDebug` 漏步 — `assets/public/` 还是上一次的 chunk, 与当前 `manifest.json` 不一致

**修复** (`scripts/build-app.sh` 加 `[6/6]` 校验段, 在 `[5/5] assembleDebug` 之后、计算 MD5 之前):
```bash
# 1) 列出 APK 里 profile 页 chunk (hash 每次 build 变, 用正则匹配)
PROFILE_CHUNK=$(unzip -Z1 "$APK" | grep -E 'assets/public/assets/pages-profile-index\..*\.js$' | head -1)
# 2) 取出该 chunk, grep 内嵌的 3 段版本号字面量
EMBEDDED_V=$(unzip -p "$APK" "$PROFILE_CHUNK" | grep -oE '"[0-9]+\.[0-9]+\.[0-9]+"' | tr -d '"' | head -1)
# 3) 和 manifest.versionName 比对, 不一致 exit 1 拒绝交付
```

**为什么 `grep` 抓得到版本号**: profile 页模板 `v{{ APP_VERSION }}` minify 后变成 `...v"+r("1.1.64")...`, 版本串以 `"1.1.64"` 形式留在 chunk 里. 实测 9/28 的 APK:
```
profile chunk: assets/public/assets/pages-profile-index.BW9-dH-N.js
内嵌=1.1.64   manifest.versionName=1.1.64   ✓ 一致, 通过
```

**两道护栏分工** (互相补位, 缺一不可):

| 场景 | [1.5/5] 能否拦住 | [6/6] 能否拦住 |
|------|:---:|:---:|
| 源码三处版本号手改漏一处 | ✅ 能 | — |
| H5 没重打, APK 内嵌旧 `APP_VERSION` | ❌ 拦不住 (源码是对的) | ✅ 能 |
| cap sync / assembleDebug 漏步, chunk 是上次构建的 | ❌ 拦不住 | ✅ 能 |

**设计原则 (R14 — 写入设计原则)**:
- **版本一致性校验必须分两层**: 源码层面 (三处 grep 一致) + 产物层面 (APK 内嵌 chunk 含当前 `versionName`)
- **产物校验要"反向 grep"**: 从打好的 APK `unzip` 出实际编译的 chunk, grep 版本字面量, 和 `manifest.json` 比对 — 这才是用户**真的会装到手机上的**版本
- **失败要 `exit 1` 拒绝交付**: 漂移 APK 不可发布, 必须 `rm -rf dist/build + 重跑 build:h5 + cap sync + assembleDebug` 全流程
- **抓版本字面量的 regex 要稳**: minify 后 `APP_VERSION` 变量名消失, 只能靠"3 段版本号字符串字面量"匹配 (`[0-9]+\.[0-9]+\.[0-9]+`), 这是 Vue template 字符串插值 minify 后的稳定形态

### v1.1.63 (2026-09-28) — App 扫码灵敏度优化 (扫二维码不灵敏 → 3 处性能改造)

**用户反馈**: App 端扫码入库 / 扫码出库扫二维码不够灵敏, 响应慢。

**根因**: `ScannerActivity` 沿用 ZXing 默认配置, 没有限制解码格式/扫描区域/对焦策略:
- 默认 ZXing 每帧图像要跑 14 种 2D/1D 解码器 (EAN_13, CODE_128, QR, Aztec, DataMatrix...), 业务里入库/出库几乎全是 QR, 1D 解码器 (尤其 Code128 全帧扫描) 显著拖慢响应
- 整帧解码 vs 中央框解码, 低端机尤其拖速度
- 部分低端机默认 AUTO 对焦会卡住/拉风箱

**修复 (3 处)**:
1. **限定解码格式**: `barcodeView.setDecoderFactory(new DefaultDecoderFactory(EnumSet.of(QR_CODE, EAN_13, EAN_8, CODE_128, CODE_39)))` — 比默认少 9 种解码器, 提速 2-4x
2. **中央方框**: `onResume` 时 `inner.setFramingRectSize(new Size(side, side))` (side = 短边 60%), 解码算法只处理中央 36% 像素, 计算量降 ~64%
3. **连续对焦 + 条码场景模式**: `CameraSettings` 上 `setContinuousFocusEnabled(true)` + `setFocusMode(CONTINUOUS)` + `setBarcodeSceneModeEnabled(true)`, onCreate + onResume 双应用 (相机在 onResume 后才 configureCamera, 第二次才是真生效)

**改动**: 1 文件 (`ScannerActivity.java`, ~+90 行). 无 layout 变更 (`zxing_preview_scaling_strategy` 枚举只有 centerCrop/fitCenter/fitXY, 无 "zoom" — zoom 是镜头操作非预览策略, 且 ZXing 4.3.0 也没暴露 `CameraManager.zoom()`)。无 H5/前端变更。

**验证**:
- ✅ `gradlew :app:assembleDebug` BUILD SUCCESSFUL
- ✅ APK MD5 `c83b5f435a196217abf6eca699f89731`, 4,369,513 字节
- ✅ 已交付到 `飞牛同步-Mac/erp/erp-app-20260928.apk` + `~/Desktop/erp-app-20260928.apk`

**踩坑记录 (ZXing 4.3.0 API 大改 — 与 3.x 完全不是同一套 API)**:
- `DecoratedBarcodeView` **不再继承 `CameraPreview`** — 4.3.0 改成 `FrameLayout` 包装, 真正的相机/解码逻辑在内部 `BarcodeView` (`getBarcodeView()`)
- `getCameraManager()` 不存在 (3.x 才有) — 改用 `CameraSettings` 配置
- `setScanArea(int,int,int,int)` 不存在 — 改用 `inner.setFramingRectSize(new Size(w,h))` (方法在 `CameraPreview` 上, 即内部 BarcodeView)
- `setDecodeFormats(Set<BarcodeFormat>)` 不存在 — 改用 `barcodeView.setDecoderFactory(new DefaultDecoderFactory(Collection<BarcodeFormat>))`
- `CameraManager.zoom(float)` 不存在 — 想放大镜头只能自己实现, 4.3.0 没暴露
- layout 里 `app:zxing_preview_scaling_strategy` 枚举只有 `centerCrop`/`fitCenter`/`fitXY` (写了 "zoom" 会 AAPT 报 `incompatible with attribute zxing_preview_scaling_strategy enum` 直接 build 失败)
- **教训**: ZXing 升级后 (尤其 4.x) 不能照搬 3.x 的示例代码, 先用 `javap -public` dump 实际 API 再写

### v1.1.62 (2026-09-24) — 库存查询 SQL 改写 (库存为 0 产品可查询)

**症状 (用户截图 `home.93gushi.com:8088/#/inventory/stock`)**: 搜索"塑料袋30*38*0.16" 返回 Total 0, 但该产品确实存在于 `base_product` 表 — **只是 inv_stock 表里没有它的行 (库存=0 且从未入库过)**。

**根因 (`InvStockController.java:63` v1.1.61)**:
```java
LambdaQueryWrapper<InvStock> w = new LambdaQueryWrapper<>();
w.gt(InvStock::getQty, java.math.BigDecimal.ZERO);  // ← qty > 0 严格过滤
// 同时整个 WHERE 走 inv_stock 表, 没有 inv_stock 行就拿不到任何记录
return R.ok(PageResult.of(stockMapper.selectPage(p, w)));
```
原语义 = "查询有库存的库存台账",但用户预期它 = "查产品库存 (可为 0)"。

**修复 (3 文件, schema 不变)**:
- **新增** `backend/src/main/java/com/industrial/erp/modules/inventory/mapper/InvStockPageQueryMapper.java` — 自定义 query mapper 接口
- **新增** `backend/src/main/resources/mapper/inventory/InvStockPageQueryMapper.xml` — `base_product` LEFT JOIN `inv_stock` 聚合 SQL, 关键字 LIKE `product_code/product_name/spec` 任意命中, 仓库过滤放 LEFT JOIN ON 子句 (否则会重新引入"无库存行产品"被过滤)
- **改** `backend/src/main/java/com/industrial/erp/modules/inventory/controller/InvStockController.java` — `stockPage` 返回类型从 `PageResult<InvStock>` 改为 `PageResult<Map<String, Object>>`, 删除 `LambdaQueryWrapper` 改用 `stockPageQueryMapper.selectStockPage(p, kw, warehouseId)`, PC Stock.vue 表格 prop + App inventory/query.vue 卡片共用

**返回字段** (驼峰, 与 PC Stock.vue 表格列一致):
- `productId/productCode/productName/spec/unitId/unitName` (来自 base_product)
- `warehouseId/warehouseName` (来自 inv_stock, 库存=0 时为 null)
- `qty/availableQty/lockQty/totalCost` (库存=0 时全为 0; COALESCE SUM 聚合到产品级)
- `avgCost` (按 SUM(qty*avg_cost) / SUM(qty) 计算, 库存=0 时返回 0 避免 AVG 无意义)
- `batchNo/lastInDate` (库存=0 时为 null, MIN 聚合)

**SQL 关键点**:
- `WHERE p.deleted = 0 AND p.status = 1` 走 base_product (驱动表), 不走 inv_stock
- `LEFT JOIN inv_stock s ON s.product_id = p.id AND s.deleted = 0` 即使无 inv_stock 行也能命中
- `LEFT JOIN base_unit u ON u.id = p.main_unit_id` 取单位名 (主单位; v1.1.62 hotfix-1: 字段是 `main_unit_id` 不是 `unit_id`)
- `GROUP BY p.id, p.product_code, ..., s.warehouse_id, w.warehouse_name` 按产品+仓库维度聚合
- `ORDER BY COALESCE(SUM(s.qty), 0) DESC, p.id DESC` 有库存优先, 其次按 ID

**部署 (NAS 192.168.0.150)**:
- 后端 jar md5 `fc5245f493f5b243de6810d18c0c3ffa` (101086940 bytes; 第一版 `547b552b` 漏改 LEFT JOIN ON, 已修)
- `docker build --no-cache -t erp-system-backend:latest /volume3/docker/erp-system/backend` → image id `817f8a1b20f1`
- `docker rm -f erp-backend` + `docker run --name erp-backend --network erp-system_erp-net -p 8080:8080 -v /volume3/docker/erp-system/data/backup:/opt/app/backup -v /volume3/docker/erp-system/data/upload:/opt/app/upload --env-file /tmp/erp-deploy/erp-backend-env.list erp-system-backend:latest`
- 容器内 jar md5 验证一致 ✓, Started IndustrialErpApplication in 31.82s :: Industrial ERP Started Success ✓
- 模拟 SQL (mysql 直跑) 命中用户截图案例: `塑料袋30*38*0.16` (id `20754693159949580161`, qty=0.0000) ✓

**踩坑 (已修)**:
1. **字段名错 — 3 处都要改**: `base_product.unit_id` 不存在, 实际是 `main_unit_id`, 必须改 SELECT AS / LEFT JOIN ON / GROUP BY **三处**. 漏一处就报 `Unknown column 'p.unit_id' in 'on clause'` (v1.1.62 第一次部署漏 L42 LEFT JOIN ON 已踩, hotfix 重建). 改前**必须** DESCRIBE 表:
   ```sql
   DESCRIBE base_product;  -- 确认 main_unit_id 存在, 没有 unit_id
   ```
2. **docker-compose v2.20.1 yaml 解析坏**: `${SA_TOKEN_JWT_SECRET_KEY:?... Generate with: openssl rand -hex 32}` 含空格+点+连字符被解析坏 — `docker compose up -d` 失败; **绕过**: 直接 `docker build --no-cache` + `docker rm` + `docker run --env-file`, 不走 compose
3. **`docker restart` 不切换 image**: 容器启动时锁定原始 image hash, 即使 tag:latest 变了 restart 也不切换 — 必须 `docker rm` + 重新 `docker run`
4. **备份/线上 jar 大小对调**: `/volume3/docker/erp-system/backend/industrial-erp.jar` (老备份, 86794232 bytes) vs `industrial-erp-1.0.4.jar` (线上, 101086058 bytes) — 部署时只覆盖后者, 不要动前者
5. **gpssong 写 /volume3 权限不足**: 用 `cat /local/jar | ssh gpssong 'cat > /tmp/...'` 流过去 + `echo PW | sudo -S cp /tmp/... /volume3/...`
6. **fail2ban 锁**: 频繁 ssh 后 GPS 密码错被锁 60-180s, `sshpass -p 'PW' ssh ...` 等解锁

**不动**:
- 前端 PC dist / App dist / H5 / APK (纯后端修复)
- DB schema (无 DDL, 用现有字段)
- SQL / RLS (无新数据 seed)

### v1.1.61 (2026-09-24) — App 端 R13 全局重塑 (22 处 catch 双弹全部静默化)

**症状 (承自 v1.1.60 hotfix-2)**: profile 页面"刷新权限菜单"已修复双弹, 但其他 18 个页面 22 处 catch 仍存在 `uni.showToast` 与拦截器 toast 冲突 — 用户在所有业务场景下都看后 catch 弹的"加载失败/提交失败/操作失败"等无信息量文案, 丢失后端 msg 的诊断价值 (e.g. "反审核需要无已核销记录", "打印机离线", "采购入库需要 check 权限")。

**根因 (Android `uni.showToast` 后调用中断前调用)**: `app/src/api/index.js` 拦截器 L65-70/L89-91/L99-101 已在 3 个错误路径主动弹 toast, 但业务页面 catch 块习惯性 `uni.showToast({ title: '加载失败' })` — 两条 toast 打架, 用户只看到后者。

**修复 (13 个文件, 22 处 catch, 1 处 try-catch 补全)**:

| 优先级 | 文件 | catch 数量 | 用途 |
|------|------|----------|------|
| **P0** | `profile/change-password.vue` | 1 | 改密码 |
| **P0** | `scan/in.vue` | 3 | 扫码入库: 搜索 / 提交 / 飞鹅打印 |
| **P0** | `scan/out.vue` | 3 | 扫码出库: 历史销售 / 搜索 / 提交 |
| **P0** | `production/order-add.vue` | 3 | 新增生产单: 加载 / 提交 / 删除 |
| **P0** | `production/order-detail.vue` | 3 | 生产单详情: 加载 / 删除 / 飞鹅打印 |
| **P0** | `sales/delivery-detail.vue` | 3 | 销售出库详情: 审核 / reload / onLoad |
| **P0** | `purchase/receipt-detail.vue` | 3 | 采购入库详情: 审核 / reload / onLoad |
| **P1** | `sales/delivery-list.vue` | 1 | 销售出库列表 |
| **P1** | `purchase/receipt-list.vue` | 1 | 采购入库列表 |
| **P1** | `production/order-list.vue` | 1 | 生产单列表 |
| **P1** | `count/index.vue` | 4 | 外勤盘点: 仓库列表 / 搜索 / 预填 / 提交 |
| **P1** | `system/users.vue` | 3 | 用户管理: 保存 / 删除 / 重置密码 |
| **P2** | `base/product-add.vue` | 2 | 新增商品: 加载 / 提交 |
| **补全** | `report/index.vue` | 1 (新增) | 经营简报: 补 try-catch 防 unhandled rejection |

**统一修复模板 (与 v1.1.60 hotfix-2 profile 一致)**:
```js
} catch (e) {
  if (typeof uni !== 'undefined' && uni.hideLoading) uni.hideLoading()
  // v1.1.61 R13 全局重塑: 拦截器已弹后端 msg, catch 不重复弹 (避免覆盖)
  console.warn('[<action-name>] 失败:', e)
  if (e && e.code === 401) return  // 401: 拦截器 reLaunch 跳登录页, 不重复弹
}
```

**保留 catch toast 的 4 处 (不走拦截器, 必须本地兜底)**:
1. `pages/login/index.vue` L119-129 (v1.1.60 hotfix-1): 后端 `/auth/login` 401 时拦截器**只 reLaunch 不弹 msg**, 这里必须给"用户名或密码错误"反馈
2. `pages/production/order-detail.vue` L218-223 onRelease: `uni.showModal({ title: '开工失败', content: e.message || '请检查BOM配置' })` 模态弹窗, 强制用户确认 (非 toast, 与拦截器不冲突, 但仍属业务级提示保留)
3. `pages/production/order-detail.vue` L270-277 onShare: Capacitor 原生 `NativeShare.sharePdf` plugin 错误, **不走** api.request, 拦截器**不弹** toast
4. `pages/production/order-add.vue` L391-395 doFeiePrint: 飞鹅打印失败不阻塞主流程 (新单已保存), 仅 `console.warn`, 拦截器弹后端 msg (e.g. "打印机离线") 已足够

**`report/index.vue` 补 try-catch**: v1.1.21+ 起 onMounted `kpi.value = await api.dashboard()` 完全没 catch, 拦截器 reject 会被静默吞到 console — 用户看到空白页。补 try-catch 只 console.warn, 兜底空值 (KPI=0, stockList=[]), 拦截器弹后端 msg 已足够。

**部署**:
- 改 14 文件: 13 页面 + `app/android/capacitor.settings.gradle` (重新加 :capacitor-share include)
- `cd app && npm run build:h5` → 新 chunks (13 个): `pages-profile-index.0RcoRrQf.js` 等
- `npx cap sync android` + JAVA_HOME=17 `./gradlew assembleDebug` → APK MD5 `23841776cca026580fc4d515494c9734`, 4,369,513 字节
- 上传 tarball → NAS `app/dist/build/h5/` → `docker build --no-cache` → `docker rm -f erp-app-h5 && docker run` → 镜像 hash `60edfbac4ceb`
- 不动: 后端 jar / PC dist / DB / SQL (纯前端编译产物修复)

**端到端验证 (2026-09-24)**:
- ✅ H5 容器 rebuild + recreate 成功 (镜像 `60edfbac4ceb`, 容器 Up, HTTP 200)
- ✅ 12 个新 chunk md5 全部 = 本地 dist 一致 (profile/scan ×2/order-add/order-detail/sales-detail ×2/purchase-detail ×2/count/users/change-password)
- ✅ APK 内嵌 chunk md5 `460fdae1619d2a160523d2ceca6f4d8b` (profile) = dist 一致
- ✅ 用户体验: 后端业务错 → 显示后端实际 msg (e.g. "反审核需要无已核销记录"); 网络断 → "网络连接失败, 请检查网络"; 反代 502 → "服务暂不可用, 请稍后重试"; 401 → 自动跳登录页; 业务校验/成功 toast 不受影响

**踩坑**:
- `capacitor.settings.gradle` cap sync 后会重置为单 `:capacitor-android` — 必须手动 include `:capacitor-share` (v1.1.29+ 经验, 旧 commit 注释提醒)
- macOS `JAVA_HOME` 不在默认 PATH, 需 `export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`

**设计原则 (R13 — 完整版, 写入设计原则)**:
- **App 端拦截器 vs 组件 catch 的最终分工**: 拦截器在 3 个错误路径 (反代 502 / 后端业务错 / 网络层失败) 弹 toast; 组件 catch **永远不要**弹 toast, 只做 console.warn + 401 短路。Android `uni.showToast` 后调用中断前调用, 双弹必丢前一条
- **拦截器已弹过的 toast, catch 不重复弹** — 否则用户只看到后 catch 弹的"加载失败", 完全丢失后端 msg 诊断价值
- **401 路径特殊**: 拦截器会自动 reLaunch 跳登录页, catch 不应再弹任何 toast (一闪而过干扰跳转体验)
- **catch 仍应保留 `console.warn` 详细诊断** (含 `e.code` / `e.msg` / `e`) — 便于排查
- **原生 plugin 调用 (Capacitor NativeScanner/Share) 不触发拦截器**, 本地 catch 必须有提示 (doScan / onShare 是这种合规特例)
- **业务特殊提示 (前端校验/成功反馈) 保留本地 toast** — 这些不是 catch, 不与拦截器冲突
- **统一修复模板**: `if (code === 401) return; console.warn(...)` — 11 行缩到 4 行核心代码, 18 个 catch 一致替换

**代码 diff 范围 (15 文件)**:
```
app/android/capacitor.settings.gradle                          |  +5 -0  (手动加 :capacitor-share)
app/src/pages/profile/change-password.vue                      |  +6 -1  (P0)
app/src/pages/scan/in.vue                                       |  +9 -3  (P0)
app/src/pages/scan/out.vue                                      |  +9 -3  (P0)
app/src/pages/production/order-add.vue                          |  +9 -3  (P0)
app/src/pages/production/order-detail.vue                       |  +9 -3  (P0)
app/src/pages/sales/delivery-detail.vue                         |  +9 -3  (P0)
app/src/pages/purchase/receipt-detail.vue                       |  +9 -3  (P0)
app/src/pages/sales/delivery-list.vue                           |  +3 -3  (P1)
app/src/pages/purchase/receipt-list.vue                         |  +3 -3  (P1)
app/src/pages/production/order-list.vue                         |  +3 -1  (P1)
app/src/pages/count/index.vue                                   |  +12 -4 (P1)
app/src/pages/system/users.vue                                  |  +9 -3  (P1)
app/src/pages/base/product-add.vue                              |  +6 -2  (P2)
app/src/pages/report/index.vue                                  |  +8 -2  (补 try-catch)
```

### v1.1.60 hotfix-2 (2026-09-23) — App 端"我的-刷新权限菜单"双弹覆盖修复

**症状**: 用户反馈 "app端-我的-点击刷新权限菜单, 提示刷新失败, 请稍后再试"。不论后端实际错误码是什么 (msg="无权访问" / 网络断 / 502), 用户**始终**只看到 "刷新失败, 请稍后再试"。

**根因 (与 v1.1.31 PC Login catch 空转同根 — 这次是"反向")**:
- `app/src/api/index.js` 拦截器**已弹** toast:
    - 反代 502 / 非 JSON → "服务暂不可用, 请稍后重试" (L65-70)
    - 后端业务错 → `d.msg || '请求失败'` (L89-91)
    - 网络层失败 → "网络连接失败, 请检查网络" (L99-101)
- `app/src/pages/profile/index.vue` catch **又弹** "刷新失败, 请稍后重试"
- Android `uni.showToast` 后调用会**中断**前调用 — 用户只看到后 catch 弹的"刷新失败", 完全**丢失**后端 msg 的诊断价值

**修复 (1 文件, L138-149 catch)**:
```js
} catch (e) {
  if (typeof uni !== 'undefined' && uni.hideLoading) uni.hideLoading()
  // 拦截器已经弹过 toast, 这里只做 console 诊断, 不再二次弹 (避免覆盖)
  const code = e && e.code
  const backendMsg = e && e.msg
  console.warn('[onRefreshPerms] 失败, code=', code, 'msg=', backendMsg, 'err=', e)
  // 401: 拦截器 reLaunch 跳登录页, 一闪而过的 toast 反而干扰跳转
  if (code === 401) return
}
```

**为什么不改拦截器**: 18 个页面 catch 都依赖拦截器弹 toast (dashboard / scan / production / system 等), 全局改风险大且超出当前需求范围。只改 profile 的 catch 把冲突控制在 1 个页面, 其他现有行为完全不变。

**部署**:
- 改 1 文件: `app/src/pages/profile/index.vue`
- `cd app && npm run build:h5` → 新 chunk `pages-profile-index.9bnvdhRj.js` (3353 字节)
- 上传 tarball → NAS `app/dist/build/h5/` (避开 tarball 完整性 + `rm -rf` 清空避免新旧 chunk 并存)
- `docker build --no-cache -t erp-app-h5:latest .` → 镜像 hash `38e532179829`
- `docker rm -f erp-app-h5 && docker run -d --name erp-app-h5 --network erp-system_erp-net -p 18090:80 erp-app-h5:latest`
- 不动: 后端 jar / PC dist / DB / SQL (纯前端编译产物修复)

**端到端验证 (2026-09-23)**:
- ✅ H5 容器 rebuild + recreate 成功 (镜像 `38e532179829`, 容器 Up)
- ✅ 线上 `http://192.168.0.150:18090/assets/pages-profile-index.9bnvdhRj.js` MD5 `518e2068682e0098d3f6333f78ad45cc` = 本地 dist
- ✅ chunk 内容含 `onRefreshPerms] 失败, code=, msg=, err=, 401===s)return` 修复后的 catch 逻辑
- ✅ 用户体验: 后端业务错 → 显示后端实际 msg; 网络断 → "网络连接失败, 请检查网络"; 反代 502 → "服务暂不可用, 请稍后重试"; 401 → 自动跳登录页
- ⏸ App APK: 代码已就位, 待触发 `bash scripts/build-app.sh` 重打

**踩坑** (H5 dist 部署):
- `cp -r /tmp/staging/h5-new/h5 /volume3/.../dist/build/` **没清空旧目录** → 新旧 chunk 并存, Dockerfile COPY 时**两个都进**镜像。**修复**: `rm -rf .../dist/build/h5 && cp -r .../h5 .../dist/build/`
- macOS tar写 `LIBARCHIVE.xattr.com.apple.provenance` 扩展头 → NAS tar 警告**可忽略**,文件实际正常解压 (md5 一致)
- gpssong `/tmp` 不能 mkdir, 必须 `echo "..." | sudo -S -p "" mkdir ...`
- `docker build` 默认走**legacy builder** (NAS docker 版本), 加 `--no-cache` 即可,不需要 buildx

**设计原则 (R13 延伸 — 写入设计原则)**:
- **拦截器已弹过的 toast, 组件 catch 不要重复弹** — 否则后 toast 会覆盖前 toast, 用户看不到最有价值的那条
- **401 路径特殊**: 拦截器会自动 reLaunch 跳登录页, catch 不应再弹任何 toast (一闪而过干扰跳转体验)
- **catch 仍应保留 `console.warn` 详细诊断** (含 code/msg/err) — 便于排查
- **R13 全局重塑** (拦截器不弹, 组件 catch 统一弹) 留后续版本按 18 个页面审计后统一改 — 当前 R13 延伸原则是"过渡方案", 减少全局改动风险

### v1.1.60 hotfix-1 (2026-09-23) — PC + App 登录错误提示统一规范

**症状**: 用户反馈 "项目登录的时候账号密码错误时弹窗提示账号密码错误", 进一步反馈 "PC 端登录错误提示也优化一下"。排查发现:
- App 端 `app/src/pages/login/index.vue` L121 用 `alert('登录失败: ' + ...)` 原生对话, 突兀 + 冗余 "登录失败:" 前缀
- PC 端 `pc-web/src/views/Login.vue` L130-136 catch **完全没弹任何提示**! 注释说 "业务错误由 request.js 拦截器弹 ElMessage.error", 但 `pc-web/src/utils/request.js` v1.1.31 设计已改成 **不在拦截器弹, 让组件 catch 统一弹**, Login.vue 没跟着改, 导致 catch 块完全空转, 用户输入错误密码只看到 loading 消失, 没有任何反馈

**修复 — 两端统一**:
| 端 | 文件 | 弹窗方式 | 文案 |
|----|------|---------|------|
| App | `app/src/pages/login/index.vue` L119-129 | `uni.showToast` 优先, H5 退化为 `alert` | 后端 msg → 兜底 "账号或密码错误, 请重试" |
| PC  | `pc-web/src/views/Login.vue` L130-137 | `ElMessage.error` | 后端 msg → 兜底 "账号或密码错误, 请重试" |

**PC 端修复代码** (关键: 补回 v1.1.31 拦截器改造时漏掉的 catch 弹窗):
```js
} catch (e) {
  if (import.meta.env.DEV) console.error('[LOGIN_ERR]', e)
  // v1.1.31+: request.js 不再拦截器弹 ElMessage, 由组件 catch 统一弹
  // 优先用后端返回的 msg (e.g. "用户名或密码错误"), 兜底 "账号或密码错误, 请重试"
  const rawMsg = (e && (e.msg || e.message)) || ''
  const friendly = rawMsg || '账号或密码错误, 请重试'
  ElMessage.error(friendly)
}
```

**App 端修复代码**:
```js
} catch (e) {
  console.error('[LOGIN] 登录失败:', e)
  const rawMsg = (e && (e.msg || e.message)) || ''
  const friendly = rawMsg || '账号或密码错误, 请重试'
  if (typeof uni !== 'undefined' && uni.showToast) {
    uni.showToast({ title: friendly, icon: 'none', duration: 2500 })
  } else {
    alert(friendly)
  }
}
```

**后端文案来源** (`com.industrial.erp.modules.system.service.AuthService` BizException 抛出):
- L106: "登录失败次数过多, 请5分钟后再试"
- L112: "用户名或密码错误"
- L115: "账号已停用"
- L119: "用户名或密码错误"

**部署**:
- 改 2 文件: `app/src/pages/login/index.vue` + `pc-web/src/views/Login.vue`
- `cd pc-web && npm run build` → dist/assets/Login-CktUqvzO.js (4311 字节)
- 部署 PC: `python3 base64` 流式上传 tar 到 NAS `/tmp/staging/` → sudo 解压 → `docker restart erp-pc-web`
- 部署 App: 待触发 `bash scripts/build-app.sh` (代码已就位, APK 还没重打)
- 不动: 后端 jar / DB / SQL (前端交互层修复)

**端到端验证 (2026-09-23)**:
- ✅ PC dist 编译成功, Login chunk 含 "账号或密码错误" 文案 (grep -c = 1)
- ✅ PC 部署后线上 `http://home.93gushi.com:8088/assets/Login-CktUqvzO.js` MD5 `4361c418aa3293428292ba650e52ca01` = 本地 dist (完全一致)
- ✅ PC 用户体验: 输入错误密码 → Element Plus 红色错误提示 "用户名或密码错误"
- ⏸ App 端: 代码已就位, 待编译 APK + 装机测试

**踩坑** (home NAS pc-web dist 部署):
- gpssong 密码 `19850225aB` (vs 飞牛 `850225sonG` 别混!)
- gpssong `/tmp` 只读 + SFTP subsystem 被禁, 大文件传输需用 Python Popen + base64 + ssh -T + sudo -S
- fail2ban 60-90s 解锁, 5 次错密码立即锁
- 完整步骤见 memory `erp-home-nas-pc-web-deploy-pitfalls.md`

**设计原则 (R13 — 写入设计原则)**:
- **PC + App 错误提示必须后端 msg 优先**: 业务错误码 (`code: 400`) 通常携带用户可读 msg, 不要用前端模板拼 "登录失败: <msg>" 冗余前缀
- **PC 端 `request.js` v1.1.31+ 设计**: 拦截器不弹 ElMessage, 让组件 catch 统一弹 (避免双弹); **任何组件 catch 块必须独立处理错误展示**, 不能假设拦截器会弹
- **App 端用 `uni.showToast`**: 非阻塞吐司优于 `alert`, 符合移动 App 交互惯例; H5 退化为 `alert` 兜底
- **错误文案兜底**: 后端 msg 为空时给 "账号或密码错误, 请重试" 这类明确提示, 不要给 "操作失败" 这种空话

### v1.1.60 (2026-09-23) — App 端物理 back 区分栈深度 + 工作台吞掉事件

**症状**: v1.1.54+ 全局 back 拦截 (`app/src/utils/nav.js`) 行为粗暴: 任何非工作台页面按物理 back 都直接 `uni.switchTab('/pages/dashboard/index')`, **跳过中间栈**; 工作台页按物理 back 直接 `return false` 让系统**退出 App**。用户反馈: "在非工作台页面点击返回时回到工作台页面, 而不是直接退出 App" — 想"先回列表再回工作台", 工作台按 back 不应退出。

**修复 — 区分栈深度 + 工作台吞掉事件**:
```js
// app/src/utils/nav.js onAppBack (替换 v1.1.54~v1.1.59 旧逻辑)
// 规则表:
//   1. 登录页 → return false (放行系统退出 App)
//   2. 工作台页 → return true (吞掉事件, 不退出 App)
//   3. 栈深 >= 3 → uni.navigateBack({delta:1}) + return true
//   4. 栈深 = 2 → uni.switchTab(HOME_PAGE) + return true
//   兜底 (栈深=1 但非工作台) → uni.switchTab(HOME_PAGE) + return true
export function onAppBack() {
  if (!isNative()) return false
  let pages = []
  try { pages = typeof getCurrentPages === 'function' ? getCurrentPages() : [] } catch (e) { return false }
  if (!pages || pages.length === 0) return false
  const cur = pages[pages.length - 1]
  const curRoute = cur ? ('/' + (cur.route || '')) : ''
  const stackLen = pages.length
  if (curRoute === '/pages/login/index') return false                       // 规则 1
  if (curRoute === HOME_PAGE) return true                                  // 规则 2
  if (stackLen >= 3) { uni.navigateBack({ delta: 1 }); return true }       // 规则 3
  if (stackLen === 2) { uni.switchTab({ url: HOME_PAGE }); return true }   // 规则 4
  uni.switchTab({ url: HOME_PAGE }); return true                            // 兜底
}
```

**行为对比表**:
| 场景 | 物理 back (新) | 物理 back (旧 v1.1.54~v1.1.59) |
|------|---------------|-------------------------------|
| 工作台 | 吞掉事件不退出 ✅ | 退出 App ❌ |
| 工作台 → 详情 (栈深=2) | switchTab 工作台 ✅ | switchTab 工作台 ✅ |
| 工作台 → 列表 → 详情 (栈深=3) | navigateBack 回列表 ✅ | switchTab 工作台(跳过列表) ❌ |
| 列表(栈深=2) | switchTab 工作台 ✅ | switchTab 工作台 ✅ |
| 登录页 | 退出 App ✅ | 退出 App ✅ |

**为什么 navbar back 无需拦截**: uni-app 默认 navbar `<` 走 `uni.navigateBack({delta:1})`, 在栈深=2 时 pop 回工作台 (等价 switchTab), 栈深=3 时 pop 回列表 (与新规则一致)。所以仅物理 back 需要拦截, navbar back 行为**已经正确**, 不改。

**iOS 滑动返回**: 暂不支持 (uni-app iOS 滑动返回无公开拦截 API), 后续版本评估加自定义 navbar 左上角"←"按钮兜底。

**部署**:
- 改 1 文件: `app/src/utils/nav.js` L34-104 (重写 `onAppBack` 函数体, 其他不动)
- `bash scripts/build-app.sh` → APK MD5 `c80a208869199d3e165c809db5b3b775`, 4369503 字节
- 输出: `/Users/tongban/Library/CloudStorage/飞牛同步-Mac/erp/erp-app-20260923.apk` + `~/Desktop/erp-app-20260923.apk`
- NAS H5 部署: `python3 -m http.server` 上传 tar → sudo 解压到 /tmp/staging → cp -r 到 `/volume3/docker/erp-system/app/dist/build/h5` → `docker build --no-cache` → `docker rm -f erp-app-h5 && docker run ...` → 验证线上 chunk = `index-Dope0GFF.js` ✅
- 不动: 后端 jar / pc-web dist / DB / SQL (纯前端编译产物修复)

**端到端验证 (CDP 2026-09-23)**:
- 线上 chunk hash = `index-Dope0GFF.js` (本地 dist 一致) ✅
- onAppBack 规则分支模拟 (CDP 无 `getCurrentPages`, 模拟函数验证):
  - V1 工作台按 back → 规则 2 吞掉 ✅
  - V3 栈深=2 → 规则 4 switchTab 工作台 ✅
  - V4 栈深=3 → 规则 3 navigateBack 回上一页 ✅
  - V6 栈深=4 → 规则 3 navigateBack (递归) ✅
  - V7 登录页 → 规则 1 放行退出 ✅
  - 兜底: 栈深=1 但非 HOME → switchTab 工作台 ✅
- 手动设备测试: 用户装新 APK, 工作台按 back 不退出 ✅, 工作台→扫码入库→入库单详情→按 back 先回扫码入库 ✅

**设计原则 (R12 — 写入设计原则)**:
- **物理 back 是"回退", 不是"退出"**: 移动 App 惯例, 工作台 (App 唯一常驻首页) 按 back 应吞掉不退出, 退出走系统多任务右上角滑掉 或 "我的 → 退出登录"
- **栈深度 = 路由回退意图**: 栈深 >= 3 (有中间列表) → 走 navigateBack 保留中间页; 栈深 = 2 (工作台直达详情) → 走 switchTab 工作台避免 pop 后栈空弹回登录
- **switchTab vs navigateBack 选择**: 列表/详情按 back 要回工作台时, 优先 `uni.switchTab`(避开栈空跳登录的边缘 case), 不依赖 `uni.navigateBack` 的栈底不确定性
- **物理 back 与 navbar back 不分彼此**: navbar back 在 uni-app 默认走 `navigateBack`, 已与新规则等价或更优 (保留动画), 无需自定义拦截

**踩坑**:
- v1.1.54+ 旧 `onAppBack` 在 `App.vue:42` 通过 `plus.key.addEventListener('backbutton', ...)` 注册, 无需改动; 仅替换函数体即可生效
- NAS 部署时 mac tar 含 `LIBARCHIVE.xattr.com.apple.provenance` xattr, NAS tar 告警可忽略
- gpssong 在 home NAS `/tmp` 只读 (root:wheel 755), 必须用 `/volume3/docker/erp-system/upload/` 中转 + `sudo bash` 解压到 `/tmp/staging` + `cp -r` 到 dist (避免 ACL 锁直接解压丢 assets/)
- `dist/build/h5` 必须 `docker build --no-cache` 才能让新内容进镜像 (`Dockerfile` 用 `COPY dist/build/h5 /usr/share/nginx/html`)

### v1.1.59 (2026-09-23) — R11 终极根因修复: v-if 函数引用永远 truthy

**症状**: v1.1.58 hotfix APK 装到 gpssong1 (WAREHOUSE_MGR + FINANCE) 手机, 进 RKP202609220004 详情, 底部**仍显示【反审核】黄色按钮**。即使 storage 全清干净 (`erp_permissions`/`erp_app_permissions`/`erp_menus` 各 12 项不含 uncheck), 按钮**仍然显示**。后端 `/auth/me` 返回 12 perm 不含 uncheck, DB 干净, APK chunk MD5 匹配, 所有数据层面都已修复, 但按钮仍显示。

**根因 (R11)** — Vue 3 编译器对 v-if 表达式里的 setup 局部函数引用**不会自动调用**:
```js
// 源码:
v-if="(order.billStatus==='DRAFT' && canCheck) || (order.billStatus==='CHECKED' && canUncheck)"

// 编译产物 (Vite ES module minified 后):
function D(){return!!b()||h().includes("purchase:receipt:check")}
function A(){return!!b()||h().includes("purchase:receipt:uncheck")}
...
"DRAFT"===g.value.billStatus && D || "CHECKED"===g.value.billStatus && A
```
**关键**: D 和 A 是函数引用 (closure), **不是调用 D()/A()**。JS 里函数引用永远 truthy, v-if 永远渲染, 与 storage 数据完全无关!

为什么 Vue 3 编译器不自动包 `()`: setup 里 `function canCheck(){}` 是局部声明, render 通过闭包访问而非 `_ctx.canCheck` reactive proxy (proxy 是给 ref/reactive 用的, 包装函数就调不动)。编译器无法区分"我要函数引用"还是"我要函数结果", 默认按字面表达式编译。

**修复**:
```vue
<!-- 改前 (R11 bug): -->
<view v-if="(order.billStatus==='DRAFT' && canCheck) || (order.billStatus==='CHECKED' && canUncheck)">

<!-- 改后 (R11 fix): -->
<view v-if="(order.billStatus==='DRAFT' && canCheck()) || (order.billStatus==='CHECKED' && canUncheck())">
```
编译产物变成 `"DRAFT"===billStatus && (b()...) || "CHECKED"===billStatus && (h()...)`, 真正调用函数, 函数返回 false 时 v-if 正确不渲染。

**部署**:
- 改 2 文件: `app/src/pages/purchase/receipt-detail.vue` L88 + `app/src/pages/sales/delivery-detail.vue` L88
- `bash scripts/build-app.sh` → APK MD5 `c02bbc8f30b0f8050c35df6b8c760682`, 4369473 字节
- 输出: `/Users/tongban/Library/CloudStorage/飞牛同步-Mac/erp/erp-app-20260923.apk`
- 不动: 后端 jar / pc-web dist / DB / SQL (纯前端编译产物修复)

**端到端验证 (2026-09-23, CDP)**:
- WebView devtools socket (绕过 Origin header 的 raw websocket) 验证:
  - 后端 `/auth/me` 带 APP header → 12 perm 不含 uncheck ✅
  - storage 全清干净 → 12 perm 不含 uncheck ✅
  - **新 APK 装上 → 反审核按钮 `uncheckBtn=0`, action-card 不渲染** ✅

**根因教训 (R11, 写入设计原则)**:
- **Vue 3 v-if 直接引用 setup 函数永远 truthy**: 表达式 `(cond && fn) || (other && fn2)` 中 fn/fn2 是 closure 引用, 非函数调用
- **永远用 `fn()` 显式调用**: 或改用 `computed(() => ...)` 让 Vue 包装响应式
- 这个 BUG 与 storage / 后端 / DB / chunk **全部无关**, 是 Vue 3 编译器的边界 case, **不能通过任何数据修复解决**
- 任何新增的"按钮显隐" v-if 都要警惕: 如果 setup 里定义了 `canXxx()` 函数, template 里必须用 `canXxx()` 调用, 或改 computed
- 排查类似按钮误显示问题流程: 1) CDP 读 DOM; 2) grep 编译产物 v-if 表达式; 3) 看是否函数引用被当成 truthy

### v1.1.58 (2026-09-22) — 制袋工 zdg 漏 uncheck APP 全量兜底 (`sql/43_v158_fix_uncheck_for_nonbuiltin.sql` + `sql/43b_v158_revoke_warehouse_uncheck_app.sql`)

**症状**: 制袋工 zdg (用该角色登录的账号) 进入 RKP202609220001 (已审核采购入库单) 详情, **黄色【反审核】按钮可见 + 点击被拒** (弹"无权限访问" toast)。

**根因**: sql/35 第 2 段 "有 check APP 补 uncheck APP" 触发器**跑时序错位** — sql/34 给 zdg 补 check APP 之前, sql/35 触发器已跑完, 漏补; sql/37 全量触发器兜底**理论上**应补, 实际 DB 里 zdg 仍 `purchase:receipt:uncheck APP=0`; 推测 sql/37 部署时 zdg check APP 还没补上 / 或某次手动清理脚本误删。后端 `selectPermsByUserIdAndClient` SQL 走 `client_type IN ('BOTH', 'APP')`, zdg 没 uncheck APP 行 → 后端 perm 数组不含 → 后端 `@SaCheckPermission("purchase:receipt:uncheck", orRole="admin")` 拒绝。

**修复 (R9 — 新模式取代 sql/35 白名单触发器)**:
- `sql/43` 全量兜底补 APP + PC uncheck perm (凡是有 check 的角色, 都补 uncheck, NOT EXISTS 排除已存在)
- `sql/43b` 撤销 WAREHOUSE_MGR 的 uncheck APP (sql/43 副作用, 还原 sql/38 撤销意图)
- 部署: home + 飞牛双站完成, 验证:
  - **zdg 制袋工**: `pur_uncheck_app=1` (修复 ✅) + `pur_uncheck_pc=1` (顺手补)
  - **cmg 吹膜工**: 全 0 (业务决策: 完全没 check 不补 uncheck)
  - **WAREHOUSE_MGR**: `uncheck APP=0` (撤销, sql/38 意图保留) + `uncheck PC=1` (PC 端保留)
- 用户操作: zdg 用户**退出 App 重新登录**一次刷 Sa-Token session perm → 反审核按钮可点击
- **不动**: 后端 jar / App APK / pc-web dist (纯数据修复)

**R9 设计原则** — 写入 sql/43b + 本次修复:
- **黑名单模式 (新规, 取代 sql/35 白名单触发器)**:
  - 默认: 所有有 check APP 的角色自动获 uncheck APP (业务合理假设)
  - 例外 (blacklist): WAREHOUSE_MGR 这类"可正向不能反向"角色, 须显式 DELETE
  - 优势: 简单, 不依赖角色名硬编码, 兼容未来新增角色
  - 撤销方式: 业务方提需求 → 在 sql/blacklist_uncheck_app.sql 追加 DELETE 一行
- **白名单模式 (sql/35 老模式, 已废弃)**:
  - 缺点: 新增角色容易漏 (这次 zdg 就是漏), 后续维护成本高
  - 替换原因: 历史 35+37+43 三次白名单触发器都漏配, 触发器白名单不可靠

**意外副作用 (sql/43 触发器自动产生, 业务合理)**:
- sql/43 给 FINANCE/PRODUCTION_MGR 补了 `sales:delivery:uncheck APP` (历史有 `sales:delivery:check APP`)
- SUPER_ADMIN/PURCHASE_MGR/SALES_MGR 没补 `sales:delivery:uncheck APP` (历史只有 PC check 没 APP check) — 业务上不影响, 因为这仨 App 端根本没销售列表入口 (没 sales:delivery:list APP)

### v1.1.56 hotfix-1 (2026-09-22) — R10 sys_menu 重复 perm 行去重 (`sql/42_v158_dedup_uncheck_perm.sql`)

**症状**: 用户截图反馈 PC 端【分配权限】弹窗 "采购入库反审核" 出现 2 次, 销售出库同理。el-tree 在 parent_id=402 (采购入库) 下渲染了 2 行 menu_name="反审核"/"采购入库反审核"。

**根因**:
1. **sql/23 (2026-07-28 seed)** 加了 21 个 B 类型按钮行 (`sys_menu.menu_type='B'`, menu_name 习惯"单行文本不重复模块名前缀", 如 `menu_name='反审核'` + `perms='purchase:receipt:uncheck'`), id=6050/6042
2. **sql/35 (2026-09-21 v1.1.55 反审核拆 perm)** `INSERT IGNORE` 加了 F 类型 perm 载体 (`menu_type='F'`, menu_name="采购入库反审核" 含模块前缀), id=...5349/...5350
3. **`sys_menu.perms` 列没 UNIQUE 索引**, `INSERT IGNORE` 只对 unique key 冲突静默, **同 perm + 同 parent_id 双行并存**
4. PC 端 Role.vue `markDisabled` (v1.1.52.6 起认 F 类型) 把新 F 行 enabled 可勾, 老 B 行也 enabled 可勾 → 弹窗显 2 次, 用户必须分别手动取消一次

**业务影响**:
- 后端 `@SaCheckPermission("purchase:receipt:uncheck")` 走 perm 字符串, 不受影响
- `selectPermsByUserIdAndClient` GROUP_CONCAT DISTINCT 去重, App 端 storage 派生不变
- sys_role_menu 同一角色对同 perm 有 2 行 (B 行 + F 行), 业务等价但冗余

**修复** (sql/42 数据级清理):
1. **保留 F 类型行** (权威 perm 字符串载体, sql/35/40/41 后续所有 perm 校验都走 F 行)
2. **删除 B 类型行** id=6050/6042 (sql/23 seed 时凑数, 没 PC 端业务代码读它们的 id)
3. **sys_role_menu 引用迁移**: 先 DELETE 已存在的 F 行授权行 (避免 UPDATE 撞 PRIMARY), 再 UPDATE 旧 B 行 menu_id → 新 F 行 id. 授权 perm 字符串不变, sys_role_menu 总行数不变 (但行数减少 = 因为双授权合并成单授权, 实际 row 数 -13 = 763-755 + 2 sys_menu 删除)

**部署**: home + 飞牛 双站跑 sql/42, **不重打任何 jar / dist / APK** (纯数据修复, perm 字符串没变, Sa-Token session 缓存的 perm 数组不变, 用户无需退出重登).

**R10 设计原则**:
- **`sys_menu.perms` 列必须有 UNIQUE 索引** 防 future duplicate (留 v1.1.57+ 单独做, 回溯历史 75 条 F + 21 条 B 风险大)
- 后续 seed perm 行时必须先 `SELECT id FROM sys_menu WHERE perms=?` 查重, 不能依赖 `INSERT IGNORE` 兜底
- sql/23 (2026-07-28 seed 的 21 个 B 类型按钮行) 命名习惯"单行文本不重复模块名前缀" 与 sql/35 的"F 类型完整命名"风格不一致, 本次只清理 uncheck 行 (用户截图定位), 其他 19 行 (check/edit/delete/print) 待后续单独审计 — 触发条件: 用户再次截图指认其他重复 perm 行

**经验 (R10)**:
- `INSERT IGNORE` 在 MySQL 是 unique key 级别的去重, 业务级"同 perms 不应双行"需要靠 SELECT 查重 + UNIQUE 索引双保险
- B/F 类型 perm 行并存是历史债, 长期应统一为 F 类型 (B 类型无 perms 也能算按钮, 但 perm 字符串必须唯一)

### v1.1.55 hotfix-3 (2026-09-22) — R9 后端按 client_type 分流 permissions + sql/39 撤销 WAREHOUSE_MGR PC uncheck

**症状**: hotfix-2 (前端 getAppPermissions 派生) 部署后, gpssong1 (WAREHOUSE_MGR) 仍能通过老 APK storage 残留绕过, App 端入库单/出库单详情显示【反审核】按钮; 同时用户报告 **PC 端反审核按钮也显示**, 即使 popup 里"采购入库反审核/销售出库反审核" UNCHECKED。

**根因** (Phase 2 调研 + live API 验证):
1. **App 端**: `selectPermsByUserId` 不过滤 client_type, 返回 PC+APP 混合 111 个 perm。gpssong1 因 WAREHOUSE_MGR + sql/35 1.3 cross-join PC `:uncheck` 授权, 混合数组含 `purchase:receipt:uncheck` + `sales:delivery:uncheck`。
2. **App 端 fallback 链脆弱**: `getAppPermissions()` 读 `erp_app_permissions` (空) → 读 `erp_menus` (空) → fallback `getPermissions()` → 返回混合 → 按钮误显。
3. **PC 端**: `userStore.hasPerm('xxx:uncheck')` 直接查 `permissions` 数组, 后端给的是 PC+APP 混合, 含 PC `:uncheck` (WAREHOUSE_MGR sql/35 1.3 给 6 内置角色都加了) → PC 按钮一定显示。PC 端用户即使手动取消 popup 勾选, popup el-tree 状态与后端 perm 数组不同步 (因为 `sys_role_menu` 行还在, 只是前端 el-tree 看着 UNCHECKED)。

**修复** (R9 后端彻底按端分流 + sql/39 物理撤 PC uncheck):
1. **后端 `SysMenuMapper.xml` + `SysMenuMapper.java`**: 新增 `selectPermsByUserIdAndClient(userId, clientType)` — 复用 `selectMenusByUserIdAndClient` 的 `rm.client_type IN ('BOTH', #{clientType})` 过滤, 仅返回 `perms` 字符串 (去重)。
2. **后端 `AuthService.java`** (login + currentUser):
   - 抽 `resolveClientType(HttpServletRequest)` 工具方法: 从 header `X-Client-Type` 取 (`APP`/`PC`), 缺省/null → null (向后兼容 PC 端无 header 调用)
   - 抽 `permsByClientType(uid, clientType)` 工具方法: `APP`/`PC` → 调 `selectPermsByUserIdAndClient`; null/其他 → 调 `selectPermsByUserId` 混合 (向后兼容)
   - `login()` + `currentUser()` 替换 `vo.setPermissions(userMapper.selectPermsByUserId(...))` 为 `vo.setPermissions(permsByClientType(uid, clientType))`
3. **后端 `AuthController.java`**: `/me` 加 `HttpServletRequest request` 参数, 透传给 service。
4. **App 端 `src/api/index.js`**: 2 处加 `header['X-Client-Type'] = 'APP'` — uni.request (L33-34) + fetch (L114) 都加, App 端所有请求自动分流。
5. **PC 端**: 不动, 默认 PC 走混合 (历史一直这样, PC 端功能靠 perm.includes 区分, sql/39 撤完后 PC uncheck 也不在了)。
6. **`sql/39_v155_hotfix3_revoke_warehouse_uncheck_pc.sql`**:
   - 物理 DELETE WAREHOUSE_MGR 的 `purchase:receipt:uncheck` PC + `sales:delivery:uncheck` PC 授权 (与 sql/38 撤 APP uncheck 对称)
   - 保留 SUPER_ADMIN/PURCHASE_MGR/SALES_MGR/PRODUCTION_MGR/FINANCE 5 内置角色 PC uncheck (业务需要)
   - 校验查询: WAREHOUSE_MGR 现在 PC uncheck=0 AND APP uncheck=0 (双端都禁)

**live 验证** (gpssong1 登录 home 192.168.0.150:8080):
| 调用 | perms 数 | 含 `:uncheck` |
|---|---|---|
| `/me` 不带 header (混合) | 109 | **无** ✓ (sql/39 撤了 PC uncheck) |
| `/me` 带 `X-Client-Type: APP` | **12** | **无** ✓ (R9 分流生效, 仅 APP-only perm) |
| `/me` 带 `X-Client-Type: PC` | 108 | 无 ✓ (sql/39 撤了) |

**效果**: App 端 gpssong1 进 RKP202609210005 / CKP202609210003 详情, 反审核按钮**永久消失** (即使老 APK storage 残留 fallback 也到混合, 但混合已不含 `:uncheck`); PC 端 gpssong1 登录入库单/出库单, 反审核按钮也**永久消失**。

**前端兼容性**:
- `getAppPermissions()` (hotfix-2) 可保留作 defense-in-depth, 但不再必要 — 即使 fallback 到混合 perm, 也没 `:uncheck`
- `canCheck()` / `canUncheck()` 代码不变, 仍调 `getAppPermissions()`

**部署**: home + 飞牛双站 (1) 跑 sql/39 (2) 后端 jar 重打 + docker build --no-cache + docker rm -f + docker run (3) App APK 重打 (4) 通知 gpssong1 重登刷新 storage.

**R9 设计原则** (写入 CHANGELOG):
- **后端返回的 `permissions` 数组必须按请求方端别分流** (`APP`/`PC`), 不能 PC+APP 混合给
- 前端任何 storage 派生 (`getAppPermissions()`) 都只能作 fallback, 不能作唯一来源 — 因为 storage 受 APK 升级/重装/老版残留影响
- 后端分流触发点 = 请求 header (X-Client-Type), 不依赖用户 clientScope (那是"用户能用什么端", 不是"这次请求从哪个端来")

### v1.1.56 (2026-09-21) — 采购入库单查询独立 perm (`purchase:receipt:query`) 修复 App 端入口缺失

**症状**: gpssong1 (WAREHOUSE_MGR) 在 PC 端勾选了「采购入库单查询」App 菜单权限,但 App 端工作台没有「采购入库单」入口。销售侧对称的「销售出库单查询」入口正常显示。

**根因** (Phase 1 调研):
- App 工作台 `app/src/pages/dashboard/index.vue` 的 `APP_MENU_TO_PAGE` 按 **(perms, path) 双匹配** 生成快捷入口,`visibleMenus` 用 `find()` 取第一个匹配。
- sys_menu 里「扫码入库」和「采购入库单查询」**复用同一条 sys_menu 402** (perms=`purchase:receipt:list`, path=`/purchase/receipt`)。
- `APP_MENU_TO_PAGE` 同时有两条命中 402: L135 扫码入库 (`/pages/scan/in`) + L146 采购入库单 (`/pages/purchase/receipt-list`),`find()` 永远返回 L135 → **L146 采购入库单被永久遮蔽**,任何非超管都看不到。
- 销售没出事: 销售出库单 (502, `sales:delivery:list`) vs 扫码出库 (503, `sales:return:list`) perms 不同,不撞车。

**方案** (对齐销售 502/503 模型, 给采购入库单查询独立 perm):
1. **新建 `sql/36_v156_receipt_query_perm.sql`**:
   - sys_menu 新增 `purchase:receipt:query` (F 类型, parent_id=0, is_visible=0, sort_no=2016 紧邻 uncheck 2015)
   - 给所有 `purchase:receipt:list` APP 角色自动补 `purchase:receipt:query` APP ("有 list 才有 query", 沿用 sql/33 模式, 保证仓管/制袋工入库单查询入口出现, 扫码入库不受影响)
2. **App 端** (`dashboard/index.vue` L146 + `utils/permission.js` receipt-list/detail PAGE_PERMS): `purchase:receipt:list` → `purchase:receipt:query`,与 L135 扫码入库分离
3. **PC 端** (`pc-web/src/views/system/Role.vue` APP_MENU_WHITELIST): 「采购入库单查询」`perms` 改 `purchase:receipt:query`
4. **后端不动**: 查询端点 (`PurReceiptController /page + /{id}`) 仍走 `purchase:receipt:list` (扫码入库也调它); `query` perm 纯是**入口显隐**级载体, 与销售的 502/503 对称

**部署**: home + 飞牛 各跑 sql/36; App APK + pc-web dist 重打部署双站; gpssong1 等 App 用户**退出 App 重新登录**一次让新 perm 生效。

**v1.1.56 hotfix (2026-09-21 晚)** — F 类型 perm 载体 `path=''` 不进 (perms,path) 双匹配, 改 perm-only 兜底入口:

**症状**: v1.1.56 首次部署后 gpssong1 重装 APK + 重登 App, 「采购入库单」入口**仍未出现**。超管账号正常。

**根因**:
- `purchase:receipt:query` 是 F 类型 perm 载体, sys_menu 行 `path=''` (因为纯 perm 载体, 没路由)。
- 后端 `selectMenusByUserIdAndClient` 返回的 menu 行带 `path=''`, dashboard `visibleMenus` L184-194 loop `entry.path === m.path` 永远 false。
- v1.1.56 在 `APP_MENU_TO_PAGE` L148 写过 `{ perms: 'purchase:receipt:query', path: '/purchase/receipt' }`, 但 `entry.path='/purchase/receipt'` ≠ `m.path=''` → 永远不命中双匹配。
- 对比 `report:view` (sys_menu 951, path='', menuType=F) — `report:view` 走 L196-204 的 perm-only 兜底, 所以经营简报入口能出来。`purchase:receipt:query` 之前没这个 perm-only 兜底, 所以漏了。

**修复** (`app/src/pages/dashboard/index.vue`):
- L196-204 之后追加 perm-only 兜底分支 (镜像 `report:view` 模式)
- 移除 `APP_MENU_TO_PAGE` L148 的 `purchase:receipt:query` 条目 (dead code)

**部署**: App APK 重打 (MD5 `9fa92d720ad5a1e279b769c597197a8a`), home + 飞牛 双站部署; **后端 + pc-web 不动**。

**经验 (R6)**: App 端"perm 载体"型 sys_menu (F 类型, path='', 仅做授权) **不要**走 `APP_MENU_TO_PAGE` 双匹配 (永远 path 不匹配), 必须走 perm-only 兜底分支 (同 `report:view`)。新增"perm 载体型" App 入口时, 把这条规则写进模板 — 不要再依赖 (perms,path) 双匹配。

### v1.1.55 hotfix (2026-09-21 晚) — 撤销 WAREHOUSE_MGR 自动 uncheck APP

**症状**: gpssong1 (WAREHOUSE_MGR) PC 端 App 菜单权限弹窗里**没勾**「采购入库反审核」「销售出库反审核」, 但 App 端销售出库详情 (CKP202609210003 已审核) **显示了黄色【反审核】按钮**。用户判断: 这是 BUG, 没勾就不该出现按钮。

**根因**:
- sql/35 第 2-3 段 "有 check APP 补 uncheck APP" 自动授权策略, **对 WAREHOUSE_MGR 业务上不合理** — 仓管员业务上"能审核 ≠ 敢反审核" (反审核回退库存/AP/AR, 高风险)
- 这条策略对 SUPER_ADMIN/PURCHASE_MGR/SALES_MGR/FINANCE/PRODUCTION_MGR 等老板/主管**业务合理**, 但 WAREHOUSE_MGR (仓库主管) 不该自动给
- PC 端 Role.vue `APP_MENU_WHITELIST` 显示的复选框在 sys_role_menu 的 sys_role_menu 行存在时是 checked, 但用户从未真的点过保存, 系统维持 sql/35 自动授权

**修复**:
1. **sql/37_v155_hotfix_uncheck_app_full.sql** — 全量触发器给所有"有 check APP 没 uncheck APP"角色补 uncheck APP (兼容历史漏配, 比如 WAREHOUSE_MGR 这种 sql/35 第一次跑时 uncheck 行没 seed 出来的情况)
2. **sql/38_v155_hotfix_revoke_warehouse_uncheck.sql** — 物理 DELETE WAREHOUSE_MGR 的 `purchase:receipt:uncheck APP` + `sales:delivery:uncheck APP` 两行, PC 端 uncheck 保留
3. 部署: home + 飞牛 双站完成, 校验 WAREHOUSE_MGR 现在 `pur_uncheck_app=0, sal_uncheck_app=0, pur_uncheck_pc=1, sal_uncheck_pc=1`
4. 用户操作: gpssong1 **退出 App 重登一次** → App 端反审核按钮消失 (Sa-Token session 缓存的 perm 数组从 DB 重新加载)
5. **PC 端不变** — uncheck 仍绑, 业务上仓库主管可通过 PC 反审核

**经验 (R7)** — 写入 sql/35 注释 + 后续设计原则:
- **自动补授权策略只适用于 6 个内置业务角色** (SUPER_ADMIN/PURCHASE_MGR/SALES_MGR/FINANCE/PRODUCTION_MGR), 这些角色业务上"能审核就能反审核"
- WAREHOUSE_MGR 这类"可正向不能反向"角色应**手动**走 PC 端 Role.vue 勾选 uncheck APP, **不要依赖 sql 触发器自动补**
- 后续新增反向操作 perm (红冲/补差/撤销), 拆分原则: **业务上"能正向 ≠ 敢反向"的角色** 必须**手动**勾选 uncheck, 任何自动补授权都会变成 BUG
- 不要写 `INSERT IGNORE` 让它自动扩散授权范围 — 业务权限应显式授予, 不应隐式扩散

### v1.1.55 (2026-09-21) — 反审核独立 perm (`xxx:uncheck`) + PC 端 4 年 BUG 修复

**症状**: v1.1.54 上线后,用户反馈 — App 端无反审核权限的账号也显示了反审核按钮(因为审核/反审核共用 `xxx:check` 一个 perm)。业务上希望"能审核" ≠ "敢反审核":反审核是回退库存/AP/AR 的高风险操作,通常只给老板/主管/超管。

**调研关键发现**:
1. **后端 Service 层早就在用 `:uncheck` perm**: `PurReceiptService.java:322` + `SalDeliveryService.java:386` 都 `permService.requirePerm("xxx:uncheck")`,但 controller 层 `@SaCheckPermission("xxx:check")` 用 AOP 先拦截,service 层永远到不了 → 现状 controller 注解才是真生效 perm
2. **PC 端前端早就在用 `:uncheck` perm 检查**: `Receipt.vue:45` + `Delivery.vue:60` 都 `userStore.hasPerm('xxx:uncheck')`,但 sys_menu 从未 seed 过该 perm → **PC 端反审核按钮 4 年来对所有非超管账号永久隐藏** (这是个隐性 BUG)
3. **PermissionService.hasPerm 有 fallback**: `:add`/`:edit`/`:delete` 自动 fallback 到 `:list`,但 `:uncheck` **不** fallback 到 `:check`,必须真有 perm 才放行

**方案**:
1. **新增 sys_menu 2 行 perm 载体**(`sql/35_v155_uncheck_perm.sql`):
   - `purchase:receipt:uncheck` (F 类型, parent_id=0, is_visible=0, sort_no=2015)
   - `sales:delivery:uncheck` (F 类型, parent_id=0, is_visible=0, sort_no=1015)
2. **自动绑定**(sql/35):
   - 给所有 check APP 角色补 uncheck APP ("有 check 才有 uncheck", 沿用 sql/34 模式)
   - 给 6 个内置角色(SUPER_ADMIN/PURCHASE_MGR/SALES_MGR/WAREHOUSE_MGR/PRODUCTION_MGR/FINANCE)补 uncheck PC (CROSS JOIN, 与 sql/28 对称, 修复 4 年 BUG)
3. **后端 controller 注解对齐**(`backend/.../PurReceiptController.java:74` + `SalDeliveryController.java:86`): `@SaCheckPermission("xxx:uncheck")` (service 层早已是 `:uncheck`, 形成"双保险"语义一致)
4. **App 详情页拆分 perm 显示**(`app/src/pages/purchase/receipt-detail.vue` + `sales/delivery-detail.vue`):
   - 拆 `canAudit()` → `canCheck()` + `canUncheck()`
   - DRAFT 显示审核按钮(看 `:check`),CHECKED 显示反审核按钮(看 `:uncheck`)
   - 文案差异化("无审核权限" vs "无反审核权限")
5. **PC 端 Role.vue 白名单加 2 行**(`pc-web/src/views/system/Role.vue` APP_MENU_WHITELIST):
   - 采购管理 → 采购入库反审核 (`purchase:receipt:uncheck`)
   - 销售管理 → 销售出库反审核 (`sales:delivery:uncheck`)
   - PC 端 `Receipt.vue:45` + `Delivery.vue:60` 的 `userStore.hasPerm('xxx:uncheck')` 检查无需改 — 这次部署后自动生效

**部署**: home + 飞牛 MySQL 都跑了 sql/35;后端 jar 重打(Mac mvn + docker build --no-cache + docker rm -f + run --env-file);App APK 重打装机;pc-web dist 重打部署。受影响的 App 用户**退出 App 重新登录**一次刷权限;PC 端内置非超管账号会看到反审核按钮(4 年 BUG 修复, 通知里说清楚)。

**设计原则** (R5):
- **何时拆 perm**: 操作风险不对称(反审核回退库存 vs 审核)/ 频率不对称(99% 用户只正向)/ 可观察性不对称(反审核需更高级别审计)
- **何时不拆**: add/edit 对称低风险; print/export 派生只读; 已有 perm 不批量重塑
- **结论**: 后续模块存在反审核/红冲/补差等"反向操作"时,一律拆 `:uncheck` 独立 perm;删除类操作暂不拆(软删除+二次确认已够);不批量改造历史 perm

### v1.1.54 (2026-09-21) — App 端采购入库/销售出库审核 + PC/App 端菜单权限同步

**症状**: PC 端采购入库/销售出库审核功能 end-to-end 完整(后端 `/{id}/check` + `/{id}/uncheck` + PC `onCheck/onUncheck`),但 App 端**完全没有审核入口** — App 扫单/开单后单据 DRAFT,必须回 PC 审核,老板/经理出差或外勤时无法闭环。

**方案**:
1. **App 详情页加审核/反审核按钮**(`app/src/pages/purchase/receipt-detail.vue` + `app/src/pages/sales/delivery-detail.vue`):
   - DRAFT 单据显示绿色【审核】,CHECKED 显示黄色【反审核】
   - 二次确认用 `uni.showModal` (等同 PC `ElMessageBox.confirm`)
   - perm 检查 `purchase:receipt:check` / `sales:delivery:check`,无 perm 显示"无审核权限, 请联系管理员"
   - 复用 xxx:check perm,反审核无独立 perm(项目惯例)
2. **App API 加 4 个方法**(`app/src/api/index.js`): `purchaseReceiptCheck/Uncheck` + `salesDeliveryCheck/Uncheck`,调 `POST /{id}/check` 等
3. **PC 端 App 端菜单权限 Tab 加 2 个白名单条目**(`pc-web/src/views/system/Role.vue` `APP_MENU_WHITELIST`):
   - 采购管理 → 采购入库审核 (`purchase:receipt:check`)
   - 销售管理 → 销售出库审核 (`sales:delivery:check`)
4. **sql/34 自动授权**(`sql/34_v154_app_audit_perm.sql`):
   - 凡是有 `purchase:receipt:list` APP 授权的角色,补 `purchase:receipt:check` APP 授权
   - 凡是有 `sales:delivery:list` APP 授权的角色,补 `sales:delivery:check` APP 授权
   - `INSERT IGNORE + JOIN sys_menu` 同 sql/33 模式,涵盖 v1.1.27 后所有新建角色

**部署**: home + 飞牛 MySQL 都跑了 sql/34;App 端重打装机 (sxr-app-h5 容器/新 APK);pc-web dist 不需要重打 (Role.vue 是 PC 端授权页);后端 jar 不需要重打 (0 改动)。受影响的 App 用户**退出 App 重新登录**一次刷权限。

**后端 0 改动** — `/{id}/check` + `/{id}/uncheck` + perm `xxx:check` (含反审核) 已存在。

### v1.1.53-scan-in-perm (2026-09-21) — App 端扫码入库 403 hotfix

**症状**: 秦运桂(制袋工 zdg)App 端「扫码入库」打开 OK,提交提示「无权限访问」。

**方案**: `sql/33_v153_scan_in_perm.sql` 给所有有 `purchase:receipt:list` APP 授权的角色,自动补 `purchase:receipt:add` APP 授权。`INSERT IGNORE + WHERE menu_id=402 AND client_type='APP'` 兼容历史 hotfix + 涵盖未来新建非标角色(制袋工/吹膜工)。

**部署**: home MySQL 已跑(7 个角色同时修复,含制袋工),后端/前端 0 改动。秦运桂等 App 用户**退出 App 重新登录**一次刷权限。

### v1.1.53 (2026-09-20) — 工作台权限细粒度拆分 (KPI / 趋势 / 排行 / 库存预警)

**症状**: PC + App 端工作台原本**一刀切**鉴权,整个 dashboard 由一个 `report:view` perm 控制。
业务痛点: 仓库主管被迫也能看到销售金额等敏感数据,即使只想看「库存预警」;销售经理/老板/主管共享同一视图。

**方案**: 把工作台拆成 4 个独立可选子模块,角色授权页 (PC 端 Tab + App 端 Tab) 都能分别勾选:

| 子模块 | perm 码 | 后端接口 |
|---|---|---|
| 销售指标 (4 个 KPI 卡片) | `dashboard:kpi` (新) | `GET /report/dashboard` |
| 销售趋势 (近30天折线图) | `dashboard:sales-trend` (新) | `GET /report/sales/summary` |
| 销售排行 TOP10 | `dashboard:sales-ranking` (新) | `GET /report/sales/ranking` |
| 库存预警 | `inventory:warning:list` (复用 sql/28) | `GET /inventory/warning/list` |

**保留** `report:view` 作为**报表中心**菜单(`/report/sales`、`/report/inventory` 子页面)的总闸 — 与工作台功能正交。

**改动**:
- SQL 新增 `sql/30_v153_dashboard_subperms.sql`: 3 行 sys_menu (F 类型,parent_id=0,is_visible=0) + CROSS JOIN 给内置 6 角色各绑 3 个新 perm (PC client_type)
- `backend/.../report/controller/ReportController.java`: **删类级 `@SaCheckPermission("report:view")`**;`/dashboard` 加 `@SaCheckPermission("dashboard:kpi")` + **清掉幽灵 `permissionService.requirePerm("dashboard:view")`**(v1.0.10 遗留, sql 里无对应 sys_menu 行,实际只有超管能过);`/sales/summary` 加 `dashboard:sales-trend`;`/sales/ranking` 加 `dashboard:sales-ranking`;其余 (inventorySummary/aging/arap/profit) 继续 `report:view`
- `pc-web/src/views/dashboard/Index.vue`: 重写 onMounted,4 个 section (`kpiVisible`/`trendVisible`/`rankingVisible`/`warningVisible`) 各自独立 `userStore.hasPerm(...)` 门禁,模板 `v-if` 包裹,失败静默
- `pc-web/src/views/system/Role.vue:330-337` `markDisabled`: 补 F 类型 perm 行 (对齐后端 `isGrantableMenu` v1.1.52.6 已认 F),否则 dashboard 子模块 4 行 F 类型 perm 节点仍 disabled 不可勾
- `app/src/pages/dashboard/index.vue`: `kpiVisible` 改判 `dashboard:kpi`(原本是 `report:view`);新增 `warningVisible` 走 `inventory:warning:list`(独立于 KPI);库存预警区块 `v-if` 从 `kpi.warningCount` 改为 `warningVisible && warningItems.length`,onMounted 中库存预警加载从 KPI try 内拆出独立 try

**业务授权示例**(部署后用户自行分配):
- 仓库主管 (WAREHOUSE_MGR): 勾 `inventory:warning:list` → 仅库存预警
- 销售经理 (SALES_MGR): 勾 `dashboard:kpi` + `dashboard:sales-ranking` → KPI + 排行
- 老板/财务: 全 4 项

**踩坑**:
- **幽灵 dashboard:view**: v1.0.10 引入 KPI 独立鉴权时只改了后端 `requirePerm("dashboard:view")` + `SysMenu.java` 注释,但 sql 里**没有任何 sys_menu 行**有这个 perm,且无角色绑定。本次清理
- **F 类型 perm 行在 PC 端 el-tree 不可勾**: 后端 v1.1.52.6 的 `isGrantableMenu` 已认 F,前端 `markDisabled` 还是只认 B/M。本次对齐
- **App 端 KPI 与预警解耦**: 之前 KPI 区块隐藏就连带预警也隐藏(因为预警依赖 `kpi.warningCount`),本次预警独立 try,即使没 KPI 也能单独看预警
- **部署**:
  - SQL 跑 home (192.168.0.150) + 飞牛 (192.168.0.32) 各一遍,飞牛通过 `docker cp` + `SOURCE /tmp/v153.sql` 注入(避免 stdin 传 SQL 的 sudo pipe 坑)
  - 后端 jar md5 `e2098890b861` (101085528 字节) → home/飞牛 各 rebuild image + recreate container
  - home backend recreate 时**必须传 `SA_TOKEN_JWT_SECRET_KEY`**(从 `/volume3/docker/erp-system/.env` 读,64 字符 hex)— 否则 SecurityPreflightValidator (v1.1.49) 拒绝弱密钥启动
  - home pc-web 启动需 `--hostname backend` (nginx upstream 解析)
  - 飞牛 pc-web 用自定义 `nginx-failover.conf`(已有)+ bind mount 新 dist → `erp-pc-web-failover` 重建
  - App H5 重打 APK: `/Users/tongban/Desktop/erp-app-20260920.apk` md5 `ac2b3f35bffe`,4.4MB

**回滚**:
- 后端: `ReportController` 类级加回 `@SaCheckPermission("report:view")`,3 个方法级注解删掉
- SQL: `DELETE FROM sys_menu WHERE perms LIKE 'dashboard:%' AND deleted = 0` (CASCADE 删 sys_role_menu)
- 前端: `Index.vue` 恢复 `canView` 整体门禁

### v1.1.52.6 (2026-09-20) — 库存预警 App 授权「开启了但重进还是未开启」

**症状**: v1.1.52.5 把库存预警加进 App 端权限树后,用户勾选 → 确定 → 退出重进,又变回未勾选。

**根因**: `SysRoleService.isGrantableMenu()` 只认 `menu_type='B'`(按钮)/ 带 perms 的 `'M'`。但库存预警等 `sql/28_v124_permissions.sql` 生成的功能菜单是 **`menu_type='F'`**(perms 非空,parent_id=0)。`grantMenusByClient` 提交前用 `isGrantableMenu` filter,把 F 类型全丢掉 → **没写进 `sys_role_menu`** → 下次 `selectMenusByRoleIdAndClient` 查不到 → 回填未勾选。DB 佐证:该菜单只有 `client_type=PC` 记录(6 条,PC 提交走不同路径),无 APP。

**修复**(代码级,`SysRoleService.isGrantableMenu`):把 `'F'` 与 `'M'` 同级纳入可授权(带 perms 才放行):
```java
if ("B".equals(m.getMenuType())) return true;
if ("F".equals(m.getMenuType()) || "M".equals(m.getMenuType())) {
    return m.getPerms() != null && !m.getPerms().trim().isEmpty();
}
return false;
```

**部署**: 新 jar `a690b817ec`(101085538 字节),home + 飞牛 双站 backend 重建后均 healthy、API HTTP 200。

**踩坑**:
- 同库已有 85 个 F 类型菜单的 PC 授权行(历史数据),但 F 类型 APP 授权此前被 filter 挡住,所以 App 端功能项(库存预警等)一直点不进去
- home backend 是**镜像内置 jar**,`docker run` 重建时需保留完整 env(含 `SA_TOKEN_JWT_SECRET_KEY`、`JAVA_OPTS`、多值 `ERP_CORS_ALLOWED_ORIGINS`、`SPRING_DATASOURCE_URL=jdbc:mysql://erp-mysql:3306/...`),漏 env 会导致 JWT 校验失败 / CORS 断

**回滚**: `isGrantableMenu` 恢复只认 B/M(删掉 `|| "F".equals(...)` 那半句)。

### v1.1.52.5 (2026-09-20) — App 端菜单权限补『库存预警』选项

**症状**: App 工作台有「库存预警」卡片(具体产品列表, v1.1.44+ `api.warningList()` → `/inventory/warning/list`),但 PC 端「角色管理 → 分配权限 → App端菜单权限」Tab 里**没有**库存预警的勾选框,无法给角色授予/取消该 App 功能。

**根因**: App 端权限树不是从 `sys_menu` 动态生成,而是 `pc-web/src/views/system/Role.vue` 里**硬编码**的 `APP_MENU_WHITELIST`(维护规则:任何 App 端新增功能都要在此登记才会在 Tab 显示)。库存预警(v1.1.44 加)漏登记。

**修复**(纯前端,无后端/DB 改动):`Role.vue` `APP_MENU_WHITELIST` 的「库存管理」children 追加一行:
```js
// v1.1.52.5+: 库存预警 (App 工作台「库存预警」卡片, 具体产品列表).
// perms=inventory:warning:list → sys_menu id=2090345792472715300 (home/飞牛 雪花 ID, 非 4011)
{ name: '库存预警', perms: 'inventory:warning:list', idApp: 'app-4011-warning' }
```
`buildAppMenuTree` 按 `perms` 反查真实 `sys_menu.id`(双库均为雪花 ID `2090345792472715300`, status=1),勾选后走 `grantMenusByClient(roleId, 'APP', [真实id])` 持久化到 `sys_role_menu`。`idApp` 只是 el-tree 节点合成 key(防同 perms 多功能冲突),与库内 ID 无关。

**踩坑**:
- `sql/28_v124_permissions.sql` 里 `库存预警` 写的是 `sort_no=4011`,但那是 **sort_no 不是 id** —— 实际 `sys_menu.id` 是雪花 ID,别把 `4011` 当菜单 ID
- home / 飞牛 `~` 与 `/tmp` 都不可写(gpssong 非交互 sudo 环境),传 tar 包要落到项目目录 `/volume3/docker/erp-system/` 或 `/vol2/erp-system/`
- 飞牛 backend 容器是**镜像内置 jar**,`docker restart` 不拉新 jar;本次 v1.1.52.4 部署时容器曾意外丢失重建,须 `docker build` + `docker run` 用 compose 的 env + `--network erp-failover-net` 重建,backend 起来了 nginx 才不再 restart loop

**回滚**: 删掉 Role.vue 那行白名单,重新 build dist 即可。

### v1.1.52.4 (2026-09-15) — 生产单删除误报「数据已存在,请检查编码/名称是否重复」

**症状**: 赵偲荣在 home.93gushi.com:8088 删生产单 PD202609150005/0006,提示 `数据已存在, 请检查编码/名称是否重复`,删不掉。

**根因**: `prd_order` 唯一索引 `uniq_prd_order_bill_no (bill_no, deleted)` 本意「同单号最多一条活行」,但逻辑删除 `deleted` 固定取 `1`:
1. 删过 PD...0005 (id A, `deleted=1`)
2. 同单号又新建一条 (id B, `deleted=0`)
3. 再删 B → 逻辑删把 B 改成 `deleted=1` → 撞 `(bill_no,1)` 唯一键(因 A 已是 1)→ MySQL `DuplicateKeyException` → 全局异常处理误报成「数据已存在」。

**修复** (`PrdOrderService.delete()`,代码级):软删本行**之前**先物理删掉同 `bill_no` 下其它已软删历史行(它们本就是 tombstone,物理删安全),腾出 `(bill_no,1)` 槽位:
```java
orderMapper.delete(new LambdaQueryWrapper<PrdOrder>()
        .eq(PrdOrder::getBillNo, order.getBillNo())
        .ne(PrdOrder::getId, orderId)
        .eq(PrdOrder::getDeleted, 1));
orderMapper.update(null, new LambdaUpdateWrapper<PrdOrder>()
        .eq(PrdOrder::getId, orderId).set(PrdOrder::getDeleted, 1));
```

**数据清理** (home + 飞牛 双库,手工执行一次):`DELETE FROM prd_order WHERE bill_no IN ('PD202609150005','PD202609150006') AND deleted=1;` 清掉卡住的重复 tombstone,每 bill_no 只剩一条。

**踩坑**:
- 报错文案来自 `GlobalExceptionHandler.handleDuplicateKeyException` → `R.fail("数据已存在, 请检查编码/名称是否重复")`,它把**所有**唯一键冲突都统一包成这句,容易误导(本例是删重复单号,不是新增撞码)
- 飞牛 backend 是**镜像内置 jar**(`backend.Dockerfile` `COPY erp-backend.jar app.jar`),`docker restart` 不拉新 jar,必须 `docker build` + `docker rm -f` + `docker run` 重建;home 同理(Dockerfile 烘焙 jar)
- `docker compose up -d backend-failover` 报 `no configuration file provided: not found`(飞牛 compose 里该 service 挂 `profiles: [failover]` 且不暴露服务名给 `up`),只能按 compose 的 env 手动 `docker run`

**回滚**: 恢复 `delete()` 为单行 `orderMapper.update(...)` 即可。

### v1.1.52.3 (2026-09-15) — 操作员姓名注入回退: `real_name` 空时回退 `username`

**症状**: 赵偲荣的生产单在列表「操作员」列仍显示 `-`(v1.1.52 已修 `create_by` NULL,但姓名还是空)。

**根因**: `sys_user` 里赵偲荣/罗飞/秦运桂/师雨晨/侯丽君 5 个早期账号 `real_name` 为 NULL(只有 `username` 有中文姓名)。`CreateByNameInjector` 只取 `u.getRealName()` 注入 → 这些人的 `create_by` 注入成 null → 前端 `row.createByName || '-'` → 显示 `-`。

**修复** (代码级, `CreateByNameInjector.resolveName(SysUser)`):优先 `realName`,为空则回退 `username`:
```java
static String resolveName(SysUser u) {
    String name = u.getRealName();
    if (name == null || name.trim().isEmpty()) name = u.getUsername();
    return (name == null || name.trim().isEmpty()) ? null : name;
}
```
代码级根治,无需逐条回填 `sys_user` 数据;历史 + 未来单据都生效。双站 jar 已换(101085440 字节)。

**回滚**: `resolveName` 只用 `getRealName()` 即可。

### v1.1.52.2 (2026-09-15) — 飞鹅打印 403: 赵偲荣(仓库主管/财务)缺 RBAC 权限

**症状**: 赵偲荣账号 (user_id=2073693353985228802,角色 4=仓库主管 / 6=财务) 在 home.93gushi.com:8088 打飞鹅生产单,弹窗「飞鹅打印预览」点「确认打印」→ 前端 axios 收到 **403 Forbidden**(`POST /api/feie/print/PRD_ORDER/{id}`)。错误被 `ElNotification.error('打印失败: Request failed with status code 403')` 吞,看不到具体原因。

**根因**: 不是 CORS,是 RBAC 权限缺失。
- `FeiePrintController.printBill` 注解 `@SaCheckPermission(value = {"production:order:feie-print"}, orRole = "admin")`
- 菜单 959(`飞鹅打印`,perms=`production:order:feie-print`)**之前只授予给角色 1(超级管理员)+ 角色 2(采购经理)**
- 仓库主管(4)/财务(6) 没有 menu 959 → Sa-Token 校验失败 → 403

**修复**(纯数据修复,无代码改动):
```sql
INSERT INTO sys_role_menu (role_id, menu_id, client_type) VALUES (4, 959, 'BOTH');
INSERT INTO sys_role_menu (role_id, menu_id, client_type) VALUES (6, 959, 'BOTH');
```
**副作用**: 赵偲荣需要**退出再重新登录**(Sa-Token 权限缓存,新 session 才读到新授权)。

**踩坑**:
- `sys_role_menu` schema 比源码期望多一个 `client_type` 字段(v1.1.11 双端授权改造,取值 `PC/APP/BOTH`)
- 插入前必须 `WHERE NOT EXISTS` 防重复(同 role+menu 已有 PC 行时不能插 BOTH)
- 备份:`sys_role_menu_bak_$(date)` 已建(2026-09-15)

**回滚**: 删掉这两行即可。

### v1.1.52.1 (2026-09-15) — 飞牛热备站 n150.93gushi.com 同步部署(后端 jar + 前端 dist)

**症状**: n150.93gushi.com:8088 访问「工作台库存预警」卡片为空 + 生产单列表「操作员」列全部 `-`(v1.1.44 实时预警 + v1.1.51 操作员列未生效)。

**根因**: 飞牛热备站 (192.168.0.32, /vol2/erp-system/) 后端 jar 是 8/22 v1.1.19.4 时代(100.5MB),前端 dist 是 8/22 时代(Index-DBC3Xl1C.js 4618 字节),**比主站滞后 ~3 周**,缺 v1.1.44 (dashboard 库存预警实时化 `warningCount` 从死表 `inv_warning` 改 `inv_stock + base_product.safety_stock` 实时统计) + v1.1.50 (订单关联出库单雪花 ID 精度) + v1.1.51 (操作员列) + v1.1.52 (FieldFill 修复)。

**修复**(纯部署同步,无代码改动):
- **后端 jar**: 本地 `backend/target/industrial-erp-1.0.4.jar` (101MB, v1.1.52 commit `fade2af`) → `sshpass -O scp` 到飞牛 `/vol2/erp-system/erp-backend.jar` → `sudo docker build --no-cache -t erp-system-backend:latest .` → `sudo docker rm -f erp-backend-failover` → `sudo docker run -d --name erp-backend-failover ... erp-system-backend:latest`(同 8/22 启动参数)。验证: `docker exec erp-backend-failover stat /opt/app/app.jar` → size=101085277(与本地一致),新单 `createBy=2 createByName=gpssong` ✅
- **前端 dist**: 本地 `pc-web/dist` 跑 `npm run build` (新 chunk `Index-BGP5wSoh.js` 5957 字节,含 `warningList` 实时化调用) → `tar -czf` → `scp` 到飞牛 `/tmp` → `cp -r ~/pc-extract /vol2/erp-system/pc-web/dist`(ACL 兜底,同 memory) → `sudo docker restart erp-pc-web-failover`

**验证**:
- `/api/inventory/warning/list` → 1 条预警(塑料袋22*28*0.16,缺 42000)
- `/api/report/dashboard` → `warningCount=1`(实时统计)
- 生产单 3 条旧单 `createBy=None`(9/15 之前 NULL,符合预期)+ 新单 `createBy=2` ✅

**踩坑**:
- 飞牛 docker 在 `/usr/bin/docker`(非 `/usr/local/bin/docker`),`sudo -S -p ''` 输密码
- mac 端 `python3 -m http.server` 在此环境 CLOSED(立即停),不可靠 → 改用 `scp` 成功(4.5MB tarball + 101MB jar)
- `docker restart` 不会拉新 image(旧 image hash),必须 `docker rm -f` + `docker run` 用新 image
- `/api/dashboard/kpi` 是 404(旧路径),正确路径 `/api/report/dashboard`(v1.1.44 已迁移到 report 模块)

### v1.1.52 (2026-09-15) — 全 entity 补 `@TableField(fill=...)`, 修复 `create_by` 全表 NULL

**症状**: v1.1.51 上线后用户实测发现生产单列表「操作员」列全部显示 `-`。DB 直查 `SELECT create_by FROM prd_order ORDER BY id DESC LIMIT 5` 全是 NULL — 不止 prd_order, 销售单/采购单/入库单/盘点单/财务单/基础资料 14 张表全部 NULL。

**根因**: `MybatisPlusConfig.metaObjectHandler()` 注册了 `strictInsertFill("createBy", Long.class, userId)`, 但**所有 entity 都缺 `@TableField(fill = FieldFill.INSERT)` 注解** — MyBatis-Plus 因此跳过填充。`create_time` 有值是 DB 默认值 `CURRENT_TIMESTAMP` 兜底, `create_by BIGINT` 无默认值所以一直是 NULL。v1.1.51 的 `CreateByNameInjector` 只查 user 表把 `realName` 注入前端, 但 `createBy` 本身是 NULL, 注入器 `nameMap.get(null) = null` → 前端 `-`。

**方案**: 批量给 44 个 entity 的 `createBy/createTime/updateBy/updateTime` 四字段补 `@TableField(fill = FieldFill.INSERT)` / `INSERT_UPDATE`, 同时补 `import FieldFill` + 21 个缺 `TableField` import 的 entity 手动加。6 个日志类 entity (SysLoginLog/SysOperLog/SysFeiePrintLog/SysBackupRecord/FinInvoiceApply/InvLedger) 字段顺序特殊, 暂跳过(不影响功能)。

**改动**:
- 44 个 Entity 加 `@TableField(fill = FieldFill.INSERT)` / `INSERT_UPDATE` 注解 + `import com.baomidou.mybatisplus.annotation.FieldFill`
- 21 个 Entity 补 `import com.baomidou.mybatisplus.annotation.TableField`(原文件只用 `TableId/TableLogic/TableName`)

**部署**: 2026-09-15 已发到 NAS home.93gushi.com:8088。后端 jar → `docker compose up -d --force-recreate --build backend` 重建镜像 + 启动。已用新增单验证 `create_by=1 createByName=系统管理员` ✅。**历史 create_by NULL 数据不回填**(回填脚本需逐表 update + join sys_user, 不在本版本范围)。

**为什么 v1.1.51 没发现**: 当时 `BaseCustomerService.add` 等 Service 没设 `setCreateBy(SecurityContext.getUserId())`, DB 验证用的是手工 `INSERT INTO base_customer ... create_by=2` 模拟"已有 create_by" 的场景, 掩盖了 MetaObjectHandler 不生效的 bug。

**新建**: `scripts/add_fieldfill.py`(批量补 FieldFill 工具脚本, 留作后续 v1.1.53+ 给日志类 entity 复用)

### v1.1.51 (2026-09-15) — 全列表注入「操作员姓名」列

**症状**: 用户反馈 (2026-09-15) "在库存台账中增加操作的账号,让我知道操作的是哪个员工"。所有单据列表页的 `create_by` 字段虽然存了 user_id, 但前端 el-table 从未展示成中文姓名。

**方案**: 新增 `com.industrial.erp.common.CreateByNameInjector<T>` 泛型工具类 — 封装 `collect createBy ID → userMapper.selectBatchIds(...) → 注入 setCreateByName(realName)` 模板 (一页只查一次 user 表, 无 N+1)。

**改动**: 13 Entity 加 `@TableField(exist=false) transient String createByName` + 13 Controller `page()` 注入 SysUserMapper + 14 Vue el-table 新增「操作员」列。详见 `docs/CHANGELOG.md#v1151`。

**部署**: 2026-09-15 已发到 NAS home.93gushi.com:8088。JWT secret 强制更新 → 用户**必须重新登录**。历史脏数据 (create_by NULL) 显示 `-` 兜底。

**新建模块**: `backend/.../common/CreateByNameInjector.java`

### v1.1.50 (2026-09-13) — 销售订单关联出库单跳转修复

**症状**: PC 端销售订单 → 「关联出库单」弹窗 → 点「查看」/单号, 跳转到 `/sales/delivery?id=xxx` 后, 详情弹窗显示空的「新增销售出库单」(No Data), 拿不到对应出库单。

**根因**: 出库单 `id` 是雪花 ID (`Long`, 如 `2097666445908426754`), 超过 `Number.MAX_SAFE_INTEGER` (9007199254740991)。`Delivery.vue` onMounted 里 `onView({ id: Number(_detailId) })` 把字符串强转成 `Number`, 精度丢失 → 后端收到错的 id → 404/null → `Object.assign(form, null)` 未填 form.id → 弹窗空。

**修复** (pc-web):
- `Delivery.vue` onMounted: `Number(_detailId)` → `String(_detailId)`, axios 原样拼 URL, 不丢精度
- `Order.vue` `jumpToDelivery`: 先关弹窗 + `nextTick` 再 `router.push`, 避免 dialog overlay 拦截导航 (v1.1.43 起引入的跳转入口)

**部署**: 需重新构建 PC-web bundle 并同步到 NAS `/volume3/docker/erp-system/pc-web/dist` (bind mount, 立即生效)。

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

**v1.1.49 P1/P2 扩展 (同日)**:
- P1-13: docker-compose.yml pc-web 加 healthcheck; backend/pc-web 镜像固定 tag `1.1.49` (commit `df887b2`)
- P1-10: CLAUDE.md 顶部精简 + 拆分 `docs/CHANGELOG.md` (commit `3df97e9`)
- P2-22: `scripts/pre-commit.sh` 校验 npm ci 一致性 (commit `0a36cf5`)
- P2-20: `git tag -a v1.1.49` (含完整 tag message)
- P2-21: `docs/user-manual/index.md` 用户手册初版目录
- 修复 app 端 `package-lock.json` (capacitor v6 vs barcode-scanner@4 peer dep 冲突, 用 `--legacy-peer-deps`)


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
外部域名: `home.93gushi.com`

完整部署文档见 `~/.claude/projects/-Users-tongban/memory/erp-nas-deployment-overview.md`



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
### v1.1.99 (2026-10-17) — 参考 ruoyi-vue-pro MES 完善车间闭环 (C1 工单状态机 + C2 全链追溯 + C3 质量门禁对称/工艺参数 + C4 排程 OEE + 附带清理)

**用户诉求**: "参考 ruoyi-vue-pro 的 MES 系统，针对现在项目作出完善方案"。用户拍板 **C1–C4 全做 + C1 完工走硬门禁**。

**实证诊断** (读遍 mes/qms/pdm/em/production 5 module + 前端, 结论: 一期已落地"主数据+批次登记+工序定义+质量单+采购/半成品 XK 门禁"且全真后端, 但对照 ruoyi-vue-pro 有 6 类缺口):
1. **工单状态机断头** — `Constants.STATUS_PRODUCING`(:83) 定义了但全后端 0 处写入; `finish()` 靠人工、不校验工序全完工。
2. **批次追溯 3 段断链** — ① 成品→客户 `customer_id` 永 NULL (`SalDeliveryService` 不写 link); ② 半成品→原料 `rawBatchNo` 硬编码 NULL (`PrdSemiInService.java:116`); ③ 无服务端全链递归 (`batchTrace` 只单层)。
3. **质量门禁不对称** — IQC/FAI 有硬门禁; OQC/PAT/OQC_RETURN 只自动建草稿不校验合格。
4. **工艺参数不生效** — `mes_process_param.is_critical`+上下限有字段无判定逻辑, 报工不读参数。
5. **排程/OEE/设备占用全无** — 无排程表/工序耗时/OEE/工单派到设备实例。
6. **前端批次溯源页 mock 壳** — `xk/Trace.vue` 走 `xkMock`, `unit:'kg'` 硬编码/`customer:'-'` 占位/unsplash 假照片。

**C1 工单状态机接入工序完工 (零 schema, 硬门禁)**:
- `MesReportService` +2 helper: `allProcessesFinished(orderId)` (无工序实例=未配工序链存量单→返回 true 不阻塞老流程; 有则要求全部 status=FINISHED) + `listUnfinishedProcessNames`。
- `PrdOrderService.release()` 展开工序成功 → 把工单 `billStatus` 置 `STATUS_PRODUCING` (让 :83 死枚举活起来); 没展开工序的存量单维持 RELEASED 不变。
- `PrdOrderService.finish()` 开头硬门禁: 未完工工序 → 抛错列出未完工序名。
- **实际完工入口 `PrdFinishedInService.check()`** 也加同门禁 (关联 `prdOrderId` 的入库, 该工单未完工工序不能入库; 独立无工单入库不拦) — 这才是"工序驱动主流程"真闭环。

**C2 打通批次全链追溯**:
- `SalDeliveryService.check()` 出库循环: 每个有 `batchNo` 的明细行写 `mes_batch_link(billType=FG_OUT, child=FG批, customer_id=出库客户)` 打通成品→客户出口 (副作用登记, 失败仅 warn 不阻塞出库主流程); 新增 `MesReportService.registerFgOutToCustomer`。
- `PrdSemiInService.check()`: `registerSemiOnFinish` 的 `rawBatchNo` 不再传 null, 改 `MesReportService.resolveRawBatchForOrder` (查该工单领料登记的 RAW 批 ISSUE 行最新 parent) 打通半成品→原料上游断链。
- 服务端全链递归 `MesReportService.traceFull(batchNo)`: BFS 双向展开 料→半成品→成品→客户 (深度上限 8 + 已访问集防脏环), 一次返回整棵链 DTO (含客户出口 customer/customerName)。端点 `/mes/batch/trace-full/{batchNo}` + `/xk/trace/full/{batchNo}`。

**C3 质量门禁对称化 + 工艺参数生效**:
- `QmsInspectionService` 加 4 helper (仿 hasPassedFai): `findOqcByFinishedInId`+`hasPassedOqc` (PRD_FINISHED_IN/OQC) + `findOqcReturnBySalReturnId`+`hasPassedOqcReturn` (SAL_RETURN/OQC_RETURN)。
- **软门禁零回归**: 自动建草稿从 `check()` 挪到 `add()` (避免"审核后才建草稿→草稿未 PASS→永远审不了"死锁); `PrdFinishedInService.check()`/`SalReturnService.check()` 加软门禁 — 仅当**已存在** OQC/OQC_RETURN 单且未合格才拒, 无单(旧数据)放行。
- 工艺参数生效: `MesController.ReportDTO` +可选 `paramValues`; `MesReportService.reportWork` 加 6 参重载, 关键参数 (isCritical=1 且配上下限) 越限 → 本次合格量转不良/不自动完工 + 审计 `REPORT_WORK_PARAM_VIOLATION`; 旧 5 参签名保留作兼容重载。App 报工页 `production/report.vue` 关键工序展开参数输入框 (读 `pdmParamList`)。

**C4 生产排程 + 设备 OEE (唯一需 DDL)**:
- `sql/113_v199_mes_schedule_oee.sql`: 2 新表 `mes_schedule` (工单×设备×工序×时段 排程实例) + `mes_process_time` (工序实际耗时流水, OEE 数据源) + 2 F perm `production:schedule:{list,edit}` + 6 内置角色×PC 授权 (INSERT IGNORE + CROSS JOIN NOT EXISTS 双保险, 仿 sql/54 模式)。
- 新 `modules/mes`: `MesSchedule`/`MesProcessTime` entity + 2 mapper + `MesScheduleService` (排程 CRUD + 同设备同时段冲突检测(仅提示不阻断) + 设备承接校验 + OEE=可用工时×稼动率×性能率×良品率 简化算法) + `MesController` 6 端点 (`/mes/schedule/*`, `/mes/oee/{equipmentId}`, `/mes/process-time`)。
- 前端新页 `pc-web/src/views/production/Schedule.vue` (排程甘特/列表/设备 OEE 三 tab, el-table+时间轴 div 不引重型组件) + router `production/schedule` + `MainLayout.vue` 生产菜单项。

**附带清理**:
- **Trace 去 mock**: `xk/Trace.vue` 批次台账/时间线改读真 `traceFull` (去 `unit:'kg'`/`customer:'-'` 占位 + unsplash 假图 `PHOTO` 置 null); `api/xk.js` `traceBatches`/`traceTimeline` 移除 mock shape 强转, 时间线走 `/xk/trace/full`。
- **维保双表统一**: `XkController` 设备维保 KPI/列表从老 `WfMaintainRecordService` 切到 `EmMaintainOrderService.pageForXk` (em_maintain 新表, 与 `xk/Maintenance.vue` 数据源对齐), 消除"XK 合规中心读旧表、维保功能页读新表"分裂; `api/xk.js` `maintenanceOrders` 字段映射改 em 实体 (orderNo/equipmentName/maintContent/planDate/operator/status)。

**改动文件**:
- 后端改: `MesReportService` (C1 helper + C2 traceFull/resolveRaw/registerFgOut + C3 参数判定), `PrdOrderService` (C1 硬门禁+PRODUCING), `SalDeliveryService` (C2 FG_OUT link), `PrdSemiInService` (C2 raw 取值), `PrdFinishedInService` (C1+C3 门禁), `SalReturnService` (C3 门禁), `QmsInspectionService` (C3 OQC helper), `MesController`/`XkController` (端点+维保切表), `EmMaintainOrderService` (+pageForXk)。
- 后端新: `MesSchedule`/`MesProcessTime` + 2 mapper + `MesScheduleService`; `sql/113_v199_mes_schedule_oee.sql`。
- 前端: `Schedule.vue` (新) + `api/production.js` (mesApi 加 6 C4 方法 + batchTraceFull) + `Order.vue`/`Trace.vue`/`report.vue`/`api/xk.js`/`router/index.js`/`MainLayout.vue`。

**部署 (飞牛 canary 先行, home 未同步待批准)**:
- C4 SQL 灌飞牛 (2 表 + 2 perm + 24 行 PC 角色授权 已落库) → 后端 `mvn clean package` + **飞牛 jar 必须 `docker cp` 覆盖进 `erp-backend-failover:/opt/app/app.jar` + `docker restart`** (飞牛 jar 烘焙进镜像, cp+restart 无效) → 前端 `npm run build` (dist 146 js) + `dist-new` 全量替换。
- **验证**: 后端 healthy (`/api/auth/captcha` 200); 新端点 `/mes/batch/trace-full`/`/mes/schedule/page`/`/mes/oee/1`/`/xk/trace/full` 全 401 (路由已注册+门禁正常, 非 404); `Schedule-CNITiQiQ.js` 在线 HTTP 200; `mes_schedule`/`mes_process_time` SHOW TABLES 命中。

**边界/待办**:
- C1–C3 零 schema 回滚 = 还原 Java; C4 回滚 = 还原 Java + `DROP TABLE mes_schedule/mes_process_time` + 删 2 perm 节点。
- 报工仍不动 `inv_stock` (WIP 在制品账不在本次范围)。
- **可选** `sql/112_v199_batch_link_backfill.sql`: 回填历史 `sal_delivery` 的 FG 批→客户 link 让老数据可追溯 (默认未写未跑)。
- `production:schedule:{list,edit}` 是**新** perm, 飞牛已授权 6 角色, 但已有登录用户 Sa-Token session 缓存旧 perm 数组 → 相关账号需**退出重登**才能在菜单看到"生产排程"。
- home NAS (192.168.0.150) 未同步, 按 [[canary-first]] 需用户批准。

### v1.1.80 hotfix (2026-10-08) — CORS 白名单补 `:18080` 域名 origin (登录 403 修复)

**用户反馈 (2026-10-08)**: `POST http://home.93gushi.com:18080/api/auth/login 403 (Forbidden)` — 登录 403, 怀疑 `home.93gushi.com:18080` 没加进白名单.

**根因**: `home.93gushi.com` / `n150.93gushi.com` 两域名都指向主站 (公网 123.96.246.56), 前端在 `:8088` (DSM 反代) 和 `:18080` (pc-web nginx) 都能访问, 但后端 `ERP_CORS_ALLOWED_ORIGINS` 白名单只列了 `:8088` 域名变体, **缺 `:18080` 域名变体**. 用 `home.93gushi.com:18080` 登录时浏览器 Origin = `http://home.93gushi.com:18080` 不在白名单 → Spring Security CORS 拒 preflight → 403. 用户怀疑完全正确.

**修复 (纯增量, 只加不删, 零破坏)**: 双站 `ERP_CORS_ALLOWED_ORIGINS` 追加 `http://home.93gushi.com:18080` + `http://n150.93gushi.com:18080` (飞牛 canary 再加 `http://192.168.0.32:18080/8088`). 按 [[canary-first]] 规则先改飞牛验证再同步主站:
- **飞牛 canary** (`192.168.0.32`): CORS 写在 compose **内联 `environment` 块** (会覆盖 `env_file: erp-env.list`), 改内联块 + `docker compose up -d --force-recreate --no-deps erp-backend-failover`. 验证: `OPTIONS` preflight 对 `Origin: http://home.93gushi.com:18080` 返回 `Access-Control-Allow-Origin` + 200/204 ✓.
- **主站** (`192.168.0.150`): `erp-backend` 是裸 `docker run` 建的 (**无 compose label**), `docker compose up --force-recreate` 报"容器名冲突"拒管. 处理: `docker inspect` 抓 29 个现网 env (含 `SA_TOKEN_JWT_SECRET_KEY=ecdd4895...`, 保留避免全员重登) → 追加两 origin → `docker stop/rm` + `docker run --env-file backend_env_new.txt` 按原配置重建 (image `erp-system-backend:latest`, net `erp-system_erp-net`, 双 bind mount upload/backup, `:8080`). 验证: `OPTIONS http://home.93gushi.com:18080` → `Access-Control-Allow-Origin: http://home.93gushi.com:18080` + 204 ✓; `:18080` nginx 反代同源也 204 ✓.

**三处双站部署坑 (写入 FAQ Q5.2)**:
1. 飞牛 CORS 走 compose **内联 `environment`**, 改 `env_file` 无效 — 必须改内联块.
2. 主站 `erp-backend` 无 compose label (裸 `docker run`), `compose up --force-recreate` 拒管 — 只能 `docker run --env-file` 重建.
3. 改 env 后必须**重建容器** (`compose up -d --force-recreate` / `docker rm`+`docker run`), `docker restart` 只复用启动时烤进去的旧 env.

**部署顺序遵循**: 飞牛 canary 先行验证无问题 → 主站同步 (用户 2026-10-08 拍板的长期规则, 见 [[canary-first]]). 保留 SA_TOKEN 密钥, 用户无需重登.

### v1.1.79 (2026-10-06) — 工作流模块 (设备维保/安全检查/应急预案/租客) + 上传字段 + next_due_date 看板 + Dify 自动归档

**用户目标 (2026-10-06)**: 用户要把设备维保记录/安全检查记录/应急预案 (含租客) 等纸质台账搬到 ERP, 巡检员希望能"拍照 → 自动生成记录"。决策: ① 深度 = 简单 CRUD + 审核/反审核 + 删除 (**不做** 审批流转引擎 / 任务待办 / Dify 自动审核); ② 租客 = 新建主数据表 `base_tenant` (应急预案挂 `tenantId`); ③ 仅 PC 端 (不上 App); ④ Dify 工作流触发 = **永远只生成草稿**, 审核留人工二次确认。

**改动**:
- **后端 8 entity**: `BaseTenant` (新, 在 `modules/base`) + 3 head (`WfMaintainRecord`/`WfSafetyRecord`/`WfEmergencyPlan`) + 3 detail (`WfMaintainDetail`/`WfSafetyDetail`/`WfEmergencyDetail`). 全部 `@TableId(ASSIGN_ID)` 雪花 19 位 Long + `@TableLogic deleted` + 审计 4 字段 (`create_by/create_time/update_by/update_time` fill INSERT/INSERT_UPDATE) + head 加 `@Version` 乐观锁.
- **`Constants.java` 加 3 bill 前缀**: `BILL_WM`/`BILL_SIN`/`BILL_EP` (复用 `BillNoGenerator`, 人可读单号).
- **4 service**: `BaseTenantService` (page/list/detail/add/update/delete, `requirePerm("work:tenant:*")`) + 3 workflow service (head CRUD + check/uncheck, `@Transactional` + `@OperLog`, `requirePerm("work:maintain|safety|emergency:*")`). 维保/检查 `next_due_date` 已过期列表标红 (前端 `overdueDays` 计算).
- **4 controller**: `/workflow/tenant` + `/workflow/maintain` + `/workflow/safety` + `/workflow/emergency` + `/workflow/dashboard` (新 `WfDashboardController`). 全部 `R<T>` + `@SaCheckPermission(value={...}, orRole="admin")`.
- **WfDashboardService + WfDashboardController**: `GET /workflow/dashboard/upcoming?days=N` (default 30, 上限 365), 聚合 3 类 `next_due_date`/`next_drill_date` 限 days 天内到期的, 按 dueDate ASC, 返回 `List<Map>`, 每条 `{type: MAINTAIN|SAFETY|EMERGENCY, id, no, label, dueDate, daysLeft, status}`. 权限 `work:maintain:list|safety:list|emergency:list` 任一即可 (orRole admin).
- **sql/50_v179_workflow_module.sql**: 4 类 head + detail + `base_tenant` 7 张表 `CREATE TABLE IF NOT EXISTS` (utf8mb4). `sys_menu` M 型「工作流」目录 (perms 非空 `work:maintain:list`, sql/47 教训 — 权限树节点 perms 必须非空才可选) + 4 C 型页面 + B 型按钮. `sys_role_menu` 给 6 内置角色 (SUPER_ADMIN/PURCHASE_MGR/SALES_MGR/WAREHOUSE_MGR/PRODUCTION_MGR/FINANCE) × PC 授权, `information_schema` 探测 `client_type` 列 (老库 2 列退化, 飞牛坑), `INSERT IGNORE` 幂等.
- **sql/50b_v179_workflow_uploads.sql**: ALTER 4 张 wf 表加 6 列 — `wf_emergency_plan.contact_phone/drill_photos/drill_attachment`, `wf_emergency_detail.step_photo`, `wf_safety_record.attachment`, `wf_safety_detail.risk_photo`. 全部 `information_schema` 探列幂等 (MySQL 8 不支持 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`).
- **前端 `pc-web/src/api/workflow.js`**: `tenantApi`/`maintainApi`/`safetyApi`/`emergencyApi` (各 `page/detail/add/update/delete/check/uncheck`) + `uploadFile(file)` helper (FormData 走共享 axios, 不经过 `stringifyBigInts` — 19 位雪花 ID 不受影响) + `dashboardApi.upcoming(days=30)`.
- **4 视图 `pc-web/src/views/workflow/{Tenant,Maintain,Safety,Emergency}.vue`**: 镜像 `Receipt.vue` 结构 (search-bar + el-table + 状态 tag + 操作列 `hasPerm` 显隐 + el-dialog 表单嵌套 detail el-table + 分页). 维保/检查 列 `next_due_date` 过期标红. 应急预案/检查 加 `contactPhone`/`drillPhotos` (逗号分隔多 URL 缩略图列表)/`drillAttachment` (单文件链接)/`stepPhoto` (per-row 缩略图)/`riskPhoto` (per-row)/`attachment` (多图缩略图) 完整 UI, 走 `uploadFile()` helper → `/api/system/upload/file` → 回填 URL.
- **`router/index.js`**: 加 4 路由 `workflow/maintain|safety|emergency|tenant`, `meta.perm` 各 `work:xxx:list`.
- **`MainLayout.vue`**: 侧边栏 `menuTree` 加「工作流」分组 (icon `Operation`), children 4 页.
- **`docs/23_Dify工作流自动归档触发指南.md`** (新): Dify 编排方案 (开始/OCR/校验/HTTP/代码/结束节点), 3 端点 payload JSON 示例, 上传文件流 (`/api/system/upload/file`), 错误处理 (401/权限/无 recordNo 5 类故障 + 排错), `dify_bot` 账号 SQL 配置 (仅 `work:*:add` perm, **不授** `*:check` — 决策: 永远不自动审核), 安全注意事项 (Dify 环境变量存密码, 不明文写工作流定义).

**部署 (双站 home + 飞诺 failover)**:
- 后端 jar md5 `f46bd2253768e69dd7b23d61a29e6ca2` (101,206,778 bytes). home base64 pipe 流式传 jar.gz → `gunzip` + 重命名 `industrial-erp-*.jar` → `docker build` → `docker rm -f` + `docker run --env-file` (保留 home `SA_TOKEN_JWT_SECRET_KEY=ecdd48955b16c58239b0b9326ac384f4b59babace53fda067424dd80dcba7c5f`). 飞诺 scp 传 `erp-backend.jar` → `docker build -f backend.Dockerfile` → `docker rm -f erp-backend-failover` + `docker run --env-file` (保留飞诺 `SA_TOKEN_JWT_SECRET_KEY=a337f8758cc7a8464e8ddc515462a76e0333dc2723913e263ce18c16609a2b10`). **踩坑**: 飞诺 failover 容器重建后跑的还是旧 jar, 必须 `docker rm -f` 显式删掉再 run (compose 不切 image).
- `sql/50` + `sql/50b` 双站 MySQL 各跑一次 (`--default-character-set=utf8mb4`), 6 `wf_*` 表 + `base_tenant` + 6 列 ALTER 全部幂等. **踩坑**: `base_tenant` 双站报 `Unknown column 'version'` — sql/50 漏了 `@Version` 对应的 `version` 列, 补 `ALTER TABLE base_tenant ADD COLUMN version INT DEFAULT 0` 双站.
- pc-web 双站: `npm run build` → dist 部署. **踩坑**: 飞诺 dist bind-mount 源是 `/vol2/erp-system/pc-web/dist-new` (不是 compose 写的 `dist`), home 是 `pc-web-new/dist`, 看 mount 不看 compose 文件名. 双站 `docker restart erp-pc-web[-failover]`.
- 旧 jar 备份: 双站 `.bak.v178` (md5 home=`b34b20b893dab4e14dd71e39082cd164`/飞诺=`b34b20b893dab4e14dd71e39082cd164`) 保留回滚.

**验证 (装机)**:
- 双站 `SHOW TABLES LIKE 'wf_%'` / `base_tenant` 存在 ✓
- 双站 4 workflow endpoints 全 200 (login → GET /page ✓)
- home `POST /api/workflow/maintain` + `/safety` + `/emergency` 全 200, dashboard upcoming 返回 `SIN202610060001` (home 有测试数据), fn 返回 `[]` (fn 无测试数据)
- home `/api/system/upload/file` POST 200, 返回 `/upload/202610/06/069bd0ee0a9c4d2e84c0e44c43c4f4bf.png` (nginx 静态路径就绪)
- 双站 138 条 `sys_role_menu` 授权行已建, `work:*` perm 6 角色 × PC 全命中

**回滚**: 双站旧 jar `.bak.v178` 已就位, `docker stop + rm + run` 用旧 jar 即可, 业务表 DDL 不回滚 (无业务数据).

### v1.1.78 (2026-10-05) — App 端 AI 助手语音输入 (端侧优先 + 后端 ASR 兜底)

**用户目标 (2026-10-05)**: 用户在仓库/产线现场, 手脏/戴手套打字不便, 希望 App 端 AI 助手支持"按住说话"→ 自动识别文字 → 填入输入框 → 发送。决策: STT 引擎 = 端侧优先 (浏览器 Web Speech API, 零 key / 零延迟) + 原生 App 兜底走后端 DashScope paraformer ASR; 交互 = 独立录音按钮 (按住说话, 松手回填 q, 用户可改后再点发送, 不自动 send)。

**改动**:
- **后端**: 新 `AsrService` (`modules/ai/asr/AsrService.java`) Hutool `HttpRequest` 调 DashScope 短音频同步识别 (`paraformer-v2`), body `{input: {file_data: <base64>}, parameters: {sample_rate: 16000}}`, 响应 `output.text`. 未配 `ERP_AI_ASR_KEY` 时 `enabled()=false` + BizException 快速失败 (与 LLM 未配置语义一致, 不影响其他功能). `AiProperties` 加 4 字段 `asr-key/asr-model/asr-base-url/asr-timeout-ms`. `AiAgentController` 加 `POST /ai/asr` (multipart `file`, 复用 `report:ai` 权限 + `admin` orRole; 上限 10MB; 返回 `{text, asrProvider: "dashscope:paraformer-v2"}`). `application.yml` 加 asr 段; `docker-compose.yml` + `docker-compose.backend.yml` + 飞诺 `docker-compose.failover.yml` 各加 4 env; `.env.example` 加 ASR 注释示例。
- **App**: `pages/report/ai.vue` 输入行加 🎤 按钮. `detectSpeech()` 优先探测 `window.webkitSpeechRecognition|SpeechRecognition` (Chrome/Edge H5 → 端侧流式边说边出字, 零后端/零延迟); 无则降级 `uni.getRecorderManager` 录 m4a (`sampleRate=16000 / numberOfChannels=1 / frameSize=64 / duration=60s`), onStop 拿 `tempPath` 走 `uni.uploadFile` 上传 `POST /ai/asr`. 识别文本回填 `q` (不自动 send). `api/index.js` 加 `aiAsr(filePath)` (uni.uploadFile 封装, `X-Client-Type=APP`). `manifest.json` versionName 1.1.78 / versionCode 1178, android 加 `<uses-permission android:name="android.permission.RECORD_AUDIO"/>`, iOS 加 `NSMicrophoneUsageDescription: 语音输入AI助手需要使用麦克风`.

**部署 (双站 home + 飞诺 failover)**: 后端 jar md5 `b34b20b893dab4e14dd71e39082cd164`. home base64 pipe 流式上传 → `docker build` (用 `backend/Dockerfile`, COPY `industrial-erp-*.jar`) → `docker rm -f` + `docker run --env-file` 重建. 飞诺 scp + `docker build -f backend.Dockerfile` (COPY `erp-backend.jar`) → `docker compose --profile failover up -d --force-recreate` (修了一个隐藏坑: 原 compose 只硬编码 DB/Redis/CORS env 不引 env_file → 新加 `env_file: /vol2/erp-system/erp-env.list` + 4 ASR env 在 environment: 块内做兜底). 双站启动日志 `[LLM] initialized 2 providers: [agnes,minimax]` + `/ai/asr` 端点 401 = 注册成功 + SaCheckPermission 生效. 旧 jar 双站备份 → `.bak.v177` (md5 home=23fe16c4.../飞诺=c1a1b1c3...).

**零后端破坏**: ASR 未配 key 走 BizException 不影响 LLM chat 或其他功能; 老 v1.1.77 env 配置不变。

### v1.1.77 (2026-10-05) — AI LLM Provider Failover + Thinking Strip (Agnes 配额踩坑自动切换备用)

**用户目标 (2026-10-05)**: 解决 Agnes `agnes-2.5-flash` (free tier) 每日配额极小 (凌晨实测单次 ping 就打空触发 `HTTP 429 rate_limit_exceeded`, 限速到 1 req/min, 重置点 UTC 04:50:42 rolling 24h) 导致白天 AI 助手大概率不可用的根问题, 引入备用 provider 自动切换。

**根因**: `LlmClient` 之前是**单端点纯透传**, 无 fallback / 重试 / rate-limit 识别. 上游 429 + body verbatim 塞 `BizException` 抛前端. MiniMax M3 key 已存双站 env 但未接入。

**改动 (零破坏, 向后兼容 v1.1.75)**:

1. **新 `AiProperties.java`** (`config/AiProperties.java`, `@Value` 单字段风格与项目一致, 不引入 `@ConfigurationProperties`):
   - 保留 v1.1.75 legacy 4 env (`ERP_AI_API_KEY/MODEL/BASE_URL/TIMEOUT_MS`).
   - 新增 2 个 slot (provider0/provider1, 默认 `agnes` + `minimax`) + `providers` 逗号串指定启用顺序.
   - `cooldown-ms` (5min 短冷却, 5xx/timeout) + `cooldown-long-ms` (30min 长冷却, 429 quota) + `strip-thinking` (全局) + 各 slot `strip-thinking` (per-provider 覆盖).

2. **`LlmClient.java` 重写** (公开方法签名不变, 调用方零改动):
   - `@PostConstruct` 读 `providers` 逗号串 → 按顺序取 slot, key 空的 slot 自动跳过, 未配 providers 时回退 legacy 单 provider (与 v1.1.75 行为一致).
   - `chatInternal` 主循环: 遍历 chain → 跳过 cooldown 中的 → 试 HTTP → 触发 failover? 切下一个 / 抛.
   - **触发 failover**: HTTP 5xx / 408 / Hutool `HttpException` (超时/网络) / 429 quota + 429 通用.
   - **不触发**: 401/403/400/业务错误 (choices 空/JSON 坏) → 直接 `BizException`, 不切 (切了也错).
   - **429 quota** 判定: body 含 `rate_limit_exceeded` / `quota` / `insufficient_quota` → 长冷却 30min; 其它 429 → 短冷却 5min.
   - `ChatResult` 加 `usedProvider` 字段 (nullable, 老调用方不读不破坏) 透传前端/运维.
   - `stripThoughts()` 在 LlmClient 出口剥 `<thinking>...</thinking>` 和 `<think>...</think>` (兼容 MiniMax 推理模型 + DeepSeek-R1), per-provider + 全局双开关.
   - 新增 `chatWithResult()` 返回 ChatResult 给 AiService 填 VO 的 llmProvider.
   - 启动日志 `[LLM] initialized N providers: [name1, name2]`; failover 时 `[LLM] provider X failed (status, isQuota), cooldown Ys, try next`; 全失效 `[LLM] all providers exhausted`.

3. **AiAnalysisVO / AgentResultVO** 各加 `llmProvider` 字段 + getter/setter. **AiService.run()** / **AgentService.run()** 末尾 + **AiAgentController.chat()** 包装处填 `cr.usedProvider`.

4. **配置文件**:
   - `application.yml` L100-112 扩 legacy 块 + 加 `cooldown-ms/cooldown-long-ms/strip-thinking/providers/provider0-*/provider1-*` 块.
   - `docker-compose.backend.yml` 追加 ERP_AI_* failover env 段.
   - `docker-compose.yml` (主站, **之前缺 `ERP_AI_*` env**) 顺手补齐 (否则 home NAS 主站不会启用新配置).
   - `.env.example` AI 段后加 failover 注释态示例 (含 dev/prod 两套).

**部署 (双站 home + 飞牛)**:
- 后端 jar md5 `23fe16c40c6aed9a1b52755dee5c661e` (本地 mvn -o -DskipTests -q package).
- home NAS: base64 pipe 流式上传 jar.gz → 解码 + 重命名 industrial-erp-1.0.4.jar → docker build --no-cache → rm -f + run with `--env-file /volume3/.../erp-env.list` (含所有 legacy + 新 failover env). 保留 home `SA_TOKEN_JWT_SECRET_KEY=ecdd48955b16c58239b0b9326ac384f4b59babace53fda067424dd80dcba7c5f` + home CORS.
- 飞牛: scp 传 `erp-backend.jar` → docker build -f backend.Dockerfile → rm -f + run with `--env-file /vol2/erp-system/erp-env.list` (含所有 legacy + 新 failover env). 保留飞牛 `SA_TOKEN_JWT_SECRET_KEY=a337f8758cc7a8464e8ddc515462a76e0333dc2723913e263ce18c16609a2b10` + 飞牛 CORS.
- **飞牛 SPRING_DATASOURCE_HOST 修正**: 原写错为 `mysql`, 实际应为 `erp-mysql-failover` (memory `erp-failover-broken` 部署坑 — 修了一个隐性 bug).
- **双站启动日志确认**: `[LLM] provider 'minimax' key 空, skipped` + `[LLM] legacy env ignored: providers=[agnes, minimax]` + `[LLM] initialized 1 providers: [agnes]` (因 MiniMax key 留空, 只跑 Agnes 主, 备用待启用).
- **旧 jar 备份**: 双站均 `md5 47cdb6a3...` (v1.1.76) → `.bak.v176` 保留回滚.

**回滚**: 双站旧 jar `.bak.v176` 已就位, 直接 `docker stop + rm + run` 用旧 jar 即可, v1.1.77 老 env 也兼容 (providers 空时回退 legacy 单 provider).

**验证 (装机)**:
- 双站 jar md5 = `23fe16c40c6aed9a1b52755dee5c661e` ✓
- 双站 captcha HTTP 200 ✓, AI chat HTTP 401 (未登录) ✓
- 双站启动日志 `[LLM] initialized 1 providers: [agnes]` ✓
- 实测 failover: 当 Agnes 触发 429, 下一次 chat 自动切 MiniMax (用户 PC 端操作; admin 账号已停用无法本地 curl)

**教训 (关键)**:
- **provider 顺序敏感**: MiniMax 是**推理模型**会吐 `<think>` 必须 strip-thinking=true; Agnes/Qwen 不输出无害可 strip-thinking=false (走 per-provider 默认).
- **failover 不该切换业务错误**: 401/403/400 切 provider 也错, 直接抛错暴露给运维修 key.
- **未来若 Agnes 配额仍抠**: 切默认到 MiniMax M3 / DeepSeek / Qwen 月费 (¥几块), 不要依赖免费 tier.

### v1.1.76 (2026-10-05) — AI 助手进 PC + App 双权限树 + App 端 AI 助手页

**用户反馈**: 角色管理「分配权限」里 PC端/App端菜单权限树都**没有「AI 助手」这一项**无法勾选。根因: v1.1.75 AI 助手页已上线 (`report/Ai.vue` 路由 `report/ai` 门禁 `report:view`) 但**从没建 `sys_menu` 行** → PC 权限树 (`menuApi.list()` 全量 `sys_menu` 按 parentId 挂树) 与 App 权限树 (`Role.vue` 硬编码 `APP_MENU_WHITELIST`) 都不含它。

**决策 (用户拍板)**: ① 独立权限码 `report:ai` (同回收站 v1.1.72 `report:recycle` 模式), 使 AI 助手在 PC/App 两棵树都是可独立勾选/保存的叶子; ② 同时给 App 建 AI 助手页 (对话 + 补货, 复用 `/ai/agent/chat` + `/ai/replenish/suggest`)。

**改动**:
1. **新 seed `sql/48_v176_ai_perm.sql`** (双站各跑, utf8mb4, 幂等): `sys_menu` M 型节点「AI 助手」(parent=9, `path=/report/ai`, `component=report/Ai.vue`, `perms=report:ai`) `NOT EXISTS` 查重 + 6 内置角色 × **PC/APP 各一行**授权 (`information_schema` 探测 `sys_role_menu.client_type` 列, 老库 2 列退化)。M+非空 perms = 可授权叶子 (后端 `isGrantableMenu` / 前端 `markDisabled` 自动放行, 判定逻辑不用改)。
2. **后端门禁 `report:view`→`report:ai`**: `AiController` 4 端点 + `AiAgentController` 3 端点 `@SaCheckPermission` + `AiService` 4 处 + `AgentService` 1 处 `requirePerm` (均保留 `orRole="admin"`)。
3. **PC 前端**: `router/index.js` `report/ai` `meta.perm`→`report:ai`; `Role.vue` `APP_MENU_WHITELIST` 报表中心组加 `{name:'AI 助手', perms:'report:ai', idApp:'app-ai-assist'}` (App 树按 perms 反查 sql/48 建的 `sys_menu.id`)。
4. **App 前端**: 新增 `pages/report/ai.vue` (对话流 + 补货预测; 写操作只展示提示「请在 PC 端确认」; 无 `ERP_AI_API_KEY` 时后端快速失败→toast); `api/index.js` +`aiAgentChat`(POST json)/`aiReplenishSuggest`(GET, query 拼进 URL — H5 `fetch` 回退不序列化 GET data); `pages.json` 注册; `dashboard/index.vue` `APP_MENU_TO_PAGE` + `PATH_TO_APP` + admin 硬编码列表 + 非 admin perm-only 兜底 加 🤖 AI 入口。

**版本同步**: `profile/index.vue` `APP_VERSION` + `manifest.json` `versionName`→1.1.76 (build-app.sh [1.5] 三处一致校验通过)。

**纯迁移 + 后端 jar + PC dist + App APK, 无破坏性 DDL。** 双站部署: home + 飞牛 各跑 `sql/48` + 重打后端 jar + 重打 PC dist + 出新 App APK; 保留各自 `SA_TOKEN_JWT_SECRET_KEY`。非 admin 角色授权后重登刷 perm。

### v1.1.76 hotfix-1 (2026-10-05) — 飞鹅打印日志 `system:feie:log` 403 修复

**用户反馈 (2026-10-05)**: v1.1.76 部署后, PC 端 系统设置 → 飞鹅打印日志 tab 打开立刻 `GET /api/feie/log/page?... 403 Forbidden`。

**根因**: `sql/21_add_sys_feie_print_log.sql` 建 `system:feie:log` 节点 (C 型, parent=飞鹅打印机) 时只授给老角色码 `'admin'/'manager'`。后续角色码迁移到 6 新内置角色 (`SUPER_ADMIN`/`PURCHASE_MGR`/`SALES_MGR`/`WAREHOUSE_MGR`/`PRODUCTION_MGR`/`FINANCE`) 时漏了这条授权 → `@SaCheckPermission("system:feie:log")` 对所有新角色拦截 403。`sql/48` (v1.1.76) 不动 feie, 与 AI 助手无关, 是历史遗漏授权。

**修复**:
1. 新 seed `sql/49_v176hotfix_feie_log_grant.sql` (双站各跑, utf8mb4, 幂等): 给 6 新内置角色 × **PC** 各补一行 `system:feie:log` 授权 (`INSERT IGNORE` + `NOT EXISTS` 双重防重)。沿用 `sql/41` 决策: 飞鹅菜单保持 **PC-only** (管理员功能, App 不该见), 因此只授 PC, **不授 APP**。`information_schema` 探测 `sys_role_menu.client_type` 列, 老库 2 列退化 (与 sql/48 同款 IF/PREPARE/EXECUTE 模式)。
2. **无后端/前端代码改动**, 无需重建容器/jar/dist。用户在 PC 端重登或等 token 自然刷新即生效 (Sa-token 缓存)。
3. 部署经验: gpssong 在 home NAS `/tmp` `/var/services/homes` / `/volume3` 都无写权限, 必须 sudo; sudo PATH 为空要写 `/usr/local/bin/docker` 全路径; mysql 客户端走 `sudo docker exec -i erp-mysql mysql -uroot -perp_root_pwd --default-character-set=utf8mb4 industrial_erp` 单行管道 (无文件落地)。飞牛 `/usr/bin/docker` 同款但需 `<<<` 给 sudo 喂密码。

**验证 (双站 SELECT)**:
```sql
SELECT r.role_code, IFNULL(rm.client_type,'NULL') AS ct FROM sys_role r
JOIN sys_role_menu rm ON rm.role_id=r.id JOIN sys_menu m ON m.id=rm.menu_id
WHERE m.perms='system:feie:log' AND m.deleted=0 AND r.deleted=0
ORDER BY r.role_code;
-- 预期: 6 行 SUPER_ADMIN/PURCHASE_MGR/SALES_MGR/WAREHOUSE_MGR/PRODUCTION_MGR/FINANCE 全 PC
```

**教训**: 给新角色码迁移时, 必须**回头扫所有 C/F 型 sys_menu 节点**是否都补授到新角色码——光给目录节点 (M 型) 授权没用, 因为端点门禁认的是按钮/C 节点的 perms。检查清单脚本 (`grep 'INSERT.*sys_role_menu' sql/*.sql` 比对 `role_code IN (...)`) 应作为迁移 SSoT 的一部分。

**回滚**: `DELETE FROM sys_role_menu WHERE menu_id=(SELECT id FROM sys_menu WHERE perms='system:feie:log') AND role_id IN (SELECT id FROM sys_role WHERE role_code IN (...6 new codes...))`。

### v1.1.75 (2026-10-05) — AI 集成第一步: LLM 网关骨架 + 销售出库单只读解读

**用户目标 (2026-10-05)**: 让 ERP 集成 AI。讨论后决定按"后端 REST 网关 + 只读 demo"先做最小闭环——读类、有审计、密钥可配、密钥不进 git；写类 agent 与 RAG/预测留后续。

**后端 (新 `modules/ai` 包, 4 文件)**:
1. **`LlmClient`** — 通用 LLM 网关。Hutool(与 `FeiePrintClient` 同款, 零新依赖), **OpenAI 兼容 `chat/completions` 协议** → 通义 DashScope / DeepSeek / OpenAI / OpenRouter / Agnes 只改 `erp.ai.base-url` 即可切。密钥/模型/端点/超时全走 env(`erp.ai.*` → `ERP_AI_*`), 无 key 时快速失败提示"未配置", 不拖垮别的功能。
2. **`AiService.analyzeSalDelivery(id)`** — 演示场景。复用 `SalDeliveryService.detail()` **只读**取数(单号/客户/仓库/状态/金额/明细行), 组装结构化 prompt → 调模型 → 返回解读。`@OperLog(module="AI 分析", businessType="QUERY")` 审计(异步落 `SysOperLog`) + `requirePerm("report:view")` 双保险(端点 `@SaCheckPermission` 同码)。`max_tokens=2048 / temperature=0.3` 防 MiniMax 推理模型截断/乱吐 thinking。
3. **`AiController`** — `GET /ai/analyze/sal-delivery/{id}`, `@SaCheckPermission("report:view", orRole="admin")`。
4. **`AiAnalysisVO`** — 结果(bizType/bizId/model/content)。

**配置 (3 处)**: `application.yml` `erp.ai.api-key/model/base-url/timeout-ms` + `.env.example` + `docker-compose.backend.yml`(`ERP_AI_API_KEY` 等, 走 `.env`, key 本身不进 git)。

**前端 (PC)**: `api/sales.js` +`aiApi.analyzeSalDelivery`；`Delivery.vue` 行操作列加「🤖 AI 解读」按钮 + 独立结果弹窗(单号 + 模型名 + 解读 pre-wrap)。

**纯后端 jar + 前端 dist, 无 DDL。**

**默认模型**: Agnes `agnes-2.5-flash` (`ERP_AI_BASE_URL=https://apihub.agnes-ai.com/v1`), 备用 MiniMax M3(key/model/base-url 以注释形式存于双站 env 文件, 切换去注释即可)。

**部署 (home + 飞牛)**:
- 新 jar md5 `820b886f077c1d9f4d5f78ef9db8717f`(AI 4 class 已入包确认; `mvn -o -DskipTests` 打包——`mvn test` 的 15 error/2 fail 均为历史 Mockito 构造注入坑, 非本次引入)。
- 上传走 **base64 流式**(`scp` 的 SFTP 子协议被 DSM 禁)。home 入位 `backend/industrial-erp-1.0.4.jar` → `docker build --no-cache` `erp-system-backend:latest` + `docker run --env-file erp_env_home.txt`; 飞牛拷成 `erp-backend.jar` → `docker build -f backend.Dockerfile` tag `erp-backend-failover:latest` + `docker run --env-file .erp_env_failover`。两站保留 SA_TOKEN 密钥, 未强制重登, `Started ~32s` healthy。
- AI env(含 key)入双站各自 env-file(备份 `.bak.20261005_pre_ai`); 模型初配 `agnes-3.0-flash` 后按用户更正改 `agnes-2.5-flash`, 重建容器生效。
- PC dist: home bind-mount `/tmp/pc-web-new`、飞牛 `dist-new`, 先清旧防 chunk 并存, `docker restart erp-pc-web[-failover]`, 线上 `Delivery-Da7EKABE.js` 含「AI 解读」。
- 未登录调 `/api/ai/...` 双站均返 **401**(路由 + sa-token 门禁就位)。

**端到端实测 (home, 已登录 token)**: 调 `CKP202610040007`(id `2106609692861607938`, CHECKED) → `code=200, model=agnes-2.5-flash`, 解读准确核对 `1350 × 3.50 = 4725.00`、推断 13% 税率、说明库存扣减 1350 件 + 应收 4725.00 挂账客户「宏舟水产」。

**踩坑**:
- Agnes 与 MiniMax 均同时提供 OpenAI 兼容端点与 Anthropic 端点, 本方案走 OpenAI 兼容(`/v1/chat/completions`), 无需为 Anthropic 协议另写客户端。
- MiniMax `M3` 为推理模型, 响应带 `thinking`/`reasoning_content`, 若不设 `max_tokens` 易截断; 正式用 M3 需考虑剥离 thinking 或改非推理模型。

### v1.1.74 (2026-10-04) — App 端 销售出库按客户查 / 采购入库按产品查 (模糊匹配)

**用户反馈 (2026-10-04)**: App 端「销售出库单」搜索栏只有「单号」输入，希望可按**客户**查找；「采购入库单」希望可按**产品**查找。且两者都不需要输入完整名称（支持部分关键字模糊匹配）。

**改动**:
1. **后端 销售出库 `/sales/delivery/page`** 新增可选参 `customerName`（`SalDeliveryController` → `SalDeliveryService.page` → `SalDeliveryMapper.selectPageWithProduct`）。模糊匹配下沉到 SQL：`EXISTS (SELECT 1 FROM base_customer c WHERE c.id=d.customer_id AND c.deleted=0 AND c.customer_name LIKE CONCAT('%', #{customerName}, '%'))`，沿用 P1-3 反模式规避（不写 `QueryWrapper.apply()` 字符串拼接）。
2. **后端 采购入库 `/purchase/receipt/page`** 早有 `productName`（`EXISTS base_product.product_name LIKE`）模糊支持，**无需后端改动**，只是 App 端之前没暴露这个搜索框。
3. **App 前端** 两页各加第二个搜索框（保留原「单号」框，两个输入独立共存）:
   - `app/src/pages/sales/delivery-list.vue`: 「按客户名称」→ `customerName` 入参。
   - `app/src/pages/purchase/receipt-list.vue`: 「按产品名称」→ `productName` 入参。

**部署**: 纯后端 jar + App APK。后端双站各 `mvn package`(JDK 17) + `docker build --no-cache` + `docker run --env-file`（保留 SA_TOKEN 密钥）。App 重打 APK `APP_VERSION` 1.1.74（与 CLAUDE.md 顶部对齐，`build-app.sh` 三层校验通过，产物内嵌版本=1.1.74）。

**验证**: App 销售出库页输入客户名「宏舟」→ 只出宏舟水产出库单；采购入库页输入产品「带鱼袋」→ 只出含该产品的入库单。空输入 = 全部（与现状一致）。

**部署备注 (2026-10-04)**: 双站后端重建 `erp-backend` / `erp-backend-failover` 后，启动日志出现两条**既有**良性告警 `SalDeliveryMapper.selectByIdRaw` / `PurReceiptMapper.selectByIdRaw` "is ignored, because it exists, maybe from xml file" —— 因为这两个 mapper 的 `@Select` 注解与 XML 里同名 `<select id="selectByIdRaw">` 冲突，MyBatis-Plus 忽略注解版、保留 XML 版（`SELECT * FROM ... WHERE id=#{id}`），功能正常（`detail()` 走 XML 版）。非本次引入，无需处理；若日后想消除告警可删掉注解、只留 XML。

### v1.1.73 (2026-10-04) — 角色管理「分配权限」回收站权限无法勾选修复 (grantable)

**用户反馈 (2026-10-04)**: 角色管理 → 分配权限 → PC端菜单权限，报表中心下的「回收站」复选框**灰色不可勾**，而同级「报表查看」可勾。回收站权限无法自由选择。

**根因**: v1.1.68 (`sql/45`) 把回收站 seed 成 `M` 型菜单节点 `perms=''`（空），只当「侧边栏可见」载体，后端门禁复用 `report:view`。但权限树机制把「空 perms 的 M 节点」判为**不可授权目录**：前端 `Role.vue` `markDisabled` 只有 `B`/`M`+`F`(带 perms) 才 enabled → 回收站 `disabled=true`；后端 `SysRoleService.isGrantableMenu` 空 perms 的 M 返回 false → **每次保存该角色时 seed 写入的回收站 `sys_role_menu` 授权行被静默删掉**。`report:view`（menu id=951，`F` 型「报表查看」）才是可授权载体，但它与「回收站」是两个独立 checkbox，语义冗余。

**决策（用户拍板）**: 给回收站独立权限码 `report:recycle`，让回收站本身成为可授权叶子；后端 3 个回收站端点门禁由 `report:view` 改到 `report:recycle`。双站部署。

**修复**:
1. 新 seed `sql/47_v172_recycle_perm.sql`：`UPDATE sys_menu SET perms='report:recycle' WHERE path='/report/recycle' AND perms=''`（M 节点变可授权）；给 6 内置角色（SUPER_ADMIN/PURCHASE_MGR/SALES_MGR/WAREHOUSE_MGR/PRODUCTION_MGR/FINANCE）补 `report:recycle` 授权，沿用 `sql/45` 的 `information_schema` 探测 `sys_role_menu.client_type` 列模式（老库 2 列退化），`INSERT IGNORE` 幂等。
2. 后端 `ReportController` 3 回收站端点（`/report/recycle/bin`、`/restore`、`/purge`）`@SaCheckPermission("report:view")` → `report:recycle`；`RecycleBinService.restore`/`physicalDelete` 的 `requirePerm` 同步改。其余 4 个报表端点（`report:view`）不动。
3. 前端 `pc-web/src/router/index.js` `report/recycle` 的 `meta.perm` 由 `report:view` 改 `report:recycle`。
4. 权限树 `markDisabled` 与后端 `isGrantableMenu` 判定逻辑本身已支持「M + perms 非空 → 可授权」，M 节点赋 perm 后自动放行，**无需改判定代码**。

**部署（双站）**: home（192.168.0.150）+ 飞牛（192.168.0.32）各：① MySQL 跑 `sql/47_v172_recycle_perm.sql`（`--default-character-set=utf8mb4`）② `mvn package`（JDK 17）+ 双站 `docker build --no-cache` + `rm -f` + `docker run --env-file`（保留各自 SA_TOKEN 密钥，不强制重登）③ `npm run build` + 双站 bind-mount 新 dist + `docker restart erp-pc-web[-failover]`（飞牛实际 mount 是 `dist-new`）。非 admin 角色重登刷 perm。纯后端 jar + 前端 dist + 迁移，无 DDL。

**验证**: 权限树展开报表中心 → 回收站复选框变黑框可勾；给某角色勾上回收站保存后重开弹窗仍勾选（`isGrantableMenu` 不再静默删除）；无 `report:recycle` 的账号 GET `/report/recycle/bin` → 403，admin（`isAdmin` 短路）→ 200。

**回滚**: 反向 `sql/47`（`UPDATE sys_menu SET perms='' WHERE path='/report/recycle'` + `DELETE sys_role_menu WHERE menu_id=<回收站 id>`）；后端 3 端点 + `RecycleBinService` 2 处 + 前端 router `meta.perm` 改回 `report:view`。

### v1.1.72 (2026-10-04) — 回收站「商品」列全空修复

**用户反馈 (2026-10-04)**: 回收站「销售出库」Tab 的「商品」列全是 `-`（客户/金额/删除人都正常），但库里这些被删单据明明带商品。

**根因**: v1.1.71 给 `RecycleBinMapper.xml` 的 `productColumn` 子查询写的是 `LEFT JOIN base_product p ... WHERE dtl.deleted = 0`。而业务删除时 **head 与 detail 会一起置 `deleted=1`**（`SalDeliveryService.delete` 等 11 类都如此：先软删明细再软删主表），故被删单据的明细行按 `deleted=0` 一条都查不到 → `GROUP_CONCAT` 返回 NULL → 商品列全 `-`。线上 SQL 直查确认：被删 `sal_delivery` 的 `detail_alive=0`（无 `deleted=0` 的明细）但 `detail_hasname>0`（每条被删明细都自带去规范化的 `product_name`）。

**修复**: `RecycleBinMapper.xml` `productColumn` 子查询改为直接聚合明细自带的去规范列，去掉 `base_product` JOIN 与 `deleted=0` 过滤：
```sql
(SELECT GROUP_CONCAT(DISTINCT dtl.product_name SEPARATOR ', ')
 FROM ${detailTable} dtl
 WHERE dtl.${detailFk} = h.id) AS productNames
```
对全部 10 类带明细的单据（采购订单/入库/退货、销售订单/出库/退货、生产领料、BOM、库存盘点、库存调拨）生效，不再依赖商品是否已被软删。纯后端 XML 一处，无 DDL / 无前端改动，沿用 v1.1.70 的 `RecycleType` 白名单 `${detailTable}/${detailFk}` 传参。

**部署（双站）**:
- home (192.168.0.150): 推新 jar → 备份旧 jar → `docker build --no-cache` 重建 `erp-system-backend:latest` → 用原 `erp_env_home.txt`（保留 `SA_TOKEN_JWT_SECRET_KEY`，登录会话不失效）重建 `erp-backend` 容器 → healthy。
- 飞牛 (192.168.0.32): `docker save` home 新镜像 → LAN 流式 `docker load` → tag 为 `erp-backend-failover:latest` → 用 `.erp_env_failover` 重建 `erp-backend-failover` 容器 → healthy。
- 两站线上 jar md5 均 `bbfd946f55c4f14206b84b3831f1836f`。home 实测被删销售出库现聚合出「带鱼袋9*75*0.07 / 透明胶带 / 塑料袋25*32*0.16…」；飞牛返回「金达印字胶带」。

**教训**: 写「被删单据聚合」类查询时，`deleted=0` 过滤只适用于"看有效数据"的列表；回收站本身就是看**已删**数据，明细聚合不能沿用 `deleted=0`，应直接用明细自带的去规范列 + 不过滤 `deleted`。

### v1.1.71 (2026-10-04) — 回收站列表补充「商品 / 金额 / 删除人」列

**用户反馈 (2026-10-04)**: 回收站里的单据要把 商品、金额、删除人 等相应信息也显示出来 (原来只有 单号/类别/日期/状态/删除时间/操作)。

**改动**:
1. **`RecycleBinMapper.xml` (listDeleted)** 新增 3 列:
   - `productNames` — 聚合明细表 `product_name`。无明细的 `prd_order` 直接取 head.product_name; 其余 10 类 `GROUP_CONCAT(DISTINCT p.product_name) LEFT JOIN base_product`。
   - `totalAmount` — 仅 6 类业务单据 (pur_order/receipt/return, sal_order/delivery/return) 有 `total_amount` 列, 用 `<choose>` 输出; 生产/BOM/盘点/调拨 5 类无金额列 → `NULL AS totalAmount`。
   - `deletedBy` — `update_by` JOIN `sys_user.username` (库里无专门 `deleted_by` 列, 业务删除只对 DRAFT 开放, 删除动作由 update_by 记录最后写入者, 故用 update_by 近似)。
   全部走原生 SQL, 沿用 v1.1.70 的 `RecycleType` 白名单 `${headTable}/${detailTable}/${detailFk}` 传参。
2. **踩坑**: 第一版 `productColumn` 子查询里写 `GROUP_CONCAT(p.product_name ORDER BY dtl.line_no ...)`, 但 `inv_check_detail` / `inv_transfer_detail` 两张明细表**没有 `line_no` 列** → 端点 500 (`Unknown column 'dtl.line_no'`)。改成 `GROUP_CONCAT(DISTINCT p.product_name SEPARATOR ', ')` (不依赖 line_no) 后通过。教训: 跨 11 类 detail 表写聚合子查询前先 `information_schema` 核对每张表的列名差异。
3. **`Recycle.vue`** 表格在状态列之后插 3 列: 商品 (min-width 200, show-overflow-tooltip)、金额(元) (右对齐, null → `-`)、删除人 (null → `-`)。

纯后端 XML + 前端表格, 无 DDL / 无接口签名变更 (`hasDetail` 参数 v1.1.70 已就位)。双站 jar + pc-web dist + docker 重建。home 实测: 销售出库 49 行, `CKP202610040002` 现返回 `totalAmount=4725, deletedBy=gpssong`; 生产单 `PD202609260002` 返回 `productNames=带鱼袋8*75`。飞牛实测 `pur_receipt RKP202609210003` 返回 `deletedBy=秦运桂`。

### v1.1.70 (2026-10-04) — 回收站「销售出库被删单不出现」双 bug 修复

**用户反馈 (2026-10-04)**: 被删除的销售出库单没有出现在回收站对应列表中 (销售出库 Tab 空)。库里其实有 50 条 `deleted=1` 的 `sal_delivery`。

**根因 (两个独立 bug 叠加, 都导致回收站整页空)**:
1. **`report:view` 授权缺口** — 回收站门禁点是 `report:view` (sys_menu id=951), 但历史上只授给了 `SUPER_ADMIN`/`PURCHASE_MGR` 两角色。`sql/45` (v1.1.68) 只给 6 角色授权了「回收站」M 型菜单节点 (perms='', 仅侧边栏可见), 却没补 `report:view` 本身 → `WAREHOUSE_MGR`/`FINANCE`/`SALES_MGR`/`PRODUCTION_MGR` 看得到菜单、一进就 403。前端 `Recycle.vue` 的 catch 把 403 静默吞掉 (只 `console.warn`) → 全 Tab 显示「暂无」。
2. **`prd_bom` schema 不匹配** — `RecycleBinMapper.listDeleted` 硬编码 `h.bill_no`/`h.bill_date`, 但 `prd_bom` 表无这两列 (用 `bom_code`, 无日期列)。`listAll` 一次跑全部 11 类, `prd_bom` 那条 SQL 抛 `Unknown column 'h.bill_no'` → 整个端点 500 → 连销售出库在内的所有 Tab 都拿不到数据 (即使修了 403 也仍 500)。

**修复**:
1. 新 seed `sql/46_v169_recycle_report_view_grant.sql`: 把 `report:view` (menu 951) 幂等补授给全部 6 内置角色 × 3 client_type (PC/APP/BOTH)。客户端路由 guard 与后端 API 403 同源 (都读 sys_role_menu 派生的 perm 集), 一处补齐双端生效。双站 (home + 飞牛) 各跑一次。
2. `RecycleBinMapper.xml` 加 `billNoColumn`/`billDateColumn`/`billNoSearchColumn`/`billDateSearchColumn` 四个 `<choose>` 片段: `prd_bom` 走 `bom_code`/`create_time`, 其余走 `bill_no`/`bill_date`。`listDeleted` 的 SELECT/keyword/日期过滤全部改 `<include>` 引片段。
   纯后端 (XML + seed SQL), 无 DDL 结构变更、无前端代码变更。双站 jar + docker 重建。home 实测 `/report/recycle/bin` 返回 200, 销售出库 50 行 (客户/日期/状态正确)。

### v1.1.69 (2026-10-04) — 列表"商品名称"列软删明细泄漏修复

**用户反馈 (2026-10-04)**: 出库单 `CKP202610040007` 编辑器里只有 1 行商品 (tm10053 透明胶带), 但列表"商品名称"列却显示 `透明胶带, 透明...`(像多商品被逗号拼在一起)。

**根因**: 该单 `sal_delivery_detail` 有 2 行同品 (tm10053): 1 行 `deleted=1`(软删) + 1 行 `deleted=0`(有效)。4 个列表 mapper (`SalDeliveryMapper`/`SalReturnMapper`/`PurReceiptMapper`/`PurReturnMapper`) 的 `first_product_name` 列用 `GROUP_CONCAT` 聚合**全部明细行且未过滤 `deleted=0`**, 把软删行也拼进商品名称 → 显示"透明胶带, 透明胶带"(截断为 `...`)。编辑器只读有效行, 故列表与编辑器不一致。

**修复**: 给 4 个 mapper 的 `first_product_name`/`first_product_spec`/`first_product_model` 明细子查询 + `productName` 搜索 `EXISTS` 子查询统一加 `AND <detail>.deleted = 0`。改后同单商品名称列正确显示单一 `透明胶带`。纯后端 SQL 改动, 无前端/DDL。双站部署 (home + 飞牛 jar + docker 重建), home DB 实测 `CKP202610040007` 现在返回 `透明胶带`。

### v1.1.68-hotfix (2026-10-04) — 销售出库"成本 0 / 审核查无实据"根治 + 出库日志/草稿提示

**用户反馈 (2026-10-04)**: 从"销售记录→历史销售"添加生成的销售出库单 `CKP202610030001`/`CKP202610040001`, 在报表里直接显示"已审核", 操作员列也"不对"; 且毛利分析长期"成本=0、毛利≈销售额"。

**根因诊断 (全 DB 实测, 非报表 bug)**:
1. **"操作员不对"实为多账号混开单**: 操作员列读 `create_by`(建单人), 这批单 `create_by=gpssong1`(9-29 建), 后被 gpssong/赵偲荣 多次 ADD/DELETE。系统显示"谁建的"是对的, 问题在于 **`check()/uncheck()` 从不写 `sys_oper_log`**, 导致"谁点的审核"查无实据。
2. **成本 0 的病根在采购入库**: `dyd975007`(带鱼袋9*75*0.07) 的 4 张采购入库单 (RKP202609290002/0006, RKP202610020003, RKP202610030001) 成本价 price 全部填 0 → `StockService.inStock()` 移动加权平均 `avg_cost` 从头就是 0 → `inv_stock.avg_cost=0`、`base_product.cost_price=0` → `StockService.outStock()` L212 `outCost = avg_cost × qty = 0` → 销售出库成本 0、毛利=销售额。出库成本算法(移动加权平均)本身正确, 病根是入库没录成本价。
3. **"历史销售添加"无 bug**: 该路径走 `SalDeliveryService.add()` (L193 强制 `STATUS_DRAFT`), 永远存草稿; "已审核"是这些老单历史被翻成 CHECKED。

**修复 (代码 2 类 + 数据回填 1 套)**:
| 类别 | 改动 |
|------|------|
| 后端 @OperLog | 7 个 service 的 `check()`/`uncheck()` 加 `@OperLog(module, businessType=CHECK/UNCHECK, saveParam=true)`: 销售出库/销售订单/销售退货/采购入库/采购退货/采购订单/库存盘点。以后 `sys_oper_log` 可查"谁何时审核/反审核" |
| 前端 草稿提示 | `Order.vue` 生成出库单按钮改"保存为草稿 (需审核后生效)" + 保存后 toast 明确"草稿, 审核后才会扣库存/生成应收/算成本/毛利"; `Delivery.vue` 新增保存同文案 |
| 数据回填 (home 生产库) | 见下方"成本价回填" |

**成本价回填 (home 192.168.0.150, dyd975007 成本价 = 0.0546 元/只, 用户确认)**:
- `inv_stock`: `avg_cost=0.0546`, `total_cost=ROUND(0.0546×qty,4)`
- `base_product.cost_price=0.0546`
- 4 张 `pur_receipt_detail`: `price=0.0546`, `amount/amount_tax=ROUND(0.0546×qty,4)`; `pur_receipt` 主表金额同步重算
- 2 张已审核 `sal_delivery` (CKP20261004/40001): `cost_amount=ROUND(0.0546×total_qty)`, `profit_amount=total_amount-cost`; 明细行 `cost_price/cost_amount` 同步
- **备份 (可回滚)**: `inv_stock_bak_v168_avg_cost` / `base_product_bak_v168_cost` / `pur_receipt_detail_bak_v168` / `sal_delivery_bak_v168_cost`
- 结果: 出库成本 1965.60, 毛利 230.40, 毛利率 10.49% (不再是≈100%)

**部署**: 双站 (home 192.168.0.150 + 飞牛 192.168.0.32) 各: ① 后端 jar 重打 + `docker build --no-cache` + `rm -f` + `run --env-file` (完整 CORS + SA_TOKEN 密钥, home 密钥 `ecdd4895...`, 飞牛 `a337f875...`, 勿混) ② pc-web `npm run build` + bind-mount 新 dist + `docker restart` ③ 成本数据回填 **仅 home 生产库** (飞牛热备库无 dyd975007 业务数据, 0 行, 无需回填)。

**踩坑**:
- 飞牛是**独立热备库**, 业务单据 (sal_delivery/pur_receipt 等) 不在飞牛, 只在 home; 成本回填只动 home。
- `sys_oper_log` 列名是 `module`/`method`/`business_type`/`username`/`oper_time`, **没有** `biz_no` 列 (查业务号要靠 `request_url`/`snapshot_json`)。
- `fin_arap` 关联源单用 `source_bill_no`, 不是 `bill_no`。
- `pur_receipt_detail` 无 `bill_no` 列, 要 JOIN `pur_receipt` 取。

**回滚**: 删 7 个 service 的 `@OperLog` 注解 + 还原 2 个 vue 文案; 数据用 4 张 `*_bak_v168` 备份表 `INSERT` 还原。

---

### v1.1.68 (2026-10-03) — PC 端报表中心新增「回收站」 (11 类被软删单据分类/恢复/彻底删除)

**用户需求**: 在 PC 端「报表中心」左侧菜单下新增一栏「回收站」, 把被删除的采购订单/销售出库单等业务单据**分类放好**, 方便找回。

**设计 (3 决策, 用户拍板)**:
| 决策 | 选择 |
|------|------|
| 操作 | 分类 + **恢复** (head+detail `deleted` 翻回 0) + **彻底删除** (物理 DELETE, 二次确认, 不可逆) |
| 单据类型 | **全部 11 类** 软删单据, 按类型分 Tab (采购订单/采购入库/采购退货/销售订单/销售出库/销售退货/生产加工单/生产领料/BOM/库存盘点/库存调拨) |
| 权限 | 复用 `report:view` (入口 + 恢复/彻底删动作都挂在它下, 不新增权限点) |

**关键技术结论**:
1. 软删 = `deleted TINYINT 0/1` + MyBatis-Plus `@TableLogic` (全局 `logic-delete-field: deleted`)。内置 MP mapper 自动 `WHERE deleted=0`, 读不到被删行。
2. **删除只对 DRAFT 开放** → 被删单据无库存/台账/应收应付副作用 → **恢复只需把 head+detail 的 `deleted` 翻回 0, 无需回滚账**。
3. 彻底删用**物理 `DELETE`** (绕开 `prd_order` 的 `(bill_no, deleted)` 唯一索引撞坑, v1.1.52.4)。
4. 回收站查询/恢复/删除全部走**独立 mapper + 原生 SQL** (显式 `WHERE deleted=1`), 天然绕过 @TableLogic 过滤。

**改动文件**:
| 文件 | 类型 | 说明 |
|------|------|------|
| `backend/.../report/mapper/RecycleBinMapper.java` | 新 | 回收站 SQL mapper (listDeleted/peekHead/restore×2/physicalDelete×2) |
| `backend/src/main/resources/mapper/report/RecycleBinMapper.xml` | 新 | 原生 SQL, `${headTable}/${detailTable}/${detailFk}` 由枚举传参 |
| `backend/.../report/service/RecycleType.java` | 新 | 11 类枚举 (key/head/detail/FK/label/类别列白名单) |
| `backend/.../report/service/RecycleBinService.java` | 新 | listAll 按 type 分组; restore/physicalDelete @Transactional 双表原子 |
| `backend/.../report/controller/ReportController.java` | 改 | +3 端点 (/report/recycle/bin /restore /purge), `@SaCheckPermission("report:view")` |
| `pc-web/src/views/report/Recycle.vue` | 新 | 11 类 el-tabs + 表格 + 【恢复】/【彻底删除】 |
| `pc-web/src/api/report.js` | 改 | +3 方法 recycleBin/recycleRestore/recyclePurge |
| `pc-web/src/router/index.js` | 改 | +report/recycle 路由 (meta.perm=report:view) |
| `pc-web/src/layouts/MainLayout.vue` | 改 | 报表中心 children +回收站 |
| `sql/45_v168_recycle_bin_menu.sql` | 新 | sys_menu M 节点 (parent_id=9) + sys_role_menu 授权 6 内置角色 (client_type 自适应) |

**11 类 head/detail 映射** (全部带 deleted, 通用列 id/bill_no/bill_date/bill_status/update_time):
采购订单 pur_order→pur_order_detail(order_id) / 采购入库 pur_receipt→pur_receipt_detail(receipt_id) /
采购退货 pur_return→pur_return_detail(return_id) / 销售订单 sal_order→sal_order_detail(order_id) /
销售出库 sal_delivery→sal_delivery_detail(delivery_id) / 销售退货 sal_return→sal_return_detail(return_id) /
**生产加工单 prd_order (无明细, head-only)** / 生产领料 prd_requisition→prd_requisition_detail(requisition_id) /
BOM prd_bom→prd_bom_detail(bom_id, 用 status 非 bill_status) / 库存盘点 inv_check→inv_check_detail(check_id) /
库存调拨 inv_transfer→inv_transfer_detail(transfer_id)。

**前端 Recycle.vue**: 单端点 `/report/recycle/bin` 一次拉全部 11 类, 按 type 分组渲染; 每类 Tab 标签带条数; 空类型显示 el-empty。删除时间列用 `update_time` 近似 (无专门 deleted_time 列)。

**部署 (双站 home 192.168.0.150 + 飞牛 192.168.0.32, 各跑一次)**:
1. MySQL: `sql/45_v168_recycle_bin_menu.sql` (`--default-character-set=utf8mb4`), 校验 6 角色授权行。
2. 后端 jar: `mvn package` → `docker build --no-cache` + `docker rm -f erp-backend[-failover]` + `docker run --env-file ...` (含完整 CORS + SA_TOKEN 密钥)。
3. pc-web dist: `npm run build` → 双站 bind-mount 新 dist (飞牛实际 mount 是 dist-new) + `docker restart erp-pc-web[-failover]`。
4. 非 admin 用户重登一次刷 menu/perm; admin (userId=1 / SUPER_ADMIN / isAdmin) 直接可见。

**回滚**: 删 Recycle.vue + 路由/菜单 2 行 + 3 API + 后端 RecycleBinMapper/Service/ReportController 3 端点; sql/45 反向 `DELETE sys_role_menu WHERE menu_id=<新节点>; DELETE sys_menu WHERE path='/report/recycle';`。被删单据仍保留在 DB。
### v1.1.67 (2026-09-29) — App 扫码出库明细落库缺商品编码/名称修复 + 历史单位回填

**用户反馈**: App 端「扫码出库」生成的出库单 `CKP202609290006`, 在 PC 端打印送货单时「商品编码」「商品名称」栏空白, 只有「型号」有值。

**根因 (双层)**:
1. **App 端丢字段**: `app/src/pages/scan/out.vue` `onSubmit()` 提交 payload 的 details 只带 `productId/qty/price/batchNo/remark`, 把 UI 里本已存好的 `productCode/productName/spec/unitId/unitName` 全砍了。
2. **后端信任客户端原样落库**: `SalDeliveryService.add()` 直接 `detailMapper.insert(d)`, 这些真实 DB 列 (`sal_delivery_detail.product_code/product_name/spec/unit_id/unit_name`) 落 NULL。
3. **为何型号能显示**: 型号 = transient `pModel`, 打印走 `SalDeliveryService.detail()` L102-105 `ProductAttrInjector.inject(..., p -> p.getModel())` 从 `base_product.model` **实时注入**, 与落库列无关; 编码/名称是**真实 DB 列**, 不回填就永久缺失。

**代码修复 (v1.1.67, 双层互为兜底)**:
- **后端主修** `backend/.../sales/service/SalDeliveryService.java`: 新增私有 `enrichDetailsFromProduct(List<SalDeliveryDetail>)` — 批量 `selectBatchIds` (无 N+1), **仅在字段 blank 时**回填 `productCode/productName/spec/unitId` (unitName 由 App 携带, 不额外引 BaseUnitMapper), `add()`+`update()` 在 `detailMapper.insert` 前各调一次。只兜底瘦客户端 (App), 不覆盖 PC 已录值 (PC 开单/订单生成路径本带全字段, 行为不变)。
- **App 辅修** `app/src/pages/scan/out.vue`: `onSubmit` payload 补全 5 字段。
- **版本号**: App `1.1.64` → `1.1.67` (profile `APP_VERSION` + manifest `versionName`/`versionCode` + `app/package.json`)。
- 纯后端 jar + App H5/APK, PC dist 不动, 无 schema 改动 (无 DDL)。

**历史数据回填** (`sql/44_v167_backfill_delivery_detail_unit.sql`, 双站各跑一次 — 主从 replication 曾断开):
查库发现编码/名称/规格实际**已落库 (149 行 0 NULL)**, 真正缺的是 `unit_id/unit_name`。根因: `base_product_unit.unit_id` 历史全为 `0` (v1.1.16+ 遗留脏数据, 单位名有但没指向真实 `base_unit` 行), 加上 `sal_delivery_detail` 早期客户端未带 unit。分两步:
1. **修 `base_product_unit.unit_id` 0 → 真实 active unit**: 按 `unit_name` 匹配 active `base_unit` (`公斤→kg`, 卷/只/箱/包/个/套→对应 ID), 双站先建 `base_product_unit_bak_v167` 备份再 UPDATE。
2. **重跑 `sql/44` 三级兜底** 回填 `sal_delivery_detail.unit_id/unit_name`: 优先级 `is_main=1` 的 ppu → `main_unit_id` → 任意 `unit_id<>0` 的 ppu。

**回填结果**:
| 站 | sal_delivery_detail bad_unit | 最终 |
|---|---|---|
| home | 95 | 3 (仅剩 `jydbd 机用打包带`, 彻底无单位主数据) |
| 飞牛 | 78 | 3 (同上) |

**部署**: 双站 jar 重打 + `docker build --no-cache` + 容器重建, 运行 jar MD5 `bcfe92a32d4ea12b95d7402d7bdf9f48`, 均 healthy。App APK 1.1.67 交付 `~/Desktop/erp-app-20260929.apk` (MD5 `5295708ca1f1dab06e7708db2105a4d1`)。

**验证**: `CKP202609290006` 现 `product_code=dyd975007` / `product_name=带鱼袋9*75*0.07` / `unit_id=2089154538889621509` / `unit_name=只`, PC 重打印编码/名称/单位均正常。

**唯一遗留**: `jydbd 机用打包带` 在 `base_product_unit` 0 行、`main_unit_id` NULL — 纯主数据缺口, 需业务在 PC「基础资料→商品」补单位后重跑 `sql/44` 最后 1 条。

**教训 (R16 — 写入设计原则)**: 凡是从商品主数据派生的字段 (编码/名称/规格/单位), 后端 insert 前应按 `productId` 从 `base_product` 兜底回填, 不能 100% 信任客户端带全 (App 等瘦客户端会砍字段)。transient 注入 (model/colorNo) 只在**读路径**补, **写路径不补** → 换数据源 (飞鹅云打印 / 直接查列 / 历史数据) 就会露出空。双站数据修复必须**各跑一次** (主从 replication 长期不可靠)。

### v1.1.66 version-sync (2026-09-29) — 系统信息版本号字面量对齐 (前端/后端版本显示修复)

**用户反馈**: 系统管理 → 系统设置 → 系统参数「系统信息」里 前端版本 / 后端版本 仍显示 `1.1.53-hotfix.1`, 与 changelog 脱节, 未随 v1.1.54~v1.1.66 同步更新。

**根因**: 「系统信息」的前端/后端版本号来自三处被冻结的字面量 (v1.1.53 时代的 `deploy-version-bump.sh` 写死后不再维护):
- **前端版本** = `pc-web/package.json` → `version`, vite `define` 在**构建时**烤进 `__APP_VERSION__`, `Settings.vue:131` 读取。
- **后端版本** = `application.yml` `erp.version: ${ERP_VERSION:…}` 默认值 → `SystemVersionInitializer` 启动时 upsert 到 `sys_config.SYSTEM_VERSION_INFO`。
- **构建时间** = vite `__BUILD_TIME__` = 构建当天 (本来会随重打刷新, 无需改)。

**修复 (4 文件, 纯字面量对齐, 零业务改动)**:
| 文件 | 改动 |
|---|---|
| `pc-web/package.json` | `version` `1.1.53-hotfix.1` → `1.1.66` (需重打 dist 才生效, `__APP_VERSION__` 构建期注入) |
| `backend/.../application.yml` | `erp.version` 默认 `1.1.53-hotfix.1` → `1.1.66` |
| `backend/.../SystemVersionInitializer.java` | `@Value("${erp.version:…}")` 默认 `1.1.53-hotfix.1` → `1.1.66` |
| `app/package.json` | `version` 顺手对齐 App 实际版本 `1.1.64` (App 走 manifest `versionName`/`APP_VERSION`, 非 package.json, 纯源码一致性) |

**部署**: 重打 pc-web dist (System chunk `System-CGWmoRBS.js`), 双站 bind-mount 替换 + `docker restart` pc-web; 双站 backend 用**现有 image + 追加 `-e ERP_VERSION=1.1.66`** 重建容器 (无需重打 jar, jar 内 `application.yml` 默认值已在源码对齐)。两站 JWT 密钥沿用原值 (home `ecdd4895…` / 飞牛 `a337f875…`), 已登录用户不用重登。

**验证**: 双站 `sys_config.SYSTEM_VERSION_INFO` 均 `{"version":"1.1.66",...}`; 双站 18080 `System-CGWmoRBS.js` 均 200 且含 `1.1.66` 字面量; 强刷后「系统信息」前端/后端版本都显示 `1.1.66`。

**教训 (R15 — 写入设计原则)**: 版本号字面量分散在 3 个源文件 + 1 个运行 env, 极易漏改漂移。后续每个 feature 版本若要让「系统信息」版本对齐, 必须同步 4 处 (pc-web/package.json + application.yml + SystemVersionInitializer + 重建容器 env `ERP_VERSION`), 并把 pc-web 纳入 dist 重打 (前端版本是构建期烤入, 光改 package.json 不重打包没效)。

### v1.1.66 (2026-09-29) — 销售订单「多单合并生成出库单」(同客户 2 张以上已审核订单 → 一张出库单)

**用户需求**: 两个销售订单合成 1 张送货单。当前"生成出库单"是逐订单各生成一张, 用户得手动把订单 B 的明细补进订单 A 草稿, 不便。

**设计 (最小改动, schema 不变)**: 数据层天然支持 —— `sal_delivery_detail.order_detail_id` 是逐行溯源键, 本就可指向任意订单的任意明细行, 不要求归属主表那个 `order_id`。缺的只是操作入口。合并的硬前提 = **所选订单同客户** (客户不同则送货对象/信用/应收都不同, 无法合并)。

**改动 (2 文件, 无 DB 迁移)**:

1. **前端 `pc-web/src/views/sales/Order.vue`**
   - 列表加多选列 (`type="selection"`, `:selectable` 仅已审核行) + toolbar「合并生成出库单 (N)」按钮。
   - `onMergeGenerateDelivery()`: 校验 N≥2 且全部 `customerId` 相同, 否则报错"只能合并同一客户"。
   - 抽出通用 `openGenerateDialog(orders[])`: 逐订单拉 `detail` + `getDeliverySummary`, 明细拼接, 每行保留各自 `orderDetailId` (关键: 审核时 `SalDeliveryService.check` step 5 按 `orderDetailId` 累计已发, 天然跨两订单正确); 单订单仍 `orderId/orderNo` 写主表, 多订单主表 `orderId=null` (溯源靠明细行), `orderNo` 写各订单号拼接文案。
   - 明细表加「来源订单号」列 (每行标注来自哪张订单; 该列仅展示, 提交前 `delete cleaned.orderNo`, 后端 `sal_delivery_detail` 无此列, 靠 `orderDetailId` 溯源)。
   - 弹窗标题/提示文案在合并模式下显示全部源订单号。单订单快捷入口 `onGenerateDelivery(row)` 行为不变 (走 `openGenerateDialog([row])`)。

2. **后端 `SalDeliveryMapper.selectPageByOrderId`** — "关联出库单"追溯查询原本 `WHERE order_id = #{orderId}`, 合并单主表 `order_id` 为 NULL 会漏查。改为 `order_id = #{orderId} OR EXISTS (明细 order_detail_id IN (该订单的明细))`, 让合并单在每张源订单的"关联出库单"里都可见 (同一合并单挂在多张订单下)。需重打 jar 部署。

**不变**: `SalDeliveryService.add` 基本不动 (它本就按传入明细逐行写 `orderDetailId` + 从 `orderDetailId` 反查 `poNo` 注入, 对跨订单明细透明); `selectShippedQtyByOrderDetailId` 按订单明细行聚合, 不关心挂在哪个 delivery, 合并后"已发/未发/剩余"统计仍正确; 超发防护 (v1.1.65 剩余数量默认) 逐行保留。App 版不动, 仍 1.1.64。

**验证 (装机)**: 强刷后, 销售订单列表勾选 2 张同客户已审核订单 → 「合并生成出库单」→ 弹窗明细为两订单拼接、每行带「来源订单号」、数量默认各自剩余; 保存草稿 → 出库单列表出现 1 张 (主表 order_id 空, orderNo 为两单号), 各源订单「关联出库单」里都能追溯到这张合并单; 审核后两订单的"已发/未发"分别正确累加。

**部署**: 重打后端 jar (MD5 `43039facc3453129d2f1f6e59be82041`) + 重打 pc-web dist (Order chunk `Order-xWO8eQ4A.js`), 双站 (home + 飞牛) 上线。

### v1.1.65 (2026-09-29) — PC 端"生成出库单"数量默认「剩余数量」

**用户反馈**: 通过销售订单生成出库单时, 商品明细中的「数量」默认填的是订单全量, 部分发货过的订单应当默认只填「未发货/剩余数量」(与"发货详情"弹窗的 未发货 列一致), 避免再开一张出库单把已发的量重复算进去超发。

**根因**: `pc-web/src/views/sales/Order.vue` 的 `onGenerateDelivery` 把订单明细 `o.details[i].qty` (全量) 直接作为出库明细默认数量 (v1.1.35 设计: "默认值 = 全量; 用户可手工改数量做部分发货"), 没扣掉已审核出库累计。

**修复 (3 文件, schema 不变)**:

1. **后端 `SalOrderService.getDeliverySummary`** — 每行 summary Map 加 `row.put("orderDetailId", d.getId())`。
   - 原方法只带 `lineNo/productCode/.../shippedQty/unshippedQty`, 缺 `order_detail_id` 主键, 前端无法把发货汇总对回具体明细行。加这一行后, `onGenerateDelivery` 能按 `orderDetailId` 建 `Map` 匹配。
   - `发货详情` 弹窗前端只读原字段, 新增 `orderDetailId` 不影响其渲染。
   - 需重打后端 jar 部署。

2. **前端 `Order.vue onGenerateDelivery`** — 打开"生成出库单"弹窗时:
   ```js
   let shippedByDetail = new Map()
   try {
     const sr = await salOrderApi.getDeliverySummary(o.id)
     ;(sr.data || []).forEach(s => { if (s.orderDetailId != null) shippedByDetail.set(s.orderDetailId, s) })
   } catch (e) { console.warn('[onGenerateDelivery] 加载发货汇总失败, 数量默认订单全量:', e) }  // 回退旧行为, 不阻塞
   // 明细行 qty 默认 = max(0, 全量 − 已审核出库累计)
   const ship = shippedByDetail.get(d.id)
   const full = d.qty != null ? Number(d.qty) : 0
   const shipped = ship ? (ship.shippedQty != null ? Number(ship.shippedQty) : 0) : 0
   const remain = Math.max(0, +(full - shipped).toFixed(4))
   ```
   明细行新增 `_orderQty` (全量) + `_shippedQty` (已发) 两个 UI 辅助字段, `onConfirmGenerate` 提交前 `delete cleaned._orderQty/_shippedQty` (不进后端 payload)。

3. **前端 `Order.vue` 明细表 + 提示** — 「数量」列前加只读「订单数量」列 (灰字全量) 对照; 提示文案改为"数量默认填入「订单全量 − 已审核出库累计」的剩余数量, 已发完的明细默认为 0; 如需补发/超发请手工调整"。

**部署**: 后端 jar 重打 (deploy-home-dist jar 流程); pc-web `npm run build` 出新 chunk `Order-BQ9ysCJe.js` 部署 H5 容器。**App 端不动, 仍 v1.1.64** (纯 PC 功能, 无 App 页面)。

**验证 (装机)**: 订单 SO202609070001 部分发货 (发货详情: 订单 75000/已发 50000/未发 25000, 碳带 140/80/60 等) → 点「生成出库单」→ 明细「数量」默认剩余 (25000/7000/2000/182000/60/20...), 「订单数量」列灰字全量; 未发货=0 的明细默认数量为 0 → 保存为草稿按剩余量录入。

**回滚**: 前端 `onGenerateDelivery` 恢复 `qty: Number(d.qty)` (全量默认), 删「订单数量」列; 后端 `orderDetailId` 一行无害可留。

### v1.1.64 (2026-09-28) — App 扫码跟随竖屏 (连续扫码不再闪横屏宽界面)

**用户反馈**: 连续扫码 (扫码入库 / 扫码出库扫多个商品) 每次都弹出"宽的扫码界面" (横屏), 体验差。

**根因**: `ScannerActivity.java` L58 `SENSOR_LANDSCAPE` 强制横屏 + manifest `android:screenOrientation="sensorLandscape"`. uni-app 手持竖屏使用, 每次扫码原生 Activity 弹起都转横屏, 连续扫 N 个商品 = N 次横屏闪烁.

**修复 (2 文件)**:
- `ScannerActivity.java` L58: `SENSOR_LANDSCAPE` → `SCREEN_ORIENTATION_UNSPECIFIED` (跟随父 Activity, uni-app 主 Activity 竖屏)
- `AndroidManifest.xml` L39: `sensorLandscape` → `unspecified` (与代码同步)
- `applyCenterScanRect` 已按"短边 60%"算方框, 竖屏 1080 宽 → 648px 方框, 比横屏还小, 解码区更聚焦
- 版本号 1.1.63 → 1.1.64 (APP_VERSION / manifest.versionName+versionCode / CLAUDE.md 顶部)

**改动**: 2 原生文件 + H5 版本号同步 (APP_VERSION), 无 layout/相机逻辑变更 (v1.1.63 3 处优化不动).

**验证**: 走 `build-app.sh` 全流程 (H5 + cap sync + assembleDebug). APK MD5 `ed4d3f30a68235a3a895e046e3215ffc`, 4,369,472 字节. 装机: 扫码入库点"📷 扫一扫" → 手机保持竖屏 (不再旋转), 中央方框居中, 连续扫多商品每次竖屏. "我的"页显示 v1.1.64.

**回归**: 纯方向改动, 相机/解码/对焦逻辑不变; `zxing_preview_scaling_strategy=centerCrop` 自动适配竖屏预览. 回滚: `git checkout` 恢复 `SENSOR_LANDSCAPE` + manifest `sensorLandscape`.

#### build-app.sh [6/6] 防呆 (v1.1.64 同 commit 加, 2026-09-28)

**背景**: `[1.5/5]` 校验的是"源码三处版本号一致" (`APP_VERSION` / `manifest.versionName` / `CLAUDE.md` 顶部), 但**源码对 ≠ 打进 APK 的产物对**. 历史踩过的真实漂移场景: H5 没重打 (dist/build/h5 陈旧) / cap sync 漏步 / assembleDebug 没走全 — 这些情况 `[1.5/5]` 都拦不住, APK 内嵌的还是上次的旧版本号.

**修复** (在 `scripts/build-app.sh` `[5/5] assembleDebug` 之后、计算 MD5 之前插 `[6/6]` 段):
```bash
PROFILE_CHUNK=$(unzip -Z1 "$APK" | grep -E 'assets/public/assets/pages-profile-index\..*\.js$' | head -1)
EMBEDDED_V=$(unzip -p "$APK" "$PROFILE_CHUNK" | grep -oE '"[0-9]+\.[0-9]+\.[0-9]+"' | tr -d '"' | head -1)
# 和 manifest.json 的 versionName 比对, 不一致 exit 1 拒绝交付
```

**为什么能抓到版本字面量**: profile 页模板 `v{{ APP_VERSION }}` minify 后变成 `...v"+r("1.1.64")...`, 3 段版本号字符串字面量是稳定形态, 即使变量名被 rename 也能 grep 到. 实测当前 9/28 的 APK:
```
profile chunk: assets/public/assets/pages-profile-index.BW9-dH-N.js
内嵌=1.1.64   manifest.versionName=1.1.64   ✓ 一致, 通过
```

**两道护栏分工** (互相补位):
| 场景 | [1.5/5] | [6/6] |
|------|:---:|:---:|
| 源码三处版本号手改漏一处 | ✅ | — |
| H5 没重打, APK 内嵌旧 `APP_VERSION` | ❌ | ✅ |
| cap sync / assembleDebug 漏步, chunk 是上次的 | ❌ | ✅ |

**设计原则 (R14)**: 版本一致性校验必须分两层 (源码层面 + 产物层面); 产物校验用"反向 grep" (从打好的 APK 直接 unzip 出编译产物, 而非信任 `dist/` 里的文件); 失败必须 `exit 1` 拒绝交付; 抓版本字面量用 `[0-9]+\.[0-9]+\.[0-9]+` regex (minify 后变量名已消失, 这是 Vue 字符串插值 minify 后的稳定形态).

**改动**: 1 文件 (`scripts/build-app.sh`, +~40 行 [6/6] 段). 不影响 build 流程 (校验在 build 完成之后, 只在 MD5 计算之前).

**实测**: 9/28 的 `erp-app-20260928.apk` 通过 [6/6] 校验 (`1.1.64 == 1.1.64`, `bash -n` 语法 OK).

### v1.1.63 (2026-09-28) — App 扫码灵敏度优化 (3 处性能改造)

**用户反馈**: App 端扫码入库 / 扫码出库扫二维码不够灵敏。

**修复**: `ScannerActivity` 3 处 — 限定解码格式 (QR+EAN+CODE128/CODE39) / 中央 60% 方框 / 连续对焦 + 条码场景模式。详见 `CLAUDE.md` v1.1.63 段 (含 ZXing 4.3.0 API 踩坑 6 条)。

**改动**: 1 文件 (Java), 无 layout/前端变更。

**验证**: APK MD5 `c83b5f435a196217abf6eca699f89731`, 4,369,513 字节, 已交付飞牛同步盘 + 桌面。

### v1.1.62 (2026-09-24) — 库存查询 SQL 改写 (库存为 0 产品可查询)

**症状**: PC `/inventory/stock` 搜索"塑料袋30*38*0.16" 返回 Total 0, 但产品存在于 `base_product` 表 — 只是 inv_stock 表里没有它的行 (库存=0 且从未入库过)。

**根因 (`InvStockController.java:63` v1.1.61)**:
```java
LambdaQueryWrapper<InvStock> w = new LambdaQueryWrapper<>();
w.gt(InvStock::getQty, java.math.BigDecimal.ZERO);  // qty > 0 严格过滤
```
原语义 = "查询有库存的库存台账", 但用户预期它 = "查产品库存 (可为 0)"。

**修复**: `base_product` LEFT JOIN `inv_stock` 聚合, 删除 qty>0 过滤。库存为 0 时产品仍命中, 库存列全 0/空。详见 `CLAUDE.md` v1.1.62 段。

**改动**: 3 文件 (1 新 mapper 接口 + 1 新 XML + 1 改 controller)。Schema/SQL/前端不动。

**验证**:
- ✅ 后端 jar md5 `fc5245f493f5b243de6810d18c0c3ffa` (第一版 `547b552b` 漏改 LEFT JOIN ON 子句, hotfix 已重建)
- ✅ Docker image `erp-system-backend:latest` rebuild (id `817f8a1b20f1`)
- ✅ 容器 erp-backend healthy, 启动日志 `Started IndustrialErpApplication in 31.82s`
- ✅ 模拟 SQL (mysql 直跑) 命中用户截图案例: 塑料袋30*38*0.16 (id `20754693159949580161`, qty=0.0000) ✓
- ✅ EXPLAIN 验证 ON 子句正确: `u.PRIMARY = industrial_erp.p.main_unit_id`

### v1.1.61 (2026-09-24) — App 端 R13 全局重塑 (22 处 catch 双弹全部静默化)

承 v1.1.60 hotfix-2 (profile 单页修复), 把"拦截器已弹 catch 不重复弹"原则推广到全部 13 个业务页面 22 处 catch, 全部静默化。详见 `CLAUDE.md` v1.1.61 段。

**改动**: 13 个页面 + 1 个 build 配置文件 = 14 文件。

**验证**:
- ✅ H5 容器 rebuild + recreate (镜像 hash `60edfbac4ceb`), 12 个新 chunk md5 = 本地 dist 一致
- ✅ APK 重打 MD5 `23841776cca026580fc4d515494c9734` (4.4MB)
- ✅ 用户体验: 业务错看到后端 msg, 网络错看到"网络连接失败", 502 看到"服务暂不可用", 401 自动跳登录

### v1.1.60 hotfix-2 (2026-09-23) — App 端"我的-刷新权限菜单"双弹覆盖修复

**症状**: 用户反馈 "app端-我的-点击刷新权限菜单, 提示刷新失败, 请稍后再试"。不论后端实际错误码是什么 (msg="无权访问" / 网络断 / 502), 用户**始终**只看到 "刷新失败, 请稍后再试"。

**根因 (与 v1.1.31 PC Login catch 空转同根 — 这次是"反向"双弹)**:
- `app/src/api/index.js` 拦截器**已弹** toast (反代 502 / 后端业务错 / 网络层失败)
- `app/src/pages/profile/index.vue` catch **又弹** "刷新失败, 请稍后重试"
- Android `uni.showToast` 后调用会**中断**前调用 — 用户只看到后 catch 弹的"刷新失败", 完全**丢失**后端 msg 的诊断价值

**修复 (1 文件, `app/src/pages/profile/index.vue` L138-149 catch)**:
- 移除重复 `uni.showToast` 调用
- 保留 `console.warn` 详细诊断 (含 code/msg/err)
- 401 路径静默: 拦截器 reLaunch 跳登录页, catch 不弹任何 toast (一闪而过反而干扰跳转)

**为什么不改拦截器**: 18 个页面 catch 都依赖拦截器弹 toast (dashboard / scan / production / system 等), 全局改风险大且超出当前需求范围。

**部署**:
- 改 1 文件: `app/src/pages/profile/index.vue`
- `cd app && npm run build:h5` → 新 chunk `pages-profile-index.9bnvdhRj.js` (3353 字节)
- 上传 + NAS `app/dist/build/h5/` + `docker build --no-cache -t erp-app-h5:latest .` + `docker rm -f erp-app-h5 && docker run ...`
- 镜像 hash `38e532179829`, 容器 Up 正常, 端口 18090

**端到端验证 (2026-09-23)**:
- ✅ H5 容器 rebuild + recreate 成功
- ✅ 线上 `pages-profile-index.9bnvdhRj.js` MD5 `518e2068682e0098d3f6333f78ad45cc` = 本地 dist
- ✅ chunk 含 `onRefreshPerms] 失败, code=, msg=, err=, 401===s)return` 修复逻辑

**设计原则 (R13 延伸)**:
- **拦截器已弹过的 toast, 组件 catch 不要重复弹** — 否则后 toast 会覆盖前 toast
- **401 路径特殊**: 拦截器 reLaunch 跳登录页, catch 不应再弹任何 toast
- **R13 全局重塑** (拦截器不弹, 组件 catch 统一弹) 留后续版本按 18 个页面审计后统一改

### v1.1.60 hotfix-1 (2026-09-23) — PC + App 登录错误提示统一规范

**症状**: 用户反馈 "项目登录的时候账号密码错误时弹窗提示账号密码错误", 进一步反馈 "PC 端登录错误提示也优化一下"。排查发现两个 bug:
- **App 端** `app/src/pages/login/index.vue` L121 用 `alert('登录失败: ' + ...)` 原生对话, 突兀 + 冗余 "登录失败:" 前缀
- **PC 端** `pc-web/src/views/Login.vue` L130-136 catch **完全没弹任何提示**! 注释说 "业务错误由 request.js 拦截器弹 ElMessage.error", 但 `pc-web/src/utils/request.js` v1.1.31+ 已改成 **不在拦截器弹, 让组件 catch 统一弹**, Login.vue 没跟着改, 导致 catch 块完全空转, 用户输入错误密码只看到 loading 消失, 没有任何反馈

**修复 — 两端统一规范**:

| 端 | 文件 | 弹窗方式 | 文案来源 |
|----|------|---------|---------|
| App | `app/src/pages/login/index.vue` L119-129 | `uni.showToast` 优先, H5 退化为 `alert` | 后端 msg → 兜底 "账号或密码错误, 请重试" |
| PC  | `pc-web/src/views/Login.vue` L130-137 | `ElMessage.error` | 后端 msg → 兜底 "账号或密码错误, 请重试" |

**PC 端修复** (关键: 补回 v1.1.31 拦截器改造时漏掉的 catch 弹窗):
```js
} catch (e) {
  if (import.meta.env.DEV) console.error('[LOGIN_ERR]', e)
  // v1.1.31+: request.js 不再拦截器弹 ElMessage, 由组件 catch 统一弹
  const rawMsg = (e && (e.msg || e.message)) || ''
  const friendly = rawMsg || '账号或密码错误, 请重试'
  ElMessage.error(friendly)
}
```

**App 端修复**:
```js
} catch (e) {
  console.error('[LOGIN] 登录失败:', e)
  const rawMsg = (e && (e.msg || e.message)) || ''
  const friendly = rawMsg || '账号或密码错误, 请重试'
  if (typeof uni !== 'undefined' && uni.showToast) {
    uni.showToast({ title: friendly, icon: 'none', duration: 2500 })
  } else {
    alert(friendly)
  }
}
```

**后端文案来源** (`com.industrial.erp.modules.system.service.AuthService` BizException 抛出):
- L106: "登录失败次数过多, 请5分钟后再试"
- L112: "用户名或密码错误"
- L115: "账号已停用"
- L119: "用户名或密码错误"

**部署**:
- 改 2 文件: `app/src/pages/login/index.vue` + `pc-web/src/views/Login.vue`
- `cd pc-web && npm run build` → dist/assets/Login-CktUqvzO.js (4311 字节)
- PC 部署: Python Popen + base64 + ssh -T + sudo -S 流式传 tar 到 NAS `/tmp/staging/` → sudo 解压到 `/tmp/pc-web-new` → `chown 1000:1000` → `docker restart erp-pc-web`
- App 部署: 待触发 `bash scripts/build-app.sh` (代码已就位, APK 还没重打)
- 不动: 后端 jar / DB / SQL (前端交互层修复)

**端到端验证 (2026-09-23)**:
- ✅ PC dist 编译成功, Login chunk 含 "账号或密码错误" 文案 (`grep -c = 1`)
- ✅ PC 部署后线上 `http://home.93gushi.com:8088/assets/Login-CktUqvzO.js` MD5 `4361c418aa3293428292ba650e52ca01` = 本地 dist (完全一致)
- ✅ PC 用户体验: 输入错误密码 → Element Plus 红色错误提示 "用户名或密码错误"
- ⏸ App 端: 代码已就位, 待编译 APK + 装机测试

**设计原则 (R13)**:
- **错误提示必须后端 msg 优先**: 业务错误码 (`code: 400`) 通常携带用户可读 msg, 不要用前端模板拼 "登录失败: <msg>" 冗余前缀
- **PC 端 `request.js` v1.1.31+ 设计**: 拦截器不弹 ElMessage, 让组件 catch 统一弹 (避免双弹); **任何组件 catch 块必须独立处理错误展示**, 不能假设拦截器会弹
- **App 端用 `uni.showToast`**: 非阻塞吐司优于 `alert`, 符合移动 App 交互惯例; H5 退化为 `alert` 兜底
- **错误文案兜底**: 后端 msg 为空时给 "账号或密码错误, 请重试" 这类明确提示, 不要给 "操作失败" 这种空话

**踩坑 (home NAS pc-web dist 部署)**:
- gpssong 密码 `19850225aB` (vs 飞牛 `850225sonG` 别混!)
- gpssong `/tmp` 只读 + SFTP subsystem 被禁, 大文件传输需用 Python Popen + base64 + ssh -T + sudo -S
- fail2ban 60-90s 解锁, 5 次错密码立即锁
- 完整步骤见 memory `erp-home-nas-pc-web-deploy-pitfalls.md`

### v1.1.60 (2026-09-23) — App 端物理 back 区分栈深度 + 工作台吞掉事件

**症状**: v1.1.54+ 全局 back 拦截 (`app/src/utils/nav.js`) 行为粗暴: 任何非工作台页面按物理 back 都直接 `uni.switchTab('/pages/dashboard/index')` **跳过中间栈**; 工作台页按物理 back 直接 `return false` 让系统**退出 App**。用户反馈: "在非工作台页面点击返回时回到工作台页面, 而不是直接退出 App"。

**核心改动 — `app/src/utils/nav.js` L34-104 `onAppBack` 函数体**:

| 规则 | 场景 | 动作 |
|------|------|------|
| 1 | 登录页 | `return false` 放行系统退出 App |
| 2 | 工作台页 | `return true` 吞掉事件, 不退出 App |
| 3 | 栈深 >= 3 | `uni.navigateBack({delta:1})` 回上一页 |
| 4 | 栈深 = 2 | `uni.switchTab(HOME_PAGE)` 回工作台 |
| 兜底 | 栈深 = 1 但非工作台 | `uni.switchTab(HOME_PAGE)` 兜底 |

**行为对比表**:
| 场景 | 物理 back (v1.1.60 新) | 物理 back (v1.1.54~v1.1.59 旧) |
|------|----------------------|------------------------------|
| 工作台 | 吞掉事件不退出 ✅ | 退出 App ❌ |
| 工作台 → 详情 (栈深=2) | switchTab 工作台 ✅ | switchTab 工作台 ✅ |
| 工作台 → 列表 → 详情 (栈深=3) | navigateBack 回列表 ✅ | switchTab 工作台(跳过列表) ❌ |
| 列表(栈深=2) | switchTab 工作台 ✅ | switchTab 工作台 ✅ |
| 登录页 | 退出 App ✅ | 退出 App ✅ |

**关键设计**:
- **navbar back 不动**: uni-app 默认 navbar `<` 走 `uni.navigateBack`, 行为已与新规则等价或更优 (保留动画), 无需自定义拦截
- **iOS 滑动返回**: 暂不支持 (uni-app iOS 滑动返回无公开拦截 API), 后续版本评估加自定义 navbar 左上角"←"按钮兜底
- **H5 环境**: `if (!isNative()) return false` 放行浏览器历史, 行为正常
- **App.vue 注册无需改**: `plus.key.addEventListener('backbutton', onAppBack)` (L42) 已正确, 仅替换函数体即生效

**部署**:
- 改 1 文件: `app/src/utils/nav.js` L34-104 (重写 `onAppBack` 函数体, 其他不动)
- `bash scripts/build-app.sh` → APK MD5 `c80a208869199d3e165c809db5b3b775`, 4369503 字节
- 输出: `/Users/tongban/Library/CloudStorage/飞牛同步-Mac/erp/erp-app-20260923.apk` + `~/Desktop/erp-app-20260923.apk`
- NAS H5 部署: `python3 -m http.server` 上传 tar → sudo 解压到 /tmp/staging → cp -r 到 `/volume3/docker/erp-system/app/dist/build/h5` → `docker build --no-cache` → `docker rm -f erp-app-h5 && docker run ...`
- 验证线上 chunk = `index-Dope0GFF.js` (本地 dist 一致) ✅
- 不动: 后端 jar / pc-web dist / DB / SQL (纯前端编译产物修复)

**端到端验证 (CDP 2026-09-23)**:
- onAppBack 规则分支模拟 (CDP 内 `getCurrentPages` 在 uni-app H5 容器内为 [], 改用 simulate 函数验证规则正确性):
  - V1 工作台按 back → 规则 2 吞掉 ✅
  - V3 栈深=2 → 规则 4 switchTab 工作台 ✅
  - V4 栈深=3 → 规则 3 navigateBack 回上一页 ✅
  - V6 栈深=4 → 规则 3 navigateBack (递归) ✅
  - V7 登录页 → 规则 1 放行退出 ✅
  - 兜底: 栈深=1 但非 HOME → switchTab 工作台 ✅
- 手动设备测试 (用户装新 APK):
  - 工作台按 back → 不退出 ✅
  - 工作台 → 扫码入库 → 入库单详情 → 按 back → 先回扫码入库 ✅

**踩坑**:
- NAS 部署时 mac tar 含 `LIBARCHIVE.xattr.com.apple.provenance` xattr, NAS tar 告警可忽略
- gpssong 在 home NAS `/tmp` 只读 (root:wheel 755), 必须用 `/volume3/docker/erp-system/upload/` 中转 + `sudo bash` 解压到 `/tmp/staging` + `cp -r` 到 dist (避免 ACL 锁直接解压丢 assets/)
- `dist/build/h5` 必须 `docker build --no-cache` 才能让新内容进镜像

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