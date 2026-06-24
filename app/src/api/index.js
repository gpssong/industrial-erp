function getBase() {
  // 优先级: 本地缓存 > manifest 中配置的 BASE_URL > 默认
  try {
    return (typeof localStorage !== 'undefined' && localStorage.getItem('erp_api_base'))
      || (typeof __GLOBAL__ !== 'undefined' && typeof __GLOBAL__.API_BASE !== 'undefined' ? __GLOBAL__.API_BASE : null)
      || '/api'
  } catch (e) {
    return '/api'
  }
}

function getToken() {
  try {
    if (typeof uni !== 'undefined' && uni.getStorageSync) return uni.getStorageSync('erp_token')
    return localStorage.getItem('erp_token')
  } catch (e) { return null }
}

function request({ url, method = 'GET', data = {} }) {
  const token = getToken()
  const base = getBase()

  // H5 环境 (浏览器预览): uni.request 不可用, 退化为原生 fetch
  // 检测方式: typeof uni.request !== 'function'
  if (typeof uni === 'undefined' || typeof uni.request !== 'function') {
    return fetchRequest(base + url, method, data, token)
  }

  // 真机/小程序环境: 用 uni.request
  return new Promise((resolve, reject) => {
    uni.request({
      url: base + url,
      method,
      data,
      header: { 'Authorization': token || '' },
      success: (res) => {
        const d = res.data
        if (d.code === 200) resolve(d.data)
        else if (d.code === 401) {
          uni.removeStorageSync('erp_token')
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
      try { localStorage.removeItem('erp_token') } catch (e) {}
      if (typeof uni !== 'undefined' && uni.reLaunch) {
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

// H5 环境判断
export function isH5() {
  return typeof uni === 'undefined' || typeof uni.scanCode !== 'function' || typeof window !== 'undefined'
}

// 扫码: 优先用 uni.scanCode(真机/小程序), 否则用 html5-qrcode
export async function doScan({ onResult, onCancel }) {
  if (isH5() && typeof window !== 'undefined') {
    // H5 环境: 由调用方自己处理(用 html5-qrcode), 这里只给降级
    const c = prompt('请输入条码:')
    if (c) onResult && onResult(c)
    else onCancel && onCancel()
    return
  }
  // 原生环境
  try {
    const res = await new Promise((resolve, reject) => {
      uni.scanCode({ success: resolve, fail: reject })
    })
    onResult && onResult(res.result)
  } catch (e) {
    onCancel && onCancel()
  }
}

export const api = {
  login: (data) => request({ url: '/auth/login', method: 'POST', data }),
  me: () => request({ url: '/auth/me' }),
  // 库存
  stockPage: (params) => request({ url: '/inventory/stock/page', data: params }),
  // 客户
  customerList: () => request({ url: '/base/customer/list' }),
  // 销售
  salesOrderPage: (params) => request({ url: '/sales/order/page', data: params }),
  salesOrderAdd: (data) => request({ url: '/sales/order', method: 'POST', data }),
  salesDeliveryAdd: (data) => request({ url: '/sales/delivery', method: 'POST', data }),
  // 采购
  purchaseOrderPage: (params) => request({ url: '/purchase/order/page', data: params }),
  purchaseReceiptAdd: (data) => request({ url: '/purchase/receipt', method: 'POST', data }),
  // 报表
  dashboard: () => request({ url: '/report/dashboard' }),
  inventorySummary: () => request({ url: '/report/inventory/summary' })
}
export default api
