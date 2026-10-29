// App 端 API 基地址
// - 原生 / Capacitor: 写死绝对地址 (避免 WebView 里读 localStorage 缓存出问题)
// - H5 (浏览器): 用「同源 /api」, 由部署侧的 nginx (如飞牛 18090) 反代到 backend, 天然无 CORS.
//   这正是登录页 placeholder "留空使用默认: /api" 暗示的既有设计, 之前 getBase() 误把 H5 也硬成绝对域名, 才在 :18090 上跨域打到 home.93gushi.com:8088 触发 CORS.
//
// v1.1.105: isH5Runtime() 原先在本文件复制一份 (只认 `plus`), 误把 Capacitor APK 判成 H5
//           → base 变 '/api' → 请求打回 WebView 自己的 localhost 拿到 index.html → 登录失败。
//           判定逻辑收敛到 utils/runtime.js 单一权威源, 此处只 import。
import { isH5Runtime } from '../utils/runtime.js'
import { getApiBaseOverride } from '../utils/apiBase.js'

// v1.1.107: 生产构建静默 DEBUG 级日志。import.meta.env.DEV/PROD 由 vite 在构建期替换,
//   生产 H5 / APK 里 DEV=false → 下面的 console.log(DEBUG) 全不执行, 只有 warn(网络层/非JSON)保留。
//   保留 warn 是有意的: 非 JSON 响应 / 网络失败是最难排查的场景, R13 原则下值得打。
const DEBUG = typeof import.meta !== 'undefined' ? !!import.meta.env?.DEV : false
function logDebug(...a) { if (DEBUG) console.log(...a) }


const DEFAULT_API = 'http://home.93gushi.com:8088/api'

// 原生 / Capacitor 默认地址也读 storage 的显式覆盖 (服务器设置里填了绝对地址时生效)
function getNativeBase() {
  return getApiBaseOverride() || DEFAULT_API
}

function getBase() {
  if (isH5Runtime()) {
    // 同源: 相对路径 '/api', fetch/uni.request 会自动拼上当前 origin.
    // 用户填了服务器地址时优先使用 (统一由 utils/apiBase.js 补协议与 /api).
    const override = getApiBaseOverride()
    if (override) return override
    return '/api'
  }
  // 原生 / Capacitor: 绝对地址 (默认写死 home, 用户在服务器设置填了绝对地址则覆盖)
  return getNativeBase()
}

function getToken() {
  // v1.1.8+: Token 由后端 Set-Cookie (httpOnly, SameSite=Lax) 自动管理.
  // uni.request 和 fetch 在同源场景下自动附带 cookie, 此处不再从 storage 读 token.
  return ''
}

function request({ url, method = 'GET', data = {}, contentType, silent }) {
  const token = getToken()
  const base = getBase()
  const fullUrl = base + url

  logDebug('[api] DEBUG base:', base)
  logDebug('[api] DEBUG fullUrl:', fullUrl)
  logDebug('[api] DEBUG uni.request type:', typeof uni, typeof uni?.request)

  // H5 环境 (浏览器预览): uni.request 不可用, 退化为原生 fetch
  // 检测方式: typeof uni.request !== 'function'
  if (typeof uni === 'undefined' || typeof uni.request !== 'function') {
    logDebug('[api] → using fetch for:', fullUrl)
    return fetchRequest(fullUrl, method, data)
  }

  // 真机/小程序环境: 用 uni.request
  // 不显式带 Authorization header, 由浏览器/Capacitor WebView 自动附带 httpOnly cookie.
  const header = {}
  // v1.1.55 hotfix-3 R9: 让后端 AuthService 按 X-Client-Type=APP 返回纯 APP perms,
  //   避免 PC uncheck 等 perm 串到 App 端 (storage 派生 + 老 APK 残留问题根除)
  header['X-Client-Type'] = 'APP'
  if (contentType === 'json') header['Content-Type'] = 'application/json'
  return new Promise((resolve, reject) => {
    uni.request({
      url: base + url,
      method,
      data,
      header,
      // 跨域请求时也允许带 cookie (httpOnly SameSite=Lax cookie)
      withCredentials: true,
      success: (res) => {
        // 兼容: nginx / 反代可能返回 HTML 错误页 (例如 502 Bad Gateway)
        // uni.request 在不同平台 header 字段大小写不一致 (H5 小写, App 首字母大写),
        // 容错: 同时取 Content-Type / content-type, 再降级到 typeof 判断
        const rawCt = (res.header && (res.header['Content-Type'] || res.header['content-type'])) || ''
        const ct = typeof rawCt === 'string' ? rawCt : ''
        const looksLikeJson = ct.indexOf('application/json') >= 0
        const dataIsObject = res.data !== null && typeof res.data === 'object'
        if (!dataIsObject || (!looksLikeJson && ct !== '')) {
          // 非 JSON 响应 — 通常是反代错误页或 502/504
          console.warn('[api] 非 JSON 响应:', url, 'status=', res.statusCode, 'ct=', ct, 'data=', res.data)
          // 智能错误提示: 如果请求打到 localhost, 提示用户改服务器设置
          let msg = '服务暂不可用, 请稍后重试'
          try {
            const cached = getApiBaseOverride()
            if (cached && cached.includes('localhost')) {
              msg = '服务器地址指向本机, 请在"我的→服务器设置"中改为实际服务器地址'
            }
          } catch (e) {}
          const err = { code: res.statusCode || 500, msg, data: null }
          if (typeof uni !== 'undefined' && uni.showToast) {
            uni.showToast({ title: msg, icon: 'none', duration: 3000 })
          }
          reject(err)
          return
        }
        const d = res.data
        if (d.code === 200) resolve(d.data)
        else if (d.code === 403 && d.msg && d.msg.indexOf('默认密码') >= 0) {
          // 默认密码用户: 后端只放行改密接口, 前端统一跳到改密页
          if (typeof uni !== 'undefined' && uni.showToast) {
            uni.showToast({ title: '请先修改默认密码', icon: 'none', duration: 2500 })
          }
          setTimeout(() => {
            try { uni.navigateTo({ url: '/pages/profile/change-password' }) } catch (e) {}
          }, 100)
          reject(d)
        }
        else if (d.code === 401) {
          // 401: 清理用户信息/菜单/权限缓存 (token 由 cookie 管理, 不在 JS 端)
          try { uni.removeStorageSync('erp_user') } catch (e) {}
          try { uni.removeStorageSync('erp_menus') } catch (e) {}
          try { uni.removeStorageSync('erp_permissions') } catch (e) {}
          try { localStorage.removeItem('erp_user') } catch (e) {}
          try { localStorage.removeItem('erp_menus') } catch (e) {}
          try { localStorage.removeItem('erp_permissions') } catch (e) {}
          // v1.0.10+: 用 reLaunch 之前先 reject, 让调用方处理 UI 反馈
          reject(Object.assign(d, { needRelogin: true }))
          // 异步跳转 (用 setTimeout 避免阻塞 reject)
          setTimeout(() => {
            // H5: 走 hash 路由 (uni.reLaunch 在 401 场景可能 'reLaunch:fail'); 原生: uni.reLaunch
            if (isH5Runtime() && typeof window !== 'undefined') {
              window.location.hash = '#/pages/login/index'
            } else {
              try { uni.reLaunch({ url: '/pages/login/index' }) } catch (e) {}
            }
          }, 100)
        } else {
          // silent=true (如配置类读接口) 失败不弹 toast, 调用方 fail-open 兜底
          if (!silent && typeof uni !== 'undefined' && uni.showToast) {
            uni.showToast({ title: d.msg || '请求失败', icon: 'none' })
          }
          reject(d)
        }
      },
      fail: (err) => {
        // 网络层失败 (无网络 / DNS 失败 / 超时)
        console.warn('[api] uni.request 网络失败:', url, err)
        const e = { code: 0, msg: '网络连接失败, 请检查网络', data: null }
        if (typeof uni !== 'undefined' && uni.showToast) {
          uni.showToast({ title: e.msg, icon: 'none' })
        }
        reject(e)
      }
    })
  })
}

// H5 (浏览器) 环境的 fetch 实现, 与 uni.request 行为一致
function fetchRequest(url, method, data) {
  logDebug('[api] fetchRequest called, url:', url, 'method:', method)
  return fetch(url, {
    method,
    credentials: 'include',  // httpOnly cookie
    // v1.1.55 hotfix-3 R9: 同上, fetch (H5) 也要带 X-Client-Type=APP
    headers: { 'Content-Type': 'application/json', 'X-Client-Type': 'APP' },
    body: data && method !== 'GET' ? JSON.stringify(data) : undefined
  })
  .then(r => {
    logDebug('[api] fetchResponse status:', r.status, 'url:', url)
    return r.json()
  })
  .then(d => {
    if (d.code === 200) return d.data
    if (d.code === 401) {
      // 401: 清理用户信息/菜单/权限缓存 (token 由 cookie 管理)
      try { localStorage.removeItem('erp_user') } catch (e) {}
      try { localStorage.removeItem('erp_menus') } catch (e) {}
      try { localStorage.removeItem('erp_permissions') } catch (e) {}
      // 异步跳转, 让调用方先处理 UI
      // v1.1.106: 原用 `typeof plus` 判原生 (R19 已禁), Capacitor 下永不命中 →
      //   401 后 H5 的 fetch 路径会漏跳转。统一走 runtime.js 的 isH5Runtime。
      setTimeout(() => {
        if (isH5Runtime() && typeof window !== 'undefined') {
          window.location.hash = '#/pages/login/index'
        } else if (typeof uni !== 'undefined') {
          uni.reLaunch({ url: '/pages/login/index' })
        }
      }, 100)
      throw Object.assign(d, { needRelogin: true })
    }
    if (typeof uni !== 'undefined' && uni.showToast) {
      uni.showToast({ title: d.msg || '请求失败', icon: 'none' })
    } else {
      alert(d.msg || '请求失败')
    }
    throw d
  })
}

export const api = {
  login: (data) => request({ url: '/auth/login', method: 'POST', data }),
  getCaptcha: () => request({ url: '/auth/captcha' }),
  me: () => request({ url: '/auth/me' }),
  // 库存
  stockPage: (params) => request({ url: '/inventory/stock/page', data: params }),
  // 库存盘点 (v1.0.8+ App 外勤盘点)
  // - stockSnapshot: 列出仓库所有有库存商品的账面快照 (App 扫码前预加载, 避免盲盘)
  // - invCheckSubmit: 提交盘点, 生成 DRAFT 盘点单 (PC 端审核后调库存)
  stockSnapshot: (warehouseId) => request({ url: '/inventory/check/stock-snapshot/' + warehouseId }),
  // v1.1.117: 某仓+某商品的可选批次 (扫码出库批次复选用; 权限 inventory:stock:list 或 sales:delivery:list)
  stockBatches: (warehouseId, productId) => request({ url: '/inventory/stock/batches', data: { warehouseId, productId } }),
  invCheckSubmit: (data) => request({ url: '/inventory/check/submit-from-app', method: 'POST', data, contentType: 'json' }),
  // 客户
  customerList: () => request({ url: '/base/customer/list' }),
  // 供应商
  supplierList: () => request({ url: '/base/supplier/list' }),
  // 仓库 (v1.1.7+ App 扫码出库需要选仓库)
  warehouseList: () => request({ url: '/base/warehouse/list' }),
  // 商品 (App 端用 appSearch,PC 端用 page)
  productPage: (params) => request({ url: '/base/product/page', data: params }),
  productAppSearch: (keyword) => request({ url: '/base/product/app-search', data: { keyword } }),
  productDetail: (id) => request({ url: '/base/product/' + id }),
  productAdd: (data) => request({ url: '/base/product', method: 'POST', data, contentType: 'json' }),
  productUpdate: (id, data) => request({ url: '/base/product/' + id, method: 'PUT', data, contentType: 'json' }),
  // 销售
  salesOrderPage: (params) => request({ url: '/sales/order/page', data: params }),
  salesOrderAdd: (data) => request({ url: '/sales/order', method: 'POST', data }),
  salesDeliveryAdd: (data) => request({ url: '/sales/delivery', method: 'POST', data }),
  // v1.1.7+ App 扫码出库对应的"该客户历史销售"
  customerHistoryProducts: (customerId) => request({ url: '/sales/delivery/customer-history-products', data: { customerId } }),
  // v1.1.7+ App 扫码出库提交后,跳转到 PC 端审查 — 供前端提交后获取单据 ID
  salesDeliveryDetail: (id) => request({ url: '/sales/delivery/' + id }),
  // v1.1.14+: App 端销售出库单查询列表
  salesDeliveryPage: (params) => request({ url: '/sales/delivery/page', data: params }),
  // 采购
  purchaseOrderPage: (params) => request({ url: '/purchase/order/page', data: params }),
  // v1.1.125: 必须带 contentType:'json' — 后端 @RequestBody 只吃 application/json.
  //   漏掉时 uni.request 走默认 urlencoded → Spring 报 "Content-Type
  //   'application/x-www-form-urlencoded;charset=UTF-8' is not supported" → 500.
  //   (实测: form-encoded POST → code 500; JSON POST → code 200)
  purchaseReceiptAdd: (data) => request({ url: '/purchase/receipt', method: 'POST', data, contentType: 'json' }),
  // v1.1.125: 上次入库单价 (供应商+商品) — 非食品建单页自动带出默认单价
  purchaseReceiptLastPrice: (supplierId, productId) =>
    request({ url: '/purchase/receipt/last-price', data: { supplierId, productId } }),
  // v1.1.125: 该供应商最近 50 条历史采购明细 — 非食品建单页参考/一键带入
  purchaseReceiptSupplierHistory: (supplierId) =>
    request({ url: '/purchase/receipt/supplier-history-products', data: { supplierId } }),
  // v1.1.15+: App 端采购入库单查询列表
  purchaseReceiptPage: (params) => request({ url: '/purchase/receipt/page', data: params }),
  purchaseReceiptDetail: (id) => request({ url: '/purchase/receipt/' + id }),
  // v1.1.54+: App 端审核/反审核 (PC 端已 end-to-end, 这里只补前端调用入口)
  // 复用后端 POST /purchase/receipt/{id}/check 等, 后端 perm 用 xxx:check (反审核也复用同一 perm)
  purchaseReceiptCheck:   (id) => request({ url: '/purchase/receipt/' + id + '/check',   method: 'POST' }),
  purchaseReceiptUncheck: (id) => request({ url: '/purchase/receipt/' + id + '/uncheck', method: 'POST' }),
  salesDeliveryCheck:     (id) => request({ url: '/sales/delivery/'   + id + '/check',   method: 'POST' }),
  salesDeliveryUncheck:   (id) => request({ url: '/sales/delivery/'   + id + '/uncheck', method: 'POST' }),
  // 生产
  prdOrderPage: (params) => request({ url: '/production/order/page', data: params }),
  prdOrderDetail: (id) => request({ url: '/production/order/' + id }),
  prdOrderAdd: (data) => request({ url: '/production/order', method: 'POST', data, contentType: 'json' }),
  prdOrderUpdate: (id, data) => request({ url: '/production/order/' + id, method: 'PUT', data, contentType: 'json' }),
  prdOrderRelease: (id) => request({ url: '/production/order/' + id + '/release', method: 'POST' }),
  prdOrderDelete: (id) => request({ url: '/production/order/' + id, method: 'DELETE' }),
  // v1.1.132 XK16-204 开工确认: 生产环境合格 + 投料原料批号 + 批号照片 (吹膜) / 领用半成品 (制袋)
  prdOrderBlowStart: (id, data) => request({ url: '/production/order/' + id + '/blow-start', method: 'POST', data, contentType: 'json' }),
  prdOrderBagStart: (id, data) => request({ url: '/production/order/' + id + '/bag-start', method: 'POST', data, contentType: 'json' }),
  prdOrderStartRecords: (id) => request({ url: '/production/order/' + id + '/work-start-records', silent: true }),
  // v1.1.101 App XK 吹膜生产全流程 — 半成品入库 / 成品入库 (仓管现场建单 + 过门禁审核)
  // 复用 PC 端已有后端端点, App 端只补前端调用入口
  prdSemiInPage:    (params) => request({ url: '/production/semi-in/page', data: params }),
  prdSemiInAdd:     (data) => request({ url: '/production/semi-in', method: 'POST', data, contentType: 'json' }),
  prdSemiInCheck:   (id) => request({ url: '/production/semi-in/' + id + '/check', method: 'POST' }),
  prdSemiInUncheck: (id) => request({ url: '/production/semi-in/' + id + '/uncheck', method: 'POST' }),
  prdSemiInDelete:  (id) => request({ url: '/production/semi-in/' + id, method: 'DELETE' }),
  prdFinishedInPage:    (params) => request({ url: '/production/finished-in/page', data: params }),
  prdFinishedInAdd:     (data) => request({ url: '/production/finished-in', method: 'POST', data, contentType: 'json' }),
  prdFinishedInCheck:   (id) => request({ url: '/production/finished-in/' + id + '/check', method: 'POST' }),
  prdFinishedInUncheck: (id) => request({ url: '/production/finished-in/' + id + '/uncheck', method: 'POST' }),
  prdFinishedInDelete:  (id) => request({ url: '/production/finished-in/' + id, method: 'DELETE' }),
  // v1.1.11+: 分享 PDF (返回完整 URL, 配合 baseURL, 给 uni.downloadFile 用)
  prdOrderPdfUrl: (id) => {
    let base = getApiBaseOverride()
    if (!base) base = isH5Runtime() ? window.location.origin + '/api' : getNativeBase()
    return base + '/production/order/' + id + '/pdf'
  },
  // 飞鹅云打印
  feiePrint: (bizType, id) => request({ url: '/feie/print/' + bizType + '/' + id, method: 'POST' }),
  // v1.1.80 MES: 扫码报工 — 按生产单号查工序实例 + 报工提交
  prdOrderProgress: (orderId, opts) => request({ url: '/mes/order/' + orderId + '/progress', silent: opts && opts.silent }),
  mesReport: (data) => request({ url: '/mes/report', method: 'POST', data, contentType: 'json' }),
  // v1.1.90 Phase 2 — 质量检验 (QMS)
  // 4 类 IQC/FAI/PAT/OQC 合一, App 端只提交草稿 (DRAFT), 审核走 PC 端
  qmsInspectionPage: (params) => request({ url: '/qms/inspection/page', data: params }),
  qmsInspectionDetail: (id) => request({ url: '/qms/inspection/' + id }),
  qmsInspectionCreate: (data) => request({ url: '/qms/inspection', method: 'POST', data, contentType: 'json' }),
  qmsInspectionCheck: (id, data) => request({ url: '/qms/inspection/' + id + '/check', method: 'POST', data, contentType: 'json' }),
  qmsInspectionReject: (id, data) => request({ url: '/qms/inspection/' + id + '/reject', method: 'POST', data, contentType: 'json' }),
  // v1.1.90 Phase 2 — 工艺参数 + SOP (PDM, App 只读)
  pdmParamList: (processDefId) => request({ url: '/pdm/process/param/list', data: { processDefId } }),
  pdmSopList: (processDefId) => request({ url: '/pdm/process/sop/list', data: { processDefId } }),
  pdmSopActivate: (id) => request({ url: '/pdm/process/sop/' + id + '/activate', method: 'POST' }),
  // 工序定义列表 (复用 MES 一期端点, App 端拉工序链)
  mesProcessDefList: (productId) => request({ url: '/mes/process-def/list', data: productId != null ? { productId } : {} }),
  // v1.1.90 Phase 2 — 设备台账 + 维保 (EM)
  equipmentPage: (params) => request({ url: '/base/equipment/page', data: params }),
  equipmentProcessList: (equipmentId) => request({ url: '/base/equipment/process/list', data: { equipmentId } }),
  maintainPage: (params) => request({ url: '/workflow/maintain/page', data: params }),
  maintainAdd: (data) => request({ url: '/workflow/maintain', method: 'POST', data, contentType: 'json' }),
  // v1.1.100+: XK 端点拿 em_maintain_order 新表 PENDING 工单 (dashboard 现场待办复用).
  // v1.1.99 附带清理后 wf_maintain_record 老表已废弃, em 模块本身无独立 controller,
  //   /xk/maintenance/orders 是 App 端唯一能拿新表数据的接口.
  xkMaintenanceOrders: (params) => request({ url: '/xk/maintenance/orders', data: params || {} }),
  // v1.1.102 hotfix-4: 设备报修写新表 em_maintain_order (与 XK 设备维保页同源).
  // 旧 maintainAdd (/workflow/maintain 老表) 与维保页不通, 报修后维保页看不到工单.
  emMaintainOrderAdd: (data) => request({ url: '/em/maintain-order', method: 'POST', data, contentType: 'json' }),
  emMaintainOrderComplete: (id, result, reviewer) => request({ url: '/em/maintain-order/' + id + '/complete', method: 'POST', data: { result, reviewer }, contentType: 'json' }),
  // v1.1.99 C4 MES 排程 + OEE (App 端联动)
  // - schedulePage: 分页查排程实例 (按工单/设备/时间窗)
  // - scheduleGantt: 单设备甘特数据 (按日 24h 时间条)
  // - scheduleSave: 新建/更新排程 (App 端工人可手动派单到设备)
  // - scheduleCancel: 取消排程
  // - oee: 单设备 OEE 三率 (稼动率 × 性能率 × 良品率)
  // - traceFull: 批次全链追溯 (料→半成品→成品→客户, BFS 8 层), 替代老 mock 链
  schedulePage:    (params) => request({ url: '/mes/schedule/page', data: params || {} }),
  scheduleGantt:   (equipmentId) => request({ url: '/mes/schedule/gantt/' + equipmentId }),
  scheduleSave:    (data) => request({ url: '/mes/schedule', method: 'POST', data, contentType: 'json' }),
  scheduleCancel:  (id) => request({ url: '/mes/schedule/' + id + '/cancel', method: 'POST' }),
  oee:             (equipmentId) => request({ url: '/mes/oee/' + equipmentId }),
  batchTraceFull:  (batchNo) => request({ url: '/mes/batch/trace-full/' + batchNo }),
  // 设备列表 (App 端选设备调)
  equipmentList:   () => request({ url: '/base/equipment/list' }),
  // 报表
  dashboard: () => request({ url: '/report/dashboard' }),
  inventorySummary: () => request({ url: '/report/inventory/summary' }),
  // 库存预警 (v1.1.44+ 显示具体产品列表, 与 /report/dashboard.warningCount 共用同实时统计逻辑)
  warningList: () => request({ url: '/inventory/warning/list' }),
  // v1.1.76+: AI 助手 (对话 + 补货预测). 后端门禁 report:ai (App 端授权), 无 ERP_AI_API_KEY 时快速失败.
  aiAgentChat: (question) => request({ url: '/ai/agent/chat', method: 'POST', data: { question }, contentType: 'json' }),
  // 补货预测: query 参数直接拼进 URL (H5 fetch 回退不序列化 GET data → 必须走 URL)
  aiReplenishSuggest: (keyword, limit) => {
    const qs = [
      keyword ? 'keyword=' + encodeURIComponent(keyword) : null,
      limit != null ? 'limit=' + limit : null
    ].filter(Boolean).join('&')
    return request({ url: '/ai/replenish/suggest' + (qs ? '?' + qs : '') })
  },
  // v1.1.78: AI 语音输入 (原生 App 端录音上传后端 ASR). 走 uni.uploadFile (multipart).
  // H5 端走 Web Speech API 端侧识别, 不调本方法. filePath = uni.getRecorderManager 产出的 tempPath.
  // 未配 ERP_AI_ASR_KEY 时后端返回非 200 (code != 200), reject 给前端 toast.
  aiAsr: (filePath) => {
    const base = getBase()
    return new Promise((resolve, reject) => {
      uni.uploadFile({
        url: base + '/ai/asr',
        filePath,
        name: 'file',
        header: { 'X-Client-Type': 'APP' },
        success: (res) => {
          try {
            const d = JSON.parse(res.data)
            if (d.code === 200) resolve(d.data)
            else reject(Object.assign(d, { code: d.code }))
          } catch (e) {
            reject({ code: 0, msg: '语音识别响应解析失败' })
          }
        },
        fail: (err) => reject({ code: 0, msg: '语音上传失败, 请检查网络' })
      })
    })
  },
  // 用户管理
  userPage: (params) => request({ url: '/system/user/page', data: params }),
  userDetail: (id) => request({ url: '/system/user/' + id }),
  userAdd: (data) => request({ url: '/system/user', method: 'POST', data }),
  userUpdate: (data) => request({ url: '/system/user', method: 'PUT', data }),
  userDelete: (id) => request({ url: '/system/user/' + id, method: 'DELETE' }),
  userResetPwd: (id) => request({ url: '/system/user/' + id + '/resetPwd', method: 'POST' }),
  userGetRoles: (id) => request({ url: '/system/user/' + id + '/roles' }),
  changeMyPassword: (data) => request({ url: '/system/user/me/password', method: 'PUT', data }),
  changePassword: (data) => request({ url: '/system/user/me/password', method: 'PUT', data }),
  userAssignRoles: (id, roleIds) => request({ url: '/system/user/' + id + '/roles', method: 'PUT', data: roleIds }),
  // 角色管理
  rolePage: (params) => request({ url: '/system/role/page', data: params }),
  // v1.1.80+: App 模块开关 (admin 全局一刀切, App 端只读)
  // 后端走 sys_config 表, key=APP_MODULE_ENABLED, value=JSON 字符串
  appModuleGetConfig: () => request({ url: '/system/config/key/APP_MODULE_ENABLED', silent: true }),
  // v1.1.130+: App 工作台区块开关 (4 区块 + 全部功能 7 子分组), key=APP_DASHBOARD_CONFIG
  appDashboardGetConfig: () => request({ url: '/system/config/key/APP_DASHBOARD_CONFIG', silent: true }),
  // ===== v1.1.126: 吹膜米克重采集 (ESP32 → /mes/weight/**) =====
  // ⚠️ App 端拦截器已 resolve(d.data) — 返回值 r 就是业务数据, **不可再读 r.data**
  //    (v1.1.118 教训: 页面写 (r && r.data) || {} → 恒 {} → 字段静默空)
  // 门禁: mes:weight:list (读) / mes:weight:edit (维护通道)
  weightDevicePage:   (params) => request({ url: '/mes/weight/device/page', data: params }),
  weightChannelList:  (deviceId) => request({ url: '/mes/weight/channel/list', data: { deviceId } }),
  weightChannelSave:  (data) => request({ url: '/mes/weight/channel', method: 'POST', data, contentType: 'json' }),
  weightCurve:        (params) => request({ url: '/mes/weight/curve', data: params }),
  weightStats:        (params) => request({ url: '/mes/weight/stats', data: params }),
  weightSamplePage:   (params) => request({ url: '/mes/weight/sample/page', data: params })
}
// v1.1.91 XK 合规中心: 导出 request 供 api/xk.js 复用 (uni.request 已解包 R.data)
export { request }
export default api
