import request from '@/utils/request'

export const purOrderApi = {
  page: (params) => request.get('/purchase/order/page', { params }),
  detail: (id) => request.get(`/purchase/order/${id}`),
  add: (data) => request.post('/purchase/order', data),
  update: (data) => request.put('/purchase/order', data),
  delete: (id) => request.delete(`/purchase/order/${id}`),
  getLastPrice: (supplierId, productId) => request.get('/purchase/order/last-price', { params: { supplierId, productId } })
}

export const purReceiptApi = {
  page: (params) => request.get('/purchase/receipt/page', { params }),
  detail: (id) => request.get(`/purchase/receipt/${id}`),
  add: (data) => request.post('/purchase/receipt', data),
  update: (data) => request.put('/purchase/receipt', data),
  delete: (id) => request.delete(`/purchase/receipt/${id}`),
  check: (id) => request.post(`/purchase/receipt/${id}/check`),
  getLastPrice: (supplierId, productId) => request.get('/purchase/receipt/last-price', { params: { supplierId, productId } })
}

export const purReturnApi = {
  page: (params) => request.get('/purchase/return/page', { params }),
  detail: (id) => request.get(`/purchase/return/${id}`),
  add: (data) => request.post('/purchase/return', data),
  check: (id) => request.post(`/purchase/return/${id}/check`)
}
