// App 端 API 基地址 — 永远使用绝对路径, 不读 localStorage 缓存
const DEFAULT_API = 'http://home.93gushi.com:8088/api'

function getBase() {
  // 硬编码直接返回, 避免 Capacitor WebView 环境中 localStorage 缓存导致的问题
  return DEFAULT_API
}

function getToken() {
  // v1.1.8+: Token 由后端 Set-Cookie (httpOnly, SameSite=Lax) 自动管理.
  // uni.request 和 fetch 在同源场景下自动附带 cookie, 此处不再从 storage 读 token.
  return ''
}

function request({ url, method = 'GET', data = {}, contentType }) {
  const token = getToken()
  const base = getBase()
  const fullUrl = base + url

  console.log('[api] DEBUG base:', base)
  console.log('[api] DEBUG fullUrl:', fullUrl)
  console.log('[api] DEBUG uni.request type:', typeof uni, typeof uni?.request)

  // H5 环境 (浏览器预览): uni.request 不可用, 退化为原生 fetch
  // 检测方式: typeof uni.request !== 'function'
  if (typeof uni === 'undefined' || typeof uni.request !== 'function') {
    console.log('[api] → using fetch for:', fullUrl)
    return fetchRequest(fullUrl, method, data)
  }

  // 真机/小程序环境: 用 uni.request
  // 不显式带 Authorization header, 由浏览器/Capacitor WebView 自动附带 httpOnly cookie.
  const header = {}
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
            const cached = (typeof localStorage !== 'undefined' && localStorage.getItem('erp_api_base'))
            if (cached && (cached.includes('localhost') || cached === '/api')) {
              msg = '服务器地址错误, 请在"我的→服务器设置"中改回 http://home.93gushi.com:8088/api'
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
            try { uni.reLaunch({ url: '/pages/login/index' }) } catch (e) {}
          }, 100)
        } else {
          if (typeof uni !== 'undefined' && uni.showToast) {
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
  console.log('[api] fetchRequest called, url:', url, 'method:', method)
  return fetch(url, {
    method,
    credentials: 'include',  // httpOnly cookie
    headers: { 'Content-Type': 'application/json' },
    body: data && method !== 'GET' ? JSON.stringify(data) : undefined
  })
  .then(r => {
    console.log('[api] fetchResponse status:', r.status, 'url:', url)
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
      setTimeout(() => {
        if (typeof plus !== 'undefined' && typeof uni !== 'undefined') {
          uni.reLaunch({ url: '/pages/login/index' })
        } else if (typeof window !== 'undefined') {
          window.location.hash = '#/pages/login/index'
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
  me: () => request({ url: '/auth/me' }),
  // 库存
  stockPage: (params) => request({ url: '/inventory/stock/page', data: params }),
  // 库存盘点 (v1.0.8+ App 外勤盘点)
  // - stockSnapshot: 列出仓库所有有库存商品的账面快照 (App 扫码前预加载, 避免盲盘)
  // - invCheckSubmit: 提交盘点, 生成 DRAFT 盘点单 (PC 端审核后调库存)
  stockSnapshot: (warehouseId) => request({ url: '/inventory/check/stock-snapshot/' + warehouseId }),
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
  purchaseReceiptAdd: (data) => request({ url: '/purchase/receipt', method: 'POST', data }),
  // v1.1.15+: App 端采购入库单查询列表
  purchaseReceiptPage: (params) => request({ url: '/purchase/receipt/page', data: params }),
  purchaseReceiptDetail: (id) => request({ url: '/purchase/receipt/' + id }),
  // 生产
  prdOrderPage: (params) => request({ url: '/production/order/page', data: params }),
  prdOrderDetail: (id) => request({ url: '/production/order/' + id }),
  prdOrderAdd: (data) => request({ url: '/production/order', method: 'POST', data, contentType: 'json' }),
  prdOrderUpdate: (id, data) => request({ url: '/production/order/' + id, method: 'PUT', data, contentType: 'json' }),
  prdOrderRelease: (id) => request({ url: '/production/order/' + id + '/release', method: 'POST' }),
  prdOrderDelete: (id) => request({ url: '/production/order/' + id, method: 'DELETE' }),
  // v1.1.11+: 分享 PDF (返回完整 URL, 配合 baseURL, 给 uni.downloadFile 用)
  prdOrderPdfUrl: (id) => {
    let base = ''
    try { base = (localStorage.getItem('erp_api_base') || '').replace(/\/$/, '') } catch (e) {}
    if (!base) base = 'http://home.93gushi.com:8088/api'
    return base + '/production/order/' + id + '/pdf'
  },
  // 飞鹅云打印
  feiePrint: (bizType, id) => request({ url: '/feie/print/' + bizType + '/' + id, method: 'POST' }),
  // 报表
  dashboard: () => request({ url: '/report/dashboard' }),
  inventorySummary: () => request({ url: '/report/inventory/summary' }),
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
}
export default api
