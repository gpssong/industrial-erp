function getBase() {
  // 优先级: 本地缓存 > manifest 中配置的 BASE_URL > 默认
  return uni.getStorageSync('erp_api_base')
    || (typeof __GLOBAL__.API_BASE !== 'undefined' ? __GLOBAL__.API_BASE : null)
    || 'http://home.93gushi.com/api'
}

function request({ url, method = 'GET', data = {} }) {
  return new Promise((resolve, reject) => {
    const token = uni.getStorageSync('erp_token')
    uni.request({
      url: getBase() + url,
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
