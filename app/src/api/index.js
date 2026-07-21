// 原生 App 默认后端地址 (H5 用相对路径 /api, 原生必须用绝对路径)
const NATIVE_DEFAULT_API = 'http://home.93gushi.com:8088/api'

function isNative() {
  // 原生 App 环境: HBuilderX (plus对象) 或 Capacitor (Capacitor对象)
  return typeof plus !== 'undefined' || (typeof Capacitor !== 'undefined' && Capacitor.isNativePlatform && Capacitor.isNativePlatform())
}

function getBase() {
  // 优先级: 本地缓存 > 原生默认地址 > H5 相对路径
  try {
    const cached = (typeof localStorage !== 'undefined' && localStorage.getItem('erp_api_base'))
    if (cached) return cached
    return isNative() ? NATIVE_DEFAULT_API : '/api'
  } catch (e) {
    return isNative() ? NATIVE_DEFAULT_API : '/api'
  }
}

function getToken() {
  try {
    if (typeof uni !== 'undefined' && uni.getStorageSync) return uni.getStorageSync('erp_token')
    return localStorage.getItem('erp_token')
  } catch (e) { return null }
}

function request({ url, method = 'GET', data = {}, contentType }) {
  const token = getToken()
  const base = getBase()

  // H5 环境 (浏览器预览): uni.request 不可用, 退化为原生 fetch
  // 检测方式: typeof uni.request !== 'function'
  if (typeof uni === 'undefined' || typeof uni.request !== 'function') {
    return fetchRequest(base + url, method, data, token, contentType)
  }

  // 真机/小程序环境: 用 uni.request
  const header = { 'Authorization': token || '' }
  if (contentType === 'json') header['Content-Type'] = 'application/json'
  return new Promise((resolve, reject) => {
    uni.request({
      url: base + url,
      method,
      data,
      header,
      success: (res) => {
        const d = res.data
        if (d.code === 200) resolve(d.data)
        else if (d.code === 401) {
          // 401: 清理所有登录相关缓存 (不只是 token, 还有用户信息/菜单/权限)
          try { uni.removeStorageSync('erp_token') } catch (e) {}
          try { localStorage.removeItem('erp_token') } catch (e) {}
          try { uni.removeStorageSync('erp_user') } catch (e) {}
          try { uni.removeStorageSync('erp_menus') } catch (e) {}
          try { uni.removeStorageSync('erp_permissions') } catch (e) {}
          try { localStorage.removeItem('erp_user') } catch (e) {}
          try { localStorage.removeItem('erp_menus') } catch (e) {}
          try { localStorage.removeItem('erp_permissions') } catch (e) {}
          uni.reLaunch({ url: '/pages/login/index' })
          reject(d)
        } else {
          uni.showToast({ title: d.msg || '请求失败', icon: 'none' })
          reject(d)
        }
      },
      fail: reject
    })
  })
}

// H5 (浏览器) 环境的 fetch 实现, 与 uni.request 行为一致
function fetchRequest(url, method, data, token) {
  return fetch(url, {
    method,
    headers: {
      'Content-Type': 'application/json',
      'Authorization': token || ''
    },
    body: data && method !== 'GET' ? JSON.stringify(data) : undefined
  })
  .then(r => r.json())
  .then(d => {
    if (d.code === 200) return d.data
    if (d.code === 401) {
      // 401: 清理所有登录相关缓存
      try { localStorage.removeItem('erp_token') } catch (e) {}
      try { localStorage.removeItem('erp_user') } catch (e) {}
      try { localStorage.removeItem('erp_menus') } catch (e) {}
      try { localStorage.removeItem('erp_permissions') } catch (e) {}
      if (typeof plus !== 'undefined' && typeof uni !== 'undefined') {
        uni.reLaunch({ url: '/pages/login/index' })
      } else if (typeof window !== 'undefined') {
        window.location.hash = '#/pages/login/index'
      }
      throw d
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
  // 采购
  purchaseOrderPage: (params) => request({ url: '/purchase/order/page', data: params }),
  purchaseReceiptAdd: (data) => request({ url: '/purchase/receipt', method: 'POST', data }),
  // 生产
  prdOrderPage: (params) => request({ url: '/production/order/page', data: params }),
  prdOrderDetail: (id) => request({ url: '/production/order/' + id }),
  prdOrderAdd: (data) => request({ url: '/production/order', method: 'POST', data, contentType: 'json' }),
  prdOrderUpdate: (id, data) => request({ url: '/production/order/' + id, method: 'PUT', data, contentType: 'json' }),
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
