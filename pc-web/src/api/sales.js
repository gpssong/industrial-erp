import request from '@/utils/request'

export const salOrderApi = {
  page: (params) => request.get('/sales/order/page', { params }),
  detail: (id) => request.get(`/sales/order/${id}`),
  add: (data) => request.post('/sales/order', data),
  update: (data) => request.put('/sales/order', data),
  delete: (id) => request.delete(`/sales/order/${id}`),
  check: (id) => request.post(`/sales/order/${id}/check`),
  uncheck: (id) => request.post(`/sales/order/${id}/uncheck`),
  getLastPrice: (customerId, productId) => request.get('/sales/order/last-price', { params: { customerId, productId } }),
  // v1.1.41: 订单发货明细汇总 (已发/未发数量)
  getDeliverySummary: (orderId) => request.get(`/sales/order/${orderId}/delivery-summary`)
}

export const salDeliveryApi = {
  page: (params) => request.get('/sales/delivery/page', { params }),
  detail: (id) => request.get(`/sales/delivery/${id}`),
  add: (data) => request.post('/sales/delivery', data),
  update: (data) => request.put('/sales/delivery', data),
  delete: (id) => request.delete(`/sales/delivery/${id}`),
  check: (id) => request.post(`/sales/delivery/${id}/check`),
  uncheck: (id) => request.post(`/sales/delivery/${id}/uncheck`),
  getLastPrice: (customerId, productId) => request.get('/sales/delivery/last-price', { params: { customerId, productId } }),
  // v1.1.7+ 客户历史销售产品 (按出库日期 DESC, limit 50) — 销售出库新增弹窗底部参考用
  getCustomerHistoryProducts: (customerId) => request.get('/sales/delivery/customer-history-products', { params: { customerId } }),
  // v1.1.38: 按源订单 ID 查询关联出库单 (追溯入口)
  pageByOrderId: (orderId, params) => request.get('/sales/delivery/page-by-order', { params: { orderId, ...params } })
}

export const salReturnApi = {
  page: (params) => request.get('/sales/return/page', { params }),
  detail: (id) => request.get(`/sales/return/${id}`),
  add: (data) => request.post('/sales/return', data),
  check: (id) => request.post(`/sales/return/${id}/check`),
  uncheck: (id) => request.post(`/sales/return/${id}/uncheck`)
}
